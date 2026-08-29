package io.zer0.muse.tools

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.groupchat.GroupChatRepository
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.tools.script.SkillEngineResult
import io.zer0.muse.tools.script.WebViewSkillEngine
import io.zer0.muse.R
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.web.SearchRateLimitException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.jsoup.Jsoup
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Phase 8.8: Skill 执行器(Kotlin 直实现,不用 QuickJS)。
 *
 * 根据 [SkillEntity.implementationKotlin] 路由到预定义的 Kotlin 函数执行。
 * 当前支持 4 个内置 skill:
 *  - read_file — 读取应用沙盒内文件
 *  - write_file — 写入应用沙盒内文件
 *  - http_get — HTTP GET 请求
 *  - http_post — HTTP POST 请求
 *
 * 设计原则:
 *  - 文件操作限定在应用沙盒(filesDir/cacheDir),避免越权读写
 *  - HTTP 请求设 30s 超时,响应体最大 1MB
 *  - 所有 IO 在 IO Dispatcher 执行
 *
 * @param context 应用 Context(用于 filesDir / cacheDir)
 * @param client OkHttpClient(http_get / http_post 用,由 Koin 注入 named("chat"))
 */
class SkillExecutor(
    private val context: Context,
    private val client: OkHttpClient,
    /** v0.24: web_search 用(可为 null,测试时不注入)。 */
    private val webSearchService: io.zer0.muse.web.WebSearchService? = null,
    /** v0.24: knowledge_search 用(可为 null)。 */
    private val knowledgeDocDao: io.zer0.muse.data.knowledge.KnowledgeDocDao? = null,
    /** v0.24: install_skill 用(可为 null,避免循环依赖)。 */
    private val skillRepository: io.zer0.muse.data.skill.SkillRepository? = null,
    /** v0.46: delegate_agent 用 — 调子助手跑一轮 LLM。 */
    private val chatService: ChatService,
    /** v0.46: delegate_agent 用 — 根据 assistantId 取子助手配置。 */
    private val assistantRepository: AssistantRepository,
    /** v1.30: 群聊工具(channel_reply / channel_pass / channel_read_context)用。 */
    private val groupChatRepository: GroupChatRepository? = null,
    /** v1.54: knowledge_search 向量检索用(可为 null,降级到 LIKE 搜索)。 */
    private val ragService: io.zer0.muse.rag.RagService? = null,
    /** v1.54: 获取 RAG 配置(用于向量检索的 embedding provider 选择)。 */
    private val ragConfigProvider: suspend () -> io.zer0.muse.rag.RagConfig = { io.zer0.muse.rag.RagConfig() },
    /** v1.95: 表情包工具(list_stickers / send_sticker)用。 */
    private val stickerLibraryRepository: io.zer0.muse.data.sticker.StickerLibraryRepository? = null,
    /** v1.???: generate_image 用。 */
    private val imageService: io.zer0.ai.image.ImageService? = null,
    private val imageDrawConfigProvider: suspend () -> Pair<io.zer0.ai.core.ProviderConfig?, String?> = { null to null },
    /** v1.200: 多 Agent 团队配置提供,用于 delegateAgent 处理 TEAM 目标。 */
    private val multiAgentConfigProvider: () -> io.zer0.muse.data.MultiAgentConfig = { io.zer0.muse.data.MultiAgentConfig() },
    /** v1.201: LLM 综合评审聚合器,TeamWorkflowExecutor 的 LLM_REVIEW 策略时使用;为 null 时降级为 EXPERT_REVIEW。 */
    private val llmAggregator: LlmAggregator? = null,
    /** v1.201: 委派暂停管理器,null 时跳过所有暂停点。 */
    private val pauseManager: DelegationPauseManager? = null,
    /** v1.201: 委派链路追踪器,null 时不记录链路。ChatViewModel 共享同一实例用于 UI 展示。 */
    private val delegationChainTracker: DelegationChainTracker? = null,
    /** v1.202: Agent 间私信仓库,用于委派完成后把结果回填为一条 sub-agent → main-agent 的 DM。
     *  为 null 时不发送 DM(测试环境或未注入时降级)。 */
    private val agentDmRepository: io.zer0.muse.data.agentdm.AgentDmRepository? = null,
    /** v1.202: 异步委派结果回灌(非阻塞委派核心基础设施)。
     *  为 null 时 nonBlocking=true 自动降级为阻塞模式。 */
    private val deferredResultStore: DeferredResultStore? = null,
    /** v1.202: 子 agent 线程管理器(可续接的子 agent 会话线程,串行执行避免并发竞争)。
     *  为 null 时 nonBlocking=true 自动降级为阻塞模式。 */
    private val subagentThreadStore: io.zer0.muse.data.subagent.SubagentThreadStore? = null,
    /** v1.0.53: 子 agent 全局并发限流器(Koin 注入)。所有委派入口(TeamWorkflowExecutor 并行节点 / delegateAgent nonBlocking / SubagentRunner)共享同一配额。 */
    private val agentConcurrencyLimiter: AgentConcurrencyLimiter,
    /** v1.0.53 Phase 2: 工作流断点恢复日志(Koin 注入);null 时 TeamWorkflowExecutor 跳过 journal 读写。 */
    private val journal: WorkflowJournal? = null,
    /**
     * v1.0.53 Phase 5: GroupChatScheduler 懒加载 provider(避免与 SkillExecutor 循环依赖)。
     *
     * agent_phone 工具触发 whisper 私聊时调用 [io.zer0.muse.schedule.GroupChatScheduler.launchWhisper]。
     * 用 lambda 形式延迟解析:SkillExecutor 在 Koin 中先于 GroupChatScheduler 注册,
     * 但 lambda 体内 get() 在工具实际执行时才解析,此时 GroupChatScheduler 已初始化完成。
     *
     * 为 null 时(测试环境)agent_phone 工具返回"未配置"提示。
     */
    private val groupChatSchedulerProvider: (() -> io.zer0.muse.schedule.GroupChatScheduler?)? = null,
    /** B6-01: 外部插件管理器(可为 null,测试环境不注入)。 */
    private val pluginManager: PluginManager? = null,
    /** P1-3b: 文件/公共目录工具实现(可为 null,测试环境不注入)。 */
    private val fileTools: SkillFileToolsImpl? = null,
    /** P1-3b: 搜索/HTTP 工具实现(可为 null,测试环境不注入)。 */
    private val searchTools: SkillSearchToolsImpl? = null,
    /** P1-3b: Skill 管理工具实现(可为 null,测试环境不注入)。 */
    private val managementTools: SkillManagementToolsImpl? = null,
    /** P1-3b: 媒体/JS/插件工具实现(可为 null,测试环境不注入)。 */
    private val mediaTools: SkillMediaToolsImpl? = null,
    /** P1-3b: 翻译工具实现(可为 null,测试环境不注入)。 */
    /** P1-3e: Agent 工作流/群聊工具实现(可为 null,测试环境不注入)。 */
    private val agentTools: SkillAgentToolsImpl? = null,
    /** P1-3e: delegateAgent 实现(可为 null,测试环境不注入)。 */
    private val delegateTools: SkillDelegateAgentImpl? = null,
    private val translateTools: TranslateToolsImpl? = null,
) {
    /**
     * 执行 skill。
     * @param skill skill 实体(含 implementationKotlin 路由 key)
     * @param argumentsJson LLM 传来的参数 JSON 字符串
     * @param onProgress v0.49: 进度回调,在耗时工具(web_fetch/web_search/delegate_agent/install_skill)
     *                   执行前调用,供 UI 在 TaskStep 上显示"正在搜索..."等进度文本。默认空实现,不破坏现有调用。
     * @return 执行结果字符串
     */
    suspend fun execute(
        skill: SkillEntity,
        argumentsJson: String,
        onProgress: (String) -> Unit = {},
        turnKey: String = "default",
        sessionId: String = "default",
    ): String = withContext(Dispatchers.IO) {
        val args = ToolArgsParser.parse(argumentsJson, skill.id)
    // H-SE1: 改用 resultOf{}(正确重抛 CancellationException),避免 runCatching 吞协程取消信号
        resultOf {
            when (skill.implementationKotlin) {
                "read_file" -> fileTools?.execReadFile(args) ?: context.getString(R.string.skill_impl_not_configured)
                "write_file" -> fileTools?.execWriteFile(args) ?: context.getString(R.string.skill_impl_not_configured)
                "http_get" -> searchTools?.execHttpGet(args) ?: context.getString(R.string.skill_impl_not_configured)
                "http_post" -> searchTools?.execHttpPost(args) ?: context.getString(R.string.skill_impl_not_configured)
                // v0.24: 搜索与信息获取类
                "web_search" -> { onProgress(context.getString(R.string.skill_progress_searching)); searchTools?.execWebSearch(args, turnKey) ?: context.getString(R.string.skill_impl_not_configured) }
                "web_fetch" -> { onProgress(context.getString(R.string.skill_progress_fetching)); searchTools?.execWebFetch(args) ?: context.getString(R.string.skill_impl_not_configured) }
                "knowledge_search" -> searchTools?.execKnowledgeSearch(args) ?: context.getString(R.string.skill_impl_not_configured)
                "arxiv_search" -> searchTools?.execArxivSearch(args) ?: context.getString(R.string.skill_impl_not_configured)
                // v0.24: 自我扩展(install_skill = LLM 生成 skill 定义入库)
                "install_skill" -> { onProgress(context.getString(R.string.skill_progress_installing)); managementTools?.installSkill(args) ?: context.getString(R.string.skill_impl_not_configured) }
                // v0.46: 多 Agent 协作(委托子助手执行任务)
                "delegate_agent" -> { onProgress(context.getString(R.string.skill_progress_delegating)); execDelegateAgent(args) }
                // v1.55: Agent 工作流(结构化任务计划)
                "task_plan" -> { onProgress(context.getString(R.string.skill_progress_planning)); agentTools?.execTaskPlan(args, sessionId) ?: context.getString(R.string.skill_impl_not_configured) }
                "update_plan_step" -> agentTools?.execUpdatePlanStep(args, sessionId) ?: context.getString(R.string.skill_impl_not_configured)
                // v1.30: 群聊工具(多 Agent 群聊中发言/跳过/读取上下文)
                "channel_reply" -> agentTools?.execChannelReply(args) ?: context.getString(R.string.skill_impl_not_configured)
                "channel_pass" -> agentTools?.execChannelPass(args) ?: context.getString(R.string.skill_impl_not_configured)
                "channel_read_context" -> agentTools?.execChannelReadContext(args) ?: context.getString(R.string.skill_impl_not_configured)
                // v1.0.53 Phase 5: Agent Phone(主 agent 触发群聊成员私聊)
                "agent_phone" -> { onProgress(context.getString(R.string.skill_progress_whispering)); agentTools?.execAgentPhone(args) ?: context.getString(R.string.skill_impl_not_configured) }
                // 文件管理类
                "list_dir" -> fileTools?.execListDir(args) ?: context.getString(R.string.skill_impl_not_configured)
                "delete_file" -> fileTools?.execDeleteFile(args) ?: context.getString(R.string.skill_impl_not_configured)
                "file_exists" -> fileTools?.execFileExists(args) ?: context.getString(R.string.skill_impl_not_configured)
                // 公共目录与文件传输类
                "file_download" -> fileTools?.execFileDownload(args) ?: context.getString(R.string.skill_impl_not_configured)
                "read_public_file" -> fileTools?.execReadPublicFile(args) ?: context.getString(R.string.skill_impl_not_configured)
                "save_to_downloads" -> fileTools?.execSaveToDownloads(args) ?: context.getString(R.string.skill_impl_not_configured)
                "list_public_files" -> fileTools?.execListPublicFiles(args) ?: context.getString(R.string.skill_impl_not_configured)
                // Skill 管理类
                "list_skills" -> managementTools?.listSkills(args) ?: context.getString(R.string.skill_impl_not_configured)
                "uninstall_skill" -> managementTools?.uninstallSkill(args) ?: context.getString(R.string.skill_impl_not_configured)
                "disable_skill" -> managementTools?.disableSkill(args) ?: context.getString(R.string.skill_impl_not_configured)
                // v1.95: 表情包库工具
                "list_stickers" -> mediaTools?.execListStickers(args) ?: context.getString(R.string.skill_impl_not_configured)
                "send_sticker" -> mediaTools?.execSendSticker(args) ?: context.getString(R.string.skill_impl_not_configured)
                // 新增工具
                "generate_image" -> { onProgress(context.getString(R.string.skill_progress_generating_image)); mediaTools?.execGenerateImage(args) ?: context.getString(R.string.skill_impl_not_configured) }
                "translate" -> translateTools?.execTranslate(args) ?: context.getString(R.string.skill_impl_not_configured)
                "generate_qr" -> mediaTools?.execGenerateQr(args) ?: context.getString(R.string.skill_impl_not_configured)
                // JS 沙盒:让 LLM 能在 Skill 体系里执行 JavaScript 代码
                // (主入口为 ToolRegistry.execute_javascript,此处为 SkillExecutor 路由分支,供 skill 调用)
                "execute_javascript" -> mediaTools?.execExecuteJavascript(args) ?: context.getString(R.string.skill_impl_not_configured)
                else -> if (skill.implementationKotlin.startsWith("plugin:")) {
                    mediaTools?.execPluginTool(skill, argumentsJson) ?: context.getString(R.string.skill_impl_not_configured)
                } else {
                    context.getString(R.string.skill_unknown_impl, skill.implementationKotlin)
                }
            }
        }.onError { msg, t ->
            Logger.e("SkillExecutor", "skill ${skill.id} 执行失败: $msg", t)
        }.getOrNull() ?: context.getString(R.string.skill_exec_exception)
    }

    /** v1.55: 供 ChatViewModel 读取活跃计划。 */
    fun getActivePlans(): Map<String, io.zer0.muse.ui.taskcard.AgentPlan> =
        agentTools?.getActivePlans().orEmpty()

    fun getActivePlans(sessionId: String): Map<String, io.zer0.muse.ui.taskcard.AgentPlan> =
        agentTools?.getActivePlans(sessionId).orEmpty()

    /** 将历史恢复的计划回灌给当前会话的 task_plan/update_plan_step 执行器。 */
    fun restoreActivePlans(
        plans: Map<String, io.zer0.muse.ui.taskcard.AgentPlan>,
        sessionId: String = "default",
    ) {
        agentTools?.restoreActivePlans(plans, sessionId)
    }

    // v1.0.81: parseArgs 已抽取为 ToolArgsParser（可单测），不再在 SkillExecutor 内联。

    // ── 内置 skill 实现 ──────────────────────────────────────────────────────

    /** 读取应用沙盒内文件(限定 filesDir / cacheDir 子路径)。 */
    private fun validatePublicUrl(url: String): Boolean {
        val uri = try {
            java.net.URI(url)
        } catch (e: Exception) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        if (host == "localhost") return false
        // 解析 DNS 后二次校验 IP(防 DNS rebinding):只要任一解析结果指向内网就拒绝
        val addresses = try {
            java.net.InetAddress.getAllByName(host)
        } catch (e: Exception) {
            return false
        }
        return addresses.all { addr ->
            // IPv4 私网/回环/链路本地等由 InetAddress 内置方法覆盖
            if (addr.isLoopbackAddress || addr.isAnyLocalAddress ||
                addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
                addr.isMulticastAddress
            ) {
                return@all false
            }
            // IPv6 私网 fc00::/7(InetAddress.isSiteLocalAddress 对 IPv6 返回 false,需手动判断)
            if (addr is java.net.Inet6Address) {
                val bytes = addr.address
                // fc00::/7 的前 7 位是 1111110,即首字节范围 0xfc..0xfd
                if ((bytes[0].toInt() and 0xFE) == 0xFC) return@all false
            }
            true
        }
    }

    /** HTTP GET 请求。失败时(404/超时/连接失败)降级到搜索摘要;401/403 等业务错误不降级。 */

    /**
     * install_skill — LLM 自己生成 skill 定义并入库(Phase 2 自我扩展)。
     *
     * LLM 输出 .skill.json 格式的 skill 定义字符串,经 [SkillImporter.parse] 校验后入库。
     * 安全约束:implementationKotlin 必须是 4 个内置实现之一,不支持任意代码执行。
     */
    suspend fun delegateAgent(
        request: DelegationContract.DelegationRequest,
        policy: DelegationPauseManager.PausePolicy = DelegationPauseManager.PausePolicy(),
    ): DelegationContract.DelegationResult {
        return delegateTools?.delegateAgent(request, policy)
            ?: DelegationContract.DelegationResult(
                requestId = request.requestId,
                success = false,
                error = "delegateAgent 未配置",
                metadata = DelegationContract.DelegationResult.ResultMetadata(
                    startedAt = System.currentTimeMillis(),
                    finishedAt = System.currentTimeMillis(),
                    durationMs = 0,
                ),
            )
    }

    /**
     * v0.46: 多 Agent 协作 — 把任务委托给指定子助手执行。
     *
     * LLM 通过 tool_call 触发,参数:
     *  - assistantId: 子助手 id(必填,如 "researcher"/"writer" 等)
     *  - task: 任务描述(必填,自然语言)
     *  - context: 可选上下文(可选,补充信息)
     *
     * 执行流程:
     *  1. 根据 assistantId 从 AssistantRepository 取子助手配置
     *  2. 用子助手的 systemPrompt + 任务描述构造消息列表
     *  3. 调用 ChatService.completeText 跑一轮(temperature/maxTokens 用子助手配置)
     *  4. 返回子助手生成的文本给主 LLM
     *
     * 注意:
     *  - 子助手独立调用 LLM,不走主 launchStream 的流式回环
     *  - 失败时返回错误信息字符串(主 LLM 能看到错误并决定是否重试)
     *  - 不递归(子助手不能再调 delegate_agent,避免无限嵌套)
     */
    @Deprecated("使用 delegateAgent 替代", ReplaceWith("delegateAgent"))
    private suspend fun execDelegateAgent(args: Map<String, String>): String {
        // v1.202 改造 1: 统一委派入口 — execDelegateAgent 是 LLM tool_call "delegate_agent" 的入口,
        // 但此前不通知 DelegationChainTracker,导致 LLM 委派在 UI 链路卡片中不可见。
        // 现在委托到结构化 delegateAgent,自动获得链路追踪 + 暂停点 + DM 集成 + 子 agent 工具调用。
        // 注意:execDelegateAgent 返回字符串(tool result),delegateAgent 返回 DelegationResult,
        // 这里把 result.resultText 作为字符串返回。失败时返回 result.error 让主 LLM 看到错误。
        val assistantId = args["assistantId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_assistant_id)
        val task = args["task"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_task)
        if (task.isBlank()) return context.getString(R.string.skill_task_blank)
        val contextInfo = args["context"]?.trim().orEmpty()
        // timeout: 默认 60 秒;response_format: 默认 text,可选 json
        val timeoutSec = args["timeout"]?.toLongOrNull()?.coerceIn(1L, 600L) ?: 60L
        val responseFormat = args["response_format"]?.takeIf { it.isNotBlank() } ?: "text"

        // 构造上下文消息(把可选 context 作为 USER 消息的额外段落)
        val contextMessages: List<UIMessage> = if (contextInfo.isNotBlank()) {
            listOf(UIMessage(role = MessageRole.USER, content = "上下文:\n$contextInfo"))
        } else {
            emptyList()
        }

        // 构造结构化 DelegationRequest,委托到 delegateAgent(自动走链路追踪/暂停/DM/工具调用)
        val requestId = "delegate-toolcall-${System.currentTimeMillis()}-${(100..999).random()}"
        val request = DelegationContract.DelegationRequest(
            requestId = requestId,
            task = task,
            targetType = DelegationContract.DelegationRequest.TargetType.ASSISTANT,
            targetId = assistantId,
            contextMessages = contextMessages,
            timeoutSec = timeoutSec.toInt().coerceAtLeast(1),
            responseFormat = when (responseFormat.lowercase()) {
                "json" -> DelegationContract.DelegationRequest.ResponseFormat.JSON
                "markdown", "md" -> DelegationContract.DelegationRequest.ResponseFormat.MARKDOWN
                "code" -> DelegationContract.DelegationRequest.ResponseFormat.CODE
                else -> DelegationContract.DelegationRequest.ResponseFormat.TEXT
            },
        )
        val result = delegateAgent(request)

        // 把 DelegationResult 转成字符串(tool result)
        // 失败时返回错误字符串(主 LLM 能看到错误并决定是否重试)
        if (!result.success) {
            return result.error ?: context.getString(R.string.skill_unknown_error)
        }
        val resultText = result.resultText
        // 取子助手名(从 metadata,若不可用则降级到 assistantId)
        val assistantName = result.metadata.assistantName ?: assistantId

        // response_format=json 时返回结构化 JSON,否则返回带前缀的文本
        return if (responseFormat.equals("json", ignoreCase = true)) {
            buildJsonObject {
                put("assistantId", JsonPrimitive(assistantId))
                put("assistantName", JsonPrimitive(assistantName))
                put("result", JsonPrimitive(resultText))
                put("success", JsonPrimitive(true))
                // 非阻塞模式下附带 taskId/threadId,供主 LLM 后续查询
                result.taskId?.let { put("taskId", JsonPrimitive(it)) }
                result.threadId?.let { put("threadId", JsonPrimitive(it)) }
            }.toString()
        } else {
            buildString {
                appendLine(context.getString(R.string.skill_delegate_done, assistantName))
                appendLine(resultText)
            }
        }
    }

    // ── v1.55: Agent 工作流(结构化任务计划)──────────────────────────────

    // ── 文件管理类 ──────────────────────────────────────────────────────

    /** list_dir — 列出目录下的文件和子目录(沙盒内)。 */

    // ── Skill 管理类 ────────────────────────────────────────────────────

    /** list_skills — 列出已安装的 Skill,可选按 category 筛选。 */

    /** uninstall_skill — 卸载 Skill(按 id 删除,或按 name 查找后删除)。 */
    companion object {

        /**
         * 预定义 skill 模板(首次启动时写入数据库)。
         * 用 SkillRepository.upsert 插入,REPLACE 策略保证幂等。
         */
        val BUILT_IN_SKILLS: List<SkillEntity> = listOf(
            SkillEntity(
                id = "read_file",
                name = "读取文件",
                description = "读取应用沙盒内的文本文件(上限 1MB)。路径相对于 filesDir。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "相对于 filesDir 的文件路径,如 'notes/todo.txt'")
                        })
                        put("offset", buildJsonObject {
                            put("type", "integer")
                            put("description", "可选,起始行号(从 0 开始),默认 0")
                        })
                        put("length", buildJsonObject {
                            put("type", "integer")
                            put("description", "可选,读取行数,默认 0=全部")
                        })
                        put("encoding", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,文件编码,默认 utf-8(支持 utf-16)")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("path"))))
                }.toString(),
                requiredJson = """["path"]""",
                implementationKotlin = "read_file",
                category = "file",
            ),
            SkillEntity(
                id = "write_file",
                name = "写入文件",
                description = "写入文本到应用沙盒内的文件。路径相对于 filesDir。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "相对于 filesDir 的文件路径")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "要写入的文本内容")
                        })
                        put("append", buildJsonObject {
                            put("type", "boolean")
                            put("description", "是否追加写入(默认 false 覆盖)")
                        })
                        put("create_dirs", buildJsonObject {
                            put("type", "boolean")
                            put("description", "可选,是否自动创建父目录,默认 true")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(
                        JsonPrimitive("path"), JsonPrimitive("content"),
                    )))
                }.toString(),
                requiredJson = """["path","content"]""",
                implementationKotlin = "write_file",
                category = "file",
            ),
            SkillEntity(
                id = "http_get",
                name = "HTTP GET",
                description = "发起 HTTP GET 请求并返回响应(响应体上限 1MB)。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("url", buildJsonObject {
                            put("type", "string")
                            put("description", "请求 URL,http:// 或 https://")
                        })
                        put("headers", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,请求头 JSON,如 {\"Authorization\":\"Bearer xxx\"}")
                        })
                        put("timeout", buildJsonObject {
                            put("type", "integer")
                            put("description", "可选,超时秒数,默认 30")
                        })
                        put("max_size", buildJsonObject {
                            put("type", "integer")
                            put("description", "可选,响应体大小上限(字节),默认 1048576(1MB)")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("url"))))
                }.toString(),
                requiredJson = """["url"]""",
                implementationKotlin = "http_get",
                category = "http",
            ),
            SkillEntity(
                id = "http_post",
                name = "HTTP POST",
                description = "发起 HTTP POST 请求并返回响应(响应体上限 1MB)。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("url", buildJsonObject {
                            put("type", "string")
                            put("description", "请求 URL"
                            )
                        })
                        put("body", buildJsonObject {
                            put("type", "string")
                            put("description", "请求体内容")
                        })
                        put("content_type", buildJsonObject {
                            put("type", "string")
                            put("description", "Content-Type,默认 application/json")
                        })
                        put("headers", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,请求头 JSON")
                        })
                        put("timeout", buildJsonObject {
                            put("type", "integer")
                            put("description", "可选,超时秒数,默认 30")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(
                        JsonPrimitive("url"), JsonPrimitive("body"),
                    )))
                }.toString(),
                requiredJson = """["url","body"]""",
                implementationKotlin = "http_post",
                category = "http",
            ),
            // ── v0.24: 搜索与信息获取 ──────────────────────────────────────
            SkillEntity(
                id = "web_search",
                name = "网页搜索",
                // v1.0.75 fix (工具审查 01): 删冗余 time_period,补 date_range 使用指引
                description = "用配置好的搜索引擎(SearXNG/Tavily)搜索网页。返回标题、URL 和摘要。当用户问到需要最新信息的问题时调用此工具。使用时机: 用户问到需要最新/实时信息的问题(价格、版本、政策、行情、天气等),或你知识不确定时。需要限定时间时传 date_range(如用户说'最近一周',传 past_week)。不要使用: 常识性问题或用户已提供足够信息时;搜索结果不全时换关键词重搜,不要编造。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "必填,搜索关键词。中文用户问题直接用中文关键词,可加关键限定词提高精度")
                        })
                        put("max_results", buildJsonObject {
                            put("type", "integer")
                            put("description", "可选,最大返回条数(1-10),默认 5")
                        })
                        put("date_range", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,限定结果时间范围,取值: past_day / past_week / past_month / past_year。用户提到'最近/最新/本周/本月'时填写,无时间要求时不填")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("query"))))
                }.toString(),
                requiredJson = """["query"]""",
                implementationKotlin = "web_search",
                category = "search",
            ),
            SkillEntity(
                id = "web_fetch",
                name = "网页抓取",
                description = "抓取指定 URL 的网页正文(自动去除 HTML 标签,返回纯文本)。用于读取 web_search 返回的 URL 全文内容。使用时机: 读取 web_search 返回的 URL 全文,或用户给出明确 URL 时。不要使用: 无 URL 时;页面需要登录/交互时说明无法获取,不要猜测内容。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("url", buildJsonObject {
                            put("type", "string")
                            put("description", "要抓取的网页 URL,http:// 或 https://")
                        })
                        put("headers", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,请求头 JSON")
                        })
                        put("max_length", buildJsonObject {
                            put("type", "integer")
                            put("description", "可选,返回字符数上限,默认 50000")
                        })
                        put("truncate", buildJsonObject {
                            put("type", "boolean")
                            put("description", "可选,超出 max_length 是否截断,默认 true")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("url"))))
                }.toString(),
                requiredJson = """["url"]""",
                implementationKotlin = "web_fetch",
                category = "search",
            ),
            SkillEntity(
                id = "knowledge_search",
                name = "知识库搜索",
                // v1.0.75 fix (工具审查 01): 明确与 web_search 的边界,
                // 原"可能与已导入文档相关时优先调用"太模糊,模型先试本地再退搜索浪费轮次。
                description = "在用户主动导入的知识库文档中语义搜索(向量检索 + 标题/内容匹配)。仅当用户知识库中可能有答案时使用(如用户导入过资料、问'我之前存的文档里怎么说的')。通用事实、实时信息、网上能查到的问题一律用 web_search,不要先用本工具试探。用户问 muse app 自身功能(如怎么用深度思考/主动消息怎么设置)时,传 include_internal=true 可检索内置功能文档。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "搜索关键词")
                        })
                        put("top_k", buildJsonObject {
                            put("type", "integer")
                            put("description", "可选,返回条数上限,默认 5")
                        })
                        put("threshold", buildJsonObject {
                            put("type", "number")
                            put("description", "可选,相似度阈值(0-1 小数制,非满分 100),默认 0.3。低于阈值的过滤掉")
                        })
                        put("include_internal", buildJsonObject {
                            put("type", "boolean")
                            put("description", "可选,是否包含 muse app 内置功能文档,默认 false。仅在用户问 muse app 自身功能(如'怎么用深度思考''主动消息怎么设置')时传 true")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("query"))))
                }.toString(),
                requiredJson = """["query"]""",
                implementationKotlin = "knowledge_search",
                category = "knowledge",
            ),
            SkillEntity(
                id = "arxiv_search",
                name = "arXiv 论文搜索",
                description = "在 arXiv 搜索学术论文(计算机科学、物理、数学等)。返回论文标题、链接、发表日期和摘要。当用户问到学术研究、论文相关问题时调用。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "搜索关键词(英文为佳),如 'transformer attention'")
                        })
                        put("max_results", buildJsonObject {
                            put("type", "integer")
                            put("description", "可选,最大返回条数(1-10),默认 5")
                        })
                        put("category", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,学科分类,如 cs.AI/cs.CL")
                        })
                        put("date_from", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,起始日期(YYYY-MM-DD)")
                        })
                        put("date_to", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,结束日期(YYYY-MM-DD)")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("query"))))
                }.toString(),
                requiredJson = """["query"]""",
                implementationKotlin = "arxiv_search",
                category = "search",
            ),
            // ── v0.24: 自我扩展 ─────────────────────────────────────────────
            SkillEntity(
                id = "install_skill",
                name = "安装 Skill",
                description = "让助手自己生成新的 skill 定义并安装到用户设备。skill_json 参数为 .skill.json 格式的 JSON 字符串。必填字段: id, name, description, category, implementationKotlin, parametersJson。category 取值: file/http/search/knowledge/system/agent/sticker/custom。implementationKotlin 必须是内置实现之一(read_file/write_file/http_get/http_post/web_search/web_fetch/knowledge_search/arxiv_search),不支持任意代码执行。安装后用户可在设置→Skill 中查看/启停。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("skill_json", buildJsonObject {
                            put("type", "string")
                            put("description", "skill 定义 JSON 字符串(.skill.json 格式)。示例: {\"id\":\"fetch_weather\",\"name\":\"查询天气\",\"description\":\"查询城市天气\",\"parametersJson\":\"{\\\"type\\\":\\\"object\\\",\\\"properties\\\":{\\\"url\\\":{\\\"type\\\":\\\"string\\\"}}}\",\"implementationKotlin\":\"http_get\"}")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("skill_json"))))
                }.toString(),
                requiredJson = """["skill_json"]""",
                implementationKotlin = "install_skill",
                category = "system",
            ),
            // ── v0.46: 多 Agent 协作 ────────────────────────────────────────
            SkillEntity(
                id = "delegate_agent",
                name = "委托子助手",
                description = "把任务委托给指定子助手执行,用于多助手协作。传入 assistantId(助手 id)和 task(任务描述),可选 context(上下文)。子助手会用自己的人设和能力独立完成任务并返回结果。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("assistantId", buildJsonObject {
                            put("type", "string")
                            put("description", "子助手 id,如 default / researcher / writer 等。可在助手管理页查看")
                        })
                        put("task", buildJsonObject {
                            put("type", "string")
                            put("description", "要委托的任务描述,自然语言")
                        })
                        put("context", buildJsonObject {
                            put("type", "string")
                            put("description", "可选的补充上下文信息")
                        })
                        put("timeout", buildJsonObject {
                            put("type", "integer")
                            put("description", "可选,超时秒数,默认 60")
                        })
                        put("response_format", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,返回格式,text(默认)或 json")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(
                        JsonPrimitive("assistantId"), JsonPrimitive("task"),
                    )))
                }.toString(),
                requiredJson = """["assistantId","task"]""",
                implementationKotlin = "delegate_agent",
                category = "agent",
            ),
            // ── v1.30: 群聊工具 ─────────────────────────────────────────────
            SkillEntity(
                id = "channel_reply",
                name = "群聊发言",
                description = "在指定群聊中作为指定 agent 发送一条消息。传入 chatId(群聊 id)、assistantId(发言 agent id)和 body(消息正文)。消息会保存到群聊历史中,其他成员和用户可见。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("chatId", buildJsonObject {
                            put("type", "string")
                            put("description", "群聊 id")
                        })
                        put("assistantId", buildJsonObject {
                            put("type", "string")
                            put("description", "发言的 agent id(assistantId)")
                        })
                        put("body", buildJsonObject {
                            put("type", "string")
                            put("description", "消息正文内容")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(
                        JsonPrimitive("chatId"), JsonPrimitive("assistantId"), JsonPrimitive("body"),
                    )))
                }.toString(),
                requiredJson = """["chatId","assistantId","body"]""",
                implementationKotlin = "channel_reply",
                category = "agent",
            ),
            SkillEntity(
                id = "channel_pass",
                name = "群聊跳过本轮",
                description = "在群聊轮转中跳过本轮发言(不发送消息)。传入 chatId(群聊 id)和 assistantId(agent id)。当 agent 认为当前无需自己发言时调用。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("chatId", buildJsonObject {
                            put("type", "string")
                            put("description", "群聊 id")
                        })
                        put("assistantId", buildJsonObject {
                            put("type", "string")
                            put("description", "跳过发言的 agent id")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(
                        JsonPrimitive("chatId"), JsonPrimitive("assistantId"),
                    )))
                }.toString(),
                requiredJson = """["chatId","assistantId"]""",
                implementationKotlin = "channel_pass",
                category = "agent",
            ),
            SkillEntity(
                id = "channel_read_context",
                name = "读取群聊上下文",
                description = "读取指定群聊的最近消息作为上下文。传入 chatId(群聊 id),可选 limit(条数上限,默认 20)。返回格式化的消息列表,包含发送者名、时间和内容。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("chatId", buildJsonObject {
                            put("type", "string")
                            put("description", "群聊 id")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "可选,读取条数上限(1-100),默认 20")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("chatId"))))
                }.toString(),
                requiredJson = """["chatId"]""",
                implementationKotlin = "channel_read_context",
                category = "agent",
            ),
            // ── v1.0.53 Phase 5: Agent Phone(主 agent 触发群聊成员私聊) ─────
            SkillEntity(
                id = "agent_phone",
                name = "Agent 私聊",
                description = "在群聊中向指定 agent 发起私聊(whisper)。传入 chatId(群聊 id)、targetAssistantId(目标 agent id)和 message(私聊内容)。私聊消息仅目标 agent 可见,不参与群聊轮转上下文,且会持久化到独立会话账本(App 重启后可续接)。调用后立即返回,目标 agent 的回复异步到达群聊消息流。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("chatId", buildJsonObject {
                            put("type", "string")
                            put("description", "群聊 id(用于隔离 whisper 上下文)")
                        })
                        put("targetAssistantId", buildJsonObject {
                            put("type", "string")
                            put("description", "目标 agent 的 id(assistantId)")
                        })
                        put("message", buildJsonObject {
                            put("type", "string")
                            put("description", "私聊消息正文")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(
                        JsonPrimitive("chatId"), JsonPrimitive("targetAssistantId"), JsonPrimitive("message"),
                    )))
                }.toString(),
                requiredJson = """["chatId","targetAssistantId","message"]""",
                implementationKotlin = "agent_phone",
                category = "agent",
            ),
            // ── 文件管理类 ─────────────────────────────────────────────────
            SkillEntity(
                id = "list_dir",
                name = "列出目录",
                description = "列出应用沙盒内指定目录下的文件和子目录。每行一个条目,文件夹前缀 [D],文件前缀 [F],末尾标注文件大小。路径相对于 filesDir。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "相对于 filesDir 的目录路径,如 'notes' 或 ''(根目录)")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("path"))))
                }.toString(),
                requiredJson = """["path"]""",
                implementationKotlin = "list_dir",
                category = "file",
            ),
            SkillEntity(
                id = "delete_file",
                name = "删除文件",
                description = "删除应用沙盒内的文件或空目录。路径相对于 filesDir。删除非空目录会失败。支持批量删除(传 paths)。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "相对于 filesDir 的文件或空目录路径(单个,与 paths 二选一)")
                        })
                        put("paths", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,批量删除的路径列表,逗号或换行分隔,如 'a.txt,b.txt'。与 path 二选一,优先于 path")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("path"))))
                }.toString(),
                requiredJson = """["path"]""",
                implementationKotlin = "delete_file",
                category = "file",
            ),
            SkillEntity(
                id = "file_exists",
                name = "判断文件存在",
                description = "判断应用沙盒内指定路径的文件或目录是否存在。返回 exists 或 not_exists。路径相对于 filesDir。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "相对于 filesDir 的路径")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("path"))))
                }.toString(),
                requiredJson = """["path"]""",
                implementationKotlin = "file_exists",
                category = "file",
            ),
            // ── 公共目录与文件传输 ─────────────────────────────────────────
            SkillEntity(
                id = "file_download",
                name = "下载文件",
                description = "从 URL 下载文件到应用沙盒。支持指定超时时间(默认 60 秒)。下载的文件保存在 filesDir 下指定相对路径,自动创建父目录。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("url", buildJsonObject {
                            put("type", "string")
                            put("description", "下载地址,http:// 或 https://")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "保存到沙盒的相对路径(相对于 filesDir),如 'downloads/file.zip'")
                        })
                        put("timeout", buildJsonObject {
                            put("type", "integer")
                            put("description", "可选,超时秒数,默认 60")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(
                        JsonPrimitive("url"), JsonPrimitive("path"),
                    )))
                }.toString(),
                requiredJson = """["url","path"]""",
                implementationKotlin = "file_download",
                category = "file",
            ),
            SkillEntity(
                id = "read_public_file",
                name = "读取公共文件",
                description = "通过 content:// URI 读取公共文件。可读取 list_public_files 返回的 URI(含 MediaStore URI),也可读用户分享/打开方式传入的 URI。返回文本内容(上限 1MB)。注:仅支持文本类文件,二进制文件可能乱码。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("uri", buildJsonObject {
                            put("type", "string")
                            put("description", "文件 URI,如 list_public_files 输出的 uri=content://media/... 或 SAF 传入的 content://...")
                        })
                        put("encoding", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,文件编码,默认 utf-8")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("uri"))))
                }.toString(),
                requiredJson = """["uri"]""",
                implementationKotlin = "read_public_file",
                category = "file",
            ),
            SkillEntity(
                id = "save_to_downloads",
                name = "保存到下载目录",
                description = "保存文本内容或本地沙盒文件到系统 Download 目录。Android 10+ 通过 MediaStore 写入,Android 9 及以下直接写公共 Download 目录。传 file_path 时支持二进制文件转存(无需先 read_file 再写文本)。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "要保存的文本内容(与 file_path 二选一)")
                        })
                        put("file_path", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,沙盒内源文件路径(相对 filesDir),直接转存到 Download,支持二进制。与 content 二选一")
                        })
                        put("filename", buildJsonObject {
                            put("type", "string")
                            put("description", "文件名,如 'notes.txt'")
                        })
                        put("mime_type", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,MIME 类型,默认 text/plain。转存二进制时建议显式指定(如 image/png)")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(
                        JsonPrimitive("filename"),
                    )))
                }.toString(),
                requiredJson = """["filename"]""",
                implementationKotlin = "save_to_downloads",
                category = "file",
            ),
            SkillEntity(
                id = "list_public_files",
                name = "列出公共目录",
                description = "列出指定公共目录(Downloads/Documents/Pictures/Music 等)的文件。通过 MediaStore 查询,返回文件名和大小。默认列 Downloads,最多 50 条,按修改时间倒序。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("directory", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,目录名,如 Downloads/Documents/Pictures/Music,默认 Downloads")
                        })
                        put("limit", buildJsonObject {
                            // v1.52: 修正类型为 integer(原 string 与实际 parseIntOrNull 不匹配)
                            put("type", "integer")
                            put("description", "可选,最大返回条数,默认 50")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf()))
                }.toString(),
                requiredJson = """[]""",
                implementationKotlin = "list_public_files",
                category = "file",
            ),
            // ── Skill 管理类 ───────────────────────────────────────────────
            SkillEntity(
                id = "list_skills",
                name = "列出 Skill",
                description = "列出已安装的全部 Skill,每行格式为 'id | name | category | enabled/disabled'。可选按 category 筛选。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("category", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,按分类筛选,如 file/http/search/knowledge/system/agent/skill")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf()))
                }.toString(),
                requiredJson = """[]""",
                implementationKotlin = "list_skills",
                category = "skill",
            ),
            SkillEntity(
                id = "uninstall_skill",
                name = "卸载 Skill",
                description = "卸载(删除)已安装的 Skill。需传入 id 或 name(至少一个),优先用 id。删除后该 Skill 不再可用。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "string")
                            put("description", "要卸载的 skill id")
                        })
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,按名称查找并卸载(id 未传时使用)")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf()))
                }.toString(),
                requiredJson = """[]""",
                implementationKotlin = "uninstall_skill",
                category = "skill",
            ),
            SkillEntity(
                id = "disable_skill",
                name = "禁用 Skill",
                description = "禁用已安装的 Skill(不删除,只置为不可用)。后续可通过启用恢复。需传入 id。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "string")
                            put("description", "要禁用的 skill id")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("id"))))
                }.toString(),
                requiredJson = """["id"]""",
                implementationKotlin = "disable_skill",
                category = "skill",
            ),
            // v1.55: Agent 工作流 — 结构化任务计划
            SkillEntity(
                id = "task_plan",
                name = "创建任务计划",
                description = "面对复杂多步骤任务时,先创建一个结构化计划再逐步执行。传入计划标题和步骤列表(每步含标题和描述)。计划会在用户界面显示为可追踪的检查清单。创建后按顺序执行各步骤,每完成一步调用 update_plan_step 更新状态。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("title", buildJsonObject {
                            put("type", "string")
                            put("description", "计划标题,简述整体目标")
                        })
                        put("steps", buildJsonObject {
                            put("type", "array")
                            put("description", "步骤列表,按执行顺序排列")
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("title", buildJsonObject {
                                        put("type", "string")
                                        put("description", "步骤标题(简短)")
                                    })
                                    put("description", buildJsonObject {
                                        put("type", "string")
                                        put("description", "步骤详细描述,包括要做什么和预期结果")
                                    })
                                })
                                put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("title"))))
                            })
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("steps"))))
                }.toString(),
                requiredJson = """["title","steps"]""",
                implementationKotlin = "task_plan",
                category = "agent",
            ),
            SkillEntity(
                id = "update_plan_step",
                name = "更新计划步骤",
                description = "更新任务计划中某个步骤的状态。传入 planId 和 stepIndex(从0开始),以及新状态(done/failed/in_progress/skipped)和可选的结果摘要。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("planId", buildJsonObject {
                            put("type", "string")
                            put("description", "计划 id(task_plan 返回的 planId)")
                        })
                        put("stepIndex", buildJsonObject {
                            put("type", "integer")
                            put("description", "步骤索引(从 0 开始)")
                        })
                        put("status", buildJsonObject {
                            put("type", "string")
                            put("description", "新状态:done(完成)/ failed(失败)/ in_progress(执行中)/ skipped(跳过)")
                        })
                        put("result", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,步骤执行结果摘要"
                            )
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("planId"), JsonPrimitive("stepIndex"), JsonPrimitive("status"))))
                }.toString(),
                requiredJson = """["planId","stepIndex","status"]""",
                implementationKotlin = "update_plan_step",
                category = "agent",
            ),
            // ── v1.95: 表情包库工具 ─────────────────────────────────────────
            SkillEntity(
                id = "list_stickers",
                name = "列出表情包",
                description = "列出表情包库中可用的表情包(可按分类筛选)。仅当用户已上传表情包时可用。发送前先判断对话情绪:用户生气/难过时优先筛选安慰、可爱、治愈类分类;开心/兴奋时优先筛选庆祝、搞笑类分类。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("category", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,按分类筛选表情包(如 猫猫/狗子)。不传则列出全部;建议结合对话情绪传入合适分类")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf()))
                }.toString(),
                requiredJson = """[]""",
                implementationKotlin = "list_stickers",
                category = "sticker",
            ),
            SkillEntity(
                id = "send_sticker",
                name = "发送表情包",
                description = "向用户发送一个表情包。先用 list_stickers 查看可用表情包,再用此工具发送。选图原则:匹配对话情绪与语境,生气/难过时发安抚治愈类,开心时发庆祝搞笑类,避免在严肃话题中发过于沙雕的表情包。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "string")
                            put("description", "要发送的表情包 id(从 list_stickers 获取)")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("id"))))
                }.toString(),
                requiredJson = """["id"]""",
                implementationKotlin = "send_sticker",
                category = "sticker",
            ),
            // ── v1.???: 新增工具 ──────────────────────────────────────────────
            SkillEntity(
                id = "generate_image",
                name = "生成图片",
                description = "根据文字描述生成图片。调用 AI 绘图模型(需配置 OpenAI 兼容绘图供应商)。返回图片 URL。使用时机: 用户要求生成图片/海报/封面时。不要使用: 未配置绘图模型时(会失败,应告知用户去设置配置);prompt 尽量用英文描述主体/风格/构图。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("prompt", buildJsonObject {
                            put("type", "string")
                            put("description", "图片描述,英文效果更佳,如 'a cute cat sitting on a sofa'")
                        })
                        put("size", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,图片尺寸,如 1024x1024/1792x1024/1024x1792,默认 1024x1024")
                        })
                        put("reference_image", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,参考图 URL 或 base64(用于图生图/图片编辑);非空时调用图生图端点。注意:LLM 无法访问用户本地相册,本地参考图由用户在工具审批卡片中从相册选择后注入,LLM 调用时无需也无法填入本参数")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("prompt"))))
                }.toString(),
                requiredJson = """["prompt"]""",
                implementationKotlin = "generate_image",
                category = "image",
            ),
            SkillEntity(
                id = "translate",
                name = "翻译",
                description = "把文本翻译成指定语言。可指定源语言(可选)和目标语言(必填)。使用 AI 模型进行高质量翻译。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("text", buildJsonObject {
                            put("type", "string")
                            put("description", "要翻译的文本内容")
                        })
                        put("target_language", buildJsonObject {
                            put("type", "string")
                            put("description", "目标语言,如 中文/English/日本語/한국어")
                        })
                        put("source_language", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,源语言,如 中文/English/自動検出")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(
                        JsonPrimitive("text"), JsonPrimitive("target_language"),
                    )))
                }.toString(),
                requiredJson = """["text","target_language"]""",
                implementationKotlin = "translate",
                category = "custom",
            ),
            // B-07: generate_qr 内置 skill 已下线 — 与本地工具 generate_qr_code 重复且语义冲突
            // (skill 版返回 cacheDir 文件路径,本地版渲染 data URI 到对话)。保留本地版。
            // implementationKotlin="generate_qr" 的 dispatch 仍保留,兼容已导入该 skill 的用户。
        )

        /**
         * B-32: 内置 skill 可路由实现集合 — 与 [execute] 内 when(implementationKotlin) 的
         * 专属分支 key 一一对应(plugin: 前缀与未知 else 分支除外)。
         *
         * 供一致性护栏测试(见 app/src/test/.../tools/ToolRegistrySmokeTest.kt)遍历
         * [BUILT_IN_SKILLS] 断言每个内置 skill 都能被 [execute] 路由到对应实现,
         * 防止"新增内置 skill 却漏写 when 路由分支"或"分支被删"导致静默走 skill_unknown_impl。
         *
         * 注意:generate_qr 属已下线实现(B-07),从此集排除;但 dispatch 分支仍保留以兼容旧数据。
         * 此集合须与 [execute] 的 when 分支保持同步,集合仅用于测试护栏,不驱动路由。
         */
        internal val ROUTABLE_SKILL_IMPL: Set<String> = setOf(
            // 文件
            "read_file", "write_file", "list_dir", "delete_file", "file_exists",
            // HTTP/搜索/信息
            "http_get", "http_post", "web_search", "web_fetch", "knowledge_search", "arxiv_search",
            // 自我扩展/Agent/群聊
            "install_skill", "delegate_agent", "task_plan", "update_plan_step",
            "channel_reply", "channel_pass", "channel_read_context", "agent_phone",
            // 文件公共目录
            "file_download", "read_public_file", "save_to_downloads", "list_public_files",
            // Skill 管理
            "list_skills", "uninstall_skill", "disable_skill",
            // 表情包/媒体/翻译/JS
            "list_stickers", "send_sticker", "generate_image", "translate", "execute_javascript",
        )
    }
}
