package io.zer0.muse.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-UI-F13: 删除会话必须同步清理 TodoTool 的会话待办快照。
 *
 * TodoTool.sessionTodos 是单例 ConcurrentHashMap,原实现只在 execute() 写入、
 * getTodos/clearSession 读取,而 clearSession 全仓 0 调用 → 每个用过 todo_write
 * 的会话都留一份 TodoList 在内存,且已删会话仍可经 getTodos 读到。
 */
class SessionTodoCleanupTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/ui/chat/ChatSessionController.kt").readText()

    @Test
    fun `delete session clears session todos`() {
        val src = source()
        val start = src.indexOf("fun deleteSession(")
        assertTrue("未找到 deleteSession", start >= 0)
        val body = src.substring(start, (start + 3000).coerceAtMost(src.length))
        assertTrue(
            "删除会话必须清理 TodoTool 待办快照",
            body.contains("TodoTool.clearSession(sessionId)"),
        )
    }
}
