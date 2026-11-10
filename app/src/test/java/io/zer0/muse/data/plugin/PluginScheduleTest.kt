package io.zer0.muse.data.plugin

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件定时任务（插件计划：schedules）。
 *
 * 两条最容易出错的地方：**重复触发**（没有上次时间就每 tick 跑一遍）与
 * **时钟回拨后疯狂重跑**（`now - lastRun` 变负数，天真的实现会认为过期很久）。
 */
class PluginScheduleTest {

    private val minute = 60_000L
    private val now = 1_700_000_000_000L

    // ── 到期判定 ────────────────────────────────────────────────────────

    @Test
    fun `a task that never ran is due immediately`() {
        assertTrue(PluginScheduleDue.isDue(lastRunMillis = null, intervalMinutes = 60, nowMillis = now))
    }

    @Test
    fun `a task inside its interval is not due`() {
        assertFalse(PluginScheduleDue.isDue(now - 10 * minute, intervalMinutes = 60, nowMillis = now))
    }

    @Test
    fun `a task past its interval is due`() {
        assertTrue(PluginScheduleDue.isDue(now - 61 * minute, intervalMinutes = 60, nowMillis = now))
    }

    @Test
    fun `a clock jump backwards never triggers a rerun`() {
        // 文件/记录里的时间比"现在"还晚：当作刚跑过
        assertFalse(PluginScheduleDue.isDue(now + 5 * minute, intervalMinutes = 60, nowMillis = now))
    }

    @Test
    fun `intervals below the floor are raised to it`() {
        // 声明 1 分钟，实际按 15 分钟算：手机上没有更短的定时任务有意义
        assertFalse(PluginScheduleDue.isDue(now - 2 * minute, intervalMinutes = 1, nowMillis = now))
        assertTrue(
            PluginScheduleDue.isDue(
                now - (PluginScheduleDue.MIN_INTERVAL_MINUTES + 1) * minute,
                intervalMinutes = 1,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `the floor is reported to authors`() {
        assertFalse(PluginScheduleDue.intervalAccepted(5))
        assertTrue(PluginScheduleDue.intervalAccepted(PluginScheduleDue.MIN_INTERVAL_MINUTES))
    }

    // ── 执行器 ──────────────────────────────────────────────────────────

    private class Fixture(
        capabilityGranted: Boolean = true,
        enabled: Boolean = true,
        schedules: List<PluginScheduleDeclaration> = listOf(
            PluginScheduleDeclaration(name = "digest", handler = "onDigest", intervalMinutes = 60),
        ),
    ) {
        val manager: PluginManager = mockk(relaxed = true)
        val bridge: PluginActivationBridge = mockk(relaxed = true)
        val ledger: PluginCapabilityGateway = mockk(relaxed = true)
        val settings: io.zer0.muse.data.SettingsRepository = mockk(relaxed = true)
        var clock = 1_700_000_000_000L

        init {
            val plugin = mockk<PluginManager.InstalledPlugin>(relaxed = true)
            every { plugin.id } returns "p1"
            every { plugin.name } returns "p1"
            every { plugin.enabled } returns enabled
            every { plugin.kind } returns "tool"
            every { manager.list() } returns listOf(plugin)
            every { manager.pluginDirFor(any()) } returns java.io.File("build/tmp/p1")

            val verified = mockk<PluginManager.VerifiedPlugin>(relaxed = true)
            every { verified.manifest } returns PluginManifest(id = "p1", name = "p1", schedules = schedules)
            coEvery { manager.loadVerifiedPlugin("p1") } returns verified
            every { manager.getPluginConfigJson(any()) } returns ""

            every { ledger.isApproved("p1", PluginCapabilityGateway.SCHEDULE) } returns capabilityGranted
            coEvery { settings.arePluginsGloballyEnabled() } returns true
            coEvery { bridge.invokeScheduled(any(), any(), any()) } returns true
        }

        fun runner() = PluginScheduleRunner(manager, bridge, ledger, settings) { clock }
    }

    @Test
    fun `a granted plugin runs its task once and then waits for the interval`() = runBlocking {
        val f = Fixture()
        val runner = f.runner()

        assertEquals(1, runner.tick())
        assertEquals("同一 tick 内不该重复派发", 0, runner.tick())

        f.clock += 61 * minute
        assertEquals("过了间隔应该再跑一次", 1, runner.tick())
        coVerify(exactly = 2) { f.bridge.invokeScheduled("p1", "onDigest", "digest") }
    }

    @Test
    fun `an unauthorized plugin never runs unattended`() = runBlocking {
        val f = Fixture(capabilityGranted = false)

        assertEquals(0, f.runner().tick())
        coVerify(exactly = 0) { f.bridge.invokeScheduled(any(), any(), any()) }
    }

    @Test
    fun `a disabled plugin is skipped`() = runBlocking {
        val f = Fixture(enabled = false)

        assertEquals(0, f.runner().tick())
    }

    @Test
    fun `a plugin without schedules does nothing`() = runBlocking {
        val f = Fixture(schedules = emptyList())

        assertEquals(0, f.runner().tick())
    }

    @Test
    fun `a failed run still records the time so it does not retry every tick`() = runBlocking {
        val f = Fixture()
        coEvery { f.bridge.invokeScheduled(any(), any(), any()) } returns false
        val runner = f.runner()

        assertEquals("失败不计入派发数", 0, runner.tick())
        assertEquals("但也不该在下一 tick 立刻重试", 0, runner.tick())
        coVerify(exactly = 1) { f.bridge.invokeScheduled(any(), any(), any()) }
    }

    // ── 后台唤醒（schedule.background） ─────────────────────────────────

    @Test
    fun `a background tick skips plugins without the background capability`() = runBlocking {
        val f = Fixture()

        // 有 schedule，但没有 schedule.background
        assertEquals(0, f.runner().tick(background = true))
        coVerify(exactly = 0) { f.bridge.invokeScheduled(any(), any(), any()) }
    }

    @Test
    fun `a background tick runs plugins that were granted the background capability`() = runBlocking {
        val f = Fixture()
        every { f.ledger.isApproved("p1", PluginCapabilityGateway.SCHEDULE_BACKGROUND) } returns true

        assertEquals(1, f.runner().tick(background = true))
        coVerify(exactly = 1) { f.bridge.invokeScheduled("p1", "onDigest", "digest") }
    }

    @Test
    fun `the foreground tick is unaffected by the background capability`() = runBlocking {
        val f = Fixture()

        // 前台触发只看 schedule
        assertEquals(1, f.runner().tick())
    }
}
