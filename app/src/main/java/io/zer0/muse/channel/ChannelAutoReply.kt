package io.zer0.muse.channel

import android.content.Context
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.transformer.TemplateTransformer
import io.zer0.muse.transformer.TransformContext
import io.zer0.muse.vision.VisionBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * v2.0: 渠道自动回复 — 入站消息闭环(收到 → 跑一轮 → 回发到来源)。
 *
 * 由 [ChannelInbox.onInbound] 驱动:webhook 收到消息落收件箱后,若该平台存在
 * 开启 [ChannelConfig.autoReply] 的渠道配置,则以默认助手跑一轮非流式对话,
 * 并把回复通过对应渠道回发到消息来源(from)。
 *
 * 设计约束:
 *  - 全局串行(单 Mutex):渠道消息低频,串行避免同一用户连发时并发跑多轮模型;
 *  - 同内容去重:[DEDUP_WINDOW_MS] 内相同 (platform, from, 文本) 只处理一次,防平台重推;
 *  - 失败只记日志,不让异常外溢到 webhook 接收侧。
 */
class ChannelAutoReply(
    private val channelManager: ChannelManager,
    private val chatService: ChatService,
    private val assistantRepository: AssistantRepository,
    private val context: Context,
    private val appScope: CoroutineScope,
    /** v2.0.1: 用户档案(助手名/用户昵称) — 供系统提示词模板变量渲染。 */
    private val settings: SettingsRepository,
    /** v2.0.1: 工具注册表 — 渠道对话继承助手工具集(排除群聊工具与高风险工具)。 */
    private val toolRegistry: ToolRegistry,
    /** v2.0.1: 视觉辅助 — 模型不支持视觉时把图片转为文字描述(降级路径)。 */
    private val visionBridge: VisionBridge,
) {
    private val mutex = Mutex()
    private var lastHandled: Triple<String, String, String>? = null
    private var lastHandledAt = 0L

    /** v2.0.1: 模板渲染器 — 与聊天主链路一致地替换 {{char}}/{{user}} 等变量。 */
    private val templateTransformer = TemplateTransformer(context)

    /** 挂载入站监听(幂等,App 启动时调用一次)。 */
    fun start() {
        ChannelInbox.attach(context)
        ChannelConversationStore.attach(context)
        ChannelInbox.onInbound = { inbound ->
            appScope.launch { handle(inbound) }
        }
    }

    private suspend fun handle(inbound: ChannelInbox.Inbound) {
        val text = inbound.summary.trim()
        if (text.isBlank()) return
        // v2.0.1: 图片消息以内容哈希参与去重,避免两人/两图在去重窗口内被误合并。
        val dedupKey = if (inbound.mediaBase64.isNotBlank()) {
            "$text#${inbound.mediaBase64.hashCode()}"
        } else {
            text
        }
        mutex.withLock {
            if (isDuplicate(inbound.platform, inbound.from, dedupKey)) return
            channelManager.refresh()
            val config = channelManager.channels.value.firstOrNull {
                it.enabled && it.autoReply && it.platform.name == inbound.platform
            } ?: return
            // v2.0.1: 渠道对话历史 — 入站消息先入库(即使回复失败也保留记录)。
            ChannelConversationStore.append(
                config.id,
                inbound.from,
                "user",
                text,
                mediaKind = inbound.mediaKind,
                mediaBase64 = inbound.mediaBase64,
            )
            val reply = runAgent(config, inbound.from) ?: return
            ChannelConversationStore.append(config.id, inbound.from, "assistant", reply)
            val result = channelManager.sendText(
                channelId = config.id,
                text = reply,
                targetOverride = inbound.from.takeIf { it.isNotBlank() },
            )
            if (result.ok) {
                Logger.i(TAG, "自动回复已发送(${inbound.platform} ← ${inbound.from})")
                // v2.0.1: 上下文自动压缩(异步,不阻塞回复与下一条消息)。
                appScope.launch {
                    runCatching { maybeCompress(config, inbound.from) }
                        .onFailure { e -> Logger.w(TAG, "上下文压缩失败: ${e.message}") }
                }
            } else {
                Logger.w(TAG, "自动回复发送失败(${inbound.platform}): ${result.detail}")
            }
        }
    }

    /** 平台重推去重:同 (platform, from, 文本) 在 [DEDUP_WINDOW_MS] 内只处理一次。 */
    private fun isDuplicate(platform: String, from: String, text: String): Boolean {
        val now = System.currentTimeMillis()
        val key = Triple(platform, from, text)
        val duplicate = key == lastHandled && now - lastHandledAt < DEDUP_WINDOW_MS
        lastHandled = key
        lastHandledAt = now
        return duplicate
    }

    /** 跑一轮对话(渠道对话上下文 + 工具循环);失败返回 null(已记日志)。 */
    @Suppress("CyclomaticComplexMethod")
    private suspend fun runAgent(config: ChannelConfig, from: String): String? = runCatching {
        val assistant = resolveAssistant(config)
        // v2.0.1: 渲染系统提示词的模板变量 — 此前直接发送原始文本,模型会复读 "{{char}}" 字面量。
        val renderedSystem = renderSystemPrompt(assistant)
        // v2.0.1: 与主链路同规则解析模型/Provider — 修复此前不带 model 请求、
        // 落到 Provider 首个模型(表现为"默认降级到免费模型")的问题。
        val (model, providerConfig) = resolveModelAndProvider(assistant)
        // v2.0.1: 视觉能力判定 — 原生优先(模型支持视觉直接带图),否则视觉模型降级。
        val visionCapable = model?.supportsVisionInput() == true
        // v2.0.1: 多轮上下文 — 滚动摘要 + 最近 [CONTEXT_TURNS] 轮(入站消息已先入库)。
        val conversation = ChannelConversationStore.conversation(config.id, from)
        val contextTurns = conversation?.turns?.takeLast(CONTEXT_TURNS).orEmpty()
        // v2.0.1: 图片携带量限制 — 只对最近若干张图走原图/降级分析,
        // 更老的图用描述缓存或占位(避免长对话每轮重带大量 base64,控制体积与费用)。
        val imageTurnAts = contextTurns
            .filter { it.role == "user" && it.mediaKind == "image" && it.mediaBase64.isNotBlank() }
            .map { it.at }
        val recentNativeAts = imageTurnAts.takeLast(MAX_NATIVE_VISION_TURNS).toSet()
        val recentFallbackAts = imageTurnAts.takeLast(MAX_FALLBACK_VISION_TURNS).toSet()
        val workingMessages = buildList {
            if (renderedSystem.isNotBlank()) {
                add(UIMessage(role = MessageRole.SYSTEM, content = renderedSystem))
            }
            conversation?.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                add(UIMessage(role = MessageRole.SYSTEM, content = "更早的对话摘要：$summary"))
            }
            contextTurns.forEach { turn ->
                val role = if (turn.role == "assistant") MessageRole.ASSISTANT else MessageRole.USER
                if (turn.role == "user" && turn.mediaKind == "image" && turn.mediaBase64.isNotBlank()) {
                    when {
                        visionCapable && turn.at in recentNativeAts -> {
                            // 原生优先:直接把图片交给支持视觉的模型。
                            add(
                                UIMessage(
                                    role = role,
                                    content = turn.text.ifBlank { "[图片]" },
                                    imageBase64List = listOf(turn.mediaBase64),
                                ),
                            )
                        }
                        !visionCapable && turn.at in recentFallbackAts -> {
                            // 降级:视觉模型转文字描述(带缓存)。
                            add(UIMessage(role = role, content = resolveImageContent(config, from, turn)))
                        }
                        turn.mediaDescription.isNotBlank() -> {
                            // 更老的图:有描述缓存则附带,不再携带原图。
                            add(UIMessage(role = role, content = turn.mediaDescription))
                        }
                        else -> {
                            add(UIMessage(role = role, content = turn.text.ifBlank { "[图片]" }))
                        }
                    }
                } else {
                    add(UIMessage(role = role, content = turn.text.ifBlank { "[空]" }))
                }
            }
        }.toMutableList()
        if (workingMessages.none { it.role == MessageRole.USER }) {
            // 防御性兜底:理论上入站消息已入库,不会走到这里。
            return@runCatching null
        }
        // v2.0.1: 工具继承 — 与聊天同源的工具集(助手白名单 + 排除群聊/高风险工具)。
        val toolDefs = resolveToolDefinitions(assistant)
        var finalText: String? = null
        var plainFallback = ""
        var round = 0
        while (round < MAX_TOOL_ROUNDS) {
            round++
            val completion = withTimeoutOrNull(LLM_TIMEOUT_MS) {
                chatService.completeText(
                    messages = workingMessages,
                    model = model,
                    providerConfig = providerConfig,
                    temperature = assistant.temperature,
                    maxTokens = assistant.maxTokens,
                    tools = toolDefs.takeIf { it.isNotEmpty() },
                    mode = ChatRequestMode.CHAT,
                )
            } ?: error(context.getString(R.string.channel_err_ai_timeout))
            plainFallback = completion.text
            val toolCalls = completion.toolCalls.orEmpty()
            if (toolCalls.isEmpty()) {
                finalText = completion.text
                break
            }
            // 回填带 tool_calls 的 assistant 消息 — OpenAI 兼容协议要求 TOOL 消息紧跟其前置。
            workingMessages += UIMessage(
                role = MessageRole.ASSISTANT,
                content = completion.text,
                toolCalls = toolCalls,
            )
            for (toolCall in toolCalls) {
                val result = runCatching {
                    toolRegistry.executeFromJson(toolCall.name, toolCall.arguments)
                }.getOrElse { e -> "工具执行失败: ${e.message}" }
                workingMessages += UIMessage(
                    role = MessageRole.TOOL,
                    content = result.take(MAX_TOOL_RESULT_CHARS),
                    toolCallId = toolCall.id,
                )
                Logger.i(TAG, "渠道工具调用: ${toolCall.name}")
            }
        }
        // 轮次用尽仍未收口时,回退最后一次纯文本输出。
        io.zer0.muse.transformer.stripThinkTags(finalText ?: plainFallback).trim().take(MAX_REPLY_LENGTH)
    }.onFailure { e -> Logger.w(TAG, "自动回复生成失败: ${e.message}") }.getOrNull()

    /**
     * v2.0.1: 渠道可用工具集 — 与聊天同源([ToolRegistry.listToolsAsToolDefinitions]),
     * 按助手白名单过滤,并排除群聊 channel_* 工具与高风险(HIGH)工具。
     */
    private fun resolveToolDefinitions(assistant: AssistantEntity): List<ToolDefinition> {
        val configured = parseToolIds(assistant.toolIdsJson)
        return runCatching {
            toolRegistry.listToolsAsToolDefinitions().filter { def ->
                val selectedByTool = configured.isEmpty() || def.name in configured
                val notGroupChatTool = !def.name.startsWith("channel_")
                val notHighRisk = toolRegistry.getToolRiskLevel(def.name) != ToolRiskLevel.HIGH
                selectedByTool && notGroupChatTool && notHighRisk
            }
        }.onFailure { e ->
            Logger.w(TAG, "工具集解析失败: ${e.message}")
        }.getOrDefault(emptyList())
    }

    /** v2.0.1: 解析助手工具白名单(toolIdsJson);解析失败返回空集(= 不过滤)。 */
    private fun parseToolIds(json: String): Set<String> = runCatching {
        AppJson.parseToJsonElement(json).jsonArray
            .mapNotNull { it.jsonPrimitive.contentOrNull }
            .toSet()
    }.getOrDefault(emptySet())

    /**
     * v2.0.1: 视觉降级 — 把图片经视觉模型转为文字描述(结果缓存回 Turn,每张图只分析一次)。
     * 失败时返回占位文本(不缓存,下次触发重试)。
     */
    private suspend fun resolveImageContent(
        config: ChannelConfig,
        from: String,
        turn: ChannelConversationStore.Turn,
    ): String {
        if (turn.mediaDescription.isNotBlank()) return turn.mediaDescription
        val prepared = runCatching {
            visionBridge.prepare(
                text = turn.text.ifBlank { "[图片]" },
                images = listOf(turn.mediaBase64),
                userRequest = turn.text,
            )
        }.getOrNull()
        val text = prepared?.takeIf { it.success && it.text.isNotBlank() }?.text
        if (text != null) {
            ChannelConversationStore.updateTurnDescription(config.id, from, turn.at, text)
            return text
        }
        return "[图片(视觉辅助暂不可用)]" + if (turn.text.isNotBlank()) "\n${turn.text}" else ""
    }

    /**
     * v2.0.1: 上下文自动压缩 — 轮次超过 [COMPRESS_THRESHOLD] 时,
     * 把最旧的一段轮次经 LLM 合并进滚动摘要;失败仅记日志,下次触发时重试。
     */
    private suspend fun maybeCompress(config: ChannelConfig, from: String) {
        val conversation = ChannelConversationStore.conversation(config.id, from) ?: return
        if (conversation.turns.size <= COMPRESS_THRESHOLD) return
        val drainCount = conversation.turns.size - KEEP_TURNS
        if (drainCount <= 0) return
        val toCompress = conversation.turns.take(drainCount)
        val remaining = conversation.turns.drop(drainCount)
        val assistant = resolveAssistant(config)
        val (model, providerConfig) = resolveModelAndProvider(assistant)
        val prompt = buildString {
            if (conversation.summary.isNotBlank()) {
                appendLine("已有摘要：${conversation.summary}")
            }
            appendLine("新增对话：")
            toCompress.forEach { turn ->
                appendLine("${if (turn.role == "assistant") "助手" else "用户"}：${turn.text}")
            }
            appendLine("请把上述内容合并成一段简洁的中文对话摘要，保留关键信息、称呼与待办。只输出摘要正文。")
        }
        val summary = withTimeoutOrNull(COMPRESS_TIMEOUT_MS) {
            chatService.completeText(
                messages = listOf(UIMessage(role = MessageRole.USER, content = prompt)),
                model = model,
                providerConfig = providerConfig,
                maxTokens = SUMMARY_MAX_TOKENS,
                mode = ChatRequestMode.UTILITY,
            )
        }?.text?.let { io.zer0.muse.transformer.stripThinkTags(it).trim() } ?: return
        if (summary.isBlank()) return
        ChannelConversationStore.replace(
            config.id,
            from,
            conversation.copy(
                summary = summary.take(SUMMARY_MAX_CHARS),
                turns = remaining,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        Logger.i(TAG, "上下文已压缩(${config.id} ← $from): ${toCompress.size} 轮并入摘要")
    }

    /**
     * v2.0.1: 解析助手应使用的模型与 Provider — 与聊天主链路同规则:
     * 助手配置的 modelId/providerId 优先,回退全局选择模型,再回退激活 Provider 首个模型。
     */
    private suspend fun resolveModelAndProvider(assistant: AssistantEntity): Pair<Model?, ProviderConfig?> {
        val allProviders = runCatching { settings.getAllProviders() }.getOrDefault(emptyList())
        if (allProviders.isEmpty()) return null to null
        val activeProviderId = runCatching { settings.activeProviderIdFlow.first() }.getOrNull()
        val selectedModelId = runCatching { settings.selectedModelIdFlow.first() }.getOrNull()
        val assistantModelId = assistant.modelId?.takeIf { it.isNotBlank() }
        val assistantProviderId = assistant.providerId?.takeIf { it.isNotBlank() }
        val resolvedModel: Model? = if (assistantModelId != null && assistantProviderId != null) {
            allProviders.firstOrNull { it.id == assistantProviderId }
                ?.models?.firstOrNull { it.id == assistantModelId }
        } else {
            assistantModelId?.let { aid ->
                allProviders.firstOrNull { it.id == activeProviderId }
                    ?.models?.firstOrNull { it.id == aid }
                    ?: allProviders.flatMap { it.models }.firstOrNull { it.id == aid }
            }
        } ?: selectedModelId?.let { sid ->
            allProviders.firstOrNull { it.id == activeProviderId }
                ?.models?.firstOrNull { it.id == sid }
                ?: allProviders.flatMap { it.models }.firstOrNull { it.id == sid }
        } ?: allProviders.firstOrNull { it.id == activeProviderId && it.models.isNotEmpty() }
            ?.models?.firstOrNull()
            ?: allProviders.firstOrNull { it.models.isNotEmpty() }?.models?.firstOrNull()
        val resolvedProvider = resolvedModel?.let { m -> allProviders.firstOrNull { it.id == m.providerId } }
            ?: allProviders.firstOrNull { it.id == activeProviderId && it.models.isNotEmpty() }
            ?: allProviders.firstOrNull { it.models.isNotEmpty() }
        return resolvedModel to resolvedProvider
    }

    /**
     * v2.0.1: 渲染人设 systemPrompt 的模板变量({{char}}/{{user}}/日期等)。
     * 与聊天主链路同一套 TemplateTransformer;渲染失败回退原文,不阻塞回复。
     */
    private suspend fun renderSystemPrompt(assistant: AssistantEntity): String {
        val raw = assistant.systemPrompt
        if (raw.isBlank() || (!raw.contains("{{") && !raw.contains("{%"))) return raw
        val rendered = runCatching {
            val userProfile = settings.getUserProfile()
            templateTransformer.transform(
                messages = listOf(UIMessage(role = MessageRole.SYSTEM, content = raw)),
                context = TransformContext(
                    extras = mapOf(
                        "user_nickname" to userProfile.userNickName,
                        "assistant_name" to (userProfile.assistantName ?: assistant.name),
                    ),
                ),
            ).firstOrNull()?.content
        }.onFailure { e ->
            Logger.w(TAG, "系统提示词模板渲染失败: ${e.message}")
        }.getOrNull()
        return rendered?.takeIf { it.isNotBlank() } ?: raw
    }

    /** v2.0.1: 渠道绑定助手优先,再回退默认助手,再兜底第一个;完全无助手则抛出(由 runAgent 捕获)。 */
    private suspend fun resolveAssistant(config: ChannelConfig): AssistantEntity {
        val assistants = assistantRepository.observeAll.first()
        val bound = config.assistantId.takeIf { it.isNotBlank() }
            ?.let { id -> assistants.firstOrNull { it.id == id } }
        return bound
            ?: assistants.firstOrNull { it.id == "default" }
            ?: assistants.firstOrNull()
            ?: error(context.getString(R.string.channel_err_no_assistant))
    }

    companion object {
        private const val TAG = "ChannelAutoReply"

        /** 单轮 AI 调用超时(与定时任务一致,毫秒)。 */
        private const val LLM_TIMEOUT_MS = 60_000L

        /** 回发文本上限,防止超长内容超出各平台消息限制。 */
        private const val MAX_REPLY_LENGTH = 4_000

        /** 平台重推去重窗口(毫秒)。 */
        private const val DEDUP_WINDOW_MS = 30_000L

        /** v2.0.1: 提供给模型的最近轮次上限。 */
        private const val CONTEXT_TURNS = 30

        /** v2.0.1: 压缩触发阈值(轮次)、压缩后保留轮次。 */
        private const val COMPRESS_THRESHOLD = 40
        private const val KEEP_TURNS = 20

        /** v2.0.1: 压缩调用超时与摘要上限。 */
        private const val COMPRESS_TIMEOUT_MS = 30_000L
        private const val SUMMARY_MAX_TOKENS = 1200
        private const val SUMMARY_MAX_CHARS = 2_000

        /** v2.0.1: 工具循环上限与单条工具结果长度上限。 */
        private const val MAX_TOOL_ROUNDS = 5
        private const val MAX_TOOL_RESULT_CHARS = 4_000

        /** v2.0.1: 上下文内携带图片的上限 — 原生模型最近 N 张、降级分析最近 N 张。 */
        private const val MAX_NATIVE_VISION_TURNS = 4
        private const val MAX_FALLBACK_VISION_TURNS = 2
    }
}
