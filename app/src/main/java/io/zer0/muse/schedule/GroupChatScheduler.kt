package io.zer0.muse.schedule

import android.content.Context
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.AgentTeam
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.subagent.SubagentThreadStore
import io.zer0.muse.util.MusePatterns
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.groupchat.GroupChatEntity
import io.zer0.muse.data.groupchat.GroupChatGenerationLedgerEntity
import io.zer0.muse.data.groupchat.GroupChatMemoryRepository
import io.zer0.muse.ui.groupchat.FileAttachment
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.data.groupchat.GroupChatRepository
import io.zer0.muse.rag.RagConfig
import io.zer0.muse.rag.RagService
import io.zer0.muse.tools.DelegationChainTracker
import io.zer0.muse.tools.DelegationContract
import io.zer0.muse.tools.SkillExecutor
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.channel.ChannelToolFactory
import io.zer0.muse.tools.channel.GroupChatToolPolicy
import io.zer0.muse.tools.channel.toToolDefinition
import io.zer0.muse.transformer.SystemPromptAssembler
import io.zer0.muse.ui.groupchat.AgentActivityStatus
import io.zer0.muse.ui.groupchat.GroupChatActivityHub
import io.zer0.muse.vision.VisionBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 群聊调度器 — 用户发消息后串行触发群聊中的 Agent 轮转发言。
 *
 * 设计参考 参考开源项目 的多 Agent 群聊模型:
 *  1. 用户在群聊中发送一条消息
 *  2. 调度器取出群聊的 memberIds,对每个 assistant 串行执行:
 *     a. 构造上下文(最近 N 条消息 + 群聊身份提示)
 *     b. 调 [ChatService.streamChat] 流式调用(60s 超时,累积 ContentDelta)
 *     c. LLM 返回的文本就是该 agent 的发言
 *     d. 调 [GroupChatRepository.sendMessage] 保存 agent 回复
 *     e. 如果 LLM 返回 "[PASS]" 或空文本,跳过该 agent
 *  3. 返回本轮所有 agent 的回复列表
 *
 * v1.134 P1-4: 改为 streamChat 流式调用,消除 completeText 的 60s 整包阻塞。
 *
 * 错误处理:
 *  - 群聊不存在时返回空列表
 *  - Agent 不存在时跳过(记录日志)
 *  - LLM 调用失败时记录日志并跳过该 agent
 *  - 超时(60s)时跳过该 agent
 *
 * @param groupChatRepository 群聊仓库(读取群聊/消息、保存 agent 回复)
 * @param assistantRepository 取 assistant 配置(systemPrompt / temperature / maxTokens)
 * @param chatService 流式调用 LLM(v1.134 P1-4 改为 streamChat)
 * @param settings 读取当前选中的 Model(activeProvider)
 * @param appScope v1.111: 应用级协程,群聊轮转运行于此(切页/后台不中断)
 * @param appContext v1.111: 启动/停止前台服务
 * @param chatGenerationManager v1.111: 复用单聊的保活机制(前台服务通知/心跳/状态)
 * @param skillExecutor 改造 1: 群聊关联团队且团队有 workflow 时,委托 TeamWorkflowExecutor
 *        执行并行/条件/聚合编排(经 SkillExecutor.delegateAgent → TeamWorkflowExecutor)。
 * @param activityHub 群聊活动状态管理器(参考 参考开源项目 ActivityHub)。
 *        轮转各阶段 upsert agent 状态(VIEWING/REPLYING/NO_REPLY/ERROR/IDLE),
 *        UI 通过 StateFlow 响应式订阅展示活动 chip。
 * @param delegationChainTracker v1.202: 委派链路追踪器,在 [invokeAgent] 中调用
 *        onDelegationStarted/onDelegationFinished 同步链路状态,让主会话 UI 能看到群聊执行过程。
 *        为 null 时不记录链路(测试环境或未注入时降级)。
 */
