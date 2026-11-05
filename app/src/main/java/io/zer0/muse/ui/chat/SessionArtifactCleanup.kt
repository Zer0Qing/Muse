package io.zer0.muse.ui.chat

import io.zer0.common.Logger
import io.zer0.muse.data.chat.ConversationTreeSnapshotStore
import io.zer0.muse.data.todo.SessionTodoRepository
import io.zer0.muse.rag.SessionAttachmentService
import io.zer0.muse.tools.SessionToolLoadRegistry
import io.zer0.muse.tools.TodoTool
import io.zer0.muse.tools.ToolRegistry
import org.koin.core.context.GlobalContext

/**
 * 会话删除后要清的**残留清单**——单条删除与「清除全部聊天」共用这一份。
 *
 * ## 为什么必须抽出来
 *
 * 这份清单以前只写在 `ChatSessionController.deleteSession` 里。数据管理页的
 * 「清除全部聊天」直接调 `SessionRepository.hardDeleteAllSessions()`，
 * 那边只清了 FTS / 消息图片 / 记忆 —— 于是批量路径把下面这些东西**原样留下**：
 *
 *  - 对话树选择快照（`filesDir/conversation_tree_snapshots/<id>.json`）：
 *    会话 id 会复用的路径是**恢复备份 / 归档回滚**，一旦同一个 id 回来，
 *    `load` 就把上一个会话的分支选中状态读回来；
 *  - 会话待办（内存 `TodoTool.sessionTodos` + 库里 `SessionTodoRepository`）；
 *  - 附件索引与 chunk（`SessionAttachmentService`，按会话存）；
 *  - 工具可见集与工具装载记录（`ToolRegistry` / `SessionToolLoadRegistry`）。
 *
 * 一条清单定义在两处、迟早分叉 —— 现在只有一处。
 *
 * ## 留痕口径
 *
 * 每一项独立捕获、独立记录：**一项失败不能挡住后面几项**（级联最容易烂掉的方式就是
 * 前面抛了后面不做）。依赖拿不到（Koin 未就绪等）也算失败并写清楚，
 * 绝不静默跳过——静默跳过等于"以为清了"。
 */
internal object SessionArtifactCleanup {

    private const val TAG = "SessionArtifacts"

    /**
     * @param snapshotStore 快照仓库。调用方手里有就传（controller 走 `sessionDeps`，
     *                      数据管理页走 Koin）；传 null 会被记成一次失败而不是跳过。
     * @param reason 写进日志的来路（"删除会话"/"清除全部聊天"…），排查时能分清是谁触发的。
     * @return 未完成的项数（0 = 全部清干净）
     */
    suspend fun purge(sessionIds: Collection<String>, snapshotStore: ConversationTreeSnapshotStore?, reason: String): Int {
        if (sessionIds.isEmpty()) return 0
        var failures = 0
        sessionIds.forEach { id ->
            failures += step(id, "对话树快照") {
                val store = snapshotStore ?: error("快照仓库不可用（未注入）")
                check(store.delete(id)) { "快照文件没删净" }
            }
            failures += step(id, "会话待办(内存)") { TodoTool.clearSession(id) }
            failures += step(id, "会话待办(库)") { koinGet<SessionTodoRepository>().delete(id) }
            failures += step(id, "附件索引") {
                koinGet<SessionAttachmentService>().dropSessionAttachments(id, keepIndex = false)
            }
            failures += step(id, "工具可见集") { koinGet<ToolRegistry>().clearVisibleTools(id) }
            failures += step(id, "工具装载记录") { SessionToolLoadRegistry.clear(id) }
        }
        Logger.i(
            TAG,
            "会话残留清理结束：${sessionIds.size} 个会话，来路=$reason，未完成 $failures 项",
        )
        return failures
    }

    private suspend fun step(sessionId: String, name: String, block: suspend () -> Unit): Int = runCatching { block() }
        .onFailure { e ->
            Logger.w(
                TAG,
                "会话残留清理失败：$name sessionId=$sessionId —— ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
        .fold({ 0 }, { 1 })

    private inline fun <reified T : Any> koinGet(): T = GlobalContext.get().get()
}
