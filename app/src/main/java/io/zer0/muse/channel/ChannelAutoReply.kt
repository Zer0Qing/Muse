package io.zer0.muse.channel

import android.content.Context
import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
) {
    private val mutex = Mutex()
    private var lastHandled: Triple<String, String, String>? = null
    private var lastHandledAt = 0L

    /** 挂载入站监听(幂等,App 启动时调用一次)。 */
    fun start() {
        ChannelInbox.attach(context)
        ChannelInbox.onInbound = { inbound ->
            appScope.launch { handle(inbound) }
        }
    }

    private suspend fun handle(inbound: ChannelInbox.Inbound) {
        val text = inbound.summary.trim()
        if (text.isBlank()) return
        mutex.withLock {
            if (isDuplicate(inbound.platform, inbound.from, text)) return
            channelManager.refresh()
            val config = channelManager.channels.value.firstOrNull {
                it.enabled && it.autoReply && it.platform.name == inbound.platform
            } ?: return
            val reply = runAgent(text) ?: return
            val result = channelManager.sendText(
                channelId = config.id,
                text = reply,
                targetOverride = inbound.from.takeIf { it.isNotBlank() },
            )
            if (result.ok) {
                Logger.i(TAG, "自动回复已发送(${inbound.platform} ← ${inbound.from})")
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

    /** 跑一轮非流式对话;失败返回 null(已记日志)。 */
    private suspend fun runAgent(prompt: String): String? = runCatching {
        val assistant = resolveAssistant()
        val messages = buildList {
            if (assistant.systemPrompt.isNotBlank()) {
                add(UIMessage(role = MessageRole.SYSTEM, content = assistant.systemPrompt))
            }
            add(UIMessage(role = MessageRole.USER, content = prompt))
        }
        val completion = withTimeoutOrNull(LLM_TIMEOUT_MS) {
            chatService.completeText(
                messages = messages,
                temperature = assistant.temperature,
                maxTokens = assistant.maxTokens,
            )
        } ?: error(context.getString(R.string.channel_err_ai_timeout))
        io.zer0.muse.transformer.stripThinkTags(completion.text).take(MAX_REPLY_LENGTH)
    }.onFailure { e -> Logger.w(TAG, "自动回复生成失败: ${e.message}") }.getOrNull()

    /** 默认助手优先,再兜底第一个;完全无助手则抛出(由 runAgent 捕获)。 */
    private suspend fun resolveAssistant(): AssistantEntity {
        val assistants = assistantRepository.observeAll.first()
        return assistants.firstOrNull { it.id == "default" }
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
    }
}
