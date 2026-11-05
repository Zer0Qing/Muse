package io.zer0.muse.proactive

import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2/P3 的调度与小事实回归。
 *
 *  - [ProactiveScheduler.backoffForSkips]:连续 Skip 指数退避、封顶 6 小时、溢出安全;
 *  - [ProactiveComposer.timeFactsLine]:无时间信息时**整行不出现**,
 *    保证提示词与 2.6.22 逐字一致(P3 只加事实,不改无事实时的措辞)。
 */
class ProactiveTimingBackoffTest {

    // ── P2 退避 ────────────────────────────────────────────────

    @Test
    fun `backoff 零跳过就是原间隔`() {
        assertEquals(60 * 60 * 1000L, ProactiveScheduler.backoffForSkips(60 * 60 * 1000L, 0))
    }

    @Test
    fun `backoff 按跳过轮数指数拉长`() {
        val idle = 60 * 60 * 1000L
        assertEquals(2 * idle, ProactiveScheduler.backoffForSkips(idle, 1))
        assertEquals(4 * idle, ProactiveScheduler.backoffForSkips(idle, 2))
    }

    @Test
    fun `backoff 指数拉长并封顶 6 小时`() {
        val withWork = 20 * 60 * 1000L
        assertEquals(160 * 60 * 1000L, ProactiveScheduler.backoffForSkips(withWork, 3))
        // 位移封顶 ×16:极端 skips 既不溢出,增长也有界
        assertEquals(320 * 60 * 1000L, ProactiveScheduler.backoffForSkips(withWork, 4))
        assertEquals(320 * 60 * 1000L, ProactiveScheduler.backoffForSkips(withWork, 60))
        // 小时级基础间隔 ×16 会越过 6 小时 → 被上限截住
        assertEquals(6 * 60 * 60 * 1000L, ProactiveScheduler.backoffForSkips(60 * 60 * 1000L, 60))
    }

    // ── P3 时间跨度事实行 ──────────────────────────────────────

    private fun composerInput(chat: Long? = null, proactive: Long? = null): ProactiveComposer.Input = ProactiveComposer.Input(
        assistant = io.zer0.muse.data.assistant.AssistantEntity(id = "t1", name = "test"),
        recentMessages = emptyList<UIMessage>(),
        now = System.currentTimeMillis(),
        minutesSinceLastChat = chat,
        minutesSinceLastProactive = proactive,
    )

    @Test
    fun `两个时间事实都缺时整行不出现`() {
        assertNull(ProactiveComposer.timeFactsLine(composerInput()))
    }

    @Test
    fun `时间跨度按人话取整`() {
        val line = ProactiveComposer.timeFactsLine(composerInput(chat = 35, proactive = 2 * 24 * 60 + 3))!!
        assertTrue("应含分钟表述: $line", line.contains("35 分钟"))
        assertTrue("天级取整天: $line", line.contains("2 天"))
    }

    @Test
    fun `role 消息不参与 事实行构造`() {
        // 只有 proactive 一条时不拼接聊天段,也不出现「null」字样
        val line = ProactiveComposer.timeFactsLine(composerInput(proactive = 90))!!
        assertTrue(!line.contains("上次聊天"))
        assertTrue(line.contains("1 小时"))
    }
}
