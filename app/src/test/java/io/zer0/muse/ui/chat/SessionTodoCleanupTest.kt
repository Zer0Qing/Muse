package io.zer0.muse.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 会话删除级联的**完整性**守卫（AUD-UI-F13 / AUDIT F14 / 第 94-96 轮的累积成果）。
 *
 * 原版本只盯"deleteSession 里有没有 TodoTool.clearSession"。后来发现这种盯法有两问题：
 *  1. 清单被抽成共用一份后（`SessionArtifactCleanup`），锚点就随重构漂移，测试变成改测试；
 *  2. 单条删除清了 6 件、「清除全部聊天」一件没清 —— 只盯一条路径等于放行另一条。
 *
 * 所以这里改成锁三件事：
 *  - 两条删除路径都必须走同一份清理器（**不许各自维护清单**）；
 *  - 清理器里必须逐项在册（少一项就红）；
 *  - 仓库层的批量删除必须把被删 id 交回来（否则调用方想清也清不了）。
 */
class SessionTodoCleanupTest {

    private fun source(vararg parts: String): String {
        val rel = "src/main/java/io/zer0/muse/" + parts.joinToString("/")
        val candidates = listOf(
            File(rel),
            File("app/" + rel),
            File("../app/" + rel),
        )
        return requireNotNull(candidates.firstOrNull { it.exists() }) {
            "找不到 $rel（工作目录=${File(".").absolutePath}）"
        }.readText()
    }

    @Test
    fun `单条删除走共用清理器`() {
        val src = source("ui/chat/ChatSessionController.kt")
        val start = src.indexOf("fun deleteSession(")
        assertTrue("未找到 deleteSession", start >= 0)
        val body = src.substring(start, (start + 2200).coerceAtMost(src.length))
        assertTrue(
            "删除会话必须调用 SessionArtifactCleanup.purge（清单不许在调用方各自维护）",
            body.contains("SessionArtifactCleanup.purge("),
        )
    }

    @Test
    fun `清除全部聊天也必须走同一份清理器`() {
        val src = source("ui/DataManagementScreen.kt")
        assertTrue(
            "「清除全部聊天」以前只清到 DB 层，快照/附件/待办/工具态全留盘上 —— 不许退回去了",
            src.contains("SessionArtifactCleanup.purge("),
        )
        assertTrue(
            "批量删除必须拿到被删会话 id，否则残留无从清起",
            src.contains("hardDeleteAllSessions()"),
        )
    }

    @Test
    fun `清理器里六项残留逐项在册`() {
        val src = source("ui/chat/SessionArtifactCleanup.kt")
        listOf(
            "store.delete(id)" to "对话树快照",
            "TodoTool.clearSession(id)" to "会话待办(内存)",
            "SessionTodoRepository" to "会话待办(库)",
            "dropSessionAttachments" to "附件索引",
            "clearVisibleTools" to "工具可见集",
            "SessionToolLoadRegistry.clear(id)" to "工具装载记录",
        ).forEach { (needle, label) ->
            assertTrue("清理清单少了「$label」（$needle）", src.contains(needle))
        }
    }

    @Test
    fun `批量删除要把被删的会话 id 交回调用方`() {
        val src = source("data/session/SessionRepository.kt")
        assertTrue(
            "hardDeleteAllSessions 必须返回被删 id 列表：残留清理在 UI 层，只有这里知道删了谁",
            src.contains("suspend fun hardDeleteAllSessions(): List<String>"),
        )
    }
}
