package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ProviderException
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.util.ErrorMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * 消息翻译：把一条消息翻成目标语言，译文挂到源消息的 `translations` 上。
 *
 * 从 [ChatViewModel] 切出——这块逻辑自成闭环：读写 `isTranslating` 状态、
 * 驱动一次独立的流式请求、把结果写回源消息并落库。它与聊天主流程只共享
 * "当前会话 id" 和 "消息列表"两个读取点。
 */
internal class ChatTranslationCoordinator(
    private val host: Host,
) {
    /** 宿主提供的最小接口——只暴露翻译真正需要的东西。 */
    interface Host {
        val state: MutableStateFlow<ChatUiState>
        val messages: MutableStateFlow<List<UIMessage>>
        val scope: CoroutineScope
        val appContext: Context
        val chatService: ChatService
        val sessionRepository: SessionRepository

        fun displayedSessionId(): String?
        fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>)
        fun classifyErrorType(message: String, throwable: Throwable?): ChatErrorType

        /** 译文落盘后失效会话快照缓存，否则切走再切回会命中旧快照。 */
        fun invalidateSessionCache(sessionId: String)
    }

    private var job: Job? = null

    fun translate(messageId: Uuid, targetLanguage: String, style: String) {
        if (host.state.value.isStreaming || host.state.value.isTranslating) return
        val target = host.messages.value.firstOrNull { it.id == messageId } ?: return
        val sourceText = buildTranslationSourceText(target)
        if (sourceText.isBlank()) return

        host.state.update {
            it.copy(isTranslating = true, translatingMessageId = messageId, errors = emptyList())
        }
        job = host.scope.launch(AppDispatchers.io) {
            try {
                runTranslation(target, sourceText, targetLanguage, style)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程取消必须重抛，避免破坏 stop()/switchSession() 语义。
                throw e
            } catch (t: ProviderException) {
                reportTranslationFailure(t)
            } catch (t: IllegalStateException) {
                reportTranslationFailure(t)
            }
        }
    }

    private fun reportTranslationFailure(error: Throwable) {
        Logger.e("ChatVM", "translate failed", error)
        host.state.update {
            it.copy(
                errors = listOf(
                    ChatError(
                        type = ChatErrorType.UNKNOWN,
                        message = host.appContext.getString(
                            R.string.err_chat_translate_failed,
                            error.message ?: "",
                        ),
                    ),
                ),
                isTranslating = false,
                translatingMessageId = null,
            )
        }
    }

    private suspend fun runTranslation(target: UIMessage, sourceText: String, targetLanguage: String, style: String) {
        val systemPrompt = io.zer0.muse.ui.translate.TranslateViewModel.buildTranslationSystemPrompt(
            targetLanguage = targetLanguage,
            sourceLanguage = io.zer0.muse.ui.translate.TranslateViewModel.SOURCE_AUTO,
            style = style,
        )
        val userContent = io.zer0.muse.ui.translate.TranslateViewModel.buildTranslationUserContent(sourceText)
        val request = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = systemPrompt),
            UIMessage(role = MessageRole.USER, content = userContent),
        )
        val sessionId = host.displayedSessionId() ?: return

        // 空串占位表示"翻译中"，流式增量直接更新源消息（UI 以可展开块展示）。
        fun apply(text: String) {
            host.updateMessages { list ->
                list.map { m ->
                    if (m.id == target.id) m.copy(translations = m.translations + (targetLanguage to text)) else m
                }
            }
        }
        apply("")

        val sb = StringBuilder()
        var failure: Pair<String, ChatErrorType>? = null
        var completed = false
        host.chatService.streamChat(
            messages = request,
            reasoningLevel = ReasoningLevel.OFF,
            mode = ChatRequestMode.UTILITY,
        ).collect { ev ->
            when (ev) {
                is ChatStreamEvent.ContentDelta -> {
                    if (failure == null) {
                        sb.append(ev.delta)
                        apply(sb.toString())
                    }
                }
                is ChatStreamEvent.Error -> failure = ev.message to host.classifyErrorType(ev.message, ev.throwable)
                is ChatStreamEvent.StreamInterrupted -> failure = ev.message to ChatErrorType.NETWORK
                is ChatStreamEvent.Done -> completed = true
                else -> Unit
            }
        }

        val resolved = failure ?: if (!completed) {
            host.appContext.getString(R.string.err_chat_stream_broken) to ChatErrorType.NETWORK
        } else {
            null
        }
        if (resolved != null) {
            // 中断/失败时保留已显示的非空部分译文（用户已看到的内容不丢），仅清掉空占位。
            val partial = sb.toString().trim()
            host.updateMessages { list ->
                list.map { m ->
                    when {
                        m.id != target.id -> m
                        partial.isEmpty() -> m.copy(translations = m.translations - targetLanguage)
                        else -> m.copy(translations = m.translations + (targetLanguage to partial))
                    }
                }
            }
            host.state.update {
                it.copy(
                    errors = listOf(
                        ChatError(
                            type = resolved.second,
                            message = ErrorMessages.resolve(host.appContext, resolved.first),
                        ),
                    ),
                    isTranslating = false,
                    translatingMessageId = null,
                )
            }
            return
        }

        val translated = io.zer0.muse.transformer.stripThinkTags(sb.toString()).trim()
        if (translated.isEmpty()) {
            host.updateMessages { list ->
                list.map { m -> if (m.id == target.id) m.copy(translations = m.translations - targetLanguage) else m }
            }
            host.state.update {
                it.copy(
                    errors = listOf(
                        ChatError(
                            type = ChatErrorType.UNKNOWN,
                            message = host.appContext.getString(R.string.err_chat_translate_empty),
                        ),
                    ),
                    isTranslating = false,
                    translatingMessageId = null,
                )
            }
            return
        }

        apply(translated)
        host.state.update { it.copy(isTranslating = false, translatingMessageId = null) }
        host.messages.value.firstOrNull { it.id == target.id }?.let {
            host.sessionRepository.upsertMessage(sessionId, it)
        }
        host.invalidateSessionCache(sessionId)
    }
}
