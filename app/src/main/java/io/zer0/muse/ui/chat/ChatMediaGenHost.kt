package io.zer0.muse.ui.chat

import android.content.Context
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
        }
        return Slot(kind, assistantId, toolSessionIdProvider(), generationTokenProvider())
    }

    override suspend fun finish(kind: MediaGenKind) {
        when (kind) {
            MediaGenKind.IMAGE -> accessor.update { it.copy(isGeneratingImage = false) }
            MediaGenKind.VIDEO -> accessor.update { it.copy(isGeneratingVideo = false) }
            MediaGenKind.QR_CODE -> Unit
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
                )
            }
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
            // 二维码失败不写助手消息(与旧实现一致):工具结果已含原因
            MediaGenKind.QR_CODE -> return ""
        }

        private val generatedRes: Int = when (kind) {
            MediaGenKind.IMAGE -> R.string.err_chat_img_generated
            MediaGenKind.VIDEO -> R.string.err_chat_video_generated
            MediaGenKind.QR_CODE -> R.string.err_chat_qr_generated
        }

        private val cancelRes: Int = when (kind) {
            MediaGenKind.IMAGE -> R.string.err_chat_img_cancelled
            MediaGenKind.VIDEO -> R.string.err_chat_video_cancelled
            MediaGenKind.QR_CODE -> R.string.err_chat_unknown
        }
    }

    private companion object {
        const val TAG = "ChatMediaGenHost"
    }
}
