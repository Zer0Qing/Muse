package io.zer0.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 性能埋点（`:common` 待查清单最后一件）。
 *
 * 本轮改的是**计时的钟**：以前耗时用 `System.currentTimeMillis()` 相减，
 * 挂钟被 NTP 校时 / 用户改时间 / 休眠唤醒拉动时，会给出**负数或巨大的耗时** ——
 * 而这些数字会进 ANR 诊断（[Perf.snapshotRecent]）和性能报表。
 * 现在耗时走单调钟，`PerfRecord.timestamp` 仍走挂钟（那个字段表达"什么时候发生"）。
 *
 * `:common` 之前没配 `isReturnDefaultValues`，`android.util.Log` 一调就抛 "Stub!"，
 * 所以 `Logger`/`Perf` 在这个模块**根本没法测**；本轮按其余四个模块的做法补上配置。
 */
class PerfTest {

    private val seen = mutableListOf<Pair<String, Long>>()

    @Before
    fun setUp() {
        Perf.enabled = true
        seen.clear()
        Perf.sink = { name, elapsed -> seen += name to elapsed }
    }

    @After
    fun tearDown() {
        Perf.sink = null
        Perf.enabled = true
    }

    @Test
    fun `track 返回块的值并记录非负耗时`() {
        val out = Perf.track("test-return") { 41 + 1 }

        assertEquals(42, out)
        assertEquals(1, seen.size)
        assertEquals("test-return", seen.first().first)
        assertTrue("耗时不能是负数：${seen.first().second}", seen.first().second >= 0)
    }

    @Test
    fun `耗时下限跟真实睡眠一致说明量的不是零`() {
        Perf.track("test-sleep") { Thread.sleep(30) }

        assertTrue("睡了 30ms 却记成 ${seen.single().second}ms", seen.single().second >= 20)
    }

    @Test
    fun `反复测量都不出现负值或荒谬大值`() {
        // 说明：单测里没法真的拨动挂钟，所以这条**不是**对「回拨场景」的复现；
        // 它钉的是改动后的不变量（耗时恒为非负、量级合理）。
        // 真正的证据是计时源已从 currentTimeMillis 换成 nanoTime（见 Perf.kt 注释）。
        repeat(20) {
            val elapsed = kotlin.runCatching {
                Perf.track("clock-$it") { Thread.sleep(1) }
                seen.last().second
            }.getOrThrow()
            assertTrue("第 $it 次耗时为 $elapsed", elapsed in 0..60_000)
        }
    }

    @Test
    fun `关闭开关时块照常执行但不记录`() {
        Perf.enabled = false

        val out = Perf.track("test-off") { "仍然要跑" }

        assertEquals("仍然要跑", out)
        assertTrue("关掉后不该再发埋点：$seen", seen.isEmpty())
    }

    @Test
    fun `sink 抛异常不能拖垮埋点调用方`() {
        Perf.sink = { _, _ -> error("订阅方炸了") }

        val out = Perf.track("test-sink-throws") { 7 }

        assertEquals(7, out)
    }

    @Test
    fun `最近记录是有界的环形快照`() {
        val before = Perf.snapshotRecent().size
        repeat(120) { Perf.log("burst-$it", 1L) }
        val after = Perf.snapshotRecent()

        assertTrue("快照应被压在 50 以内，实际 ${after.size}（改动前 $before）", after.size <= 50)
        assertEquals("burst-119", after.last().name)
        assertTrue("最早那批应当已被挤掉", after.none { it.name == "burst-0" })
        assertNotNull("每条记录都要带发生时刻", after.last().timestamp)
        assertTrue(
            "时间戳用挂钟（表达什么时候发生），与耗时用单调钟是两件事",
            kotlin.math.abs(System.currentTimeMillis() - after.last().timestamp) < 60_000,
        )
    }

    @Test
    fun `分段计时器的无分段路径也发埋点`() {
        val timer = Perf.start("test-timer")
        Thread.sleep(10)

        timer.end()

        assertEquals(1, seen.size)
        assertEquals("test-timer", seen.single().first)
        assertTrue("分段计时器也要量到真实时间：${seen.single().second}", seen.single().second >= 5)
    }

    @Test
    fun `未开启时 start 与 annotate 不产生任何记录`() {
        Perf.enabled = false

        val timer = Perf.start("test-timer-off")
        timer.annotate("firstToken", 312)
        timer.split("embedding")
        timer.end()

        assertTrue("关掉后不该发埋点：$seen", seen.isEmpty())
        assertTrue(
            "关掉后不该进最近记录",
            Perf.snapshotRecent().none { it.name == "test-timer-off" },
        )
    }
}