class GroupChatScheduler(
    private val groupChatRepository: GroupChatRepository,
    private val assistantRepository: AssistantRepository,
    private val chatService: ChatService,
    private val settings: SettingsRepository,
    private val appScope: CoroutineScope,
    private val appContext: Context,
    private val chatGenerationManager: ChatGenerationManager,
    private val visionBridge: VisionBridge,
    private val ragService: RagService,
    private val skillExecutor: SkillExecutor,
    private val activityHub: GroupChatActivityHub,
    private val delegationChainTracker: DelegationChainTracker? = null,
    /**
     * v2.x: 群聊记忆隔离仓库,可为 null(测试环境或未注入时降级)。
     *
     * 非空时,每个 agent 完成回复后把本轮群聊摘要写入独立 fact store
     * ([GroupChatMemoryRepository.saveSummary]),**不**写入助手主记忆系统,
     * 避免群聊消息污染主对话上下文。
     * SystemPromptAssembler 注入时用 `<group_chat_memory>` 标签与主记忆区分。
     */
    private val groupChatMemoryRepository: GroupChatMemoryRepository? = null,
    /**
     * v1.0.53: SystemPromptAssembler 实例,用于在群聊 system prompt 中注入
     * 长期记忆(`<long_term_memory>`)和群聊记忆(`<group_chat_memory>`)。
     *
     * 修复"群聊记忆和其他地方记忆不互通"问题:
     *  - 之前:群聊 agent 回复后写入 GroupChatMemoryRepository,但下次构造 prompt 时没读取
     *  - 之前:群聊 agent 完全不知道用户的长期记忆
     *  - 现在:注入这两段记忆,让群聊 agent 既能看到用户画像,也能记住自己在群聊中的过往发言
     *
     * 为 null 时(测试环境)降级为不注入,与原行为一致。
     */
    private val systemPromptAssembler: io.zer0.muse.transformer.SystemPromptAssembler? = null,
    /**
     * v1.0.53 Phase 5: 子 agent 线程账本(持久化版)。
     *
     * 用于 Agent Phone(whisper 私聊)复用 Phase 1 统一 ThreadStore:
     *  - launchWhisper 调用 [SubagentThreadStore.getOrCreate] 注册线程
     *  - 私聊消息通过 [SubagentThreadStore.appendMessages] 写入
     *    `filesDir/subagent_sessions/<threadId>.jsonl`,App 重启后可恢复
     *
     * 为 null 时(测试环境)降级为不持久化,与原内存版行为一致。
     */
    private val subagentThreadStore: SubagentThreadStore? = null,
    /** B8-02: 全局工具注册表,群聊成员可调用常规工具(为 null 时仅 channel 三件套)。 */
    private val toolRegistry: ToolRegistry? = null,
) {

    /**
     * v1.111: 群聊活跃生成状态(供 ViewModel 订阅,切页后恢复 UI)。
     *
     * 与 [ChatGenerationManager.ActiveGeneration] 的区别:
     *  - ActiveGeneration 是单聊语义(assistantId = 占位消息 id),群聊用 "group" 占位
     *  - 本类额外暴露 currentSpeakerId/Name,供 UI 显示"谁在思考"
     */
    data class ActiveGroupGeneration(
        val chatId: String,
        val chatName: String,
        val isResponding: Boolean = true,
        val currentSpeakerId: String? = null,
        val currentSpeakerName: String? = null,
        val lastUpdatedAt: Long = System.currentTimeMillis(),
    )

    private val _activeGroupGeneration = MutableStateFlow<ActiveGroupGeneration?>(null)
    val activeGroupGeneration: StateFlow<ActiveGroupGeneration?> = _activeGroupGeneration.asStateFlow()

    /** v1.111: 是否有指定群聊的活跃生成(用于防重入)。v1.113: 改为按 chatId 精确检查。 */
    fun hasActiveGeneration(chatId: String): Boolean =
        chatGenerationManager.isStreaming("group:$chatId")
    // ── B5-02: 群聊生成账本 ─────────────────────────────────────────────

    private suspend fun saveLedger(
        ledger: GroupChatGenerationLedgerEntity?,
        chatId: String,
        mode: String,
        round: Int,
        memberIndex: Int,
        memberIdsJson: String? = null,
        status: String = "running",
    ): GroupChatGenerationLedgerEntity? {
        if (ledger == null) return null
        val updated = ledger.copy(
            chatId = chatId,
            mode = mode,
            round = round,
            memberIndex = memberIndex,
            memberIdsJson = memberIdsJson ?: ledger.memberIdsJson,
            status = status,
            updatedAt = System.currentTimeMillis(),
        )
        resultOf { groupChatRepository.upsertGenerationLedger(updated) }
            .onError { msg, t -> Logger.w(TAG, "群聊账本写入失败: $msg", t) }
        return updated
    }

    private fun parseLedgerMemberIds(ledger: GroupChatGenerationLedgerEntity?): List<String>? {
        if (ledger == null) return null
        val json = ledger.memberIdsJson
        if (json.isBlank() || json == "[]") return null
        return runCatching {
            AppJson.decodeFromString(ListSerializer(String.serializer()), json)
        }.getOrNull()
    }

    private suspend fun memberAlreadyRepliedSince(chatId: String, memberId: String, since: Long): Boolean {
        return groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE).any {
            it.senderType == "assistant" && it.senderId == memberId && it.timestamp >= since
        }
    }

    companion object {
        private const val TAG = "GroupChatScheduler"
        /** 单个 agent 的 LLM 调用超时(毫秒)。 */
        private const val AGENT_TIMEOUT_MS = 60_000L
        /** 默认上下文消息条数。 */
        private const val DEFAULT_CONTEXT_SIZE = 20
        /** LLM 返回此标记表示跳过本轮发言。 */
        private const val PASS_MARKER = "[PASS]"
        /** 默认采样温度。 */
        private const val DEFAULT_TEMPERATURE = 0.7f
        /** 默认最大 token 数。 */
        private const val DEFAULT_MAX_TOKENS = 1000
        /** v1.97: 单成员最大调用次数(含决策修复重试),防死循环。 */
        private const val MAX_INVOCATIONS_PER_MEMBER = 2
        /**
         * ActivityHub: 终态(NO_REPLY/ERROR/REPLYING)回退到 IDLE 的延迟(ms)。
         *
         * 让用户看得到"谁跳过/出错/回复了"再隐藏 chip,3s 足够扫一眼又不至于残留太久。
         */
        private const val ACTIVITY_IDLE_DELAY_MS = 3000L
        /** v1.97: @mention 正则 — 匹配 @name 形式,name 为非空白非标点字符序列。 */
        private val MENTION_REGEX = Regex("@([^\\s@,，。.!！?？:：;；()（）\\[\\]【】]+)")
        /**
         * v1.0.53 Phase 5: channel_* 工具决策轮次上限。
         *
         * 第 1 轮 LLM 可调用 channel_reply / channel_pass / channel_read_context;
         * 若调用了 read_context,把结果回填后进入第 2 轮(强制 reply/pass,不再给 read_context)。
         * 第 2 轮仍不决策 → 视为 implicitPass(对齐参考实现 implicitPass)。
         */
        private const val MAX_CHANNEL_DECISION_ROUNDS = 2
        /**
         * v1.0.53 Phase 5: 连续不决策的降级阈值。
         *
         * 同一 chatId 内,某成员连续 [DEMOTION_THRESHOLD] 轮(含决策修复重试)都 PASS
         * → 加入 demotedMembers,本场群聊后续轮次"仅被 @ 才发言"。
         */
        private const val DEMOTION_THRESHOLD = 2

        /** v1.0.53: 相邻 agent 发言间隔(毫秒) — 摊开 RPM 配额,防商汤 429。 */
        private const val GROUP_CHAT_AGENT_INTERVAL_MS = 3000L

        /**
         * 群聊 mood 格式要求 — 让 Agent 在回复前先输出内部腹稿。
         *
         * 与单聊[SystemPromptAssembler.MOOD_FORMAT_SECTION]保持一致,
         * UI 会自动剥离 <mood> 和 <think> 块并支持展开/折叠。
         */
        private val GROUP_CHAT_MOOD_SECTION = """
            MOOD 格式要求(每次回复必须遵守):
            每次回复前必须先写一个 <mood>...</mood> 块作为内部腹稿,然后再写正文。
            MOOD 块格式如下(4 个字段,每字段一行,内容简短):

            <mood>
            Vibe: <用户当前心情状态,1 句>
            Sparks: <这个问题触发什么联想,1 句>
            Reflections: <不确定的点或可能的坑,1 句>
            Will: <想怎么推进这段对话,1 句>
            </mood>

            正文(直接跟在 </mood> 后,不要空行)

            规则:
            - MOOD 是你的思考热身,不展示给用户看(系统会自动剥离)
            - 4 个字段都要写,哪怕一句话
            - 写完 MOOD 再写正文,正文遵守输出风格约束
            - 不要在正文里重复 MOOD 的内容
            - 如需展示深度推理,可在正文前写 <think>...</think> 块,系统同样会剥离并支持折叠展示
        """.trimIndent()
    }

    /**
     * v1.0.53 Phase 5: 群聊成员降级表(chatId → 已降级 assistantId 集合)。
     *
     * 连续 [DEMOTION_THRESHOLD] 轮不决策的成员加入此表,后续轮次默认跳过(仅被 @ 才发言)。
     * 内存态:App 重启后清空(可接受 — 降级是短期行为,避免长期记忆导致 agent 永久沉默)。
     */
    private val demotedMembers: MutableMap<String, MutableSet<String>> = ConcurrentHashMap()

    /** v1.0.53 Phase 5: 每个成员的连续 PASS 计数(chatId → assistantId → count)。 */
    private val consecutivePassCount: MutableMap<String, MutableMap<String, Int>> = ConcurrentHashMap()

    /**
     * v1.0.53 Phase 5: 判断成员是否已降级(本场群聊连续不决策)。
     * 已降级成员在未被 @ 提及时直接跳过,不调用 LLM。
     */
    private fun isDemoted(chatId: String, assistantId: String): Boolean =
        demotedMembers[chatId]?.contains(assistantId) == true

    /**
     * v1.0.53 Phase 5: 记录成员本轮 PASS,达到阈值则降级。
     *
     * @param implicit v1.0.53: true 表示 API 故障/超时导致的被动跳过,不参与降级计数
     *                 (避免商汤限流时 agent 被误降级成"仅@才发言")。
     */
    private fun recordPassAndMaybeDemote(chatId: String, assistantId: String, implicit: Boolean = false, assistantName: String = assistantId) {
        if (implicit) {
            Logger.i(TAG, "Agent「$assistantName」本轮被动跳过(implicit, API 故障/超时),不参与降级计数")
            return
        }
        val perChat = consecutivePassCount.computeIfAbsent(chatId) { ConcurrentHashMap() }
        val newCount = (perChat[assistantId] ?: 0) + 1
        perChat[assistantId] = newCount
        if (newCount >= DEMOTION_THRESHOLD) {
            demotedMembers.computeIfAbsent(chatId) { ConcurrentHashMap.newKeySet() }.add(assistantId)
            Logger.i(TAG, "Agent「$assistantName」($assistantId) 在群聊 $chatId 连续 $newCount 轮不决策,降级为仅@才发言")
        }
    }

    /** v1.0.53 Phase 5: 成员本轮正常回复,清零连续 PASS 计数(但已降级状态保留 — 降级是单向的)。 */
    private fun recordReply(chatId: String, assistantId: String) {
        consecutivePassCount[chatId]?.remove(assistantId)
    }

    /**
     * v1.111: 在应用级协程中启动群聊轮转(切页/后台不中断)。
     *
     * 复用 [ChatGenerationManager] 的保活机制 + [ChatGenerationService] 前台服务。
     * 生成运行在 appScope,不依赖 ViewModel 生命周期。
     *
     * 流程:
     *  1. 通过 [ChatGenerationManager.launchGeneration] 在 appScope 启动(自动取消旧生成)
     *  2. 异步读取用户名 + 群聊名,更新通知标题
     *  3. 启动前台服务
     *  4. 保存用户消息到 DB
     *  5. 调 [triggerAgentRoundRobin] 串行触发各 Agent 发言
     *  6. finally 停止前台服务 + 清空活跃状态
     *
     * @param chatId 群聊 id
     * @param text 用户消息正文
     * @param images 待发送图片(base64 列表,可为空)
     * @param fileAttachments 待发送文件附件(可为空)
     */
    fun launchRoundRobin(chatId: String, text: String, images: List<String>, fileAttachments: List<FileAttachment> = emptyList()) {
        chatGenerationManager.launchGeneration(
            sessionId = "group:$chatId",
            assistantId = "group",
            sessionTitle = "群聊生成中",
        ) {
            try {
                // 1. 读取用户名 + 群聊信息
                val userName = resultOf { settings.accountStateFlow.first().userName }
                    .getOrNull()?.ifBlank { "我" } ?: "我"
                val chat = groupChatRepository.getChat(chatId)
                val chatName = chat?.name ?: "群聊"
                chatGenerationManager.updateSessionTitle(chatName)

                _activeGroupGeneration.value = ActiveGroupGeneration(
                    chatId = chatId,
                    chatName = chatName,
                    isResponding = true,
                )

                // v1.0.29: 前台服务通知由 MuseApp ON_STOP 统一管理,不再在此启动

                // 3. 保存用户消息
                val imageBase64Json = AppJson.encodeToString(
                    ListSerializer(String.serializer()),
                    images,
                )
                val fileAttachmentsJson = if (fileAttachments.isNotEmpty()) {
                    AppJson.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(FileAttachment.serializer()),
                        fileAttachments,
                    )
                } else "[]"
                groupChatRepository.sendMessage(
                    chatId = chatId,
                    senderType = "user",
                    senderId = "local_user",
                    senderName = userName,
                    body = text,
                    imageBase64Json = imageBase64Json,
                    fileAttachmentsJson = fileAttachmentsJson,
                )


                // 新的一轮生成会取代旧的中断轮次,先清理残留账本
                resultOf { groupChatRepository.deleteGenerationLedgersByChatId(chatId) }
                    .onError { msg, t -> Logger.w(TAG, "群聊旧账本清理失败: $msg", t) }
                // 4. B5-02: 创建群聊生成账本,进程被杀后按断点重放
                val ledgerId = "gc-ledger-$chatId-${System.currentTimeMillis()}"
                val ledger = GroupChatGenerationLedgerEntity(
                    id = ledgerId,
                    chatId = chatId,
                    mode = chat?.discussionMode ?: "round_robin",
                    round = 1,
                    memberIndex = 0,
                    status = "running",
                )
                resultOf { groupChatRepository.upsertGenerationLedger(ledger) }
                    .onError { msg, t -> Logger.w(TAG, "群聊账本创建失败: $msg", t) }

                // 5. 触发 Agent 轮转(带账本 id)
                val replies = triggerAgentRoundRobin(
                    chatId,
                    onSpeakerChange = { speaker ->
                        _activeGroupGeneration.update {
                            it?.copy(
                                currentSpeakerId = speaker.id,
                                currentSpeakerName = speaker.name,
                            )
                        }
                    },
                    ledgerId = ledgerId,
                )

                if (replies.isEmpty()) {
                    Logger.i(TAG, "群聊「$chatName」本轮所有助手未发言")
                }
            } catch (ce: CancellationException) {
                Logger.i(TAG, "群聊 $chatId 轮转被取消(用户停止/新生成抢占)")
                throw ce
            } catch (t: Exception) {
                Logger.e(TAG, "群聊 $chatId 轮转失败", t)
            } finally {
                _activeGroupGeneration.value = null
                runCatching { ChatGenerationService.stop(appContext) }
            }
        }
    }

    /**
     * v1.111: 用户手动停止群聊生成。
     *
     * 取消 chatGenerationManager 的 streamJob(触发 block 的 CancellationException → finally 清理)。
     * v1.113: 只停止群聊的生成(sessionId="group:$chatId"),不影响单聊。
     */
    fun stop(chatId: String? = null) {
        if (chatId != null) {
            chatGenerationManager.stop("group:$chatId")
        } else {
            // 兼容旧调用:无 chatId 时取消所有群聊生成
            chatGenerationManager.stop(null)
        }
        _activeGroupGeneration.value = null
        runCatching { ChatGenerationService.stop(appContext) }
    }

    /**
     * B5-02: 应用启动时恢复被强杀中断的群聊生成账本。
     *
     * 残留账本表示上一轮群聊生成未完成,按账本记录的轮次/成员下标续跑,
     * 不重新触发已完成的成员,也不重复保存用户消息。
     */
    fun recoverInterruptedGenerations() {
        appScope.launch {
            val pending = resultOf { groupChatRepository.getPendingGenerationLedgers() }
                .onError { msg, t -> Logger.w(TAG, "读取群聊账本失败: $msg", t) }
                .getOrNull().orEmpty()
            if (pending.isEmpty()) return@launch
            for (ledger in pending) {
                if (chatGenerationManager.isStreaming("group:${ledger.chatId}")) continue
                Logger.i(
                    TAG,
                    "恢复群聊账本: chatId=${ledger.chatId} mode=${ledger.mode} round=${ledger.round} index=${ledger.memberIndex}",
                )
                resumeGroupLedger(ledger)
            }
        }
    }

    private fun resumeGroupLedger(ledger: GroupChatGenerationLedgerEntity) {
        chatGenerationManager.launchGeneration(
            sessionId = "group:${ledger.chatId}",
            assistantId = "group",
            sessionTitle = "群聊恢复中",
        ) {
            try {
                _activeGroupGeneration.value = ActiveGroupGeneration(
                    chatId = ledger.chatId,
                    chatName = resultOf { groupChatRepository.getChat(ledger.chatId)?.name }.getOrNull() ?: "群聊",
                    isResponding = true,
                )
                triggerAgentRoundRobin(
                    ledger.chatId,
                    onSpeakerChange = { speaker ->
                        _activeGroupGeneration.update {
                            it?.copy(
                                currentSpeakerId = speaker.id,
                                currentSpeakerName = speaker.name,
                            )
                        }
                    },
                    ledgerId = ledger.id,
                    startRound = ledger.round,
                    startMemberIndex = ledger.memberIndex,
                )
                // 正常结束(含 chat 不存在等空结果)都清理账本;取消则保留供下次恢复
                resultOf { groupChatRepository.deleteGenerationLedger(ledger.id) }
                    .onError { msg, t -> Logger.w(TAG, "群聊账本清理失败: $msg", t) }
            } catch (ce: CancellationException) {
                Logger.i(TAG, "群聊账本恢复被取消: ${ledger.id}")
                throw ce
            } catch (t: Exception) {
                Logger.e(TAG, "群聊账本恢复失败: ${ledger.id}", t)
                resultOf { groupChatRepository.deleteGenerationLedger(ledger.id) }
                    .onError { msg, e -> Logger.w(TAG, "群聊账本清理失败: $msg", e) }
            } finally {
                _activeGroupGeneration.value = null
                runCatching { ChatGenerationService.stop(appContext) }
            }
        }
    }
    // ════════════════════════════════════════════════════════════════
    // v2.x 群聊增强 — 重新生成 / 表决 / 总结 / 悄悄话
    // ════════════════════════════════════════════════════════════════

    /**
     * v2.x: 指定 AI 重新生成其最后一条消息。
     *
     * 删除该 AI 在群聊中的最后一条发言,然后重新触发它生成新回复。
     *
     * @param chatId 群聊 id
     * @param assistantId 要重新生成的 AI id
     */
    fun regenerateAgentMessage(chatId: String, assistantId: String) {
        chatGenerationManager.launchGeneration(
            sessionId = "group:$chatId",
            assistantId = "group",
            sessionTitle = "重新生成中",
        ) {
            try {
                val chat = groupChatRepository.getChat(chatId) ?: return@launchGeneration
                val assistant = resultOf { assistantRepository.getById(assistantId) }.getOrNull()
                    ?: return@launchGeneration
                val memberIds = groupChatRepository.parseMemberIds(chat)
                val memberNames = memberIds.mapNotNull { id ->
                    resultOf { assistantRepository.getById(id) }.getOrNull()?.name
                }
                // v1.0.29: invokeAgent 内部通过 resolveAssistantModel 解析助手专属模型

                _activeGroupGeneration.value = ActiveGroupGeneration(
                    chatId = chatId,
                    chatName = chat.name,
                    isResponding = true,
                    currentSpeakerId = assistant.id,
                    currentSpeakerName = assistant.name,
                )
                // v1.0.29: 前台服务通知由 MuseApp ON_STOP 统一管理

                val result = invokeAgent(chat, chatId, assistant, memberNames, isMentioned = false)
                if (result is AgentResult.Error) {
                    Logger.w(TAG, "重新生成失败: ${result.message}")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Exception) {
                Logger.e(TAG, "重新生成异常", t)
            } finally {
                _activeGroupGeneration.value = null
                runCatching { ChatGenerationService.stop(appContext) }
            }
        }
    }

    /**
     * v2.x: 发起表决 — 让所有成员对指定议题投票。
     *
     * 每个 AI 按自身人设投出赞成/反对票并给出理由。
     * 结果以 messageType="vote" 保存到群聊。
     *
     * @param chatId 群聊 id
     * @param topic 表决议题
     */
    fun launchVote(chatId: String, topic: String) {
        chatGenerationManager.launchGeneration(
            sessionId = "group:$chatId",
            assistantId = "group",
            sessionTitle = "表决中",
        ) {
            try {
                val chat = groupChatRepository.getChat(chatId) ?: return@launchGeneration
                val memberIds = groupChatRepository.parseMemberIds(chat)
                val assistants = memberIds.mapNotNull { id ->
                    resultOf { assistantRepository.getById(id) }.getOrNull()
                }
                if (assistants.isEmpty()) return@launchGeneration

                val memberNames = assistants.map { it.name }
                // v1.0.29: invokeAgentForVote 内部通过 resolveAssistantModel 解析助手专属模型

                _activeGroupGeneration.value = ActiveGroupGeneration(
                    chatId = chatId,
                    chatName = chat.name,
                    isResponding = true,
                )
                // v1.0.29: 前台服务通知由 MuseApp ON_STOP 统一管理

                // 保存系统提示消息
                groupChatRepository.sendMessage(
                    chatId = chatId,
                    senderType = "user",
                    senderId = "system",
                    senderName = "系统",
                    body = "发起了表决:$topic",
                    messageType = "system",
                )

                activityHub.clear(chatId)
                for (assistant in assistants) {
                    _activeGroupGeneration.update {
                        it?.copy(currentSpeakerId = assistant.id, currentSpeakerName = assistant.name)
                    }
                    val voteResult = invokeAgentForVote(chat, chatId, assistant, memberNames, topic)
                    if (voteResult != null) {
                        groupChatRepository.sendMessage(
                            chatId = chatId,
                            senderType = "assistant",
                            senderId = assistant.id,
                            senderName = assistant.name,
                            body = voteResult,
                            messageType = "vote",
                        )
                    }
                }

                // 保存表决结束系统提示
                groupChatRepository.sendMessage(
                    chatId = chatId,
                    senderType = "user",
                    senderId = "system",
                    senderName = "系统",
                    body = "表决结束",
                    messageType = "system",
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Exception) {
                Logger.e(TAG, "表决异常", t)
            } finally {
                _activeGroupGeneration.value = null
                runCatching { ChatGenerationService.stop(appContext) }
            }
        }
    }

    /**
     * v2.x: 结论总结器 — 让指定 AI 总结当前群聊讨论的共识与分歧。
     *
     * @param chatId 群聊 id
     * @param summarizerId 执行总结的 AI id(null 时用第一个成员)
     */
    fun launchSummary(chatId: String, summarizerId: String? = null) {
        chatGenerationManager.launchGeneration(
            sessionId = "group:$chatId",
            assistantId = "group",
            sessionTitle = "总结中",
        ) {
            try {
                val chat = groupChatRepository.getChat(chatId) ?: return@launchGeneration
                val memberIds = groupChatRepository.parseMemberIds(chat)
                val assistants = memberIds.mapNotNull { id ->
                    resultOf { assistantRepository.getById(id) }.getOrNull()
                }
                if (assistants.isEmpty()) return@launchGeneration

                val summarizer = summarizerId?.let { id -> assistants.find { it.id == id } }
                    ?: assistants.first()
                val memberNames = assistants.map { it.name }
                // v1.0.29: invokeAgentForSummary 内部通过 resolveAssistantModel 解析助手专属模型

                _activeGroupGeneration.value = ActiveGroupGeneration(
                    chatId = chatId,
                    chatName = chat.name,
                    isResponding = true,
                    currentSpeakerId = summarizer.id,
                    currentSpeakerName = summarizer.name,
                )
                // v1.0.29: 前台服务通知由 MuseApp ON_STOP 统一管理

                val recentMessages = groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE)
                val summary = invokeAgentForSummary(chat, summarizer, memberNames, recentMessages)
                if (summary.isNotBlank()) {
                    groupChatRepository.sendMessage(
                        chatId = chatId,
                        senderType = "assistant",
                        senderId = summarizer.id,
                        senderName = summarizer.name,
                        body = summary,
                        messageType = "summary",
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Exception) {
                Logger.e(TAG, "总结异常", t)
            } finally {
                _activeGroupGeneration.value = null
                runCatching { ChatGenerationService.stop(appContext) }
            }
        }
    }

    /**
     * v2.x: 发送用户悄悄话给指定 AI,并触发该 AI 回复(仅目标 AI 可见)。
     *
     * @param chatId 群聊 id
     * @param targetAssistantId 目标 AI id
     * @param text 悄悄话内容
     */
    fun launchWhisper(chatId: String, targetAssistantId: String, text: String) {
        chatGenerationManager.launchGeneration(
            sessionId = "group:$chatId",
            assistantId = "group",
            sessionTitle = "私信中",
        ) {
            try {
                val chat = groupChatRepository.getChat(chatId) ?: return@launchGeneration
                val assistant = resultOf { assistantRepository.getById(targetAssistantId) }.getOrNull()
                    ?: return@launchGeneration
                val memberIds = groupChatRepository.parseMemberIds(chat)
                val memberNames = memberIds.mapNotNull { id ->
                    resultOf { assistantRepository.getById(id) }.getOrNull()?.name
                }
                // v1.0.29: per-assistant 模型解析 — 用目标助手配置的模型,不再用全局 selectedModel
                val (model, providerConfig) = resolveAssistantModel(assistant)

                _activeGroupGeneration.value = ActiveGroupGeneration(
                    chatId = chatId,
                    chatName = chat.name,
                    isResponding = true,
                    currentSpeakerId = assistant.id,
                    currentSpeakerName = assistant.name,
                )

                // 1. 保存用户悄悄话(whisperTargetId 标记)
                val userName = resultOf { settings.accountStateFlow.first().userName }
                    .getOrNull()?.ifBlank { "我" } ?: "我"
                groupChatRepository.sendMessage(
                    chatId = chatId,
                    senderType = "user",
                    senderId = "local_user",
                    senderName = userName,
                    body = text,
                    whisperTargetId = targetAssistantId,
                )

                // v1.0.53 Phase 5: Agent Phone 复用 SubagentThreadStore —
                // whisper 也生成 threadId,私聊消息写入 filesDir/subagent_sessions/<threadId>.jsonl,
                // App 重启后可恢复私聊历史。parentSessionId 用 "agent_phone:<chatId>" 区分群聊上下文。
                val threadStore = subagentThreadStore
                val whisperThreadId = if (threadStore != null) {
                    resultOf {
                        threadStore.getOrCreate(
                            threadId = null,  // 每次私聊都复用同一 chatId+assistantId 的线程
                            parentSessionId = "agent_phone:$chatId",
                            assistantId = targetAssistantId,
                            label = "whisper:${chat.name}:${assistant.name}",
                        ).first  // 取 threadId
                    }.getOrNull()
                } else null

                // 把用户悄悄话持久化到 ThreadStore(便于续接)
                if (threadStore != null && whisperThreadId != null) {
                    resultOf {
                        threadStore.appendMessages(whisperThreadId, listOf(UIMessage(
                            role = MessageRole.USER,
                            content = text,
                        )))
                    }.onError { msg, _ -> Logger.w(TAG, "whisper 用户消息持久化失败: $msg") }
                }

                // 2. 触发目标 AI 回复(也是悄悄话)
                val recentMessages = groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE)
                val messages = buildWhisperMessages(chat.name, assistant, memberNames, recentMessages, text)
                val temperature = assistant.temperature ?: DEFAULT_TEMPERATURE
                val maxTokens = assistant.maxTokens ?: DEFAULT_MAX_TOKENS

                activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.REPLYING)
                val rawReply = resultOf {
                    withTimeoutOrNull(AGENT_TIMEOUT_MS) {
                        val builder = StringBuilder()
                        chatService.streamChat(
                            messages = messages,
                            model = model,
                            temperature = temperature,
                            maxTokens = maxTokens,
                            providerConfig = providerConfig,
                        ).collect { event ->
                            if (event is ChatStreamEvent.ContentDelta) builder.append(event.delta)
                        }
                        builder.toString().trim()
                    }
                }.getOrNull()

                if (!rawReply.isNullOrBlank()) {
                    val replyText = sanitizeAgentReply(rawReply)
                    if (replyText.isNotBlank() && replyText != PASS_MARKER) {
                        groupChatRepository.sendMessage(
                            chatId = chatId,
                            senderType = "assistant",
                            senderId = assistant.id,
                            senderName = assistant.name,
                            body = replyText,
                            mood = extractMood(rawReply),
                            reasoning = extractReasoning(rawReply),
                            whisperTargetId = "local_user",
                        )
                        // v1.0.53 Phase 5: 把 agent 回复也持久化到 ThreadStore
                        if (threadStore != null && whisperThreadId != null) {
                            resultOf {
                                threadStore.appendMessages(whisperThreadId, listOf(UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    content = replyText,
                                )))
                            }.onError { msg, _ -> Logger.w(TAG, "whisper agent 回复持久化失败: $msg") }
                        }
                    }
                }
                // v1.0.53 Phase 5: 记录本次 whisper run 到 ThreadStore(更新 runCount/lastSummary)
                if (threadStore != null && whisperThreadId != null) {
                    resultOf {
                        threadStore.recordRun(
                            threadId = whisperThreadId,
                            status = "whisper",
                            summary = rawReply?.take(200),
                            sessionPath = threadStore.sessionPathOf(whisperThreadId),
                        )
                    }.onError { msg, _ -> Logger.w(TAG, "whisper recordRun 失败: $msg") }
                }
                scheduleIdleTransition(chatId, assistant)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Exception) {
                Logger.e(TAG, "悄悄话异常", t)
            } finally {
                _activeGroupGeneration.value = null
                runCatching { ChatGenerationService.stop(appContext) }
            }
        }
    }

    /**
     * v2.x: 构造表决专用消息列表。
     * v1.0.29: 移除 model 参数,内部通过 [resolveAssistantModel] 解析助手专属模型。
     */
    private suspend fun invokeAgentForVote(
        chat: GroupChatEntity,
        chatId: String,
        assistant: AssistantEntity,
        memberNames: List<String>,
        topic: String,
    ): String? {
        activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.VIEWING)
        val recentMessages = groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE)

        // v1.0.29: per-assistant 模型解析
        val (model, providerConfig) = resolveAssistantModel(assistant)

        val systemContent = buildString {
            if (assistant.systemPrompt.isNotBlank()) {
                appendLine(assistant.systemPrompt)
                appendLine()
            }
            appendLine(SystemPromptAssembler.buildGroupChatHintSection(chat.name, memberNames, assistant.name))
            appendLine()
            appendLine("【表决模式】群聊正在对以下议题进行表决:")
            appendLine("议题:$topic")
            appendLine()
            appendLine("请按照你的人设和立场,投出你的票并给出理由。")
            appendLine("格式:")
            appendLine("立场:[赞成/反对/弃权]")
            appendLine("理由:[你的理由,2-3 句]")
        }

        val userContent = buildString {
            appendLine("${assistant.name},请对以下议题投票:")
            appendLine(topic)
            if (recentMessages.isNotEmpty()) {
                appendLine()
                appendLine("【讨论参考】")
                appendLine(formatMessageTranscript(recentMessages.takeLast(10)))
            }
        }

        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = systemContent),
            UIMessage(role = MessageRole.USER, content = userContent),
        )

        activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.REPLYING)
        val rawReply = resultOf {
            withTimeoutOrNull(AGENT_TIMEOUT_MS) {
                val builder = StringBuilder()
                chatService.streamChat(
                    messages = messages,
                    model = model,
                    temperature = assistant.temperature ?: DEFAULT_TEMPERATURE,
                    maxTokens = assistant.maxTokens ?: 500,
                    providerConfig = providerConfig,
                ).collect { event ->
                    if (event is ChatStreamEvent.ContentDelta) builder.append(event.delta)
                }
                builder.toString().trim()
            }
        }.getOrNull()

        scheduleIdleTransition(chatId, assistant)
        return rawReply?.takeIf { it.isNotBlank() }
    }

    /**
     * v2.x: 构造总结专用消息列表。
     * v1.0.29: 移除 model 参数,内部通过 [resolveAssistantModel] 解析助手专属模型。
     */
    private suspend fun invokeAgentForSummary(
        chat: GroupChatEntity,
        summarizer: AssistantEntity,
        memberNames: List<String>,
        recentMessages: List<GroupChatMessageEntity>,
    ): String {
        // v1.0.29: per-assistant 模型解析
        val (model, providerConfig) = resolveAssistantModel(summarizer)

        val systemContent = buildString {
            if (summarizer.systemPrompt.isNotBlank()) {
                appendLine(summarizer.systemPrompt)
                appendLine()
            }
            appendLine("你是群聊「${chat.name}」的总结者。")
            appendLine("请回顾讨论记录,总结:")
            appendLine("1. 达成共识的部分")
            appendLine("2. 尚有分歧的部分")
            appendLine("3. 建议的下一步行动")
            appendLine()
            appendLine("用简洁清晰的格式输出,不要输出 [PASS]。")
        }

        val userContent = buildString {
            appendLine("请总结以下群聊讨论:")
            appendLine()
            if (recentMessages.isNotEmpty()) {
                appendLine(formatMessageTranscript(recentMessages))
            }
        }

        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = systemContent),
            UIMessage(role = MessageRole.USER, content = userContent),
        )

        val rawReply = resultOf {
            withTimeoutOrNull(AGENT_TIMEOUT_MS) {
                val builder = StringBuilder()
                chatService.streamChat(
                    messages = messages,
                    model = model,
                    temperature = 0.3f,
                    maxTokens = 1000,
                    providerConfig = providerConfig,
                ).collect { event ->
                    if (event is ChatStreamEvent.ContentDelta) builder.append(event.delta)
                }
                builder.toString().trim()
            }
        }.getOrNull()

        return rawReply ?: ""
    }

    /**
     * v2.x: 构造悄悄话专用消息列表。
     * v1.0.29: 移除未使用的 model 参数。
     */
    private suspend fun buildWhisperMessages(
        chatName: String,
        assistant: AssistantEntity,
        memberNames: List<String>,
        recentMessages: List<GroupChatMessageEntity>,
        whisperText: String,
    ): List<UIMessage> {
        val systemContent = buildString {
            if (assistant.systemPrompt.isNotBlank()) {
                appendLine(assistant.systemPrompt)
                appendLine()
            }
            appendLine(SystemPromptAssembler.buildGroupChatHintSection(chatName, memberNames, assistant.name))
            appendLine()
            appendLine("【悄悄话】用户给你发了一条私信,仅你可见,其他群成员看不到。")
            appendLine("请以私密方式回复,回复内容也仅用户可见。")
        }

        val userContent = buildString {
            appendLine("${assistant.name},用户给你发了一条悄悄话:")
            appendLine(whisperText)
            appendLine()
            appendLine("请私下回复。直接输出内容即可。")
        }

        return listOf(
            UIMessage(role = MessageRole.SYSTEM, content = systemContent),
            UIMessage(role = MessageRole.USER, content = userContent),
        )
    }

     /**
      * 触发群聊 Agent 轮转发言。
      *
      * v1.97 改进(参考 参考开源项目-orig):
      *  - @mention 解析:从最近用户消息中提取 @agentName,被提及的 agent 优先发言
      *  - 决策修复:被 @提及的 agent 如果返回 [PASS],重试一次提示"你被@提及了"
      *  - Guard limit:单成员最多 MAX_INVOCATIONS_PER_MEMBER 次调用,防死循环
      *
      * @param chatId 群聊 id
      * @return 本轮所有 agent 的回复列表(已保存到 DB)
      */

    suspend fun triggerAgentRoundRobin(
        chatId: String,
        /** v1.104: 每个 agent 开始发言时回调(用于 UI 显示"谁在思考") */
        onSpeakerChange: ((AssistantEntity) -> Unit)? = null,
        /** B5-02: 群聊生成账本 id,用于进程被杀后按断点重放。 */
        ledgerId: String? = null,
        /** B5-02: 重放起始轮次(round_robin 恒为 1)。 */
        startRound: Int = 1,
        /** B5-02: 重放起始成员下标(0-based)。 */
        startMemberIndex: Int = 0,
    ): List<GroupChatMessageEntity> = withContext(Dispatchers.IO) {
        // 1. 取群聊配置
        val chat = groupChatRepository.getChat(chatId)
        if (chat == null) {
            Logger.w(TAG, "群聊 $chatId 不存在,跳过轮转")
            return@withContext emptyList()
        }

        // B5-02: 加载群聊生成账本(重放时使用)
        var ledger = if (ledgerId != null) {
            resultOf { groupChatRepository.getGenerationLedger(ledgerId) }.getOrNull()
        } else null
        // 改造 1: 检测 chat.teamId — 关联了团队且团队有 workflow 时,委托给 TeamWorkflowExecutor
        // 执行并行/条件/聚合编排(用户在 MultiAgentSettingsPage 配置的工作流不再失效)。
        // teamId 为空或团队无 workflow 时,保持现有串行轮转逻辑(向后兼容)。
        val teamId = chat.teamId
        if (teamId != null) {
            val multiAgentConfig = settings.multiAgentConfigCache
            val team = multiAgentConfig.teams.find { it.id == teamId }
            if (team != null && team.workflow != null) {
                Logger.i(TAG, "群聊「${chat.name}」关联团队「${team.name}」且有 workflow,委托 TeamWorkflowExecutor")
                // 从最近用户消息提取任务文本(与现有 launchRoundRobin 保存的用户消息对齐)
                val recentForTask = groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE)
                val userMessage = recentForTask.lastOrNull { it.senderType == "user" }?.body
                    ?.takeIf { it.isNotBlank() } ?: chat.name

                val workflowReplies = executeWithWorkflow(chat, chatId, team, userMessage)
                if (ledger != null) {
                    resultOf { groupChatRepository.deleteGenerationLedger(ledger.id) }
                        .onError { msg, t -> Logger.w(TAG, "群聊账本清理失败: $msg", t) }
                }
                return@withContext workflowReplies
            }
        }

        // v2.x: 根据讨论模式分流
        when (chat.discussionMode) {

            "auto" -> return@withContext executeAutoDiscussion(chat, chatId, onSpeakerChange, ledger?.id, startRound, startMemberIndex)
            "debate" -> return@withContext executeDebate(chat, chatId, onSpeakerChange, ledger?.id, startMemberIndex)
            "host" -> return@withContext executeHostMode(chat, chatId, onSpeakerChange, ledger?.id, startMemberIndex)
            else -> { /* round_robin: 继续走原有串行轮转逻辑 */ }
        }

        val memberIds = groupChatRepository.parseMemberIds(chat)
        if (memberIds.isEmpty()) {
            Logger.w(TAG, "群聊「${chat.name}」无成员,跳过轮转")
            return@withContext emptyList()
        }

        // 2. 解析成员显示名(用于群聊提示)
        val assistants = memberIds.mapNotNull { id ->
            resultOf { assistantRepository.getById(id) }.getOrNull()
                ?: run { Logger.w(TAG, "Agent $id 不存在,跳过"); null }
        }
        if (assistants.isEmpty()) {
            Logger.w(TAG, "群聊「${chat.name}」无有效成员,跳过轮转")
            return@withContext emptyList()
        }
        val memberNames = assistants.map { it.name }

        // v1.0.29: 不再获取全局 selectedModel — invokeAgent 内部通过 resolveAssistantModel
        // 解析每个助手配置的 modelId/providerId,实现 per-assistant 模型路由。

        // v1.97: 4. 解析 @mention — 从最近用户消息中提取被提及的 agent
        val recentMessages = groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE)
        val mentionedAgentIds = parseMentions(recentMessages, assistants)
        if (mentionedAgentIds.isNotEmpty()) {
            Logger.i(TAG, "群聊「${chat.name}」@提及: ${mentionedAgentIds.joinToString(", ")}")
        }

        // v1.97: 5. 重排 agent 顺序 — 被提及的优先,然后是其他成员

        val ledgerMemberIds = parseLedgerMemberIds(ledger)
        val orderedAssistants = if (ledgerMemberIds != null) {
            ledgerMemberIds.mapNotNull { id -> assistants.firstOrNull { it.id == id } }
        } else {
            assistants.sortedByDescending { it.id in mentionedAgentIds }
        }
        ledger = saveLedger(
            ledger, chatId, "round_robin", 1, startMemberIndex.coerceIn(0, orderedAssistants.size),
            memberIdsJson = groupChatRepository.serializeMemberIds(orderedAssistants.map { it.id }),
        )

        // ActivityHub: 清空上一轮残留活动状态,避免上一轮的 NO_REPLY/ERROR chip 干扰本轮视图。
        activityHub.clear(chatId)

        val replies = mutableListOf<GroupChatMessageEntity>()

        // 6. 串行触发每个 agent(被提及的有决策修复)
        // v1.0.53: 相邻 agent 之间加间隔,摊开 RPM 配额(商汤 API 限流较严格,
        // 连续调用多个 agent 会把每分钟请求数打爆导致 429 implicitPass)

        val firstIndex = startMemberIndex.coerceIn(0, orderedAssistants.size)
        for (agentIndex in firstIndex until orderedAssistants.size) {
            if (agentIndex > 0) {
                delay(GROUP_CHAT_AGENT_INTERVAL_MS)
            }
            val assistant = orderedAssistants[agentIndex]
            val isMentioned = assistant.id in mentionedAgentIds
            // B5-02: 标记当前成员为处理中
            ledger = saveLedger(ledger, chatId, "round_robin", 1, agentIndex, status = "running")
            // B5-02: 防重复 — 进程在落库后被杀且该成员已发言时,直接推进
            if (ledger != null && memberAlreadyRepliedSince(chatId, assistant.id, ledger.updatedAt)) {
                Logger.i(TAG, "Agent「${assistant.name}」已在断点后发言,跳过续跑")
                ledger = saveLedger(ledger, chatId, "round_robin", 1, agentIndex + 1, status = "running")
                continue
            }
            // v1.0.53 Phase 5: 降级检查 — 已降级成员未被 @ 时直接跳过,不调用 LLM
            if (!isMentioned && isDemoted(chatId, assistant.id)) {
                Logger.i(TAG, "Agent「${assistant.name}」已降级(连续不决策),未被 @ 提及,本轮自动跳过")
                activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.NO_REPLY)
                scheduleIdleTransition(chatId, assistant)
                ledger = saveLedger(ledger, chatId, "round_robin", 1, agentIndex + 1, status = "running")
                continue
            }
            // v1.104: 通知 UI 当前轮到谁发言
            onSpeakerChange?.invoke(assistant)
            when (val result = invokeAgent(chat, chatId, assistant, memberNames, isMentioned = isMentioned)) {
                is AgentResult.Reply -> {
                    replies.add(result.message)
                    // v1.0.53 Phase 5: 正常回复,清零连续 PASS 计数
                    recordReply(chatId, assistant.id)
                }
                is AgentResult.Pass -> {
                    // v1.0.53 Phase 5: 记录 PASS(implicit=API故障被动跳过时不计数),达到阈值则降级
                    recordPassAndMaybeDemote(chatId, assistant.id, implicit = result.implicit, assistantName = assistant.name)
                    // v1.97: 决策修复 — 被提及的 agent 如果 PASS,重试一次
                    if (isMentioned) {
                        Logger.i(TAG, "Agent「${assistant.name}」被@提及但 PASS,决策修复重试")
                        when (val retry = invokeAgent(chat, chatId, assistant, memberNames, isMentioned = true, isRepair = true)) {
                            is AgentResult.Reply -> {
                                replies.add(retry.message)
                                recordReply(chatId, assistant.id)
                            }
                            is AgentResult.Error -> Logger.w(TAG, "Agent「${assistant.name}」决策修复失败: ${retry.message}")
                            is AgentResult.Pass -> {
                                recordPassAndMaybeDemote(chatId, assistant.id, implicit = retry.implicit, assistantName = assistant.name)
                                Logger.i(TAG, "Agent「${assistant.name}」决策修复仍 PASS")
                            }
                        }
                    }
                }
                is AgentResult.Error -> Logger.w(TAG, "Agent「${assistant.name}」流式异常: ${result.message}")
            }
            // B5-02: 当前成员处理完成,推进到下一成员
            ledger = saveLedger(ledger, chatId, "round_robin", 1, agentIndex + 1, status = "running")
        }

        if (ledger != null) {
            resultOf { groupChatRepository.deleteGenerationLedger(ledger.id) }
                .onError { msg, t -> Logger.w(TAG, "群聊账本清理失败: $msg", t) }
        }

        Logger.i(TAG, "群聊「${chat.name}」本轮轮转完成,${replies.size}/${assistants.size} 个 agent 发言")
        replies
    }


    /**
     * 改造 1: 委托给 TeamWorkflowExecutor 执行团队工作流。
     *
     * 当群聊关联了团队([GroupChatEntity.teamId])且团队配置了 [AgentTeam.workflow] 时,
     * 由 [SkillExecutor.delegateAgent] 路由到 [io.zer0.muse.tools.TeamWorkflowExecutor],
     * 按工作流节点(SEQUENTIAL / PARALLEL / CONDITIONAL)执行,并根据聚合策略
     * (MERGE / VOTE / EXPERT_REVIEW / LLM_REVIEW)汇总结果。
     *
     * 执行完成后,把汇总结果作为一条群聊消息保存(以团队名义发言)。
     *
     * @param chat 群聊实体
     * @param chatId 群聊 id
     * @param team 关联的团队(已确认 workflow 非 null)
     * @param userMessage 本轮用户消息文本(作为团队任务)
     * @return 包含单条团队发言消息的列表;执行失败或无有效结果时返回空列表
     */
    private suspend fun executeWithWorkflow(
        chat: GroupChatEntity,
        chatId: String,
        team: AgentTeam,
        userMessage: String,
    ): List<GroupChatMessageEntity> {
        val requestId = "group-$chatId-${System.currentTimeMillis()}"
        val request = DelegationContract.DelegationRequest(
            requestId = requestId,
            task = userMessage,
            targetType = DelegationContract.DelegationRequest.TargetType.TEAM,
            targetId = team.id,
            parentSessionId = "group:$chatId",
        )

        val result = resultOf { skillExecutor.delegateAgent(request) }
            .onError { msg, t ->
                Logger.e(TAG, "群聊「${chat.name}」团队「${team.name}」工作流执行失败: $msg", t)
            }.getOrNull()

        if (result == null || !result.success || result.resultText.isBlank()) {
            Logger.w(TAG, "群聊「${chat.name}」团队「${team.name}」工作流无有效结果: ${result?.error ?: "null"}")
            return emptyList()
        }

        // 把工作流汇总结果作为群聊消息保存(以团队名义发言)
        val senderName = team.name.ifBlank { "团队" }
        val msgId = groupChatRepository.sendMessage(
            chatId = chatId,
            senderType = "assistant",
            senderId = team.id,
            senderName = senderName,
            body = result.resultText,
        )
        Logger.i(
            TAG,
            "群聊「${chat.name}」团队「${team.name}」工作流完成,resultText 长度=${result.resultText.length}",
        )
        return listOf(
            GroupChatMessageEntity(
                id = msgId,
                chatId = chatId,
                senderType = "assistant",
                senderId = team.id,
                senderName = senderName,
                body = result.resultText,
                timestamp = System.currentTimeMillis(),
            ),
        )
    }

    // ════════════════════════════════════════════════════════════════
    // v2.x 群聊讨论模式 — Auto / Debate / Host
    // ════════════════════════════════════════════════════════════════

    /**
     * Auto 自由讨论模式 — AI 之间自动连续对话,无需用户介入。
     *
     * 流程:
     *  1. 用户发消息后,第一轮所有成员依次发言(与 round_robin 相同)
     *  2. 之后每轮:取最近消息作为上下文,让每个 agent 再次发言
     *     - 每个 agent 看到上一轮其他 agent 的发言,自行决定是否继续
     *  3. 终止条件(任一满足):
     *     - 达到 [GroupChatEntity.autoMaxRounds] 上限
     *     - 某一轮所有 agent 都 PASS(无人有新内容要说)
     *     - 用户手动停止或发新消息(外部取消)
     *
     * 与 round_robin 的区别:round_robin 只跑一轮就停;Auto 连续多轮直到收敛或超限。
     *
     * @param chat 群聊实体
     * @param chatId 群聊 id
     * @param onSpeakerChange 每个 agent 开始发言时回调
     * @return 所有轮次累计的 agent 回复列表
     */

    private suspend fun executeAutoDiscussion(
        chat: GroupChatEntity,
        chatId: String,
        onSpeakerChange: ((AssistantEntity) -> Unit)?,
        ledgerId: String? = null,
        startRound: Int = 1,
        startMemberIndex: Int = 0,
    ): List<GroupChatMessageEntity> = withContext(Dispatchers.IO) {
        val memberIds = groupChatRepository.parseMemberIds(chat)
        if (memberIds.isEmpty()) {
            Logger.w(TAG, "Auto 模式:群聊「${chat.name}」无成员")
            return@withContext emptyList()
        }

        val assistants = memberIds.mapNotNull { id ->
            resultOf { assistantRepository.getById(id) }.getOrNull()
        }
        if (assistants.isEmpty()) {
            Logger.w(TAG, "Auto 模式:群聊「${chat.name}」无有效成员")
            return@withContext emptyList()
        }

        val memberNames = assistants.map { it.name }
        val maxRounds = chat.autoMaxRounds.coerceAtLeast(1)

        // 解析 @mention(第一轮仍需优先被@的成员)
        val recentMessages = groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE)
        val mentionedAgentIds = parseMentions(recentMessages, assistants)

        // B5-02: 加载/恢复账本,重放时沿用已记录的有序成员列表
        var ledger = if (ledgerId != null) {
            resultOf { groupChatRepository.getGenerationLedger(ledgerId) }.getOrNull()
        } else null
        val ledgerMemberIds = parseLedgerMemberIds(ledger)
        val orderedAssistants = if (ledgerMemberIds != null) {
            ledgerMemberIds.mapNotNull { id -> assistants.firstOrNull { it.id == id } }
        } else {
            assistants.sortedByDescending { it.id in mentionedAgentIds }
        }
        ledger = saveLedger(
            ledger, chatId, "auto", startRound.coerceAtLeast(1), startMemberIndex.coerceIn(0, orderedAssistants.size),
            memberIdsJson = groupChatRepository.serializeMemberIds(orderedAssistants.map { it.id }),
        )

        activityHub.clear(chatId)
        val allReplies = mutableListOf<GroupChatMessageEntity>()
        val firstRound = startRound.coerceAtLeast(1)

        for (round in firstRound..maxRounds) {
            Logger.i(TAG, "Auto 模式:群聊「${chat.name}」第 $round/$maxRounds 轮")
            var roundReplyCount = 0
            val roundStartIndex = if (round == firstRound) startMemberIndex.coerceIn(0, orderedAssistants.size) else 0

            for (memberIndex in roundStartIndex until orderedAssistants.size) {
                val assistant = orderedAssistants[memberIndex]
                // 每轮都检查是否被取消(用户停止 / 新消息抢占)
                if (!chatGenerationManager.isStreaming("group:$chatId")) {
                    Logger.i(TAG, "Auto 模式:生成被取消,终止于第 $round 轮")
                    return@withContext allReplies
                }

                val isMentioned = assistant.id in mentionedAgentIds && round == 1
                // B5-02: 标记当前成员处理中
                ledger = saveLedger(ledger, chatId, "auto", round, memberIndex, status = "running")
                if (ledger != null && memberAlreadyRepliedSince(chatId, assistant.id, ledger.updatedAt)) {
                    Logger.i(TAG, "Auto: Agent「${assistant.name}」已在断点后发言,跳过续跑")
                    ledger = saveLedger(ledger, chatId, "auto", round, memberIndex + 1, status = "running")
                    continue
                }

                onSpeakerChange?.invoke(assistant)

                val result = invokeAgent(
                    chat, chatId, assistant, memberNames,
                    isMentioned = isMentioned,
                    isRepair = false,
                )
                when (result) {
                    is AgentResult.Reply -> {
                        allReplies.add(result.message)
                        roundReplyCount++
                    }
                    is AgentResult.Pass -> {
                        // 第一轮被@但 PASS,重试一次
                        if (isMentioned) {
                            val retry = invokeAgent(chat, chatId, assistant, memberNames, isMentioned = true, isRepair = true)
                            if (retry is AgentResult.Reply) {
                                allReplies.add(retry.message)
                                roundReplyCount++
                            }
                        }
                    }
                    is AgentResult.Error -> Logger.w(TAG, "Auto: Agent「${assistant.name}」错误: ${result.message}")
                }
                // B5-02: 当前成员处理完成
                ledger = saveLedger(ledger, chatId, "auto", round, memberIndex + 1, status = "running")
            }

            // 本轮无人发言 → 讨论收敛,终止
            if (roundReplyCount == 0) {
                Logger.i(TAG, "Auto 模式:第 $round 轮全员 PASS,讨论收敛")
                break
            }
        }

        if (ledger != null) {
            resultOf { groupChatRepository.deleteGenerationLedger(ledger.id) }
                .onError { msg, t -> Logger.w(TAG, "群聊账本清理失败: $msg", t) }
        }

        Logger.i(TAG, "Auto 模式:群聊「${chat.name}」自由讨论完成,共 ${allReplies.size} 条回复")
        allReplies
    }

    /**
     * 辩论模式 — 固定链条顺序,每个 agent 必须回应上一人的观点。
     *
     * 链条:用户提问 → A 给方案 → B 找漏洞 → C 提改进 → (循环)
     * 成员顺序由 memberIds 列表决定,每个 agent 收到的 prompt 会强调:
     *  - 其在链条中的角色定位(提方案 / 找漏洞 / 改进)
     *  - 上一人的发言内容(必须针对性回应)
     *
     * 与 round_robin 的区别:不允许 PASS(辩论中每个角色必须发言),
     * 且 prompt 中注入角色定位和上一人发言。
     *
     * @param chat 群聊实体
     * @param chatId 群聊 id
     * @param onSpeakerChange 每个 agent 开始发言时回调
     * @return 本轮所有 agent 的回复列表
     */

    private suspend fun executeDebate(
        chat: GroupChatEntity,
        chatId: String,
        onSpeakerChange: ((AssistantEntity) -> Unit)?,
        ledgerId: String? = null,
        startMemberIndex: Int = 0,
    ): List<GroupChatMessageEntity> = withContext(Dispatchers.IO) {
        val memberIds = groupChatRepository.parseMemberIds(chat)
        if (memberIds.isEmpty()) {
            Logger.w(TAG, "辩论模式:群聊「${chat.name}」无成员")
            return@withContext emptyList()
        }

        val assistants = memberIds.mapNotNull { id ->
            resultOf { assistantRepository.getById(id) }.getOrNull()
        }
        if (assistants.size < 2) {
            Logger.w(TAG, "辩论模式:群聊「${chat.name}」成员不足 2 人,回退到 round_robin")
            return@withContext triggerRoundRobinFallback(chat, chatId, assistants, onSpeakerChange, ledgerId, startMemberIndex)
        }

        val memberNames = assistants.map { it.name }

        // B5-02: 加载/恢复账本
        var ledger = if (ledgerId != null) {
            resultOf { groupChatRepository.getGenerationLedger(ledgerId) }.getOrNull()
        } else null
        val ledgerMemberIds = parseLedgerMemberIds(ledger)
        val orderedAssistants = if (ledgerMemberIds != null) {
            ledgerMemberIds.mapNotNull { id -> assistants.firstOrNull { it.id == id } }
        } else {
            assistants
        }
        ledger = saveLedger(
            ledger, chatId, "debate", 1, startMemberIndex.coerceIn(0, orderedAssistants.size),
            memberIdsJson = groupChatRepository.serializeMemberIds(orderedAssistants.map { it.id }),
        )

        activityHub.clear(chatId)
        val replies = mutableListOf<GroupChatMessageEntity>()

        // 辩论角色:按位置分配(提方案 / 质疑 / 改进 / 补充)
        val roles = generateDebateRoles(orderedAssistants.size)
        val firstIndex = startMemberIndex.coerceIn(0, orderedAssistants.size)

        for (index in firstIndex until orderedAssistants.size) {
            val assistant = orderedAssistants[index]
            if (!chatGenerationManager.isStreaming("group:$chatId")) {
                Logger.i(TAG, "辩论模式:生成被取消")
                return@withContext replies
            }

            val role = roles[index]
            val previousReply = replies.lastOrNull()?.body
                ?: groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE)
                    .lastOrNull { it.senderType == "assistant" }?.body

            // B5-02: 标记当前成员处理中
            ledger = saveLedger(ledger, chatId, "debate", 1, index, status = "running")
            if (ledger != null && memberAlreadyRepliedSince(chatId, assistant.id, ledger.updatedAt)) {
                Logger.i(TAG, "辩论: Agent「${assistant.name}」已在断点后发言,跳过续跑")
                ledger = saveLedger(ledger, chatId, "debate", 1, index + 1, status = "running")
                continue
            }

            onSpeakerChange?.invoke(assistant)

            val result = invokeAgentForDebate(
                chat, chatId, assistant, memberNames,
                role = role,
                speakerIndex = index,
                totalSpeakers = orderedAssistants.size,
                previousReply = previousReply,
            )
            when (result) {
                is AgentResult.Reply -> replies.add(result.message)
                is AgentResult.Pass -> {
                    // 辩论中不允许 PASS,重试一次强调必须发言
                    Logger.i(TAG, "辩论:Agent「${assistant.name}」尝试 PASS,重试(辩论不允许跳过)")
                    val retry = invokeAgentForDebate(
                        chat, chatId, assistant, memberNames,
                        role = role,
                        speakerIndex = index,
                        totalSpeakers = orderedAssistants.size,
                        previousReply = previousReply,
                        isRepair = true,
                    )
                    if (retry is AgentResult.Reply) replies.add(retry.message)
                }
                is AgentResult.Error -> Logger.w(TAG, "辩论:Agent「${assistant.name}」错误: ${result.message}")
            }
            // B5-02: 当前成员处理完成
            ledger = saveLedger(ledger, chatId, "debate", 1, index + 1, status = "running")
        }

        if (ledger != null) {
            resultOf { groupChatRepository.deleteGenerationLedger(ledger.id) }
                .onError { msg, t -> Logger.w(TAG, "群聊账本清理失败: $msg", t) }
        }

        Logger.i(TAG, "辩论模式:群聊「${chat.name}」链条完成,${replies.size} 条发言")
        replies
    }

    /**
     * 主持人路由模式 — 由指定 AI 分析问题后动态派发任务给其他成员。
     *
     * 流程:
     *  1. 主持人 AI 先分析用户问题,输出 JSON 指定:哪些成员应该发言、以什么顺序、各自的任务
     *  2. 调度器按主持人的指示,依次调用被派发的成员
     *  3. 主持人最后可选地做一次总结
     *
     * 若 hostId 不存在或主持人分析失败,回退到 round_robin。
     *
     * @param chat 群聊实体
     * @param chatId 群聊 id
     * @param onSpeakerChange 每个 agent 开始发言时回调
     * @return 本轮所有 agent 的回复列表
     */

    private suspend fun executeHostMode(
        chat: GroupChatEntity,
        chatId: String,
        onSpeakerChange: ((AssistantEntity) -> Unit)?,
        ledgerId: String? = null,
        startMemberIndex: Int = 0,
    ): List<GroupChatMessageEntity> = withContext(Dispatchers.IO) {
        val memberIds = groupChatRepository.parseMemberIds(chat)
        if (memberIds.isEmpty()) {
            Logger.w(TAG, "主持人模式:群聊「${chat.name}」无成员")
            return@withContext emptyList()
        }

        val assistants = memberIds.mapNotNull { id ->
            resultOf { assistantRepository.getById(id) }.getOrNull()
        }
        if (assistants.isEmpty()) {
            Logger.w(TAG, "主持人模式:群聊「${chat.name}」无有效成员")
            return@withContext emptyList()
        }

        // 找到主持人
        val host = assistants.find { it.id == chat.hostId }
        if (host == null) {
            Logger.w(TAG, "主持人模式:hostId=${chat.hostId} 不在成员中,回退 round_robin")
            return@withContext triggerRoundRobinFallback(chat, chatId, assistants, onSpeakerChange, ledgerId, startMemberIndex)
        }

        val otherMembers = assistants.filter { it.id != host.id }
        if (otherMembers.isEmpty()) {
            Logger.w(TAG, "主持人模式:除主持人外无其他成员,回退 round_robin")
            return@withContext triggerRoundRobinFallback(chat, chatId, assistants, onSpeakerChange, ledgerId, startMemberIndex)
        }

        val memberNames = assistants.map { it.name }

        // B5-02: 加载/恢复账本,已记录的派发计划直接续跑,不再重复分析
        var ledger = if (ledgerId != null) {
            resultOf { groupChatRepository.getGenerationLedger(ledgerId) }.getOrNull()
        } else null

        activityHub.clear(chatId)

        val dispatchPlan: List<AssistantEntity>
        val ledgerMemberIds = parseLedgerMemberIds(ledger)
        if (ledgerMemberIds != null) {
            dispatchPlan = ledgerMemberIds.mapNotNull { id -> otherMembers.firstOrNull { it.id == id } }
            if (dispatchPlan.isEmpty()) {
                Logger.w(TAG, "主持人模式:账本派发计划无效,回退 round_robin")
                return@withContext triggerRoundRobinFallback(chat, chatId, assistants, onSpeakerChange, ledgerId, startMemberIndex)
            }
        } else {
            // 1. 主持人分析用户问题,输出派发计划
            val recentMessages = groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE)
            val lastUserMsg = recentMessages.lastOrNull { it.senderType == "user" }?.body ?: chat.name
            onSpeakerChange?.invoke(host)
            val plan = analyzeWithHost(chat, host, otherMembers, memberNames, lastUserMsg)
            if (plan.isEmpty()) {
                Logger.w(TAG, "主持人模式:主持人未给出有效派发计划,回退 round_robin")
                return@withContext triggerRoundRobinFallback(chat, chatId, assistants, onSpeakerChange, ledgerId, startMemberIndex)
            }
            dispatchPlan = plan
            ledger = saveLedger(
                ledger, chatId, "host", 1, startMemberIndex.coerceIn(0, dispatchPlan.size),
                memberIdsJson = groupChatRepository.serializeMemberIds(dispatchPlan.map { it.id }),
            )
        }

        Logger.i(TAG, "主持人模式:主持人「${host.name}」派发 ${dispatchPlan.size} 个成员: ${dispatchPlan.joinToString { it.name }}")

        // 2. 按主持人指示依次调用被派发成员
        val replies = mutableListOf<GroupChatMessageEntity>()
        val firstIndex = startMemberIndex.coerceIn(0, dispatchPlan.size)
        for (index in firstIndex until dispatchPlan.size) {
            val member = dispatchPlan[index]
            if (!chatGenerationManager.isStreaming("group:$chatId")) {
                Logger.i(TAG, "主持人模式:生成被取消")
                return@withContext replies
            }
            // B5-02: 标记当前成员处理中
            ledger = saveLedger(ledger, chatId, "host", 1, index, status = "running")
            if (ledger != null && memberAlreadyRepliedSince(chatId, member.id, ledger.updatedAt)) {
                Logger.i(TAG, "主持人模式:Agent「${member.name}」已在断点后发言,跳过续跑")
                ledger = saveLedger(ledger, chatId, "host", 1, index + 1, status = "running")
                continue
            }

            onSpeakerChange?.invoke(member)
            val result = invokeAgent(chat, chatId, member, memberNames, isMentioned = false)
            if (result is AgentResult.Reply) replies.add(result.message)
            // B5-02: 当前成员处理完成
            ledger = saveLedger(ledger, chatId, "host", 1, index + 1, status = "running")
        }

        if (ledger != null) {
            resultOf { groupChatRepository.deleteGenerationLedger(ledger.id) }
                .onError { msg, t -> Logger.w(TAG, "群聊账本清理失败: $msg", t) }
        }

        Logger.i(TAG, "主持人模式:群聊「${chat.name}」派发完成,${replies.size} 条回复")
        replies
    }

    /**
     * 辩论模式:根据成员数量生成角色定位列表。
     *
     * - 2 人:正方 / 反方
     * - 3 人:提方案 / 找漏洞 / 提改进
     * - 4+ 人:提方案 / 质疑 / 改进 / 补充(循环)
     */
    private fun generateDebateRoles(count: Int): List<String> {
        val baseRoles = when {
            count <= 2 -> listOf("提出方案", "质疑挑战")
            count == 3 -> listOf("提出方案", "质疑挑战", "改进优化")
            else -> listOf("提出方案", "质疑挑战", "改进优化", "补充扩展")
        }
        return (0 until count).map { baseRoles[it % baseRoles.size] }
    }

    /**
     * 辩论模式:调用单个 agent 生成发言(带角色定位 + 上一人发言)。
     *
     * 与 [invokeAgent] 的区别:prompt 中注入辩论角色和上一人发言,
     * 且强调不允许 PASS(辩论中每个角色必须发言)。
     * v1.0.29: 移除 model 参数,内部通过 [resolveAssistantModel] 解析助手专属模型。
     */
    private suspend fun invokeAgentForDebate(
        chat: GroupChatEntity,
        chatId: String,
        assistant: AssistantEntity,
        memberNames: List<String>,
        role: String,
        speakerIndex: Int,
        totalSpeakers: Int,
        previousReply: String?,
        isRepair: Boolean = false,
    ): AgentResult {
        activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.VIEWING)

        // v1.0.29: per-assistant 模型解析
        val (model, providerConfig) = resolveAssistantModel(assistant)

        val contextSize = assistant.contextMessageSize.takeIf { it > 0 } ?: DEFAULT_CONTEXT_SIZE
        val recentMessages = groupChatRepository.getRecentMessages(chatId, contextSize)

        val messages = buildDebateMessages(
            chat.name, assistant, memberNames, recentMessages, model,
            role, speakerIndex, totalSpeakers, previousReply, isRepair,
        )

        val temperature = assistant.temperature ?: DEFAULT_TEMPERATURE
        val maxTokens = assistant.maxTokens ?: DEFAULT_MAX_TOKENS
        activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.REPLYING)

        val rawReplyText = resultOf {
            withTimeoutOrNull(AGENT_TIMEOUT_MS) {
                val builder = StringBuilder()
                var streamError: String? = null
                chatService.streamChat(
                    messages = messages,
                    model = model,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    providerConfig = providerConfig,
                ).collect { event ->
                    when (event) {
                        is ChatStreamEvent.ContentDelta -> builder.append(event.delta)
                        is ChatStreamEvent.ReasoningDelta -> {}
                        is ChatStreamEvent.ImageDelta -> {}
                        is ChatStreamEvent.ToolCallDelta -> {}
                        is ChatStreamEvent.Done -> {}
                        is ChatStreamEvent.Error -> streamError = event.message
                        is ChatStreamEvent.StreamInterrupted -> streamError = event.message
                        is ChatStreamEvent.FallbackNotice -> {}
                    }
                }
                if (streamError != null) throw IllegalStateException(streamError)
                builder.toString().trim()
            }
        }.onError { msg, _ ->
            activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.ERROR)
            scheduleIdleTransition(chatId, assistant)
            return AgentResult.Error(msg)
        }.getOrNull()

        if (rawReplyText == null) {
            activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.ERROR)
            scheduleIdleTransition(chatId, assistant)
            return AgentResult.Error("Agent「${assistant.name}」辩论调用超时")
        }

        val extractedMood = extractMood(rawReplyText)
        val extractedReasoning = extractReasoning(rawReplyText)
        val replyText = sanitizeAgentReply(rawReplyText)

        if (replyText.isBlank() || replyText == PASS_MARKER) {
            activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.NO_REPLY)
            scheduleIdleTransition(chatId, assistant)
            return AgentResult.Pass()
        }

        val msgId = groupChatRepository.sendMessage(
            chatId = chatId,
            senderType = "assistant",
            senderId = assistant.id,
            senderName = assistant.name,
            body = replyText,
            mood = extractedMood,
            reasoning = extractedReasoning,
        )
        scheduleIdleTransition(chatId, assistant)

        if (groupChatMemoryRepository != null) {
            val summary = buildGroupChatMemorySummary(chat.name, assistant, replyText)
            resultOf { groupChatMemoryRepository.saveSummary(chatId, assistant.id, summary) }
        }
        return AgentResult.Reply(
            GroupChatMessageEntity(
                id = msgId,
                chatId = chatId,
                senderType = "assistant",
                senderId = assistant.id,
                senderName = assistant.name,
                body = replyText,
                timestamp = System.currentTimeMillis(),
                mood = extractedMood,
                reasoning = extractedReasoning,
            )
        )
    }

    /**
     * 辩论模式:构造发给 LLM 的消息列表(含角色定位 + 上一人发言)。
     */
    private suspend fun buildDebateMessages(
        chatName: String,
        assistant: AssistantEntity,
        memberNames: List<String>,
        recentMessages: List<GroupChatMessageEntity>,
        model: io.zer0.ai.core.Model?,
        role: String,
        speakerIndex: Int,
        totalSpeakers: Int,
        previousReply: String?,
        isRepair: Boolean,
    ): List<UIMessage> {
        val systemContent = buildString {
            if (assistant.systemPrompt.isNotBlank()) {
                appendLine(assistant.systemPrompt)
                appendLine()
            }
            appendLine(
                SystemPromptAssembler.buildGroupChatHintSection(
                    chatName = chatName,
                    members = memberNames,
                    currentAgentName = assistant.name,
                )
            )
            appendLine()
            appendLine(GROUP_CHAT_MOOD_SECTION)

            // v1.0.53: 注入长期记忆(用户画像)和群聊记忆(agent 过往发言)
            // 修复"群聊记忆和其他地方记忆不互通"问题
            systemPromptAssembler?.let { assembler ->
                val longTermMemory = assembler.buildLongTermMemorySection()
                if (longTermMemory.isNotBlank()) {
                    appendLine()
                    appendLine(longTermMemory)
                }
                val groupChatMemory = assembler.buildGroupChatMemorySection(assistant.id)
                if (groupChatMemory.isNotBlank()) {
                    appendLine()
                    appendLine(groupChatMemory)
                }
            }
            appendLine()
            appendLine("【辩论模式】你正在参与一场结构化辩论。")
            appendLine("你在本轮链条中的角色定位:$role(第 ${speakerIndex + 1}/$totalSpeakers 位发言)")
            appendLine("辩论规则:")
            appendLine("- 每个人必须发言,不允许 [PASS]")
            appendLine("- 必须针对上一人的发言进行回应(赞同/反对/补充/改进)")
            appendLine("- 保持你的角色定位:提方案者给具体方案,质疑者找逻辑漏洞,改进者提出优化建议")
            if (isRepair) {
                appendLine("- 你上一轮没有发言,辩论中不允许跳过,请务必回应")
            }
        }

        val messages = mutableListOf<UIMessage>()
        messages.add(UIMessage(role = MessageRole.SYSTEM, content = systemContent))

        // User message:包含最近消息 transcript + 上一人发言强调
        val userContent = buildString {
            appendLine("${assistant.name},你正在参与「$chatName」的辩论。")
            appendLine()
            if (recentMessages.isNotEmpty()) {
                appendLine("【讨论记录】")
                appendLine(formatMessageTranscript(recentMessages))
                appendLine()
            }
            if (previousReply != null) {
                appendLine("【上一人发言】请重点回应以下内容:")
                appendLine(previousReply.take(800))
                appendLine()
            } else {
                appendLine("你是第一位发言者,请基于用户的问题给出你的方案。")
                appendLine()
            }
            appendLine("请以「$role」的视角发言。必须回应,不允许 [PASS]。")
        }
        messages.add(UIMessage(role = MessageRole.USER, content = userContent))
        return messages
    }

    /**
     * 主持人模式:让主持人 AI 分析用户问题,输出派发计划。
     *
     * 主持人收到用户问题后,输出一个简单的派发列表(每行一个成员名),
     * 调度器据此决定哪些成员发言及顺序。
     * v1.0.29: 移除 model 参数,内部通过 [resolveAssistantModel] 解析主持人专属模型。
     *
     * @return 被派发的 AssistantEntity 列表(按主持人指定顺序);失败时返回空列表
     */
    private suspend fun analyzeWithHost(
        chat: GroupChatEntity,
        host: AssistantEntity,
        otherMembers: List<AssistantEntity>,
        memberNames: List<String>,
        userMessage: String,
    ): List<AssistantEntity> {
        // v1.0.29: per-assistant 模型解析(用主持人配置的模型)
        val (model, providerConfig) = resolveAssistantModel(host)

        val systemContent = buildString {
            if (host.systemPrompt.isNotBlank()) {
                appendLine(host.systemPrompt)
                appendLine()
            }
            appendLine("你是群聊「${chat.name}」的主持人。")
            appendLine("群成员:${otherMembers.joinToString("、") { it.name }}")
            appendLine()
            appendLine("你的任务是分析用户的问题,决定哪些成员应该发言以及发言顺序。")
            appendLine("请只输出需要发言的成员名字,每行一个,按发言顺序排列。")
            appendLine("不要输出其他任何内容。如果不清楚,输出所有成员名字。")
        }

        val userContent = buildString {
            appendLine("用户问题:$userMessage")
            appendLine()
            appendLine("可选成员:")
            otherMembers.forEach { appendLine(it.name) }
            appendLine()
            appendLine("请列出应该发言的成员名字(每行一个):")
        }

        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = systemContent),
            UIMessage(role = MessageRole.USER, content = userContent),
        )

        val hostReply = resultOf {
            withTimeoutOrNull(AGENT_TIMEOUT_MS) {
                val builder = StringBuilder()
                chatService.streamChat(
                    messages = messages,
                    model = model,
                    temperature = 0.3f,
                    maxTokens = 200,
                    providerConfig = providerConfig,
                ).collect { event ->
                    when (event) {
                        is ChatStreamEvent.ContentDelta -> builder.append(event.delta)
                        else -> {}
                    }
                }
                builder.toString().trim()
            }
        }.getOrNull()

        if (hostReply.isNullOrBlank()) {
            Logger.w(TAG, "主持人模式:主持人「${host.name}」未给出有效回复")
            return emptyList()
        }

        // 解析主持人输出的成员名(每行一个,模糊匹配)
        val requestedNames = hostReply.lines()
            .map { it.trim().removePrefix("-").removePrefix("•").trim() }
            .filter { it.isNotBlank() }

        val dispatched = mutableListOf<AssistantEntity>()
        for (name in requestedNames) {
            val matched = otherMembers.firstOrNull { member ->
                member.name.equals(name, ignoreCase = true) ||
                    member.name.contains(name, ignoreCase = true) ||
                    name.contains(member.name, ignoreCase = true)
            }
            if (matched != null && matched !in dispatched) {
                dispatched.add(matched)
            }
        }

        // 如果主持人没有有效派发任何人,默认派发所有成员
        if (dispatched.isEmpty()) {
            Logger.w(TAG, "主持人模式:主持人输出「$hostReply」未匹配到成员,默认派发全部")
            return otherMembers
        }

        return dispatched
    }

    /**
     * 回退方法:当新模式条件不满足时,执行标准串行轮转。
     */

    private suspend fun triggerRoundRobinFallback(
        chat: GroupChatEntity,
        chatId: String,
        assistants: List<AssistantEntity>,
        onSpeakerChange: ((AssistantEntity) -> Unit)?,
        ledgerId: String? = null,
        startMemberIndex: Int = 0,
    ): List<GroupChatMessageEntity> = withContext(Dispatchers.IO) {
        if (assistants.isEmpty()) return@withContext emptyList()
        val memberNames = assistants.map { it.name }
        val recentMessages = groupChatRepository.getRecentMessages(chatId, DEFAULT_CONTEXT_SIZE)
        val mentionedAgentIds = parseMentions(recentMessages, assistants)

        // B5-02: 加载/恢复账本
        var ledger = if (ledgerId != null) {
            resultOf { groupChatRepository.getGenerationLedger(ledgerId) }.getOrNull()
        } else null
        val ledgerMemberIds = parseLedgerMemberIds(ledger)
        val orderedAssistants = if (ledgerMemberIds != null) {
            ledgerMemberIds.mapNotNull { id -> assistants.firstOrNull { it.id == id } }
        } else {
            assistants.sortedByDescending { it.id in mentionedAgentIds }
        }
        ledger = saveLedger(
            ledger, chatId, "round_robin", 1, startMemberIndex.coerceIn(0, orderedAssistants.size),
            memberIdsJson = groupChatRepository.serializeMemberIds(orderedAssistants.map { it.id }),
        )

        activityHub.clear(chatId)
        val replies = mutableListOf<GroupChatMessageEntity>()
        val firstIndex = startMemberIndex.coerceIn(0, orderedAssistants.size)
        for (agentIndex in firstIndex until orderedAssistants.size) {
            val assistant = orderedAssistants[agentIndex]
            val isMentioned = assistant.id in mentionedAgentIds
            // B5-02: 标记当前成员处理中
            ledger = saveLedger(ledger, chatId, "round_robin", 1, agentIndex, status = "running")
            if (ledger != null && memberAlreadyRepliedSince(chatId, assistant.id, ledger.updatedAt)) {
                Logger.i(TAG, "Agent「${assistant.name}」已在断点后发言,跳过续跑")
                ledger = saveLedger(ledger, chatId, "round_robin", 1, agentIndex + 1, status = "running")
                continue
            }

            onSpeakerChange?.invoke(assistant)
            when (val result = invokeAgent(chat, chatId, assistant, memberNames, isMentioned = isMentioned)) {
                is AgentResult.Reply -> replies.add(result.message)
                is AgentResult.Pass -> {
                    if (isMentioned) {
                        when (val retry = invokeAgent(chat, chatId, assistant, memberNames, isMentioned = true, isRepair = true)) {
                            is AgentResult.Reply -> replies.add(retry.message)
                            else -> {}
                        }
                    }
                }
                is AgentResult.Error -> Logger.w(TAG, "Agent「${assistant.name}」错误: ${result.message}")
            }
            // B5-02: 当前成员处理完成
            ledger = saveLedger(ledger, chatId, "round_robin", 1, agentIndex + 1, status = "running")
        }

        if (ledger != null) {
            resultOf { groupChatRepository.deleteGenerationLedger(ledger.id) }
                .onError { msg, t -> Logger.w(TAG, "群聊账本清理失败: $msg", t) }
        }

        replies
    }
    /**
     * v1.97: 从最近消息中解析 @mention,返回被提及的 assistant id 列表。
     *
     * 匹配规则:在最近用户消息中查找 @name,name 与 assistant.name 或 assistant.id 匹配。
     * 参考 参考开源项目-orig 的 channel-mentions.ts:支持中英文标点边界,按名称长度降序匹配。
     */
    private fun parseMentions(
        recentMessages: List<GroupChatMessageEntity>,
        assistants: List<AssistantEntity>,
    ): Set<String> {
        // 取最近一条用户消息
        val lastUserMsg = recentMessages.lastOrNull { it.senderType == "user" } ?: return emptySet()
        val text = lastUserMsg.body
        if (!text.contains("@")) return emptySet()

        // 提取所有 @token
        val tokens = MENTION_REGEX.findAll(text).map { it.groupValues[1] }.toList()
        if (tokens.isEmpty()) return emptySet()

        // 与 assistant name/id 匹配(忽略大小写)
        // v1.116 (C2-1): 按 name 长度降序排序后再匹配,避免短名称误匹配长名称的前缀。
        // 例:群内有 "Alice" 和 "Alice2" 时,@Alice2 应匹配后者而非前者。
        // 当前用 equals 精确匹配,排序不影响结果,但为未来扩展(别名/部分匹配)打好基础,
        // 且符合 参考开源项目 channel-mentions.ts 的设计意图。
        val sortedAssistants = assistants.sortedByDescending { it.name.length }
        val mentioned = mutableSetOf<String>()
        for (token in tokens) {
            val matched = sortedAssistants.firstOrNull { a ->
                a.name.equals(token, ignoreCase = true) || a.id.equals(token, ignoreCase = true)
            }
            if (matched != null) {
                mentioned.add(matched.id)
            }
        }
        return mentioned
    }

    /**
     * 单个 agent 一轮调用的结果。
     *
     * 用于区分"主动 PASS"、"正常回复"与"流式异常",避免 [triggerAgentRoundRobin]
     * 把流式异常误判为主动 PASS 触发决策修复重试。
     */
    private sealed class AgentResult {
        /**
         * 跳过本轮。
         *
         * @param implicit v1.0.53: true = API 故障/超时导致的被动跳过(不参与降级计数),
         *                 false = 模型主动 channel_pass(显式跳过,参与降级计数)。
         */
        data class Pass(val implicit: Boolean = false) : AgentResult()

        /** 正常回复了一条消息。 */
        data class Reply(val message: GroupChatMessageEntity) : AgentResult()

        /** 流式调用失败或超时,无法回复。 */
        data class Error(val message: String) : AgentResult()
    }

    /**
     * v1.0.29: per-assistant 模型解析 — 让群聊中每个助手使用各自配置的模型。
     *
     * 解析顺序(参考单聊 [ChatStreamCoordinator.resolveToolsAndModel]):
     *  1. 助手配置了 `modelId + providerId`:精确匹配 Provider+Model
     *  2. 助手仅配置 `modelId`(无 providerId):跨所有 Provider 查找匹配 id 的 Model
     *  3. 助手未配置:回退到全局 selectedModelId 对应的 Model
     *  4. 全局也未选:回退到激活 Provider 首个模型,再回退到首个有模型的 Provider 首个模型
     *
     * ProviderConfig 解析:
     *  - 优先用 model.providerId 找到对应 Provider
     *  - 找不到时回退到激活 Provider(且必须有模型),再回退到首个有模型的 Provider
     *
     * 这样可保证:
     *  - 群聊 A 助手用 OpenCode 的 DeepSeek-V4, B 助手用 SiliconFlow 的 GLM-4-9B,
     *    各自的 streamChat 调用路由到不同 Provider。
     *  - 助手未配置时与历史行为一致(用全局 selectedModel)。
     *
     * @param assistant 当前 agent
     * @return (Model, ProviderConfig) — Model 为 null 时由 ChatService 兜底;
     *         ProviderConfig 为 null 时 ChatService 会调 configStore.get() 兜底
     */
    private suspend fun resolveAssistantModel(assistant: AssistantEntity): Pair<Model?, ProviderConfig?> {
        val allProviders = resultOf { settings.providersFlow.first() }.getOrNull().orEmpty()
        if (allProviders.isEmpty()) return Pair(null, null)

        val assistantModelId = assistant.modelId?.takeIf { it.isNotBlank() }
        val assistantProviderId = assistant.providerId?.takeIf { it.isNotBlank() }
        val activeProviderId = resultOf { settings.activeProviderIdFlow.first() }.getOrNull()
        val selectedModelId = resultOf { settings.selectedModelIdFlow.first() }.getOrNull()

        // 1. 解析 Model
        val resolvedModel: Model? = if (assistantModelId != null && assistantProviderId != null) {
            // 精确匹配:provider + model id
            allProviders.firstOrNull { it.id == assistantProviderId }
                ?.models?.firstOrNull { it.id == assistantModelId }
        } else {
            // 仅 modelId:跨所有 Provider 查找
            assistantModelId?.let { aid ->
                allProviders.flatMap { it.models }.firstOrNull { it.id == aid }
            }
        } ?: selectedModelId?.let { sid ->
            // 回退到全局 selectedModelId
            allProviders.flatMap { it.models }.firstOrNull { it.id == sid }
        } ?: allProviders.firstOrNull { it.id == activeProviderId && it.models.isNotEmpty() }?.let { p ->
            // 回退到激活 Provider 首个模型
            p.models.firstOrNull()
        } ?: allProviders.firstOrNull { it.models.isNotEmpty() }?.let { p ->
            // 二级兜底:首个有模型的 Provider 首个模型
            p.models.firstOrNull()
        }

        // 2. 解析 ProviderConfig
        val resolvedProviderConfig: ProviderConfig? = resolvedModel?.let { m ->
            allProviders.firstOrNull { it.id == m.providerId }
        } ?: allProviders.firstOrNull { it.id == activeProviderId && it.models.isNotEmpty() }
            ?: allProviders.firstOrNull { it.models.isNotEmpty() }

        return Pair(resolvedModel, resolvedProviderConfig)
    }

    /**
     * 调用单个 agent 生成发言。
     *
     * v1.97: 新增 isMentioned / isRepair 参数,支持 @mention 提示和决策修复。
     * v1.0.29: 移除 model 参数,内部通过 [resolveAssistantModel] 解析助手专属模型。
     * 改造 2(Phone Session 模式):通过 [buildMessages] 构造 Phone Session 式 prompt,
     * 每个 agent 独立收到"手机推送"而非共享上下文;身份防混淆 guidance 由
     * [SystemPromptAssembler.buildGroupChatHintSection] 注入(per-agent)。
     *
     * v1.0.53 Phase 5: channel_* 工具接入 — 把 [PASS] 文本协议切换为工具调用决策。
     *  - LLM 必须调用 channel_reply(发言)/ channel_pass(跳过)/ channel_read_context(读更多历史) 之一
     *  - 多轮决策:第 1 轮三件套都可用;若调 read_context,回填结果后第 2 轮强制 reply/pass
     *  - 决策超时(60s 内未完成决策)→ implicitPass(对齐参考实现 implicitPass)
     *  - 兼容旧协议:LLM 未调工具但输出文本时,按 [PASS] 文本规则解析(平滑迁移)
     *
     * @param chat 群聊实体
     * @param chatId 群聊 id
     * @param assistant 当前 agent 配置
     * @param memberNames 群聊所有成员显示名
     * @param isMentioned v1.97: 是否被 @提及(影响 prompt 提示)
     * @param isRepair v1.97: 是否为决策修复重试(提示 agent 上一轮没有回复)
     * @return [AgentResult] — Pass 表示主动跳过,Reply 表示正常回复,Error 表示流式异常/超时
     */
    private suspend fun invokeAgent(
        chat: GroupChatEntity,
        chatId: String,
        assistant: AssistantEntity,
        memberNames: List<String>,
        isMentioned: Boolean = false,
        isRepair: Boolean = false,
    ): AgentResult {
        // ActivityHub: 进入 invokeAgent 即标记为 VIEWING(正在看消息),
        // 让 UI 立即显示该 agent 开始处理本轮消息。
        activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.VIEWING)

        // v1.0.29: per-assistant 模型解析 — 让每个助手用各自配置的模型,
        // 而非全局 selectedModel。这样群聊中 A 助手可用 OpenCode 的 DeepSeek,
        // B 助手可用 SiliconFlow 的 GLM,各自路由到对应 Provider。
        val (model, providerConfig) = resolveAssistantModel(assistant)

        // a. 构造上下文
        val contextSize = assistant.contextMessageSize.takeIf { it > 0 } ?: DEFAULT_CONTEXT_SIZE
        val recentMessages = groupChatRepository.getRecentMessages(chatId, contextSize)

        // v1.202: 群聊链路追踪 — 在 invokeAgent 入口通知 DelegationChainTracker 开始,
        // 让主会话 UI 能看到群聊执行过程(此前群聊完全缺席链路追踪)。
        // parentRequestId=null 表示群聊是顶层委派(非子 agent),与 executeWithWorkflow 中
        // TeamWorkflowExecutor 内部调用的 onDelegationStarted 区分。
        // requestId 用 "gc-" 前缀 + chatId + agentId + 时间戳,保证全局唯一且可识别来源。
        val delegationRequestId = "gc-$chatId-${assistant.id}-${System.currentTimeMillis()}"
        val taskPreview = recentMessages.lastOrNull()?.body?.take(50) ?: ""
        delegationChainTracker?.onDelegationStarted(
            requestId = delegationRequestId,
            parentRequestId = null,
            task = "群聊回复: $taskPreview",
            targetType = "assistant",
            targetId = assistant.id,
            targetName = assistant.name,
        )

        // b. 构造消息列表(改造 2 Phone Session:system 含身份 guidance,user 为推送式 phone prompt)
        val messages = buildMessages(chat, assistant, memberNames, recentMessages, model, isMentioned, isRepair)

        val temperature = assistant.temperature ?: DEFAULT_TEMPERATURE
        val maxTokens = assistant.maxTokens ?: DEFAULT_MAX_TOKENS
        // ActivityHub: 即将调 LLM 流式,标记为 REPLYING(正在回复)。
        activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.REPLYING)

        // v1.0.53 Phase 5: channel_* 工具集(Phone Session 模式)
        // 三件套:channel_reply(发言) / channel_pass(跳过) / channel_read_context(读更多历史)
        // 回调通过闭包捕获 var,执行后把结果写入 replyContent / passReason
        var replyContent: String? = null
        var passReason: String? = null
        val workingMessages = messages.toMutableList()

        // B8-02: channel 三件套 + 全局常规工具合并,群聊成员也能联网/计算/写提醒
        val channelTools = ChannelToolFactory.createChannelToolDefinitions(
            groupChatId = chatId,
            senderAssistantId = assistant.id,
            onReply = { content -> replyContent = content },
            onPass = { reason -> passReason = reason },
            contextProvider = { limit ->
                val more = groupChatRepository.getRecentMessages(chatId, limit)
                formatMessageTranscript(more)
            },
        )
        // B8-03 方案 B: 群聊暂不支持媒体输出,移除生图/生视频/二维码工具,避免模型白调
        // B8-02: 按成员助手 toolIdsJson 过滤,未配置时保持全部工具
        val enabledToolIds = runCatching {
            AppJson.decodeFromString(ListSerializer(String.serializer()), assistant.toolIdsJson)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        val regularTools = GroupChatToolPolicy.filterRegularTools(
            toolRegistry?.listToolsAsToolDefinitions(enabledToolIds) ?: emptyList(),
        )
        val allToolDefinitions = (channelTools.first + regularTools).distinctBy { it.name }
        val toolExecutors = channelTools.second.toMutableMap()
        regularTools.forEach { def ->
            toolExecutors[def.name] = { args ->
                withContext(Dispatchers.IO) {
                    toolRegistry?.executeFromJson(
                        def.name,
                        AppJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), args),
                    ) ?: "(工具不可用)"
                }
            }
        }

        // v1.0.53 Phase 5: 多轮决策循环(整体 60s 超时包裹 → 超时视为 implicitPass)
        //  - 第 1 轮:三件套都可用
        //  - 第 2 轮(仅当第 1 轮调了 read_context):移除 read_context,强制 reply/pass
        //  - 任意轮 reply/pass 即退出;未调 read_context 也退出(implicit pass)
        //  - 流式错误(HTTP 500 / 网络异常)→ 立即返回 Error(不等超时)
        //  - 整体超时 → implicitPass(对齐参考实现 implicitPass)
        var streamErrorMessage: String? = null  // 流式错误(非超时)
        val timedOut = withTimeoutOrNull(AGENT_TIMEOUT_MS) {
            for (round in 0 until MAX_CHANNEL_DECISION_ROUNDS) {
                replyContent = null
                passReason = null

                val toolsForThisRound = if (round == 0) {
                    allToolDefinitions
                } else {
                    // 第 2 轮:移除 read_context,强制 reply/pass
                    allToolDefinitions.filter { it.name != "channel_read_context" }
                }

                // 流式调用,累积 ContentDelta + ToolCallDelta
                val builder = StringBuilder()
                val toolCallAccumulator = mutableMapOf<Int, MutableList<ChatStreamEvent.ToolCallDelta>>()
                var streamError: String? = null

                chatService.streamChat(
                    messages = workingMessages,
                    model = model,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    tools = toolsForThisRound,
                    providerConfig = providerConfig,
                ).collect { event ->
                    when (event) {
                        is ChatStreamEvent.ContentDelta -> builder.append(event.delta)
                        is ChatStreamEvent.ReasoningDelta -> { /* 思考增量不入正文,与单聊保持一致 */ }
                        is ChatStreamEvent.ImageDelta -> { /* 群聊暂不支持图片输出,忽略 */ }
                        is ChatStreamEvent.ToolCallDelta -> {
                            toolCallAccumulator.getOrPut(event.index) { mutableListOf() }.add(event)
                        }
                        is ChatStreamEvent.Done -> { /* 流结束 */ }
                        is ChatStreamEvent.Error -> streamError = event.message
                        is ChatStreamEvent.StreamInterrupted -> streamError = event.message
                        is ChatStreamEvent.FallbackNotice -> { /* 已自动降级为非流式 */ }
                    }
                }
                if (streamError != null) {
                    // 流式错误:记录并跳出循环,在外部返回 Error
                    streamErrorMessage = streamError
                    break
                }

                // 累积的 tool calls(按 index 排序,合并增量)
                val toolCalls = toolCallAccumulator.toSortedMap().values.map { deltas ->
                    ToolCall(
                        id = deltas.firstNotNullOfOrNull { it.id } ?: "",
                        name = deltas.firstNotNullOfOrNull { it.name } ?: "",
                        arguments = deltas.mapNotNull { it.argumentsDelta }.joinToString(""),
                    )
                }

                val rawText = builder.toString().trim()

                // 处理 tool calls
                var calledReadContext = false
                for (tc in toolCalls) {
                    when (tc.name) {
                        "channel_reply" -> {
                            val args = parseToolArgs(tc.arguments)
                            toolExecutors["channel_reply"]?.invoke(args)
                            // replyContent 已通过 onReply 回调赋值
                        }
                        "channel_pass" -> {
                            val args = parseToolArgs(tc.arguments)
                            toolExecutors["channel_pass"]?.invoke(args)
                            // passReason 已通过 onPass 回调赋值
                        }
                        "channel_read_context" -> {
                            val args = parseToolArgs(tc.arguments)
                            val result = toolExecutors["channel_read_context"]?.invoke(args) ?: "(无上下文)"
                            // 把 read_context 结果回填为 user 消息,让 LLM 下一轮据此决策
                            workingMessages.add(UIMessage(
                                role = MessageRole.USER,
                                content = "【channel_read_context 结果】\n$result\n\n请基于以上完整上下文,调用 channel_reply 发言或 channel_pass 跳过。",
                            ))
                            calledReadContext = true
                        }
                        // B8-02: 常规工具调用 — 结果只回填当前成员上下文,不进入群聊消息表
                        else -> {
                            val args = parseToolArgs(tc.arguments)
                            val result = toolExecutors[tc.name]?.invoke(args) ?: "(工具不可用)"
                            workingMessages.add(
                                UIMessage(
                                    role = MessageRole.TOOL,
                                    content = result,
                                    toolCallId = tc.id,
                                ),
                            )
                        }
                    }
                }

                // 决策完成?
                if (replyContent != null) break      // 已发言
                if (passReason != null) break        // 已跳过
                if (!calledReadContext) {
                    // 未调 read_context 也未决策 — 检查是否有文本输出(兼容旧 [PASS] 文本协议)
                    if (rawText.isBlank() || rawText == PASS_MARKER) {
                        // 空文本或 [PASS] → implicit pass
                        break
                    }
                    // 有文本但未调工具 → 当作 reply(兼容不支持工具调用的模型)
                    replyContent = rawText
                    break
                }
                // 否则继续下一轮(read_context 后强制 reply/pass)
            }
        }

        // 流式错误优先处理(非超时,返回 Error)
        if (streamErrorMessage != null) {
            Logger.e(TAG, "Agent「${assistant.name}」LLM 调用失败: $streamErrorMessage")
            activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.ERROR)
            scheduleIdleTransition(chatId, assistant)
            delegationChainTracker?.onDelegationFinished(
                requestId = delegationRequestId,
                success = false,
                resultText = "",
                error = streamErrorMessage,
            )
            return AgentResult.Error(streamErrorMessage!!)
        }

        if (timedOut == null) {
            // 决策超时 → implicitPass(对齐参考实现 implicitPass:超时未决策自动跳过)
            Logger.w(TAG, "Agent「${assistant.name}」决策超时(${AGENT_TIMEOUT_MS / 1000}s),implicit pass")
            activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.NO_REPLY)
            scheduleIdleTransition(chatId, assistant)
            delegationChainTracker?.onDelegationFinished(
                requestId = delegationRequestId,
                success = false,
                resultText = "",
                error = "Agent 决策超时(implicitPass)",
            )
            return AgentResult.Pass(implicit = true)
        }

        // 根据决策结果返回 AgentResult
        val replyText = replyContent?.let { sanitizeAgentReply(it) }
        if (replyText.isNullOrBlank() || replyText == PASS_MARKER) {
            // channel_pass 或 implicit pass
            Logger.i(TAG, "Agent「${assistant.name}」选择跳过本轮(PASS, reason=${passReason ?: "implicit"})")
            activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.NO_REPLY)
            scheduleIdleTransition(chatId, assistant)
            delegationChainTracker?.onDelegationFinished(
                requestId = delegationRequestId,
                success = false,
                resultText = "",
                error = "Agent 跳过本轮(reason=${passReason ?: "implicit"})",
            )
            // v1.0.53: implicit=true 时是 API 故障/超时被动跳过,不计入降级
            return AgentResult.Pass(implicit = passReason == null)
        }

        // 提取 mood / think 模块(channel_reply 的 content 可能含 mood/think 标签)
        val extractedMood = extractMood(replyText)
        val extractedReasoning = extractReasoning(replyText)

        // 保存 agent 回复到群聊
        val msgId = groupChatRepository.sendMessage(
            chatId = chatId,
            senderType = "assistant",
            senderId = assistant.id,
            senderName = assistant.name,
            body = replyText,
            mood = extractedMood,
            reasoning = extractedReasoning,
        )

        Logger.i(TAG, "Agent「${assistant.name}」在群聊「${chat.name}」中发言")
        // ActivityHub: 正常回复完成 → 延迟后回退到 IDLE(消息已入列表,chip 自然隐藏)。
        scheduleIdleTransition(chatId, assistant)
        // v1.202: 链路追踪 — Agent 正常回复,记录成功节点(结果预览限 500 字)
        delegationChainTracker?.onDelegationFinished(
            requestId = delegationRequestId,
            success = true,
            resultText = replyText.take(500),
        )
        // v2.x: 群聊记忆隔离 — 把本轮 agent 回复摘要写入独立 fact store,
        // 不写入助手主记忆系统,避免群聊消息污染主对话上下文。
        if (groupChatMemoryRepository != null) {
            val summary = buildGroupChatMemorySummary(chat.name, assistant, replyText)
            resultOf { groupChatMemoryRepository.saveSummary(chatId, assistant.id, summary) }
                .onError { msg, t -> Logger.w(TAG, "群聊记忆写入失败(agent=${assistant.name}): $msg", t) }
        }
        return AgentResult.Reply(
            GroupChatMessageEntity(
                id = msgId,
                chatId = chatId,
                senderType = "assistant",
                senderId = assistant.id,
                senderName = assistant.name,
                body = replyText,
                timestamp = System.currentTimeMillis(),
                mood = extractedMood,
                reasoning = extractedReasoning,
            )
        )
    }

    /**
     * v1.0.53 Phase 5: 解析 LLM 返回的工具调用 arguments JSON 字符串为 Map<String, String>。
     *
     * 与 [SkillExecutor.parseArgs] 同源逻辑,独立实现避免依赖 SkillExecutor 的 private 方法。
     * 解析失败时返回空 Map(让工具执行器自行处理缺参错误)。
     */
    private fun parseToolArgs(json: String): Map<String, String> = resultOf {
        val obj = AppJson.decodeFromString(JsonObject.serializer(), json)
        obj.entries.associate { (k, v) ->
            val strValue = when (v) {
                is JsonPrimitive -> v.content
                else -> AppJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), v)
            }
            k to strValue
        }
    }.getOrNull() ?: emptyMap()

    /**
     * ActivityHub: 延迟回退到 IDLE 状态(不阻塞下一 agent 调用)。
     *
     * 在 [appScope] 中启动独立协程,等待 [ACTIVITY_IDLE_DELAY_MS] 后将指定 agent 状态置为 IDLE。
     * 这样 NO_REPLY / ERROR / REPLYING 等终态能在 UI 停留片刻,用户看得到"谁跳过/出错/回复了",
     * 而下一 agent 的轮转不被延迟阻塞。
     *
     * @param chatId 群聊 id
     * @param assistant 当前 agent(取 id 与 name)
     */
    private fun scheduleIdleTransition(chatId: String, assistant: AssistantEntity) {
        appScope.launch {
            delay(ACTIVITY_IDLE_DELAY_MS)
            activityHub.updateStatus(chatId, assistant.id, assistant.name, AgentActivityStatus.IDLE)
        }
    }

    /**
     * v2.x: 构造群聊记忆摘要。
     *
     * 当前实现为简单截取:把群聊名 + agent 名 + 回复正文前 200 字组合成摘要。
     * 不调 LLM 避免延迟;后续若需更精细摘要,可改为 LLM 生成。
     *
     * 摘要会写入 [GroupChatMemoryRepository],供 SystemPromptAssembler 用
     * `<group_chat_memory>` 标签注入到 system prompt,与主记忆 `<long_term_memory>` 区分。
     *
     * @param chatName 群聊名称
     * @param assistant 回复 agent
     * @param replyText agent 回复正文
     * @return 摘要文本(限 300 字以内)
     */
    private fun buildGroupChatMemorySummary(
        chatName: String,
        assistant: AssistantEntity,
        replyText: String,
    ): String {
        val preview = replyText.take(200)
        return "在群聊「$chatName」中,${assistant.name} 回复:$preview"
    }

    /**
     * 构造发给 LLM 的消息列表。
     *
     * - System: assistant.systemPrompt + 群聊身份提示(含改造 3 身份防混淆 guidance)
     * - System: v1.137 RAG 注入(知识库命中片段,@mention 定向检索)
     * - User: Phone Session 推送式 prompt(改造 2,参考 参考开源项目 channel-router.ts)
     *
     * v1.97: 新增 isMentioned / isRepair 参数,在 user message 中注入 @提及和决策修复提示。
     * v1.137: 新增 RAG 注入与视觉辅助(VisionBridge)处理:
     *  - RAG:解析最近用户消息中的 @mention,检索知识库,将命中片段作为 SYSTEM 消息注入。
     *  - 视觉:模型不支持视觉但有图片时,调用 [VisionBridge.prepare] 把图片描述注入文本,
     *    而非直接丢弃图片(参考单聊 ChatViewModel 的处理)。
     *
     * 改造 2(Phone Session 模式):每个 agent 独立收到"手机推送"式 prompt,
     * 而非把所有成员塞进同一上下文。参考 参考开源项目 channel-router.ts:793-821。
     *  - System:assistant.systemPrompt + buildGroupChatHintSection(含身份防混淆 guidance)
     *    + MOOD 格式 + 不要输出 channel_* 工具调用文本
     *  - User:buildPhonePrompt 构造的"手机推送"prompt(最近消息 + @提及 + 决策修复 + [PASS])
     *
     * 改造 3(身份防混淆 guidance):由 [SystemPromptAssembler.buildGroupChatHintSection]
     * 内部调用 [SystemPromptAssembler.buildIdentityGuidance] 注入,per-agent。
     *
     * TODO(channel_* 工具接入):channel_reply / channel_pass / channel_read_context 工具
     * 由另一个任务实现。当前保持 [PASS] 文本标记机制;工具接入后改为带 tools 的 streamChat 调用,
     * 并根据是否调用工具判断 PASS(未调用 channel_reply 即视为 PASS)。
     *
     * @param chatId 群聊 id(用于 VisionBridge 缓存 key)
     * @param chatName 群聊名称
     * @param assistant 当前 agent 配置
     * @param memberNames 群聊所有成员显示名
     * @param recentMessages 最近消息列表(按时间升序)
     * @param model 当前选中的模型(null 时无法判断视觉能力,保守不附加图片)
     * @param isMentioned 是否被 @提及
     * @param isRepair 是否为决策修复重试
     * @return UIMessage 列表
     */
    private suspend fun buildMessages(
        chat: GroupChatEntity,
        assistant: AssistantEntity,
        memberNames: List<String>,
        recentMessages: List<GroupChatMessageEntity>,
        model: io.zer0.ai.core.Model?,
        isMentioned: Boolean = false,
        isRepair: Boolean = false,
    ): List<UIMessage> {
        val chatId = chat.id
        val chatName = chat.name
        val messages = mutableListOf<UIMessage>()

        // v2.x: 悄悄话过滤 — 非目标 agent 看不到私信。
        // 可见性规则:
        //  - whisperTargetId == null:公开消息,所有人可见
        //  - whisperTargetId == assistant.id:用户→本 agent 的私信,本 agent 可见
        //  - senderId == assistant.id 且 whisperTargetId == "local_user":本 agent→用户的私信,本 agent 可见(自己说过的话)
        //  - 其他:对当前 agent 不可见(过滤掉)
        val visibleMessages = recentMessages.filter { msg ->
            msg.whisperTargetId == null ||
                msg.whisperTargetId == assistant.id ||
                (msg.senderId == assistant.id && msg.whisperTargetId == "local_user")
        }

        // System message: assistant.systemPrompt + 群聊提示(含改造 3 身份防混淆 guidance)
        // 改造 2 Phone Session:身份 guidance 由 buildGroupChatHintSection 内部注入,per-agent。
        val systemContent = buildString {
            if (assistant.systemPrompt.isNotBlank()) {
                appendLine(assistant.systemPrompt)
                appendLine()
            }
            appendLine(
                SystemPromptAssembler.buildGroupChatHintSection(
                    chatName = chatName,
                    members = memberNames,
                    currentAgentName = assistant.name,
                )
            )
            appendLine()
            appendLine(GROUP_CHAT_MOOD_SECTION)

            // v1.0.53: 注入长期记忆(用户画像)和群聊记忆(agent 过往发言)
            // 修复"群聊记忆和其他地方记忆不互通"问题
            systemPromptAssembler?.let { assembler ->
                val longTermMemory = assembler.buildLongTermMemorySection()
                if (longTermMemory.isNotBlank()) {
                    appendLine()
                    appendLine(longTermMemory)
                }
                val groupChatMemory = assembler.buildGroupChatMemorySection(assistant.id)
                if (groupChatMemory.isNotBlank()) {
                    appendLine()
                    appendLine(groupChatMemory)
                }
            }

            // v2.x: 注入群共享文档(所有成员可见的共享背景知识)
            val sharedDocs = resultOf { groupChatRepository.parseSharedDocs(chat) }.getOrNull().orEmpty()
            if (sharedDocs.isNotEmpty()) {
                appendLine()
                appendLine("【群共享文档】以下是本群共享的参考资料,请在发言时参考:")
                for (doc in sharedDocs) {
                    appendLine("— ${doc.title} —")
                    // 单文档限 2000 字,避免 prompt 膨胀;超出截断
                    val docContent = if (doc.content.length > 2000) {
                        doc.content.take(2000) + "…(已截断)"
                    } else {
                        doc.content
                    }
                    appendLine(docContent)
                    appendLine()
                }
            }

            // v2.x: 注入本 agent 的专属上下文(仅当前 agent 可见,其他成员看不到)
            val privateContextMap = resultOf { groupChatRepository.parseMemberPrivateContext(chat) }.getOrNull().orEmpty()
            val myPrivateContext = privateContextMap[assistant.id]
            if (!myPrivateContext.isNullOrBlank()) {
                appendLine()
                appendLine("【你的专属上下文】以下信息仅你可见,其他群成员不知道:")
                // 限 1500 字,避免 prompt 膨胀
                val privateText = if (myPrivateContext.length > 1500) {
                    myPrivateContext.take(1500) + "…(已截断)"
                } else {
                    myPrivateContext
                }
                appendLine(privateText)
            }

            // v1.0.53 Phase 5: channel_* 工具已接入,告知 LLM 必须通过工具决策
            // 旧版"不要输出 channel_* 工具调用文本"的提示已废弃(工具已真正注册并传给 streamChat)
            appendLine()
            appendLine("【决策工具】你已获得 channel_reply / channel_pass / channel_read_context 三个工具。" +
                "本轮必须调用其中之一表态:")
            appendLine("- 想发言:调用 channel_reply(content=你的回复),content 中先写 <mood>...</mood> 再写正文")
            appendLine("- 不发言:调用 channel_pass(reason=可选原因)")
            appendLine("- 需要更多上下文:调用 channel_read_context(limit=条数,默认20,最多50)")
            appendLine("不要直接输出回复文本,也不要输出 [PASS],必须通过工具调用表态。")
        }
        messages.add(UIMessage(role = MessageRole.SYSTEM, content = systemContent))

        // v1.137: RAG 注入 — 解析最近用户消息中的 @mention,检索知识库,
        // 将命中片段作为 SYSTEM 消息注入(参考单聊 ChatViewModel 的 RAG 注入逻辑)。
        // 失败不阻断主流程(resultOf 降级)。
        // v2.x: 使用过滤后的 visibleMessages,确保悄悄话不会被当作 RAG 查询源泄漏给非目标 agent。
        val lastUserMsg = visibleMessages.lastOrNull { it.senderType == "user" }
        val ragQuery = lastUserMsg?.body?.takeIf { it.isNotBlank() }
        if (ragQuery != null) {
            val ragConfig = resultOf { settings.getRagConfig() }.getOrNull() ?: RagConfig()
            if (ragConfig.enabled) {
                val scopeDocIds = resultOf { ragService.resolveMentionToDocIds(ragQuery) }
                    .getOrNull()?.takeIf { it.isNotEmpty() }
                val injection = resultOf {
                    ragService.buildInjectionContextWithCitations(ragQuery, ragConfig, scopeDocIds)
                }.getOrNull()
                if (injection != null && injection.text.isNotBlank()) {
                    messages.add(UIMessage(role = MessageRole.SYSTEM, content = injection.text))
                }
            }
        }

        // 改造 2 Phone Session:User message 改为"手机推送"式 prompt
        // 每个 agent 独立收到一条"你的手机收到了群聊新消息"的推送,而非把所有成员塞进同一上下文。
        // 参考 参考开源项目 channel-router.ts:793-821。
        // v2.x: 使用过滤后的 visibleMessages 构造 transcript,确保非目标 agent 看不到悄悄话。
        val userContent = buildPhonePrompt(
            chatName = chatName,
            assistant = assistant,
            recentMessages = visibleMessages,
            isMentioned = isMentioned,
            isRepair = isRepair,
        )
        // v1.136: 把最近一条用户消息的图片作为多模态输入传给模型,
        // 避免群聊图片只存不看不回复的问题。
        // v1.137: 模型不支持视觉时,改用 VisionBridge 将图片描述注入文本(而非丢弃图片),
        // 避免向纯文本模型发图导致 HTTP 400。
        val latestUserImages = lastUserMsg?.let { msg ->
            resultOf {
                AppJson.decodeFromString(ListSerializer(String.serializer()), msg.imageBase64Json)
            }.getOrNull()?.map { stripDataUriPrefix(it) }?.filter { it.isNotEmpty() }
        } ?: emptyList()

        val finalUserContent: String
        val finalImages: List<String>
        if (model != null && model.supportsVisionInput()) {
            // 模型支持视觉:直接传图片(保持现有逻辑)
            finalUserContent = userContent
            finalImages = latestUserImages
        } else if (latestUserImages.isNotEmpty()) {
            // 模型不支持视觉但有图片:调用 VisionBridge 将图片描述注入文本,清空图片避免 HTTP 400
            val prepared = resultOf {
                visionBridge.prepare(
                    text = userContent,
                    images = latestUserImages,
                    userRequest = userContent,
                    sessionId = chatId,
                )
            }.getOrNull()
            if (prepared != null) {
                finalUserContent = prepared.text
                finalImages = prepared.images
            } else {
                // VisionBridge 失败:降级不传图片(避免向纯文本模型发图导致 400)
                finalUserContent = userContent
                finalImages = emptyList()
            }
        } else {
            finalUserContent = userContent
            finalImages = emptyList()
        }
        messages.add(
            UIMessage(
                role = MessageRole.USER,
                content = finalUserContent,
                imageBase64List = finalImages,
            ),
        )

        return messages
    }

    /**
     * 改造 2: 构造 Phone Session 推送式 prompt(参考 参考开源项目 channel-router.ts:793-821)。
     *
     * 每个 agent 独立收到一条"你的手机收到了群聊新消息"的推送,而非把所有成员塞进同一上下文。
     *  - 顶部声明"手机收到新群聊消息"
     *  - 中间是最近消息 transcript(发言者行首标注,与身份防混淆 guidance 配合)
     *  - 底部根据是否被 @ 提示优先级,并要求"调用 channel_reply 发言 / channel_pass 跳过"
     *
     * v1.0.53 Phase 5: 把 [PASS] 文本指令改为工具调用指令。
     * LLM 不再输出 [PASS] 文本,而是调用 channel_pass 工具跳过;调用 channel_reply 发言。
     *
     * @param chatName 群聊名称
     * @param assistant 当前 agent 配置(用于在提示中点名称呼)
     * @param recentMessages 最近消息列表(按时间升序)
     * @param isMentioned 是否被 @提及(被提及时提示"本轮优先被提醒")
     * @param isRepair 是否为决策修复重试(提示"上一轮没有回复")
     * @return Phone Session 推送式 prompt 文本
     */
    private fun buildPhonePrompt(
        chatName: String,
        assistant: AssistantEntity,
        recentMessages: List<GroupChatMessageEntity>,
        isMentioned: Boolean,
        isRepair: Boolean,
    ): String = buildString {
        appendLine("${assistant.name},你的手机收到了「$chatName」的新群聊消息。")
        appendLine()
        if (recentMessages.isNotEmpty()) {
            appendLine("【最近消息】")
            appendLine(formatMessageTranscript(recentMessages))
            appendLine()
        }
        // v1.0.53 Phase 5: 决策指令改为工具调用
        if (isMentioned) {
            appendLine("⚠️ 这轮消息明确 @ 了你,你必须调用 channel_reply 回复这条消息。")
            appendLine("- 用户 @ 你,是因为想听到你的回应。回复应当自然、贴合你的角色。")
            appendLine("- 被 @ 时不得调用 channel_pass,必须调用 channel_reply 给出你的回应。")
        } else {
            appendLine("你也是群聊成员,看到这段消息后可以主动参与。")
            appendLine("- 简单问候/寒暄/闲聊:调用 channel_reply 回复一句即可,不要 pass。")
            appendLine("- 只有确实与自己无关、无话可说时,才调用 channel_pass 跳过本轮。")
        }
        if (isRepair) {
            appendLine()
            appendLine("⚠️ 上一轮你没有回复,但你被 @ 提及了。本轮必须调用 channel_reply 回复,不得再次 channel_pass。")
        }
        appendLine()
        appendLine("发言格式(channel_reply 的 content 参数):")
        appendLine("- 先写 <mood>...</mood> 块(内部腹稿,系统会自动剥离)")
        appendLine("- 然后直接写正文(像群聊里的自然发言,口语化)")
        appendLine("- 用 <mood>...</mood>(尖括号),不要用 [mood]...[/mood](方括号)")
    }

    /**
     * v1.136: 去除 data URI 前缀,返回纯 base64 字符串。
     * 群聊中图片以 "data:image/jpeg;base64,..." 形式存储,而 OpenAIProvider 期望纯 base64。
     */
    private fun stripDataUriPrefix(dataUri: String): String {
        val commaIndex = dataUri.indexOf(',')
        return if (commaIndex >= 0 && dataUri.startsWith("data:", ignoreCase = true)) {
            dataUri.substring(commaIndex + 1)
        } else {
            dataUri
        }
    }

    /**
     * 把最近消息格式化为文本 transcript。
     *
     * 格式:
     * ```
     * [发送者名 | MM-dd HH:mm] 内容
     * [发送者名 | MM-dd HH:mm] 内容
     * ```
     *
     * 若消息附带图片,则在内容后标注 `[图片xN]`。
     */
    private fun formatMessageTranscript(messages: List<GroupChatMessageEntity>): String {
        val timeFormatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        for (msg in messages) {
            val timeStr = timeFormatter.format(Date(msg.timestamp))
            val imageCount = resultOf {
                AppJson.decodeFromString(ListSerializer(String.serializer()), msg.imageBase64Json).size
            }.getOrNull() ?: 0
            val imageHint = if (imageCount > 0) " [图片x$imageCount]" else ""
            sb.appendLine("[${msg.senderName} | $timeStr] ${msg.body}$imageHint")
        }
        return sb.toString().trimEnd()
    }

    /**
     * 清理 Agent 回复中不应展示给用户的包装标记。
     *
     * - 剥离 `<mood>...</mood>` 情绪模块
     * - 剥离 `<think>...</think>` 思考模块
     * - 剥离 `[channel_reply]...[/channel_reply]` 工具调用包装,只保留内部正文
     */
    private fun sanitizeAgentReply(text: String): String {
        // M9 已知限制: 此正则会剥离所有 <mood>/<think> 块,包括 LLM 代码示例中的标签。
        // 短期可接受,长期建议用更明确的分隔符。
        val withoutMood = text.replace(MusePatterns.MOOD_TAG_REGEX, "")
        val afterThinkReplace = withoutMood.replace(MusePatterns.THINK_TAG_REGEX, "")
        // L9: 处理未闭合的 <think> 标签 — 若仍有 <think> 残留(无对应 </think>),则从 <think> 截断到末尾
        val thinkIdx = afterThinkReplace.indexOf("<think>", ignoreCase = true)
        val withoutThink = if (thinkIdx >= 0) afterThinkReplace.substring(0, thinkIdx) else afterThinkReplace
        val withoutChannelReply = withoutThink.replace(
            // L6: 加 RegexOption.IGNORE_CASE 忽略大小写
            Regex("\\[channel_reply\\](.*?)\\[/channel_reply\\]", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
        ) { it.groupValues[1].trim() }
        return withoutChannelReply.trim()
    }

    /**
     * 从原始回复中提取 <mood>...</mood> 块内容。
     */
    private fun extractMood(text: String): String? {
        val sb = StringBuilder()
        var match = MusePatterns.MOOD_TAG_REGEX.find(text)
        while (match != null) {
            sb.appendLine(match.groupValues[1].trim())
            match = match.next()
        }
        return sb.toString().trim().ifBlank { null }
    }

    /**
     * 从原始回复中提取 <think>...</think> 块内容。
     */
    private fun extractReasoning(text: String): String? {
        val sb = StringBuilder()
        var match = MusePatterns.THINK_TAG_REGEX.find(text)
        while (match != null) {
            sb.appendLine(match.groupValues[1].trim())
            match = match.next()
        }
        return sb.toString().trim().ifBlank { null }
    }
}
