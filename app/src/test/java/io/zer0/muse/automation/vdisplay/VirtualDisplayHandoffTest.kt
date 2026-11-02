package io.zer0.muse.automation.vdisplay

import io.mockk.mockk
import io.zer0.muse.automation.agent.UiAgentRunner
import io.zer0.muse.automation.core.AutomationManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 虚拟屏交接台账（对比清单 P1-02 的第一段）。
 *
 * 原来的行为：`ui_agent` 跑完在 `finally` 里直接销毁专属显示屏 ——
 * 用户永远看不到助手刚才在哪块屏上操作，也没法接手。
 *
 * 这里守住三条：
 *  1. 交出去的屏**有过期时间**（没人接手就自己失效，不留孤儿屏）；
 *  2. `take` **取走即移除**（两个入口同时驱动同一块屏，迟早互相顶掉画面）；
 *  3. 只有"确实有屏 + 没要求交接"时才销毁。
 */
class VirtualDisplayHandoffTest {

    private val runner = UiAgentRunner(
        manager = mockk<AutomationManager>(relaxed = true),
        vision = mockk(relaxed = true),
    )

    @Before
    fun setUp() {
        VirtualDisplayHandoffRegistry.resetForTests()
    }

    @After
    fun tearDown() {
        VirtualDisplayHandoffRegistry.resetForTests()
    }

    @Test
    fun `a handed off display is listed until someone takes it`() {
        VirtualDisplayHandoffRegistry.offer(displayId = 7, packageName = "com.example.shop", now = 1_000)

        val listed = VirtualDisplayHandoffRegistry.list(now = 1_000)

        assertEquals(1, listed.size)
        assertEquals(7, listed.first().displayId)
        assertEquals("com.example.shop", listed.first().packageName)
    }

    @Test
    fun `taking a display removes it so two consumers cannot fight over it`() {
        VirtualDisplayHandoffRegistry.offer(7, "com.example.shop", now = 1_000)

        val taken = VirtualDisplayHandoffRegistry.take(7, now = 1_000)
        val second = VirtualDisplayHandoffRegistry.take(7, now = 1_000)

        assertNotNull("第一次取走应该成功", taken)
        assertNull("第二次必须拿不到 —— 否则两个入口会互相顶掉画面", second)
        assertTrue(VirtualDisplayHandoffRegistry.list(now = 1_000).isEmpty())
    }

    @Test
    fun `expired handoffs are pruned and reported so the caller can destroy them`() {
        VirtualDisplayHandoffRegistry.offer(7, "com.example.shop", now = 1_000, ttlMs = 100)

        val expired = VirtualDisplayHandoffRegistry.pruneExpired(now = 1_200)

        assertEquals("过期项要报出来，调用方据此销毁远端屏", listOf(7), expired)
        assertTrue(VirtualDisplayHandoffRegistry.list(now = 1_200).isEmpty())
    }

    @Test
    fun `an expired entry is not listed even without an explicit prune`() {
        VirtualDisplayHandoffRegistry.offer(7, "com.example.shop", now = 1_000, ttlMs = 100)

        assertTrue(VirtualDisplayHandoffRegistry.list(now = 5_000).isEmpty())
    }

    @Test
    fun `release drops the handoff without destroying anything`() {
        VirtualDisplayHandoffRegistry.offer(7, "com.example.shop", now = 1_000)

        VirtualDisplayHandoffRegistry.release(7)

        assertTrue(VirtualDisplayHandoffRegistry.list(now = 1_000).isEmpty())
    }

    @Test
    fun `re-offering the same display updates it instead of duplicating`() {
        VirtualDisplayHandoffRegistry.offer(7, "a", now = 1_000)
        VirtualDisplayHandoffRegistry.offer(7, "b", now = 2_000)

        val listed = VirtualDisplayHandoffRegistry.list(now = 2_000)

        assertEquals(1, listed.size)
        assertEquals("b", listed.first().packageName)
    }

    // ── 销毁/交接的判定（纯规则） ─────────────────────────────────────────

    @Test
    fun `no display means nothing to destroy`() {
        assertFalse(runner.shouldDestroyOnFinish(displayId = null, handoffOnFinish = false))
        assertFalse(runner.shouldDestroyOnFinish(displayId = null, handoffOnFinish = true))
    }

    @Test
    fun `a display is destroyed unless the caller asked for a handoff`() {
        assertTrue(runner.shouldDestroyOnFinish(displayId = 7, handoffOnFinish = false))
        assertFalse("要求交接时不能销毁 —— 否则用户永远看不到那块屏", runner.shouldDestroyOnFinish(7, true))
    }
}
