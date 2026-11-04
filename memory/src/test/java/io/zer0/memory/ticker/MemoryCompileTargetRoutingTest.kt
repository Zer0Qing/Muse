package io.zer0.memory.ticker

import io.zer0.memory.compile.MemoryCompileTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 后台通知记忆的编译路由（21 行，但错一次就是跨助手污染）。
 *
 * 关键点写在源码注释里：后台任务是 fire-and-forget，**等它跑起来时前台助手可能已经切走了**，
 * 那时不能再拿"当前助手"决定这一轮的记忆写进谁的库 —— 否则用户 A 的对话内容会进 B 的记忆。
 */
class MemoryCompileTargetRoutingTest {

    private val current = MemoryCompileTarget(assistantId = "writer", spaceId = "work")

    @Test
    fun `通知带过来的助手优先于当前助手`() {
        val target = notificationCompileTarget(current, assistantId = "researcher", spaceId = "trip")

        assertEquals("researcher", target.assistantId)
        assertEquals("researcher", target.scope)
        assertEquals("trip", target.spaceId)
    }

    @Test
    fun `助手 id 空白时退回当前助手 再退回主助手`() {
        val fallbackToCurrent = notificationCompileTarget(current, assistantId = "  ", spaceId = null)
        assertEquals("writer", fallbackToCurrent.assistantId)
        assertEquals("writer", fallbackToCurrent.scope)

        val emptyCurrent = MemoryCompileTarget(assistantId = null, spaceId = "")
        val fallbackToMain = notificationCompileTarget(emptyCurrent, assistantId = "", spaceId = null)
        assertEquals(MemoryTicker.MAIN_ASSISTANT_ID, fallbackToMain.assistantId)
        assertEquals("main", fallbackToMain.scope)
        assertEquals("空白空间要归一到 default", "default", fallbackToMain.spaceId)
    }

    @Test
    fun `空间留空时沿用当前空间而不是写进空空间`() {
        listOf(null, "", "   ").forEach { spaceId ->
            val target = notificationCompileTarget(current, assistantId = "researcher", spaceId = spaceId)

            assertEquals("空白要回落到当前空间: $spaceId", "work", target.spaceId)
        }
    }

    @Test
    fun `主助手的 scope 固定是 main 不是助手 id`() {
        val main = MemoryCompileTarget(assistantId = "assistant-main", spaceId = "default")

        val target = notificationCompileTarget(main, assistantId = MemoryTicker.MAIN_ASSISTANT_ID, spaceId = null)

        assertEquals("main", target.scope)
    }
}
