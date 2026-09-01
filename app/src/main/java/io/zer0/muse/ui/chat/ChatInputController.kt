package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.muse.R
import io.zer0.muse.ui.PendingMessage
import io.zer0.muse.ui.common.feedback.MuseToast

/** v1.205 B2: 待发送队列上限。 */
internal const val MAX_PENDING_SEND_QUEUE = 8

/**
 * v1.x: 从 ChatViewModel 抽离的输入/草稿/待发送队列/输入历史 Controller。
 *
 * 职责边界:
 *  - 输入框文本、草稿标记、待发送队列(入队/出队/编辑/清空/发送)、输入历史导航。
 *  - 全部通过 [ChatStateAccessor] 读写共享 state,自身不持有 state。
 *  - "发送"动作通过 [onEnqueueSend] 回调交给宿主(走发送管道),本类不持有生成逻辑。
 */
class ChatInputController(
    private val accessor: ChatStateAccessor,
    private val appContext: Context,
    private val onEnqueueSend: (text: String, images: List<String>, sessionId: String) -> Unit,
) {

    fun updateInput(text: String) {
        // v1.0.47 P5: 用户手动编辑输入时退出历史导航,重置 inputHistoryIndex
        // v1.0.72: 草稿功能已砍掉(不再防抖写 DataStore)
        accessor.update { it.copy(input = text, hasDraft = false, inputHistoryIndex = null) }
    }

    /** v1.205 B2: 把当前输入加入待发送队列(生成期间排队,不打断当前生成)。 */
    fun enqueuePendingSend() {
        val st = accessor.snapshot
        val text = st.input.trim()
        if (text.isBlank() && st.pendingImages.isEmpty()) return
        if (st.sendQueue.size >= MAX_PENDING_SEND_QUEUE) {
            MuseToast.show(appContext.getString(R.string.chat_pending_queue_full))
            return
        }
        accessor.update {
            it.copy(
                input = "",
                pendingImages = emptyList(),
                sendQueue = it.sendQueue + PendingMessage(text, st.pendingImages),
            )
        }
    }

    /** v1.205 B2: 移除队列中第 [index] 条。 */
    fun removePendingSend(index: Int) {
        accessor.update { it.copy(sendQueue = it.sendQueue.filterIndexed { i, _ -> i != index }) }
    }

    /** v1.205 B2: 单独发送队列中第 [index] 条;先出队再发送,避免重入重复发送。 */
    fun sendPendingSend(index: Int) {
        val st = accessor.snapshot
        val item = st.sendQueue.getOrNull(index) ?: return
        val sessionId = st.currentSessionId ?: return
        accessor.update { it.copy(sendQueue = it.sendQueue.filterIndexed { i, _ -> i != index }) }
        onEnqueueSend(item.text, item.images, sessionId)
    }

    /** v1.205 B2: 把队列中第 [index] 条回填到输入框(编辑后重新入队/发送),并出队。 */
    fun editPendingSend(index: Int) {
        val st = accessor.snapshot
        val item = st.sendQueue.getOrNull(index) ?: return
        accessor.update {
            it.copy(
                input = item.text,
                pendingImages = item.images,
                sendQueue = it.sendQueue.filterIndexed { i, _ -> i != index },
            )
        }
    }

    /** v1.205 B2: 清空整个待发送队列。 */
    fun clearPendingQueue() {
        accessor.update { it.copy(sendQueue = emptyList()) }
    }

    /**
     * v1.0.47 P5: 输入框上/下箭头回调,遍历本会话输入历史。
     * 约定:inputHistory 按"新→旧"存储(index 0 = 最近一条)。
     */
    fun navigateInputHistory(direction: Int) {
        val st = accessor.snapshot
        val target = historyNavigationTarget(direction, st.inputHistory, st.inputHistoryIndex) ?: return
        if (target == -1) {
            accessor.update { it.copy(input = "", inputHistoryIndex = null) }
        } else {
            accessor.update { it.copy(input = st.inputHistory[target], inputHistoryIndex = target) }
        }
    }

    /**
     * 计算输入历史导航目标。约定 inputHistory 按"新→旧"存储(index 0 = 最近一条)。
     *
     * @return 目标索引(>=0);-1 表示"退出导航,清空输入";null 表示无操作。
     */
    private fun historyNavigationTarget(direction: Int, history: List<String>, current: Int?): Int? {
        if (history.isEmpty() || direction == 0) return null
        return when {
            // 上箭头:向更旧
            direction < 0 -> {
                val candidate = (current ?: -1) + 1
                if (candidate >= history.size) null else candidate
            }
            // 下箭头:向更新;未在导航中不动,已在最旧则退出导航
            current == null -> null
            current <= 0 -> -1
            else -> current - 1
        }
    }
}
