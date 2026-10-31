package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.ai.core.UIMessage
import io.zer0.muse.R
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.tools.MediaGenHost
import io.zer0.muse.tools.MediaGenKind
import io.zer0.muse.tools.MediaGenResult
import io.zer0.muse.tools.MediaGenSlot
import kotlin.uuid.Uuid

/**
 * P2-23: 媒体生成工具(图片 / 视频 / 二维码)在聊天页的投递宿主。
 *
 * 工具实现本身([io.zer0.muse.tools.MediaGenToolsImpl])与 ViewModel 无关,由
 * `MediaGenToolsRegistrar` 在 App 启动时注册;本类只负责把生成结果写回"当前正在
 * 生成的助手消息",是原先 exec* 方法里与 UI 耦合的那部分:
 *  - 生成开始:占位文案 + isGeneratingImage / isGeneratingVideo 标志;
 *  - 视频进度:每 5 秒刷新一次"已等待 N 秒";
 *  - 生成结束:成功时把媒体追加到消息并落库(切会话时直接按原会话落库),
 *    失败/取消时写入对应文案,并清理占位标志。
 *
 * 只有 UI 对话进程(ChatViewModel)会安装本宿主;定时任务 / 群聊 / 子代理等无 UI
 * 链路拿不到它,媒体工具退化为返回文件路径 / URL,不依赖 UI 才能注册执行。
 */
