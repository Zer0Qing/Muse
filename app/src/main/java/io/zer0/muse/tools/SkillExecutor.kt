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
    /**
     * 绘图供应商配置提供者(读 ImageGenConfig 并解析为 ProviderConfig + 显式 modelId)。
     *
     * 返回 Pair<ProviderConfig?, String?>:
     *  - first: 用户在"设置→绘图供应商"配置的 ProviderConfig;null 时 ImageService 用当前聊天 Provider
     *  - second: 用户配置的绘图模型 ID;null 时由 args["model"] 或 ImageService 兜底
     *
     * 修复:此前 execGenerateImage 没传 providerConfig,导致 LLM 调用 generate_image 工具时
     * 始终用当前聊天 Provider(如 opencode.ai 中转站)而非用户配置的 agnes 绘图供应商,
     * 路由到 OpenAIImageProvider + dall-e-3 后中转站返回 404。
     */
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
    private val subagentThreadStore: SubagentThreadStore? = null,
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
    ): String = withContext(Dispatchers.IO) {
        val args = parseArgs(argumentsJson)
        // H-SE1: 改用 resultOf{}(正确重抛 CancellationException),避免 runCatching 吞协程取消信号
        resultOf {
            when (skill.implementationKotlin) {
                "read_file" -> execReadFile(args)
                "write_file" -> execWriteFile(args)
                "http_get" -> execHttpGet(args)
                "http_post" -> execHttpPost(args)
                // v0.24: 搜索与信息获取类
                "web_search" -> { onProgress(context.getString(R.string.skill_progress_searching)); execWebSearch(args) }
                "web_fetch" -> { onProgress(context.getString(R.string.skill_progress_fetching)); execWebFetch(args) }
                "knowledge_search" -> execKnowledgeSearch(args)
                "arxiv_search" -> execArxivSearch(args)
                // v0.24: 自我扩展(install_skill = LLM 生成 skill 定义入库)
                "install_skill" -> { onProgress(context.getString(R.string.skill_progress_installing)); execInstallSkill(args) }
                // v0.46: 多 Agent 协作(委托子助手执行任务)
                "delegate_agent" -> { onProgress(context.getString(R.string.skill_progress_delegating)); execDelegateAgent(args) }
                // v1.55: Agent 工作流(结构化任务计划)
                "task_plan" -> { onProgress(context.getString(R.string.skill_progress_planning)); execTaskPlan(args) }
                "update_plan_step" -> execUpdatePlanStep(args)
                // v1.30: 群聊工具(多 Agent 群聊中发言/跳过/读取上下文)
                "channel_reply" -> execChannelReply(args)
                "channel_pass" -> execChannelPass(args)
                "channel_read_context" -> execChannelReadContext(args)
                // 文件管理类
                "list_dir" -> execListDir(args)
                "delete_file" -> execDeleteFile(args)
                "file_exists" -> execFileExists(args)
                // 公共目录与文件传输类
                "file_download" -> execFileDownload(args)
                "read_public_file" -> execReadPublicFile(args)
                "save_to_downloads" -> execSaveToDownloads(args)
                "list_public_files" -> execListPublicFiles(args)
                // Skill 管理类
                "list_skills" -> execListSkills(args)
                "uninstall_skill" -> execUninstallSkill(args)
                "disable_skill" -> execDisableSkill(args)
                // v1.95: 表情包库工具
                "list_stickers" -> execListStickers(args)
                "send_sticker" -> execSendSticker(args)
                // 新增工具
                "generate_image" -> { onProgress(context.getString(R.string.skill_progress_generating_image)); execGenerateImage(args) }
                "translate" -> execTranslate(args)
                "generate_qr" -> execGenerateQr(args)
                // JS 沙盒:让 LLM 能在 Skill 体系里执行 JavaScript 代码
                // (主入口为 ToolRegistry.execute_javascript,此处为 SkillExecutor 路由分支,供 skill 调用)
                "execute_javascript" -> execExecuteJavascript(args)
                else -> context.getString(R.string.skill_unknown_impl, skill.implementationKotlin)
            }
        }.onError { msg, t ->
            Logger.e("SkillExecutor", "skill ${skill.id} 执行失败: $msg", t)
        }.getOrNull() ?: context.getString(R.string.skill_exec_exception)
    }

    // H-SE1: 改用 resultOf{}(正确重抛 CancellationException)
    // L-SE13: 对非 JsonPrimitive 的值(对象/数组)用 AppJson 序列化为字符串,而非简单 toString()
    private fun parseArgs(json: String): Map<String, String> = resultOf {
        val obj = AppJson.decodeFromString(JsonObject.serializer(), json)
        obj.entries.associate { (k, v) ->
            val strValue = when (v) {
                is JsonPrimitive -> v.content
                else -> AppJson.encodeToString(JsonElement.serializer(), v)
            }
            k to strValue
        }
    }.onError { msg, _ ->
        Logger.w("SkillExecutor", "parseArgs 失败: $msg")
    }.getOrNull() ?: emptyMap()

    // ── 内置 skill 实现 ──────────────────────────────────────────────────────

    /** 读取应用沙盒内文件(限定 filesDir / cacheDir 子路径)。 */
    private fun execReadFile(args: Map<String, String>): String {
        val path = args["path"] ?: return context.getString(R.string.skill_missing_param_path)
        val file = resolveSandboxFile(path) ?: return context.getString(R.string.skill_path_violation, path)
        if (!file.exists()) return context.getString(R.string.skill_file_not_found, path)
        if (file.length() > 1_000_000) return context.getString(R.string.skill_file_too_large, file.length())
        // v1.52: 二进制文件检测 — 读取前 1024 字节,若含 NUL 字节或 UTF-8 替换字符占比过高则判定为二进制
        // v1.52 修订: 空文件直接返回空串;使用实际读入字节数判断,避免 read 未读满导致尾部 NUL 误判
        // L-SE12: 设计权衡 — 这里探测后又调 readText/readLines 重新读全文,存在重复 IO。
        // 复用 headBytes 需处理 offset/length/charset 三种读取模式的拼接,复杂度收益不划算
        // (1MB 上限下二次读取成本可接受)。保持当前实现,后续若支持大文件再改为流式探测+读取。
        if (file.length() == 0L) return ""
        val readLen = minOf(1024, file.length().toInt())
        val headBytes = ByteArray(readLen)
        val actualRead = file.inputStream().use { it.read(headBytes) }
        if (actualRead > 0) {
            val probe = if (actualRead < readLen) headBytes.copyOf(actualRead) else headBytes
            if (probe.any { it == 0.toByte() }) {
                return context.getString(R.string.skill_binary_file_nul, path)
            }
            val decoded = String(probe, Charsets.UTF_8)
            val replacementCount = decoded.count { it == '\uFFFD' }
            if (replacementCount.toDouble() / probe.size > 0.05) {
                return context.getString(R.string.skill_binary_file_utf8, path)
            }
        }
        // offset/length: 分段读取(起始行号 + 读取行数,默认 0=全部)
        val offset = args["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val length = args["length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        // encoding: 默认 utf-8(当前仅支持 utf-8/utf-16,其它回退 utf-8)
        val encoding = args["encoding"]?.takeIf { it.isNotBlank() } ?: "utf-8"
        val charset = when (encoding.lowercase()) {
            "utf-16", "utf-16le" -> Charsets.UTF_16
            "utf-16be" -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        if (offset > 0 || length > 0) {
            val lines = file.readLines(charset)
            val from = offset.coerceAtMost(lines.size)
            val to = if (length > 0) (from + length).coerceAtMost(lines.size) else lines.size
            return lines.subList(from, to).joinToString("\n")
        }
        return file.readText(charset)
    }

    /** 写入应用沙盒内文件。 */
    private fun execWriteFile(args: Map<String, String>): String {
        val path = args["path"] ?: return context.getString(R.string.skill_missing_param_path)
        val content = args["content"] ?: return context.getString(R.string.skill_missing_param_content)
        val append = args["append"]?.toBoolean() ?: false
        // create_dirs: 默认 true,自动创建父目录
        val createDirs = args["create_dirs"]?.toBoolean() ?: true
        val file = resolveSandboxFile(path) ?: return context.getString(R.string.skill_path_violation, path)
        if (createDirs) {
            file.parentFile?.mkdirs()
        } else {
            // 不自动建目录时,父目录不存在则报错
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                return context.getString(R.string.skill_parent_dir_not_exist, parent.absolutePath)
            }
        }
        val isOverwrite = file.exists() && !append
        if (append) file.appendText(content) else file.writeText(content)
        // v1.28: 返回绝对路径,让 MessageBubble 的 extractFilePaths 能匹配并渲染附件芯片
        val timestamp = System.currentTimeMillis()
        // v1.47: 返回内容预览(前 200 字符),让调用方能核对写入结果,而非只看到字节数盲信工具
        val previewLimit = 200
        val preview = if (content.length <= previewLimit) content else
            content.take(previewLimit) + context.getString(R.string.skill_write_preview_truncated, content.length)
        return context.getString(
            R.string.skill_write_result,
            file.length(),
            file.absolutePath,
            if (isOverwrite) context.getString(R.string.skill_yes) else context.getString(R.string.skill_no),
            timestamp,
            preview,
        )
    }

    /**
     * SSRF 防护:校验 URL 是否指向公网地址。
     *
     * 拒绝以下地址:
     *  - 主机名为 localhost
     *  - 回环地址 127.0.0.0/8、::1
     *  - 私网 10.0.0.0/8、172.16.0.0/12、192.168.0.0/16、IPv6 fc00::/7
     *  - 链路本地 169.254.0.0/16
     *  - 未指定地址、组播地址
     *
     * 同时用 [java.net.InetAddress.getAllByName] 解析 DNS 后二次校验 IP,
     * 防止 DNS rebinding 攻击(域名解析得到的实际 IP 仍指向内网)。
     *
     * @return true 表示安全(可继续请求);false 表示指向内网,调用方应拒绝
     */
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
    private suspend fun execHttpGet(args: Map<String, String>): String {
        val url = args["url"] ?: return context.getString(R.string.skill_missing_param_url)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return context.getString(R.string.skill_url_invalid_scheme)
        }
        // SSRF 防护:拒绝指向内网/回环地址的请求
        if (!validatePublicUrl(url)) return "URL 指向内网地址,已拒绝"
        // timeout: 默认 30 秒;max_size: 默认 1MB,限制响应体大小
        val timeoutSec = args["timeout"]?.toLongOrNull()?.coerceIn(1L, 300L) ?: 30L
        val maxSize = args["max_size"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1_048_576
        val req = Request.Builder().url(url).get()
        args["headers"]?.let { applyHeaders(req, it) }
        // 复用连接池,仅覆盖 callTimeout(OkHttp 5.x per-request 超时走 newBuilder)
        val timeoutClient = client.newBuilder()
            .callTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
        return try {
            timeoutClient.newCall(req.build()).execute().use { resp ->
                val body = resp.body.string()
                if (resp.isSuccessful) {
                    "HTTP ${resp.code}\n${body.take(maxSize)}"
                } else {
                    // 降级条件:仅 404(资源不存在,搜索可能有相关摘要);401/403 等业务错误不降级
                    if (resp.code == 404) {
                        val degraded = degradeToSearchSummary(url, resp.code)
                        if (degraded != null) return@use degraded
                    }
                    // HTTP 错误响应:返回状态码 + body 前 200 字
                    "HTTP ${resp.code}: ${body.take(200)}"
                }
            }
        } catch (e: java.io.IOException) {
            // 超时/连接失败降级到搜索摘要;若降级不可用则返回原错误
            val degraded = degradeToSearchSummary(url, -1, e.message ?: "")
            if (degraded != null) return degraded
            context.getString(R.string.skill_connect_failed, e.message ?: "")
        }
    }

    /** HTTP POST 请求。 */
    private fun execHttpPost(args: Map<String, String>): String {
        val url = args["url"] ?: return context.getString(R.string.skill_missing_param_url)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return context.getString(R.string.skill_url_invalid_scheme)
        }
        // SSRF 防护:拒绝指向内网/回环地址的请求
        if (!validatePublicUrl(url)) return "URL 指向内网地址,已拒绝"
        val body = args["body"] ?: ""
        // v1.52: 默认 Content-Type 带 charset=utf-8,避免中文 body 乱码
        val rawContentType = args["content_type"] ?: "application/json"
        val contentType = if (rawContentType.contains("charset", ignoreCase = true)) {
            rawContentType
        } else {
            "$rawContentType; charset=utf-8"
        }
        // timeout: 默认 30 秒
        val timeoutSec = args["timeout"]?.toLongOrNull()?.coerceIn(1L, 300L) ?: 30L
        val req = Request.Builder().url(url)
            .post(body.toRequestBody(contentType.toMediaType()))
        args["headers"]?.let { applyHeaders(req, it) }
        // 复用连接池,仅覆盖 callTimeout
        val timeoutClient = client.newBuilder()
            .callTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
        timeoutClient.newCall(req.build()).execute().use { resp ->
            val respBody = resp.body.string().take(1_000_000)
            return "HTTP ${resp.code}\n$respBody"
        }
    }

    /** 解析沙盒路径(限定 filesDir / cacheDir 下,防止路径穿越)。 */
    private fun resolveSandboxFile(path: String): File? {
        val filesDir = context.filesDir.canonicalPath
        val cacheDir = context.cacheDir.canonicalPath
        val target = File(context.filesDir, path).canonicalFile
        val targetPath = target.canonicalPath
        return if (targetPath.startsWith(filesDir) || targetPath.startsWith(cacheDir)) target else null
    }

    // H-SE1: 改用 resultOf{}(正确重抛 CancellationException)
    private fun applyHeaders(req: Request.Builder, headersJson: String) {
        resultOf {
            val obj = AppJson.decodeFromString(JsonObject.serializer(), headersJson)
            obj.forEach { (k, v) ->
                (v as? JsonPrimitive)?.content?.let { req.header(k, it) }
            }
        }.onError { msg, _ ->
            Logger.w("SkillExecutor", "applyHeaders 解析失败: $msg(原始: $headersJson)")
        }
    }

    // ── v0.24: 搜索与信息获取 ──────────────────────────────────────────────

    /**
     * web_search — 用配置好的 WebSearchService(SearXNG/Tavily)搜索。
     * 让 LLM 主动决定何时搜索,而非每次对话都注入。
     */
    private suspend fun execWebSearch(args: Map<String, String>): String {
        val service = webSearchService
            ?: return context.getString(R.string.skill_web_search_not_configured)
        val query = args["query"] ?: return context.getString(R.string.skill_missing_param_query)
        val maxResults = args["max_results"]?.toIntOrNull()?.coerceIn(1, 10) ?: 5
        // date_range / time_period: 时间范围(可选,二选一,time_period 兼容同义)
        val dateRange = args["date_range"]?.takeIf { it.isNotBlank() }
        val timePeriod = args["time_period"]?.takeIf { it.isNotBlank() }
        val options = buildMap<String, String> {
            dateRange?.let { put("date_range", it) }
            timePeriod?.let { put("time_period", it) }
        }
        // H-SE1: 改用 resultOf{}(正确重抛 CancellationException)
        val results = resultOf {
            service.searchWithOptions(query, maxResults, options)
        }.onError { msg, t ->
            // search-rate-limiter: 被限速时返回友好提示给 LLM，避免反复撞 429/402
            if (t is SearchRateLimitException) {
                val secs = (t.retryAfterMs / 1000).coerceAtLeast(1)
                return context.getString(R.string.skill_search_no_result, query) +
                    "\n（搜索服务被限速，请 ${secs} 秒后重试）"
            }
            Logger.w("SkillExecutor", "web_search 失败: $msg")
        }.getOrNull() ?: emptyList()
        if (results.isEmpty()) return context.getString(R.string.skill_search_no_result, query)
        val sb = StringBuilder(context.getString(R.string.skill_search_result_header, query, maxResults))
        results.forEachIndexed { idx, r ->
            sb.appendLine("[${idx + 1}] ${r.title}")
            sb.appendLine("    URL: ${r.url}")
            sb.appendLine("    摘要: ${r.snippet}")
        }
        return sb.toString().trimEnd()
    }

    /**
     * web_fetch — 抓取指定 URL 的网页正文(用 Jsoup 解析 HTML,移除噪声元素后取 body 纯文本)。
     * HTTP 失败或非 2xx 时降级:用 webSearchService 搜索该 URL 域名,返回前 3 条结果摘要。
     */
    private suspend fun execWebFetch(args: Map<String, String>): String {
        val url = args["url"] ?: return context.getString(R.string.skill_missing_param_url)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return context.getString(R.string.skill_url_invalid_scheme)
        }
        // SSRF 防护:拒绝指向内网/回环地址的请求
        if (!validatePublicUrl(url)) return "URL 指向内网地址,已拒绝"
        // max_length: 字符数上限,默认 50000;truncate: 默认 true,超出截断
        val maxLength = args["max_length"]?.toIntOrNull()?.coerceAtLeast(1) ?: 50_000
        val truncate = args["truncate"]?.toBoolean() ?: true
        val req = Request.Builder().url(url).get()
            .header("User-Agent", "Mozilla/5.0 (Android LLM client)")
        args["headers"]?.let { applyHeaders(req, it) }
        // 复用连接池,仅覆盖 callTimeout(约束:HTTP 请求 30 秒超时)
        val timeoutClient = client.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        return try {
            timeoutClient.newCall(req.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // 降级:搜索该站点域名,返回前 3 条结果摘要
                    val degraded = degradeToSearchSummary(url, resp.code)
                    if (degraded != null) return@use degraded
                    return@use "HTTP ${resp.code}"
                }
                val html = resp.body.string().take(200_000) // 上限 20 万字符
                // 用 Jsoup 解析,移除噪声元素后取 body 纯文本
                val doc = Jsoup.parse(html)
                doc.select("script, style, noscript, nav, footer, header, aside").remove()
                val bodyEl = doc.body()
                val text = bodyEl.text()
                // 折叠连续空白
                val cleaned = text.replace(Regex("\\s{3,}"), "\n\n").trim()
                val finalText = if (truncate) cleaned.take(maxLength) else cleaned
                "HTTP ${resp.code}\n$finalText"
            }
        } catch (e: java.io.IOException) {
            // 网络异常(超时/连接失败)降级到搜索摘要
            val degraded = degradeToSearchSummary(url, -1, e.message ?: "")
            if (degraded != null) return degraded
            throw e
        }
    }

    /**
     * 网页抓取/请求失败降级:用 webSearchService 搜索 URL 的域名,返回前 3 条结果摘要。
     *
     * @param url 原始请求 URL(用于提取域名作为搜索词)
     * @param httpCode HTTP 状态码(>0 表示收到响应,<0 表示网络异常)
     * @param errorMsg 网络异常时的错误信息(httpCode < 0 时使用)
     * @return 降级摘要文本;若搜索服务未配置、搜索失败或无结果,返回 null(由调用方返回原错误)
     */
    private suspend fun degradeToSearchSummary(
        url: String,
        httpCode: Int,
        errorMsg: String = "",
    ): String? {
        val service = webSearchService ?: return null
        val domain = resultOf { java.net.URI(url).host }
            .onError { msg, _ -> Logger.w("SkillExecutor", "降级搜索域名解析失败: $msg") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val results = resultOf { service.search(domain, 3) }
            .onError { msg, t ->
                // search-rate-limiter: 降级搜索被限速时仅记录日志，返回 null 让调用方展示原抓取错误
                if (t is SearchRateLimitException) {
                    Logger.w("SkillExecutor", "降级搜索被限速: ${t.retryAfterMs}ms")
                } else {
                    Logger.w("SkillExecutor", "降级搜索失败: $msg")
                }
            }
            .getOrNull() ?: return null
        if (results.isEmpty()) return null
        val fallbackMsg = if (httpCode > 0) {
            "网页抓取失败(HTTP $httpCode),以下是该站点相关搜索摘要:"
        } else {
            "网页抓取失败: ${errorMsg.ifBlank { "网络异常" }},以下是该站点相关搜索摘要:"
        }
        val sb = StringBuilder(fallbackMsg)
        results.forEachIndexed { idx, r ->
            sb.appendLine("[${idx + 1}] ${r.title} - ${r.url}")
            sb.appendLine("    ${r.snippet}")
        }
        return sb.toString().trimEnd()
    }

    /**
     * knowledge_search — 在用户知识库中全文搜索(标题 + 内容)。
     * 让 LLM 主动查知识库,而非依赖用户手动 @。
     *
     * v1.97: 新增 include_internal 参数控制是否返回内部开发文档(devdoc)。
     * 默认 false,仅搜索用户自建文档;用户问 muse app 功能时 LLM 应传 true。
     * 修复"内部 devdoc 被暴露给普通查询"的问题。
     */
    private suspend fun execKnowledgeSearch(args: Map<String, String>): String {
        val dao = knowledgeDocDao ?: return context.getString(R.string.skill_knowledge_not_configured)
        val query = args["query"] ?: return context.getString(R.string.skill_missing_param_query)
        if (query.isBlank()) return context.getString(R.string.skill_query_blank)
        val topK = args["top_k"]?.toIntOrNull()?.coerceIn(1, 50) ?: 5
        val threshold = args["threshold"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.3f
        // v1.97: include_internal — 是否包含内部开发文档(devdoc),默认 false
        val includeInternal = args["include_internal"]?.toBoolean() ?: false

        // v1.54: 优先用向量检索(语义匹配),无索引时降级到 LIKE 子串匹配
        val rs = ragService
        if (rs != null) {
            // H-SE1: 改用 resultOf{}(正确重抛 CancellationException)
            val ragConfig = resultOf { ragConfigProvider() }
                .onError { msg, _ -> Logger.w("SkillExecutor", "ragConfigProvider 失败: $msg") }
                .getOrNull() ?: io.zer0.muse.rag.RagConfig()
            val vectorResults = resultOf {
                rs.retrieve(query, topK, threshold, ragConfig)
            }.onError { msg, _ ->
                Logger.w("SkillExecutor", "向量检索失败,降级到 LIKE: $msg")
            }.getOrNull()
            if (vectorResults != null) {
                // v1.97: 过滤内部 devdoc(include_internal=false 时排除内部文档)
                // v1.133: 已统一用 SearchResult.isInternal 字段判断(RagService.retrieve 回填,
                // 数据源为 KnowledgeDocEntity.isInternal)。替代原 `docId.startsWith("devdoc-")` 硬编码,
                // 避免内部文档 id 命名变更后过滤失效。
                val filtered = if (includeInternal) {
                    vectorResults
                } else {
                    vectorResults.filterNot { it.isInternal }
                }
                if (filtered.isNotEmpty()) {
                    val sb = StringBuilder(context.getString(R.string.skill_knowledge_vector_header, query, filtered.size, threshold.toString(), topK))
                    filtered.forEachIndexed { idx, r ->
                        sb.appendLine("[${idx + 1}] 来源: ${r.docTitle} (相似度 ${"%.2f".format(r.score)})")
                        sb.appendLine("    片段: ${r.chunkContent.take(300)}")
                    }
                    return sb.toString().trimEnd()
                }
            }
            // 向量检索无结果,继续尝试 LIKE 搜索(兼容未索引的旧文档)
        }

        // 降级:LIKE 子串匹配(旧文档未分块索引)
        // M-KB1: 转义 LIKE 通配符(% _ \),配合 DAO 的 ESCAPE '\' 子句
        val allResults = dao.search(io.zer0.muse.data.knowledge.KnowledgeDocDao.escapeLikeQuery(query)).first()
        // v1.97: 过滤内部 devdoc(include_internal=false 时排除内部文档)
        // v1.133: 改用 isInternal 字段(与 MIGRATION_38_39 标记一致,替代原 fileType="devdoc" 硬编码)
        val visibleResults = if (includeInternal) {
            allResults
        } else {
            allResults.filterNot { it.isInternal }
        }
        val scored = visibleResults.map { doc ->
            // v1.97: 改进评分 — 标题完全匹配=1.0,标题包含=0.8,内容多次命中提升分数
            val titleMatch = doc.title.contains(query, ignoreCase = true)
            val contentMatches = doc.content.split(query, ignoreCase = true).size - 1
            val score = when {
                doc.title.equals(query, ignoreCase = true) -> 1.0f
                titleMatch -> 0.8f
                contentMatches > 0 -> (0.4f + minOf(contentMatches * 0.1f, 0.3f)).coerceAtMost(0.7f)
                else -> 0.0f
            }
            doc to score
        }.filter { it.second >= threshold }
        if (scored.isEmpty()) return context.getString(R.string.skill_knowledge_no_match, query, threshold.toString())
        val results = scored.sortedByDescending { it.second }.take(topK)
        val sb = StringBuilder(context.getString(R.string.skill_knowledge_like_header, query, results.size, threshold.toString(), topK))
        results.forEachIndexed { idx, (doc, score) ->
            sb.appendLine("[${idx + 1}] ${doc.title} (${doc.fileType}, ${doc.content.length} 字, score=$score)")
            val matchIdx = doc.content.indexOf(query, ignoreCase = true)
            val snippet = if (matchIdx < 0) {
                doc.content.take(200)
            } else {
                val start = (matchIdx - 80).coerceAtLeast(0)
                val end = (matchIdx + query.length + 120).coerceAtMost(doc.content.length)
                "..." + doc.content.substring(start, end) + "..."
            }
            sb.appendLine("    片段: $snippet")
        }
        return sb.toString().trimEnd()
    }

    /**
     * arxiv_search — arXiv 学术论文搜索。
     * 用 http://export.arxiv.org/api/query?search_query=all:查询&max_results=N
     * 返回 Atom XML,正则解析 entry/title/summary/link。
     */
    private fun execArxivSearch(args: Map<String, String>): String {
        val query = args["query"] ?: return context.getString(R.string.skill_missing_param_query)
        val maxResults = args["max_results"]?.toIntOrNull()?.coerceIn(1, 10) ?: 5
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        // category: 学科分类(如 cs.AI/cs.CL);date_from/date_to: 日期范围(YYYY-MM-DD)
        // M-SE10: category 用 URLEncoder 编码;date_from/date_to 校验 YYYY-MM-DD 格式
        val category = args["category"]?.takeIf { it.isNotBlank() }
            ?.let { java.net.URLEncoder.encode(it, "UTF-8") }
        val dateRegex = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        val dateFrom = args["date_from"]?.takeIf { it.isNotBlank() }
            ?.let { if (dateRegex.matches(it)) it else return "date_from 格式错误,应为 YYYY-MM-DD: $it" }
        val dateTo = args["date_to"]?.takeIf { it.isNotBlank() }
            ?.let { if (dateRegex.matches(it)) it else return "date_to 格式错误,应为 YYYY-MM-DD: $it" }
        val searchQuery = buildString {
            append("all:").append(encoded)
            category?.let { append("+AND+cat:").append(it) }
            if (dateFrom != null || dateTo != null) {
                val from = dateFrom?.replace("-", "")?.let { it + "0000" } ?: "000000000000"
                val to = dateTo?.replace("-", "")?.let { it + "2359" } ?: "999912312359"
                // submittedDate:[YYYYMMDD0000 TO YYYYMMDD2359](方括号预编码为 %5B/%5D)
                append("+AND+submittedDate:%5B").append(from).append("+TO+").append(to).append("%5D")
            }
        }
        // v1.71: 使用 HTTPS,避免明文传输的中间人风险
        // v1.109 修复: 显式指定 sortBy=relevance,避免复合查询被日期序覆盖
        val url = "https://export.arxiv.org/api/query?search_query=$searchQuery&max_results=$maxResults&sortBy=relevance&sortOrder=descending"
        val req = Request.Builder().url(url).get()
            .header("User-Agent", "muse/1.0 (Android LLM client)")
        // 复用连接池,仅覆盖 callTimeout(约束:HTTP 请求 30 秒超时)
        val timeoutClient = client.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        timeoutClient.newCall(req.build()).execute().use { resp ->
            if (!resp.isSuccessful) return context.getString(R.string.skill_arxiv_search_failed, resp.code)
            val xml = resp.body.string()
            // Atom XML entry 块:<entry>...<title>标题</title><summary>摘要</summary><link href="URL"/>...</entry>
            val entries = Regex(
                pattern = """<entry>([\s\S]*?)</entry>""",
                options = setOf(RegexOption.IGNORE_CASE),
            ).findAll(xml).take(maxResults).toList()
            if (entries.isEmpty()) return context.getString(R.string.skill_arxiv_no_result, query)
            val sb = StringBuilder(context.getString(R.string.skill_arxiv_result_header, query, entries.size))
            entries.forEachIndexed { idx, e ->
                val block = e.groupValues[1]
                val title = Regex("""<title>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
                    .find(block)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")
                    ?: "(无标题)"
                val summary = Regex("""<summary>([\s\S]*?)</summary>""", RegexOption.IGNORE_CASE)
                    .find(block)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")
                    ?.take(300) ?: "(无摘要)"
                val link = Regex("""<link[^>]*href="([^"]+)"[^>]*/>""", RegexOption.IGNORE_CASE)
                    .find(block)?.groupValues?.get(1) ?: "(无链接)"
                val published = Regex("""<published>([^<]+)</published>""", RegexOption.IGNORE_CASE)
                    .find(block)?.groupValues?.get(1)?.substringBefore("T") ?: ""
                sb.appendLine("[${idx + 1}] $title${if (published.isNotBlank()) " ($published)" else ""}")
                sb.appendLine("    URL: $link")
                sb.appendLine("    摘要: $summary")
            }
            return sb.toString().trimEnd()
        }
    }

    /**
     * install_skill — LLM 自己生成 skill 定义并入库(Phase 2 自我扩展)。
     *
     * LLM 输出 .skill.json 格式的 skill 定义字符串,经 [SkillImporter.parse] 校验后入库。
     * 安全约束:implementationKotlin 必须是 4 个内置实现之一,不支持任意代码执行。
     */
    private suspend fun execInstallSkill(args: Map<String, String>): String {
        val repo = skillRepository ?: return context.getString(R.string.skill_install_not_configured)
        val skillJson = args["skill_json"] ?: return context.getString(R.string.skill_missing_param_skill_json)
        // 预校验 JSON 格式与必需字段,返回人性化错误提示(区分格式错误/缺字段/类型错误)
        precheckSkillJson(skillJson)?.let { return it }
        when (val result = SkillImporter.parse(skillJson)) {
            is SkillImporter.Result.Ok -> {
                // H-SE2: 二次校验 id 不在内置保留 id 集合(防御纵深,parse 已校验一次)
                if (result.skill.id in SkillImporter.RESERVED_IDS) {
                    return context.getString(R.string.skill_install_conflict, result.skill.id)
                }
                // H-SE1: 改用 resultOf{}(正确重抛 CancellationException)
                resultOf { repo.upsert(result.skill) }
                    .onError { msg, _ -> return context.getString(R.string.skill_install_db_failed, msg) }
                return context.getString(R.string.skill_installed, result.skill.name, result.skill.id, result.skill.implementationKotlin)
            }
            is SkillImporter.Result.Err -> return context.getString(R.string.skill_install_validate_failed, result.reason)
        }
        return context.getString(R.string.skill_unknown_error)
    }

    /**
     * 预校验 skill JSON 定义,区分以下三类错误并返回人性化错误消息:
     *  1. JSON 格式错误(缺引号/冒号/括号不匹配)→ 由 kotlinx.serialization JsonException 捕获
     *  2. 缺少必需字段 → 解析成功后手工检查
     *  3. 字段类型错误(如 name 不是字符串)→ 解析成功后手工检查
     *
     * 通过此预校验后再交由 [SkillImporter.parse] 做业务校验(id slug / 保留 id / 允许的实现 key)。
     * @return 错误消息(可直接返回给 LLM);null 表示通过预校验
     */
    private fun precheckSkillJson(jsonText: String): String? {
        val requiredFields = listOf("name", "description", "category", "implementationKotlin", "parametersJson")

        // 1. 解析 JSON,用 resultOf 包裹(正确重抛 CancellationException)
        var jsonErrorMsg: String? = null
        val raw: JsonObject? = resultOf {
            AppJson.decodeFromString(JsonObject.serializer(), jsonText)
        }.onError { msg, throwable ->
            // 用 kotlinx.serialization JsonException 区分错误类型:
            //  - JsonException(含 JsonDecodingException)→ JSON 格式错误(引号/冒号/括号不匹配)
            //  - 其他异常 → 兜底归为格式错误(记录不同日志便于排查)
            val errorKind = if (throwable is SerializationException) "格式错误" else "非预期异常"
            jsonErrorMsg = msg
            Logger.w("SkillExecutor", "install_skill JSON 解析失败($errorKind, ${throwable?.javaClass?.simpleName}): $msg")
        }.getOrNull()

        if (raw == null) {
            return "JSON 格式错误: ${jsonErrorMsg ?: "无法解析"}。请检查字段引号、冒号、括号是否匹配。"
        }

        // 2. 检查必需字段
        for (field in requiredFields) {
            if (field !in raw) {
                return "缺少必需字段: $field。必需字段: name, description, category, implementationKotlin, parametersJson"
            }
        }

        // 3. 检查字段类型(以上必需字段都应为字符串)
        for (field in requiredFields) {
            val element = raw[field]
            if (element !is JsonPrimitive || !element.isString) {
                val actualType = when (element) {
                    is JsonPrimitive -> if (element.isString) "字符串" else "数字/布尔"
                    is JsonObject -> "对象"
                    is JsonArray -> "数组"
                    is JsonNull -> "null"
                    else -> "未知"
                }
                return "字段 $field 类型错误: 期望字符串,实际为 $actualType"
            }
        }

        return null
    }

    /**
     * v1.200/v1.201: 结构化委派入口 — 供 ChatViewModel 自动路由调用。
     *
     * 与 [execDelegateAgent] 的区别:
     *  - 接受 [DelegationContract.DelegationRequest] 结构化请求,而非 Map 参数
     *  - 返回 [DelegationContract.DelegationResult],含 metadata/subResults,便于链路追踪
     *  - 支持 TEAM 目标(委托给团队,由 [TeamWorkflowExecutor] 编排)
     *  - 支持 v1.201 暂停点(before_start / on_intermediate),与 [pauseManager] 配合
     *  - 支持 v1.201 链路追踪,通过 [delegationChainTracker] 通知 UI
     *
     * @param request 委派请求
     * @param policy 暂停策略(仅 pauseManager 非 null 时生效)
     */
    suspend fun delegateAgent(
        request: DelegationContract.DelegationRequest,
        policy: DelegationPauseManager.PausePolicy = DelegationPauseManager.PausePolicy(),
    ): DelegationContract.DelegationResult {
        val requestId = request.requestId
        val startedAt = System.currentTimeMillis()
        var finishedSuccess = false
        var finishedResultText = ""
        var finishedError: String? = null

        fun errorResult(msg: String): DelegationContract.DelegationResult {
            finishedError = msg
            val finishedAt = System.currentTimeMillis()
            return DelegationContract.DelegationResult(
                requestId = requestId,
                success = false,
                error = msg,
                metadata = DelegationContract.DelegationResult.ResultMetadata(
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    durationMs = finishedAt - startedAt,
                ),
            )
        }

        // v1.201: 入口取消检查
        if (pauseManager?.isCancelled(requestId) == true) {
            return errorResult("委派已被用户取消")
        }

        // v1.201: 通知链路开始
        delegationChainTracker?.onDelegationStarted(
            requestId = requestId,
            parentRequestId = null,
            task = request.task,
            targetType = when (request.targetType) {
                DelegationContract.DelegationRequest.TargetType.TEAM -> "team"
                else -> "assistant"
            },
            targetId = request.targetId,
            targetName = "",
        )

        // v1.202 改造 2: 非阻塞委派 — 派发后台任务,立即返回 taskId。
        // 主 agent 调用 delegate_agent(nonBlocking=true) 后可立即继续对话,
        // 子任务在后台执行,完成后通过 DeferredResultStore 回灌结果(由 ChatViewModel 订阅)。
        // 注意:
        //  - 暂停点在非阻塞模式下应跳过(不能阻塞后台任务),通过 bgRequest 清空 pausePoints + requireApproval 实现
        //  - 链路追踪仍要触发(已上方 onDelegationStarted),后台子任务完成时由其 delegateAgent 调用 onDelegationFinished
        //  - 若 deferredResultStore 或 subagentThreadStore 未注入,自动降级为阻塞模式
        if (request.nonBlocking && deferredResultStore != null && subagentThreadStore != null) {
            val taskId = "subagent-${System.currentTimeMillis()}-${(100..999).random()}"
            val threadId = request.threadId ?: "thread-${System.currentTimeMillis()}-${(100..999).random()}"
            val parentSessionId = request.parentSessionId ?: "default"
            val label = request.label ?: request.targetId
            val taskSummary = request.task.take(200)

            deferredResultStore.defer(taskId, parentSessionId, threadId, label, taskSummary)
            subagentThreadStore.beginRun(threadId, request.targetId, parentSessionId)

            // 后台协程:在 subagentThreadStore.runSerialized 内执行实际委派(串行,避免并发竞争)
            // bgRequest 清空 nonBlocking/pausePoints/requireApproval,让子调用走阻塞路径且不触发暂停
            val bgRequest = request.copy(
                nonBlocking = false,
                pausePoints = emptyList(),
                requireApproval = false,
            )
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val subResult = subagentThreadStore.runSerialized(threadId) {
                        delegateAgent(bgRequest, policy)
                    }
                    if (subResult.success) {
                        deferredResultStore.resolve(taskId, subResult.resultText)
                    } else {
                        deferredResultStore.fail(taskId, subResult.error ?: "未知错误")
                    }
                } catch (e: Throwable) {
                    Logger.e("SkillExecutor", "非阻塞委派后台任务异常: taskId=$taskId", e)
                    deferredResultStore.fail(taskId, e.message ?: "后台任务异常")
                }
                // onDelegationFinished 已在子 delegateAgent 的 finally 中触发,此处不再重复
            }

            return DelegationContract.DelegationResult(
                requestId = requestId,
                success = true,
                resultText = "任务已派发(非阻塞模式,taskId=$taskId)",
                threadId = threadId,
                taskId = taskId,
                metadata = DelegationContract.DelegationResult.ResultMetadata(
                    startedAt = startedAt,
                    finishedAt = System.currentTimeMillis(),
                    durationMs = System.currentTimeMillis() - startedAt,
                    assistantId = request.targetId,
                ),
            )
        }

        // v1.201: before_start / 高风险暂停点
        var effectiveTask = request.task
        if (pauseManager != null && (
            (policy.pauseOnHighRisk && request.requireApproval) ||
            request.pausePoints.contains("before_start")
        )) {
            val pauseReq = DelegationPauseManager.PauseRequest(
                requestId = "pause-$requestId-before-start",
                taskId = requestId,
                taskTitle = effectiveTask.take(80),
                taskDescription = effectiveTask,
                targetType = when (request.targetType) {
                    DelegationContract.DelegationRequest.TargetType.TEAM -> "team"
                    else -> "assistant"
                },
                targetName = request.targetId,
                reason = if (request.requireApproval) "高风险任务执行前确认" else "委派执行前确认",
                options = listOf(
                    DelegationPauseManager.PauseOption.APPROVE,
                    DelegationPauseManager.PauseOption.REJECT,
                    DelegationPauseManager.PauseOption.MODIFY,
                    DelegationPauseManager.PauseOption.CANCEL,
                ),
            )
            val resp = pauseManager.awaitPauseDecision(pauseReq, policy)
            when (resp.decision) {
                DelegationPauseManager.PauseDecision.CANCEL ->
                    return errorResult("用户取消委派")
                DelegationPauseManager.PauseDecision.REJECT ->
                    return errorResult("用户拒绝委派")
                DelegationPauseManager.PauseDecision.MODIFY -> {
                    // 不递归,直接用修改后的任务继续执行(避免 onDelegationStarted 重复触发)
                    effectiveTask = resp.modifiedInput?.takeIf { it.isNotBlank() } ?: effectiveTask
                }
                DelegationPauseManager.PauseDecision.APPROVE -> { /* 继续 */ }
            }
        }

        // v1.104: 递归深度兜底(当前 completeText 不执行工具不会递归,此为防御性)
        val depth = (delegateDepth.get() ?: 0) + 1
        if (depth > MAX_DELEGATE_DEPTH) {
            return errorResult(context.getString(R.string.skill_delegate_max_depth, MAX_DELEGATE_DEPTH))
        }
        delegateDepth.set(depth)
        try {
            if (request.targetType == DelegationContract.DelegationRequest.TargetType.TEAM) {
                val config = multiAgentConfigProvider()
                val team = config.teams.find { it.id == request.targetId }
                    ?: return errorResult("未找到团队: ${request.targetId}")
                return TeamWorkflowExecutor(
                    delegate = { req -> delegateAgent(req) },
                    llmAggregator = llmAggregator,
                    pauseManager = pauseManager,
                    pausePolicy = policy,
                    // v1.202: 把团队成员执行状态同步到链路追踪器,使 UI 链路卡片展示树形结构
                    delegationChainTracker = delegationChainTracker,
                    // v1.202: CONDITIONAL 节点用 LLM 做真条件判断(NO 则跳过)
                    chatService = chatService,
                ).execute(
                    workflow = team.workflow ?: DelegationContract.TeamWorkflow(),
                    teamTask = effectiveTask,
                    parentRequestId = request.requestId,
                    teamMembers = team.memberIds,
                    baseContext = request.contextMessages,
                )
            }
            if (request.targetType != DelegationContract.DelegationRequest.TargetType.ASSISTANT) {
                return errorResult("不支持的 targetType: ${request.targetType}")
            }
            val assistantId = request.targetId
            if (assistantId.isBlank()) {
                return errorResult(context.getString(R.string.skill_missing_param_assistant_id))
            }
            val task = effectiveTask.trim()
            if (task.isBlank()) {
                return errorResult(context.getString(R.string.skill_task_blank))
            }

            // v1.201: 取消检查
            if (pauseManager?.isCancelled(requestId) == true) {
                return errorResult("委派已被用户取消")
            }

            // 1. 取子助手配置
            val assistant = resultOf { assistantRepository.getById(assistantId) }
                .onError { msg, _ -> Logger.w("SkillExecutor", "delegateAgent getById 失败: $msg") }
                .getOrNull()
                ?: return errorResult(context.getString(R.string.skill_assistant_not_found, assistantId))

            // 2. 构造消息列表: system + contextMessages + user
            val messages = mutableListOf<UIMessage>()
            if (assistant.systemPrompt.isNotBlank()) {
                messages.add(UIMessage(role = MessageRole.SYSTEM, content = assistant.systemPrompt))
            }
            messages.addAll(request.contextMessages)
            val userContent = buildString {
                appendLine(task)
                if (request.attachments.isNotEmpty()) {
                    appendLine()
                    appendLine("附件/产物:")
                    request.attachments.forEachIndexed { idx, attachment ->
                        appendLine("${idx + 1}. $attachment")
                    }
                }
            }
            messages.add(UIMessage(role = MessageRole.USER, content = userContent))

            // 3. 调 LLM 跑一轮(用 withTimeoutOrNull 包裹,超时返回错误信息)
            val temperature = assistant.temperature ?: 0.7f
            val maxTokens = assistant.maxTokens ?: 1500
            // v1.202 改造 3: 子 agent 工具调用能力 — 给 completeText 传入精简工具集。
            //  - 当 depth < MAX_DELEGATE_DEPTH 时,子 agent 可用 delegate_agent 工具(允许再委派)
            //  - 当 depth >= MAX_DELEGATE_DEPTH 时,不传 delegate_agent(防递归爆炸)
            //  - 工具集精简:只给 delegate_agent 一个工具,不给危险工具(write_file/http_post 等)
            // 注意:completeText 已支持 tools 参数(见 ChatService.completeText 签名),
            // 这里只是把 tools 传进去,LLM 若决策调用 delegate_agent,会返回 completion.toolCalls
            val subagentTools: List<ToolDefinition>? = if (depth < MAX_DELEGATE_DEPTH) {
                listOf(buildDelegateAgentToolDefinition())
            } else {
                null
            }
            val completion = resultOf {
                withTimeoutOrNull(request.timeoutSec * 1000L) {
                    chatService.completeText(
                        messages = messages,
                        temperature = temperature,
                        maxTokens = maxTokens,
                        tools = subagentTools,
                    )
                }
            }.onError { msg, _ ->
                return errorResult(context.getString(R.string.skill_delegate_failed, assistant.name, msg))
            }.getOrNull()

            val finishedAt = System.currentTimeMillis()
            if (completion == null) {
                return DelegationContract.DelegationResult(
                    requestId = requestId,
                    success = false,
                    error = context.getString(R.string.skill_delegate_timeout, assistant.name, request.timeoutSec),
                    metadata = DelegationContract.DelegationResult.ResultMetadata(
                        startedAt = startedAt,
                        finishedAt = finishedAt,
                        durationMs = finishedAt - startedAt,
                        assistantId = assistantId,
                        assistantName = assistant.name,
                    ),
                )
            }

            // v1.202 改造 3: 处理子 agent 的 toolCalls(若 LLM 决策调用 delegate_agent)。
            // 取第一个 delegate_agent 工具调用,递归调用 delegateAgent,把子委派结果作为本次委派的结果。
            // 注意:这里只处理一轮 tool_call(不实现完整 agent loop),保持实现简洁;
            // 子子 agent 内部仍可继续委派(由 delegateDepth 兜底深度)。
            val toolCalls = completion.toolCalls
            if (!toolCalls.isNullOrEmpty()) {
                val delegateCall = toolCalls.firstOrNull { it.name == "delegate_agent" }
                if (delegateCall != null) {
                    val subArgs = parseArgs(delegateCall.arguments)
                    val subAssistantId = subArgs["assistantId"]?.trim()
                    val subTask = subArgs["task"]?.trim()
                    if (!subAssistantId.isNullOrBlank() && !subTask.isNullOrBlank()) {
                        val subRequest = DelegationContract.DelegationRequest(
                            requestId = "delegate-${System.currentTimeMillis()}-${(100..999).random()}",
                            task = subTask,
                            targetType = DelegationContract.DelegationRequest.TargetType.ASSISTANT,
                            targetId = subAssistantId,
                            parentSessionId = request.parentSessionId,
                            contextMessages = emptyList(),
                            timeoutSec = request.timeoutSec,
                            // 子 agent 的发起者即当前 assistant,用于结果 DM 回填
                            callerAssistantId = assistantId,
                        )
                        val subResult = delegateAgent(subRequest, policy)
                        val subFinishedAt = System.currentTimeMillis()
                        return DelegationContract.DelegationResult(
                            requestId = requestId,
                            success = subResult.success,
                            resultText = if (subResult.success) subResult.resultText
                                          else (subResult.error ?: "子委派失败"),
                            error = subResult.error,
                            metadata = DelegationContract.DelegationResult.ResultMetadata(
                                startedAt = startedAt,
                                finishedAt = subFinishedAt,
                                durationMs = subFinishedAt - startedAt,
                                assistantId = assistantId,
                                assistantName = assistant.name,
                            ),
                        )
                    }
                }
            }

            val result = completion.text.trim()
            if (result.isBlank()) {
                return DelegationContract.DelegationResult(
                    requestId = requestId,
                    success = false,
                    error = context.getString(R.string.skill_delegate_empty, assistant.name),
                    metadata = DelegationContract.DelegationResult.ResultMetadata(
                        startedAt = startedAt,
                        finishedAt = finishedAt,
                        durationMs = finishedAt - startedAt,
                        assistantId = assistantId,
                        assistantName = assistant.name,
                    ),
                )
            }

            // v1.201: 中间结果确认(pauseOnIntermediateResult)
            // v1.202 改造 4: MODIFY 决策回填 — 用户修改了中间结果时,采用修改后的版本作为最终结果
            if (pauseManager != null && (
                policy.pauseOnIntermediateResult ||
                request.pausePoints.contains("on_intermediate")
            )) {
                if (pauseManager.isCancelled(requestId)) {
                    return errorResult("委派已被用户取消")
                }
                val pauseReq = DelegationPauseManager.PauseRequest(
                    requestId = "pause-$requestId-intermediate",
                    taskId = requestId,
                    taskTitle = "中间结果确认",
                    taskDescription = task,
                    targetType = "assistant",
                    targetName = assistant.name,
                    reason = "中间结果产出后等待用户确认",
                    intermediateResult = result.take(500),
                    options = listOf(
                        DelegationPauseManager.PauseOption.APPROVE,
                        DelegationPauseManager.PauseOption.REJECT,
                        DelegationPauseManager.PauseOption.CANCEL,
                    ),
                )
                val resp = pauseManager.awaitPauseDecision(pauseReq, policy)
                when (resp.decision) {
                    DelegationPauseManager.PauseDecision.CANCEL ->
                        return errorResult("用户取消委派")
                    DelegationPauseManager.PauseDecision.REJECT ->
                        return errorResult("用户拒绝中间结果")
                    DelegationPauseManager.PauseDecision.APPROVE -> { /* 接受中间结果 */ }
                    DelegationPauseManager.PauseDecision.MODIFY -> {
                        // 用户修改了中间结果,采用修改后的版本
                        val modified = resp.modifiedInput?.takeIf { it.isNotBlank() }
                        if (modified != null) {
                            finishedSuccess = true
                            finishedResultText = modified
                            return DelegationContract.DelegationResult(
                                requestId = requestId,
                                success = true,
                                resultText = modified,
                                metadata = DelegationContract.DelegationResult.ResultMetadata(
                                    startedAt = startedAt,
                                    finishedAt = System.currentTimeMillis(),
                                    durationMs = System.currentTimeMillis() - startedAt,
                                    assistantId = assistantId,
                                    assistantName = assistant.name,
                                ),
                            )
                        }
                        // modifiedInput 为空时,降级为接受原中间结果
                    }
                }
            }

            // 4. 返回结构化结果
            finishedSuccess = true
            finishedResultText = result

            // v1.202: 委派完成后,通过 AgentDmRepository 把结果回填为一条 sub-agent → main-agent 的私信。
            // 这是一个轻量级集成,只在委派成功时多记录一条 DM,不改变委派主流程。
            // callerAssistantId 为 null(旧调用方)或 agentDmRepository 未注入时不发送,保持向后兼容。
            // AgentMessageEntity 无 type 字段,用 "[delegation_result]" 前缀标记消息类型。
            val callerId = request.callerAssistantId
            if (callerId != null && callerId != assistantId) {
                resultOf {
                    agentDmRepository?.sendMessage(
                        fromAgentId = assistantId,
                        toAgentId = callerId,
                        content = "[delegation_result] taskId=${requestId}\n${result}",
                    )
                }.onError { msg, t ->
                    Logger.w("SkillExecutor", "回填委派结果 DM 失败: $msg", t)
                }
            }

            return DelegationContract.DelegationResult(
                requestId = requestId,
                success = true,
                resultText = result,
                metadata = DelegationContract.DelegationResult.ResultMetadata(
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    durationMs = finishedAt - startedAt,
                    assistantId = assistantId,
                    assistantName = assistant.name,
                ),
            )
        } finally {
            delegateDepth.set(depth - 1)
            // v1.201: 通知链路结束 + 清理取消标记
            pauseManager?.clearCancellation(requestId)
            delegationChainTracker?.onDelegationFinished(
                requestId = requestId,
                success = finishedSuccess,
                resultText = finishedResultText,
                error = finishedError,
            )
        }
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

    /**
     * task_plan — 创建结构化任务计划。
     *
     * LLM 通过 tool_call 触发,参数:
     *  - title: 计划标题(必填)
     *  - steps: 步骤列表 JSON 数组,每步含 title 和可选 description
     *
     * 返回计划 ID + 格式化的计划摘要,LLM 可据此调用 update_plan_step 更新进度。
     * ChatViewModel 会拦截此工具调用,在 UI 中渲染检查清单。
     */
    private suspend fun execTaskPlan(args: Map<String, String>): String {
        val title = args["title"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_title)
        if (title.isBlank()) return context.getString(R.string.skill_title_blank)

        val stepsJson = args["steps"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_steps)

        // 解析 steps JSON 数组
        // H-SE1: 改用 resultOf{}(正确重抛 CancellationException)
        val steps = resultOf {
            val jsonArray = io.zer0.common.AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.json.JsonElement.serializer()),
                stepsJson,
            )
            jsonArray.mapIndexed { idx, element ->
                val obj = element.jsonObject
                val stepTitle = obj["title"]?.jsonPrimitive?.content?.trim() ?: "步骤 ${idx + 1}"
                val stepDesc = obj["description"]?.jsonPrimitive?.content?.trim() ?: ""
                io.zer0.muse.ui.taskcard.AgentPlanStep(
                    id = "step-$idx",
                    title = stepTitle,
                    description = stepDesc,
                )
            }
        }.onError { msg, _ ->
            Logger.w("SkillExecutor", "task_plan steps 解析失败: $msg")
        }.getOrNull()
            ?: return context.getString(R.string.skill_steps_invalid)

        if (steps.isEmpty()) return context.getString(R.string.skill_steps_empty)

        val planId = "plan-${System.currentTimeMillis()}"
        val plan = io.zer0.muse.ui.taskcard.AgentPlan(
            id = planId,
            title = title,
            steps = steps,
        )

        // 存入内存,供 update_plan_step 查询(ChatViewModel 也会拦截此调用更新 UI)
        activePlans[planId] = plan

        // 返回给 LLM 的确认信息
        val planSummary = buildString {
            appendLine("计划已创建。planId: $planId")
            appendLine("标题: $title")
            appendLine("步骤:")
            steps.forEachIndexed { idx, step ->
                appendLine("  $idx. ${step.title}${if (step.description.isNotBlank()) " — ${step.description.take(80)}" else ""}")
            }
            appendLine()
            appendLine("请按顺序执行各步骤。每完成一步,调用 update_plan_step(planId=\"$planId\", stepIndex=索引, status=\"done\") 更新进度。")
        }
        return planSummary
    }

    /**
     * update_plan_step — 更新计划步骤状态。
     *
     * 参数:
     *  - planId: 计划 id(task_plan 返回的)
     *  - stepIndex: 步骤索引(从 0 开始)
     *  - status: done/failed/in_progress/skipped
     *  - result: 可选的结果摘要
     */
    private suspend fun execUpdatePlanStep(args: Map<String, String>): String {
        val planId = args["planId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_plan_id)
        val stepIndex = args["stepIndex"]?.toIntOrNull()
            ?: return context.getString(R.string.skill_missing_param_step_index)
        val statusStr = args["status"]?.trim()?.lowercase()
            ?: return context.getString(R.string.skill_missing_param_status)
        val result = args["result"]?.trim() ?: ""

        val plan = activePlans[planId]
            ?: return context.getString(R.string.skill_plan_not_found, planId)

        if (stepIndex < 0 || stepIndex >= plan.steps.size) {
            return context.getString(R.string.skill_step_index_out_of_range, stepIndex, plan.steps.size)
        }

        val status = when (statusStr) {
            "done", "complete", "completed" -> io.zer0.muse.ui.taskcard.AgentPlanStepStatus.DONE
            "failed", "error" -> io.zer0.muse.ui.taskcard.AgentPlanStepStatus.FAILED
            "in_progress", "running" -> io.zer0.muse.ui.taskcard.AgentPlanStepStatus.IN_PROGRESS
            "skipped", "skip" -> io.zer0.muse.ui.taskcard.AgentPlanStepStatus.SKIPPED
            else -> return context.getString(R.string.skill_unknown_status, statusStr)
        }

        // 更新内存中的计划
        val updatedSteps = plan.steps.mapIndexed { idx, step ->
            if (idx == stepIndex) {
                step.copy(
                    status = status,
                    result = result,
                    startedAt = if (status == io.zer0.muse.ui.taskcard.AgentPlanStepStatus.IN_PROGRESS) System.currentTimeMillis() else step.startedAt,
                    finishedAt = if (status == io.zer0.muse.ui.taskcard.AgentPlanStepStatus.DONE || status == io.zer0.muse.ui.taskcard.AgentPlanStepStatus.FAILED) System.currentTimeMillis() else step.finishedAt,
                )
            } else step
        }
        activePlans[planId] = plan.copy(steps = updatedSteps)

        val step = updatedSteps[stepIndex]
        val completedCount = updatedSteps.count { it.status == io.zer0.muse.ui.taskcard.AgentPlanStepStatus.DONE }
        return "步骤 $stepIndex「${step.title}」已更新为: ${status.displayText}。进度: $completedCount/${updatedSteps.size}"
    }

    /**
     * v1.55: 当前活跃的计划内存缓存(planId → AgentPlan)。ChatViewModel 也会读取此缓存更新 UI。
     *
     * M-SE5: 改用 synchronized LinkedHashMap + accessOrder=true + removeEldestEntry,
     * 实现 LRU 淘汰(上限 50 条),避免长期运行的会话累积计划导致内存泄漏。
     */
    private val activePlans: MutableMap<String, io.zer0.muse.ui.taskcard.AgentPlan> =
        java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, io.zer0.muse.ui.taskcard.AgentPlan>(
                64, 0.75f, true, // accessOrder=true,按访问顺序排序
            ) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, io.zer0.muse.ui.taskcard.AgentPlan>,
                ): Boolean = size > 50
            },
        )

    /** v1.55: 供 ChatViewModel 读取活跃计划。 */
    fun getActivePlans(): Map<String, io.zer0.muse.ui.taskcard.AgentPlan> = activePlans.toMap()

    // ── v1.30: 群聊工具 ──────────────────────────────────────────────────

    /**
     * channel_reply — 在指定群聊中作为指定 agent 发送消息。
     *
     * LLM 通过 tool_call 触发,参数:
     *  - chatId: 群聊 id(必填)
     *  - assistantId: 发言 agent 的 id(必填)
     *  - body: 消息正文(必填)
     *
     * 执行流程:
     *  1. 从 AssistantRepository.getById 取 assistant(验证存在 + 获取 name)
     *  2. 调 GroupChatRepository.sendMessage 保存消息
     *  3. 返回"已发送消息: {body 前 50 字}"
     */
    private suspend fun execChannelReply(args: Map<String, String>): String {
        val repo = groupChatRepository
            ?: return context.getString(R.string.skill_channel_reply_not_configured)
        val chatId = args["chatId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_chat_id)
        val assistantId = args["assistantId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_assistant_id)
        val body = args["body"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_body)
        if (body.isBlank()) return context.getString(R.string.skill_body_blank)

        // 1. 取 assistant 配置(验证存在 + 获取 name)
        // H-SE1: 改用 resultOf{}(正确重抛 CancellationException)
        val assistant = resultOf { assistantRepository.getById(assistantId) }
            .onError { msg, _ -> Logger.w("SkillExecutor", "channel_reply getById 失败: $msg") }
            .getOrNull()
        val senderName = assistant?.name ?: context.getString(R.string.skill_unknown_agent)

        // 2. 保存消息到群聊
        repo.sendMessage(
            chatId = chatId,
            senderType = "assistant",
            senderId = assistantId,
            senderName = senderName,
            body = body,
        )

        // 3. 返回结果
        return context.getString(R.string.skill_message_sent, body.take(50))
    }

    /**
     * channel_pass — 本轮不发言(跳过)。
     *
     * 参数:
     *  - chatId: 群聊 id(必填)
     *  - assistantId: agent id(必填)
     *
     * 返回"已跳过"。
     */
    private suspend fun execChannelPass(args: Map<String, String>): String {
        val chatId = args["chatId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_chat_id)
        val assistantId = args["assistantId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_assistant_id)
        return context.getString(R.string.skill_skipped, chatId, assistantId)
    }

    /**
     * channel_read_context — 读取群聊最近消息作为上下文。
     *
     * 参数:
     *  - chatId: 群聊 id(必填)
     *  - limit: 可选,读取条数上限,默认 20
     *
     * 返回格式化的消息文本:
     * --- 群聊最近消息 ---
     * [发送者名 | 时间] 内容
     * ...
     */
    private suspend fun execChannelReadContext(args: Map<String, String>): String {
        val repo = groupChatRepository
            ?: return context.getString(R.string.skill_channel_read_not_configured)
        val chatId = args["chatId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_chat_id)
        val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20

        val messages = repo.getRecentMessages(chatId, limit)
        if (messages.isEmpty()) return context.getString(R.string.skill_chat_no_messages, chatId)

        val sb = StringBuilder(context.getString(R.string.skill_chat_messages_header))
        val timeFormatter = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        for (msg in messages) {
            val timeStr = timeFormatter.format(java.util.Date(msg.timestamp))
            sb.appendLine("[${msg.senderName} | $timeStr] ${msg.body}")
        }
        return sb.toString().trimEnd()
    }

    // ── 文件管理类 ──────────────────────────────────────────────────────

    /** list_dir — 列出目录下的文件和子目录(沙盒内)。 */
    private fun execListDir(args: Map<String, String>): String {
        val path = args["path"] ?: return context.getString(R.string.skill_missing_param_path)
        val dir = resolveSandboxFile(path) ?: return context.getString(R.string.skill_path_violation, path)
        if (!dir.exists()) return context.getString(R.string.skill_dir_not_exist, path)
        if (!dir.isDirectory) return context.getString(R.string.skill_not_dir, path)
        val files = dir.listFiles()
        if (files.isNullOrEmpty()) return "empty"
        return files.sortedBy { it.name }.joinToString("\n") { f ->
            val prefix = if (f.isDirectory) "[D]" else "[F]"
            val size = if (f.isFile) " (${f.length()}B)" else ""
            "$prefix ${f.name}$size"
        }
    }

    /** delete_file — 删除文件或空目录(沙盒内)。支持单个 path 或批量 paths。 */
    private fun execDeleteFile(args: Map<String, String>): String {
        // v1.47: 支持 paths 批量删除(逗号或换行分隔),兼容旧的单 path 参数
        val paths = args["paths"]?.takeIf { it.isNotBlank() }
            ?.split(",", "\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOfNotNull(args["path"]?.takeIf { it.isNotBlank() })
        if (paths.isEmpty()) return context.getString(R.string.skill_missing_param_path_or_paths)
        val results = paths.map { p ->
            val file = resolveSandboxFile(p) ?: return@map context.getString(R.string.skill_delete_path_violation, p)
            if (!file.exists()) return@map context.getString(R.string.skill_delete_not_exist, p)
            val ok = file.delete()
            if (ok) context.getString(R.string.skill_delete_success, p) else context.getString(R.string.skill_delete_failed, p)
        }
        val okCount = results.count { it.startsWith("[成功]") }
        return context.getString(R.string.skill_batch_delete_result, okCount, paths.size) + results.joinToString("\n")
    }

    /** file_exists — 判断文件是否存在(沙盒内)。 */
    private fun execFileExists(args: Map<String, String>): String {
        val path = args["path"] ?: return context.getString(R.string.skill_missing_param_path)
        val file = resolveSandboxFile(path) ?: return context.getString(R.string.skill_path_violation, path)
        return if (file.exists()) "exists" else "not_exists"
    }

    // ── 公共目录与文件传输 ──────────────────────────────────────────────

    /** file_download — 从 URL 下载文件到应用沙盒(限定 filesDir 下)。 */
    private suspend fun execFileDownload(args: Map<String, String>): String {
        val url = args["url"] ?: return "error: missing url"
        // H-SE4: 校验 url scheme,防止 SSRF/本地文件读取(file:// 协议)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return context.getString(R.string.skill_url_invalid_scheme_err)
        }
        val path = args["path"] ?: return "error: missing path"
        // H-SE3: 改用 resolveSandboxFile 校验路径,防止路径穿越(如 path="../../databases/main.db")
        val file = resolveSandboxFile(path) ?: return context.getString(R.string.skill_path_violation_err)
        // M-SE8: timeout 限制在 1..300 秒,防止无上限阻塞
        val timeoutSec = args["timeout"]?.toIntOrNull()?.coerceIn(1, 300) ?: 60

        file.parentFile?.mkdirs()

        return withTimeoutOrNull(timeoutSec * 1000L) {
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withTimeoutOrNull "error: HTTP ${response.code}"

                    response.body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    context.getString(R.string.skill_download_success, file.absolutePath, file.length())
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                context.getString(R.string.skill_download_failed, e.message ?: "")
            }
        } ?: context.getString(R.string.skill_download_timeout, timeoutSec)
    }

    /** read_public_file — 通过 SAF 读取用户分享/打开方式传入的文件 URI。 */
    private suspend fun execReadPublicFile(args: Map<String, String>): String {
        val uriStr = args["uri"] ?: return "error: missing uri"
        val encoding = args["encoding"]?.takeIf { it.isNotBlank() } ?: "utf-8"
        // L-SE14: 单独校验 charset,给出明确提示而非笼统的"读取失败"
        val cs = try {
            java.nio.charset.Charset.forName(encoding)
        } catch (e: java.nio.charset.UnsupportedCharsetException) {
            return context.getString(R.string.skill_unsupported_encoding, encoding)
        } catch (e: java.nio.charset.IllegalCharsetNameException) {
            return context.getString(R.string.skill_illegal_encoding, encoding)
        }

        return try {
            val uri = Uri.parse(uriStr)
            val input = context.contentResolver.openInputStream(uri)
                ?: return "error: cannot open uri"

            // M-SE6: 流式读取到 1MB 即停止,避免大文件 readText() 导致 OOM
            val limit = 1_000_000
            input.use { stream ->
                val reader = stream.bufferedReader(cs)
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var total = 0
                var truncated = false
                while (total < limit) {
                    val n = reader.read(buf, 0, minOf(buf.size, limit - total))
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    total += n
                }
                // 若还能继续读,说明文件超过 1MB
                if (reader.read() >= 0) truncated = true
                if (truncated) {
                    sb.append("\n... (已截断到 ${limit} 字符)")
                }
                sb.toString()
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            context.getString(R.string.skill_read_failed, e.message ?: "")
        }
    }

    /** save_to_downloads — 保存文本或本地文件到 Download 目录(Android 10+ 用 MediaStore)。 */
    private suspend fun execSaveToDownloads(args: Map<String, String>): String {
        val filename = args["filename"] ?: return "error: missing filename"
        val mimeType = args["mime_type"] ?: "text/plain"
        // v1.47: 支持 file_path 参数 — 直接从沙盒读取文件转存到 Download,无需先 read_file 再写文本
        val filePath = args["file_path"]?.takeIf { it.isNotBlank() }
        val content = args["content"]

        // content 与 file_path 二选一
        if (content == null && filePath == null) {
            return context.getString(R.string.skill_missing_content_or_filepath)
        }

        // 如果传了 file_path,读取本地文件字节(支持二进制,不强制文本)
        val srcFile = filePath?.let { resolveSandboxFile(it) }
        if (filePath != null) {
            if (srcFile == null) return context.getString(R.string.skill_path_violation_err)
            if (!srcFile.exists()) return context.getString(R.string.skill_source_file_not_exist, filePath)
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 用 MediaStore
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return "error: cannot create file in Downloads"

                resolver.openOutputStream(uri)?.use { output ->
                    if (srcFile != null) {
                        srcFile.inputStream().use { it.copyTo(output) }
                    } else {
                        // v1.74: 逻辑保证 filePath==null 时 content 非空(顶部已校验二选一),用 ?: "" 防御性降级
                        output.write((content ?: "").toByteArray())
                    }
                } ?: return "error: cannot open output stream"

                if (srcFile != null) {
                    context.getString(R.string.skill_saved_to_download_with_size, filename, srcFile.length())
                } else {
                    context.getString(R.string.skill_saved_to_download, filename)
                }
            } else {
                // Android 9 以下直接写公共 Download 目录
                // M-SE9: API < 29 需检查 WRITE_EXTERNAL_STORAGE 权限
                val hasWritePerm = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!hasWritePerm) {
                    return context.getString(R.string.skill_write_perm_needed)
                }
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadDir, filename)
                if (srcFile != null) {
                    srcFile.copyTo(file, overwrite = true)
                } else {
                    // v1.74: 逻辑保证 filePath==null 时 content 非空(顶部已校验二选一),用 ?: "" 防御性降级
                    file.writeText(content ?: "")
                }
                context.getString(R.string.skill_saved_to_path, file.absolutePath)
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            context.getString(R.string.skill_save_failed, e.message ?: "")
        }
    }

    /** list_public_files — 列出指定公共目录的文件(MediaStore 查询)。 */
    private suspend fun execListPublicFiles(args: Map<String, String>): String {
        val directory = args["directory"] ?: "Downloads"
        val limit = args["limit"]?.toIntOrNull() ?: 50

        return try {
            // v1.29: directory="all" 时遍历所有公共目录并合并结果
            if (directory.equals("all", ignoreCase = true)) {
                val allDirs = listOf("Downloads", "Documents", "Pictures", "Music", "Movies")
                val merged = StringBuilder()
                allDirs.forEachIndexed { idx, dir ->
                    merged.append("== $dir ==\n")
                    merged.append(queryPublicDir(dir, limit))
                    if (idx < allDirs.size - 1) merged.append("\n\n")
                }
                return merged.toString().trimEnd()
            }
            queryPublicDir(directory, limit)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            context.getString(R.string.skill_query_failed, e.message ?: "")
        }
    }

    /** 查询单个公共目录的文件列表(MediaStore)。v1.47: 输出含 content:// URI,可直接喂给 read_public_file。 */
    private fun queryPublicDir(directory: String, limit: Int): String {
        val collection = when (directory.lowercase()) {
            "downloads" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Files.getContentUri("external")
            "documents" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Files.getContentUri("external")
            else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }
        // v1.47: 多查 _ID 列,用于拼出 content:// URI,让 list → read 能力对称
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        // M-SE7: 用 selectionArgs 参数化绑定 directory,避免 SQL 注入;
        // 转义 LIKE 通配符(% _ \)并加 ESCAPE '\' 子句
        val escapedDir = directory.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
        } else null
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("%$escapedDir%")
        } else null
        // v1.109 修复: LIMIT 加上限 200,防止 LLM 传超大值导致资源耗尽
        val safeLimit = limit.coerceIn(1, 200)
        val cursor = context.contentResolver.query(
            collection, projection, selection, selectionArgs,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC LIMIT $safeLimit",
        ) ?: return context.getString(R.string.skill_query_failed_short)
        return cursor.use {
            val results = mutableListOf<String>()
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val name = it.getString(1) ?: "?"
                val size = it.getLong(2)
                val uri = android.content.ContentUris.withAppendedId(collection, id)
                // 格式: 文件名 (大小B) | uri=content://...
                results.add("$name (${size}B) | uri=$uri")
            }
            if (results.isEmpty()) context.getString(R.string.skill_dir_empty) else results.joinToString("\n")
        }
    }

    // ── Skill 管理类 ────────────────────────────────────────────────────

    /** list_skills — 列出已安装的 Skill,可选按 category 筛选。 */
    private suspend fun execListSkills(args: Map<String, String>): String {
        val repo = skillRepository ?: return context.getString(R.string.skill_list_not_configured)
        val category = args["category"]?.takeIf { it.isNotBlank() }
        val all = repo.observeAll.first()

        // 区分内置 skill 与用户安装 skill(按 id 是否在内置保留集合判断,不依赖 category 字段)
        val builtInIds = SkillImporter.RESERVED_IDS
        val builtInCount = all.count { it.id in builtInIds }
        val userCount = all.size - builtInCount

        if (all.isEmpty()) {
            return if (category != null) context.getString(R.string.skill_no_category_skill, category) else context.getString(R.string.skill_no_skill_installed)
        }

        // 过滤逻辑(不改变数据库存储的 category 值,只改变展示和过滤逻辑):
        //  - category = "user": 只返回用户安装的 skill
        //  - category = "custom"(向后兼容): 等同 "user",返回用户安装的 skill
        //  - category = "skill": 返回所有(内置 + 用户)
        //  - category = 其他具体值(如 file/http): 按该 category 过滤(保持原有行为)
        //  - category = null: 返回所有
        val filtered = when (category?.lowercase()) {
            "user", "custom" -> all.filter { it.id !in builtInIds }
            "skill" -> all
            null -> all
            else -> all.filter { it.category == category }
        }

        if (filtered.isEmpty()) {
            return context.getString(R.string.skill_no_category_skill, category)
        }

        // 头部统计 + 每条标注来源([内置] / [用户])
        val header = "共 ${all.size} 个 skill(内置 $builtInCount 个,用户安装 $userCount 个)"
        val body = filtered.joinToString("\n") { s ->
            val source = if (s.id in builtInIds) "[内置]" else "[用户]"
            "${s.id} | ${s.name} | ${s.category} | ${if (s.enabled) "enabled" else "disabled"} $source"
        }
        return "$header\n$body"
    }

    /** uninstall_skill — 卸载 Skill(按 id 删除,或按 name 查找后删除)。 */
    private suspend fun execUninstallSkill(args: Map<String, String>): String {
        val repo = skillRepository ?: return context.getString(R.string.skill_uninstall_not_configured)
        val id = args["id"]?.takeIf { it.isNotBlank() }
        val name = args["name"]?.takeIf { it.isNotBlank() }
        if (id == null && name == null) return context.getString(R.string.skill_missing_param_id_or_name)
        val targetId = id ?: run {
            // 按 name 查找
            val matched = repo.observeAll.first().find { it.name == name }
            matched?.id ?: return context.getString(R.string.skill_skill_not_found_by_name, name)
        }
        val existing = repo.getById(targetId) ?: return context.getString(R.string.skill_skill_not_found, targetId)
        repo.delete(targetId)
        return context.getString(R.string.skill_uninstalled, existing.name, targetId)
    }

    /** disable_skill — 禁用 Skill(不删除,只置 enabled=false)。 */
    private suspend fun execDisableSkill(args: Map<String, String>): String {
        val repo = skillRepository ?: return context.getString(R.string.skill_disable_not_configured)
        val id = args["id"]?.takeIf { it.isNotBlank() } ?: return context.getString(R.string.skill_missing_param_id)
        val existing = repo.getById(id) ?: return context.getString(R.string.skill_skill_not_found, id)
        repo.setEnabled(id, enabled = false)
        return context.getString(R.string.skill_disabled_result, existing.name, id)
    }

    // ── v1.95: 表情包库工具 ─────────────────────────────────────────────

    /**
     * list_stickers — 列出表情包库中可用的表情包(可按分类筛选)。
     *
     * 返回格式:每行一个,格式 `[{category}] {fileName} (id={id})`,便于 LLM 理解。
     * LLM 拿到列表后,可用 send_sticker(id=...) 发送指定表情包。
     *
     * @param args 可选 category(为空列出全部)
     */
    private suspend fun execListStickers(args: Map<String, String>): String {
        val repo = stickerLibraryRepository
            ?: return context.getString(R.string.skill_sticker_not_configured)
        val category = args["category"]?.takeIf { it.isNotBlank() }
        val items = repo.listStickers(category)
        if (items.isEmpty()) return context.getString(R.string.skill_sticker_list_empty)
        val sb = StringBuilder()
        sb.appendLine("共 ${items.size} 个表情包${if (category != null) "(分类: $category)" else ""}:")
        items.forEach { item ->
            sb.appendLine("[${item.category}] ${item.fileName} (id=${item.id})")
        }
        return sb.toString().trimEnd()
    }

    /**
     * send_sticker — 发送一个表情包给用户。
     *
     * 参数 id 必填(从 list_stickers 获取)。返回表情包的绝对路径,
     * MessageBubble 会扫描 content 中的 stickers/ 路径并渲染为图片。
     *
     * @param args 必填 id(表情包 id)
     */
    private suspend fun execSendSticker(args: Map<String, String>): String {
        val repo = stickerLibraryRepository
            ?: return context.getString(R.string.skill_sticker_not_configured)
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.skill_missing_param_id)
        val file = repo.getStickerFile(id)
            ?: return context.getString(R.string.skill_sticker_not_found, id)
        return context.getString(R.string.skill_sticker_sent, file.absolutePath)
    }

    // ── 新增工具实现 ──────────────────────────────────────────────────────

    /** generate_image — 调用 ImageService 生成图片。 */
    private suspend fun execGenerateImage(args: Map<String, String>): String {
        val service = imageService
            ?: return context.getString(R.string.skill_image_not_configured)
        val prompt = args["prompt"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.skill_missing_param_prompt)
        val size = args["size"]?.takeIf { it.isNotBlank() } ?: "1024x1024"
        // 读取用户在"设置→绘图供应商"配置的 ProviderConfig + modelId
        val (drawProviderConfig, configModelId) = imageDrawConfigProvider()
        // model 优先级:LLM 显式传入 > 用户配置的 ImageGenConfig.modelId > ImageService 兜底
        val model = args["model"]?.takeIf { it.isNotBlank() } ?: configModelId ?: ""
        // v1.x: 参考图(图生图),支持 URL / base64 / data URI。
        // 由用户在工具审批卡片中从相册选择后通过 argOverrides 注入(reference_image 键)。
        val referenceImage = args["reference_image"]?.takeIf { it.isNotBlank() }
        return resultOf {
            val urls = service.generate(
                prompt = prompt,
                params = io.zer0.ai.image.ImageGenParams(
                    model = model,
                    size = size,
                    responseFormat = "url",
                    n = 1,
                    referenceImageUri = referenceImage,
                ),
                providerConfig = drawProviderConfig,
            )
            if (urls.isEmpty()) return@resultOf context.getString(R.string.skill_image_no_result, prompt)
            context.getString(R.string.skill_image_generated, prompt, urls.joinToString("\n"))
        }.onError { msg, _ -> Logger.w("SkillExecutor", "generate_image 失败: $msg") }
            .getOrNull() ?: context.getString(R.string.skill_image_failed, prompt)
    }

    /** translate — 复用 ChatService.completeText 翻译文本。 */
    private suspend fun execTranslate(args: Map<String, String>): String {
        val text = args["text"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.skill_missing_param_text)
        val targetLang = args["target_language"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.skill_missing_param_target_lang)
        val sourceLang = args["source_language"]?.takeIf { it.isNotBlank() }
        val messages = listOf(
            io.zer0.ai.core.UIMessage(
                role = io.zer0.ai.core.MessageRole.SYSTEM,
                content = "你是一个专业翻译助手。请严格按照要求翻译,不要添加任何解释或额外内容。" +
                    "只返回翻译结果,不要包含原文。",
            ),
            io.zer0.ai.core.UIMessage(
                role = io.zer0.ai.core.MessageRole.USER,
                content = buildString {
                    if (sourceLang != null) append("从 $sourceLang 翻译到 $targetLang: ")
                    else append("翻译到 $targetLang: ")
                    append(text)
                },
            ),
        )
        return resultOf {
            val completion = chatService.completeText(messages = messages)
            val result = completion.text.trim()
            if (result.isBlank()) context.getString(R.string.skill_translate_empty)
            else result
        }.onError { msg, _ -> Logger.w("SkillExecutor", "translate 失败: $msg") }
            .getOrNull() ?: context.getString(R.string.skill_translate_failed, text.take(50))
    }

    /** generate_qr — 用 QrCodeGenerator 生成二维码并保存为文件。 */
    private suspend fun execGenerateQr(args: Map<String, String>): String {
        val content = args["content"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.skill_missing_param_content_qr)
        val size = args["size"]?.toIntOrNull()?.coerceIn(100, 2000) ?: 600
        return resultOf {
            val bitmap = io.zer0.muse.ui.qrcode.QrCodeGenerator.generateQrBitmap(content, size)
                ?: return@resultOf context.getString(R.string.skill_qr_gen_failed)
            val filename = "qr_${System.currentTimeMillis()}.png"
            val file = java.io.File(context.cacheDir, filename)
            file.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            context.getString(R.string.skill_qr_generated, file.absolutePath)
        }.onError { msg, _ -> Logger.w("SkillExecutor", "generate_qr 失败: $msg") }
            .getOrNull() ?: context.getString(R.string.skill_qr_gen_failed)
    }

    /**
     * execute_javascript — 在 WebView 沙盒中执行 JavaScript 代码。
     *
     * 委托 [JsSandbox.execute] 实现;SkillExecutor 是 suspend,可直接调用,无需 runBlocking。
     * Skill 路由:implementationKotlin = "execute_javascript" 时进入此分支。
     *
     * 参数:
     *  - code: 必填,JS 代码
     *  - timeout_ms: 可选,超时毫秒数,默认 10000
     *
     * 返回 JSON 字符串:{ result: string, logs: array, error: string? }
     */
    private suspend fun execExecuteJavascript(args: Map<String, String>): String {
        val code = args["code"]?.takeIf { it.isNotBlank() }
            ?: return """{"result":"","logs":[],"error":"参数 code 缺失或为空"}"""
        val timeoutMs = args["timeout_ms"]?.toLongOrNull()?.coerceIn(1L, 60_000L) ?: 10000L
        // 初始化 JsSandbox(幂等)— 注入 Application Context
        JsSandbox.init(context)

        val input = kotlinx.serialization.json.buildJsonObject {
            put("code", code)
            put("timeout_ms", timeoutMs.toString())
        }
        // 委托给 CodeExecutionTool.execute(suspend)— 复用统一的参数解析与结果序列化逻辑
        val output = CodeExecutionTool.execute(input)
        return output.toString()
    }

    companion object {
        /**
         * v1.104: delegate_agent 递归深度限制。
         *
         * 当前 delegate_agent 用 completeText(非工具调用),架构上不会递归。
         * 但为防御未来改动(如改为带 tools 的完整循环),用 ThreadLocal 计数器兜底:
         * 超过 MAX_DELEGATE_DEPTH 时拒绝继续嵌套,避免无限递归耗尽资源。
         */
        private val delegateDepth = ThreadLocal<Int>()
        private const val MAX_DELEGATE_DEPTH = 3

        /**
         * v1.202 改造 3: 构造 delegate_agent 的 [ToolDefinition](OpenAI 兼容 JSON Schema)。
         *
         * 用于给子 agent 传入精简工具集,让子 agent 决策调用 delegate_agent 时可继续委派。
         * 工具参数与 [BUILT_IN_SKILLS] 中的 delegate_agent skill 定义保持一致。
         */
        private fun buildDelegateAgentToolDefinition(): ToolDefinition {
            val schema = buildJsonObject {
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
                put("required", JsonArray(listOf(
                    JsonPrimitive("assistantId"), JsonPrimitive("task"),
                )))
            }.toString()
            return ToolDefinition(
                name = "delegate_agent",
                description = "把任务委托给指定子助手执行,用于多助手协作。传入 assistantId(助手 id)和 task(任务描述),可选 context(上下文)。子助手会用自己的人设和能力独立完成任务并返回结果。",
                parametersJsonSchema = schema,
            )
        }

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
                description = "用配置好的搜索引擎(SearXNG/Tavily)搜索网页。返回标题、URL 和摘要。当用户问到需要最新信息的问题时调用此工具。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "搜索关键词")
                        })
                        put("max_results", buildJsonObject {
                            put("type", "integer")
                            put("description", "可选,最大返回条数(1-10),默认 5")
                        })
                        put("date_range", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,时间范围,如 past_day/past_week/past_month/past_year")
                        })
                        put("time_period", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,同义于 date_range,兼容字段")
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
                description = "抓取指定 URL 的网页正文(自动去除 HTML 标签,返回纯文本)。用于读取 web_search 返回的 URL 全文内容。",
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
                description = "在用户的知识库中语义搜索(向量检索 + 标题/内容匹配)。当用户问到可能与已导入文档相关的问题时优先调用此工具。支持 top_k 和 threshold 参数控制返回条数和相似度阈值。用户问 muse app 自身功能(如怎么用深度思考/主动消息怎么设置)时,传 include_internal=true 可检索内置功能文档。",
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
                description = "列出表情包库中可用的表情包(可按分类筛选)。仅当用户已上传表情包时可用。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("category", buildJsonObject {
                            put("type", "string")
                            put("description", "可选,按分类筛选表情包(如 猫猫/狗子)。不传则列出全部")
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
                description = "向用户发送一个表情包。先用 list_stickers 查看可用表情包,再用此工具发送。",
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
                description = "根据文字描述生成图片。调用 AI 绘图模型(需配置 OpenAI 兼容绘图供应商)。返回图片 URL。",
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
            SkillEntity(
                id = "generate_qr",
                name = "生成二维码",
                description = "生成任意文本内容的二维码图片,保存到应用缓存目录并返回文件路径。内容可以是文本、URL 或其他字符串。",
                parametersJson = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "二维码内容,如 URL/文本/联系方式等")
                        })
                        put("size", buildJsonObject {
                            put("type", "integer")
                            put("description", "可选,二维码尺寸(像素),100-2000,默认 600")
                        })
                    })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("content"))))
                }.toString(),
                requiredJson = """["content"]""",
                implementationKotlin = "generate_qr",
                category = "custom",
            ),
        )
    }
}