class ChatMediaGenHost(
    private val accessor: ChatStateAccessor,
    private val sessionRepository: SessionRepository,
    private val streamCoordinator: ChatStreamCoordinator,
    private val appContext: Context,
    /** 当前工具轮次对应的助手消息 id(生成开始前锚定)。 */
    private val toolAssistantIdProvider: () -> Uuid?,
    /** 当前工具轮次对应的会话 id。 */
    private val toolSessionIdProvider: () -> String?,
    /** 生成代际令牌:用户发新消息 / 切会话后变化,晚到结果据此作废。 */
    private val generationTokenProvider: () -> Long,
    /** 写入助手消息文案(保持 isStreaming 占位);对应 ChatViewModel.updateAssistant 包装。 */
    private val updateAssistant: suspend (Uuid, String) -> Unit,
    /** 含媒体的消息立即落盘(会话已删除时由实现自行跳过)。 */
    private val persistMediaMessage: suspend (String?, Uuid) -> Unit,
    /** 登记本代媒体消息,供工具循环收尾兜底落盘。 */
    private val registerMediaMessage: (Uuid) -> Unit,
) : MediaGenHost {

    override suspend fun begin(kind: MediaGenKind): MediaGenSlot? {
        val assistantId = toolAssistantIdProvider() ?: return null
        // C-02: 置位生成中占位标志(ChatScreen 据此显示生成中卡片)
        when (kind) {
            MediaGenKind.IMAGE -> {
                updateAssistant(assistantId, appContext.getString(R.string.err_chat_img_generating))
                accessor.update { it.copy(isGeneratingImage = true) }
            }
            MediaGenKind.VIDEO -> {
                updateAssistant(assistantId, appContext.getString(R.string.err_chat_video_generating))
                accessor.update { it.copy(isGeneratingVideo = true) }
            }
            // 二维码是本地生成,无需占位卡片
            MediaGenKind.QR_CODE -> Unit
            // 语音合成只用一两秒,也不占占位卡片(失败/成功文案由 report 写入)
            MediaGenKind.VOICE -> Unit
        }
        return Slot(kind, assistantId, toolSessionIdProvider(), generationTokenProvider())
    }

    override suspend fun finish(kind: MediaGenKind) {
        when (kind) {
            MediaGenKind.IMAGE -> accessor.update { it.copy(isGeneratingImage = false) }
            MediaGenKind.VIDEO -> accessor.update { it.copy(isGeneratingVideo = false) }
            MediaGenKind.QR_CODE -> Unit
            MediaGenKind.VOICE -> Unit
        }
    }

    private inner class Slot(
        private val kind: MediaGenKind,
        private val assistantId: Uuid,
        private val sessionId: String?,
        private val generationToken: Long,
    ) : MediaGenSlot {

        override fun isActive(): Boolean = generationToken == generationTokenProvider()

        override suspend fun onProgress(elapsedSeconds: Int) {
            if (kind != MediaGenKind.VIDEO) return
            updateAssistant(
                assistantId,
                appContext.getString(R.string.err_chat_video_progress, elapsedSeconds),
            )
        }

        override suspend fun report(result: MediaGenResult) {
            when (result) {
                is MediaGenResult.Success -> deliverMedia(result)
                is MediaGenResult.Failure -> updateAssistant(assistantId, failureText(result.message))
                MediaGenResult.Cancelled -> updateAssistant(assistantId, appContext.getString(cancelRes))
            }
        }

        /** 成功:按当前显示焦点决定走 UI 合并还是直接按原会话落库(切会话不取消生成)。 */
        private suspend fun deliverMedia(result: MediaGenResult.Success) {
            if (kind == MediaGenKind.VOICE) {
                deliverVoice(result)
                return
            }
            val content = appContext.getString(generatedRes)
            // 审查修复 (2.0 B-04): 长 base64 data URI 经 MessageImageStore 落盘为 file://
            // 路径(文件名带内容哈希,并行写入互不覆盖),DB 不再存 MB 级 base64。
            val imageUrls = result.imageUrls.takeIf { it.isNotEmpty() }
                ?.let { sessionRepository.toPersistableImageUrls(assistantId.toString(), it) }
            if (isSessionDisplayed()) {
                // A-03: 原子合并,同轮并行生图(awaitAll)各自基于最新快照追加,互不覆盖
                streamCoordinator.appendMediaToAssistant(
                    id = assistantId,
                    content = content,
                    imageUrls = imageUrls,
                    videoFileUri = result.videoFileUri,
                    voiceUri = result.voiceUri,
                    voiceDurationMs = result.voiceDurationMs,
                )
                // 审计修复 (S-01): 含媒体的消息立即落盘,否则重启/切页后媒体消失
                persistMediaMessage(sessionId, assistantId)
                // C-17: 登记本代媒体消息,收尾兜底落盘
                registerMediaMessage(assistantId)
            } else {
                // 审查修复 (2.0 A-04): 切会话不取消 — 媒体按原会话直接落库,
                // 切回原会话时从 DB 恢复展示(UI 合并路径对非显示会话无消息可写)
                sessionRepository.attachMediaToMessage(
                    sessionId = sessionId,
                    messageId = assistantId,
                    content = content,
                    imageUrls = imageUrls,
                    videoFileUri = result.videoFileUri,
                    voiceUri = result.voiceUri,
                    voiceDurationMs = result.voiceDurationMs,
                )
            }
        }

        /**
         * 语音投递:一回合可以连发多条,但消息上的语音只有一个位置。
         *
         * 本轮助手消息还没有语音时沿用原有投递路径(挂到它上面);已经有语音时再写上去
         * 会把前一条覆盖掉,所以从第二条起每条语音各起一条独立助手消息承载 —— 新消息
         * 追加在锚定消息之后,先投递的排在前面。
         */
        private suspend fun deliverVoice(result: MediaGenResult.Success) {
            val voiceUri = result.voiceUri ?: return
            val content = appContext.getString(generatedRes)
            val displayed = isSessionDisplayed()
            val anchor = anchorMessage(displayed)
            if (anchor?.voiceUri == null) {
                deliverVoiceTo(assistantId, content, voiceUri, result.voiceDurationMs, displayed)
                return
            }
            deliverVoiceAsOwnMessage(anchor, content, voiceUri, result.voiceDurationMs, displayed)
        }

        /**
         * 锚定助手消息的当前状态:会话显示中读内存消息列表(最新),否则按 id 查库
         * (非显示会话的内存列表属于别的会话,拿不到这条消息)。
         */
        private suspend fun anchorMessage(displayed: Boolean): UIMessage? {
            if (displayed) {
                accessor.messagesSnapshot.firstOrNull { it.id == assistantId }?.let { return it }
            }
            return sessionRepository.getMessageAsUiMessage(assistantId.toString())
        }

        /** 把一条语音写到指定消息上(显示中走 UI 合并 + 立即落盘,否则按原会话落库)。 */
        private suspend fun deliverVoiceTo(
            messageId: Uuid,
            content: String,
            voiceUri: String,
            durationMs: Long,
            displayed: Boolean,
        ) {
            if (displayed) {
                streamCoordinator.appendMediaToAssistant(
                    id = messageId,
                    content = content,
                    voiceUri = voiceUri,
                    voiceDurationMs = durationMs,
                )
                persistMediaMessage(sessionId, messageId)
                registerMediaMessage(messageId)
            } else {
                sessionRepository.attachMediaToMessage(
                    sessionId = sessionId,
                    messageId = messageId,
                    content = content,
                    voiceUri = voiceUri,
                    voiceDurationMs = durationMs,
                )
            }
        }

        /** 新建一条助手消息承载这条语音:内容沿用同一句占位文案,挂在锚定消息所在的提问下。 */
        private suspend fun deliverVoiceAsOwnMessage(
            anchor: UIMessage,
            content: String,
            voiceUri: String,
            durationMs: Long,
            displayed: Boolean,
        ) {
            val voiceMessage = UIMessage(
                id = Uuid.random(),
                role = anchor.role,
                content = content,
                createdAt = nextVoiceCreatedAt(displayed, anchor.createdAt),
                parentGroupId = anchor.parentGroupId,
            )
            if (displayed) {
                // 追加到消息流尾部 = 紧跟锚定消息(下一轮的流式占位在这一轮工具执行之后才追加)
                val carried = voiceMessage.copy(voiceUri = voiceUri, voiceDurationMs = durationMs)
                accessor.updateMessages { list -> list + carried }
                persistMediaMessage(sessionId, carried.id)
                registerMediaMessage(carried.id)
                return
            }
            val targetSession = sessionId ?: return
            // 非显示会话:先插一条消息,再按 id 把语音挂上去(与生图切会话路径同一套落库能力)
            sessionRepository.appendMessage(targetSession, voiceMessage)
            deliverVoiceTo(voiceMessage.id, content, voiceUri, durationMs, displayed = false)
        }

        /** A-04: 当前显示会话是否仍是本次生成的目标会话。 */
        private fun isSessionDisplayed(): Boolean {
            val active = sessionId ?: return false
            val snapshot = accessor.snapshot
            val displayed = if (snapshot.isAgentMode) snapshot.agentSessionId else snapshot.currentSessionId
            return active == displayed
        }

        private fun failureText(message: String): String = when (kind) {
            MediaGenKind.IMAGE -> appContext.getString(R.string.err_chat_img_gen_failed, message)
            MediaGenKind.VIDEO -> appContext.getString(R.string.err_chat_video_gen_failed, message)
            MediaGenKind.VOICE -> appContext.getString(R.string.err_chat_voice_gen_failed, message)
            // 二维码失败不写助手消息(与旧实现一致):工具结果已含原因
            MediaGenKind.QR_CODE -> return ""
        }

        private val generatedRes: Int = when (kind) {
            MediaGenKind.IMAGE -> R.string.err_chat_img_generated
            MediaGenKind.VIDEO -> R.string.err_chat_video_generated
            MediaGenKind.QR_CODE -> R.string.err_chat_qr_generated
            MediaGenKind.VOICE -> R.string.err_chat_voice_generated
        }

        private val cancelRes: Int = when (kind) {
            MediaGenKind.IMAGE -> R.string.err_chat_img_cancelled
            MediaGenKind.VIDEO -> R.string.err_chat_video_cancelled
            MediaGenKind.QR_CODE -> R.string.err_chat_unknown
            MediaGenKind.VOICE -> R.string.err_chat_voice_cancelled
        }
    }

    /** 本次生成里最后一条新语音消息的 createdAt(单调递增的起点)。 */
    private var lastVoiceCreatedAt = 0L

    /**
     * 新语音消息的创建时间严格晚于已有消息。
     *
     * 内存排序在缺少 seq 时用 createdAt 兜底,同一毫秒内连投两条会退回按 id 排序,
     * 与投递顺序不符;这里保证单调递增。
     */
    @Synchronized
    private fun nextVoiceCreatedAt(displayed: Boolean, anchorCreatedAt: Long): Long {
        val tail = if (displayed) accessor.messagesSnapshot.lastOrNull()?.createdAt ?: 0L else 0L
        val createdAt = maxOf(System.currentTimeMillis(), maxOf(anchorCreatedAt, tail, lastVoiceCreatedAt) + 1)
        lastVoiceCreatedAt = createdAt
        return createdAt
    }

    private companion object {
        const val TAG = "ChatMediaGenHost"
    }
}
