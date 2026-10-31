package io.zer0.muse.miniphone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 主动私信 Worker 的接线契约:周期、初始延迟、失败路径。
 *
 * 为什么读源码而不是真排期:WorkManager 的周期任务只在设备上落库,单元测试里既排不上,
 * 也验不出「周期改回 15 分钟」或「失败路径写成了 retry」这类事故 —— 而这正是要防的:
 * 前者会变成一天八条骚扰,后者会在一次网络失败后 30 秒内再打一次。
 *
 * 读源码用 `Files.newBufferedReader`(仓库既有 wiring 测试的写法)。
 * 多个候选路径是因为测试工作目录可能是模块目录,也可能是仓库根。
 */
class MpProactiveChatWorkerWiringTest {

    @Test
    fun `worker never retries and always reports success`() {
        val source = read(WORKER)

        assertTrue("找不到 MpProactiveChatWorker 类", source.contains("class MpProactiveChatWorker("))
        assertTrue("取不到 runner 时必须返回 success", source.contains("if (runner == null) return Result.success()"))
        assertTrue("正常跑完也必须返回 success", source.contains("return Result.success()"))
        assertFalse("主动私信不该返回 retry", source.contains("Result.retry"))
        // 两条路径(koin 缺失 / 跑完)都要返回 success
        assertEquals(2, source.split("Result.success()").size - 1)
    }

    @Test
    fun `periodic work repeats every three hours after a ninety minute delay`() {
        val source = read(SCHEDULER)

        assertTrue("周期常量必须是 3 小时", source.contains("REPEAT_INTERVAL_HOURS: Long = 3L"))
        assertTrue("初始延迟常量必须是 90 分钟", source.contains("INITIAL_DELAY_MINUTES: Long = 90L"))
        assertTrue(
            "周期任务必须按 3 小时排期",
            source.contains("PeriodicWorkRequestBuilder<MpProactiveChatWorker>(REPEAT_INTERVAL_HOURS, TimeUnit.HOURS)"),
        )
        assertTrue(
            "首次排期必须往后挪 90 分钟,与朋友圈任务错开",
            source.contains("setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)"),
        )
        assertTrue("唯一任务名必须存在,否则会排出一堆重复实例", source.contains("UNIQUE_WORK_NAME"))
    }

    @Test
    fun `module scheduler entry registers proactive chat work`() {
        val source = read(MOMENT_SCHEDULER)

        assertTrue("模块后台注册口必须把主动私信一并排期", source.contains("MiniPhoneProactiveChatScheduler.start(context)"))
        assertTrue("取消入口必须一并取消", source.contains("MiniPhoneProactiveChatScheduler.stop(context)"))
    }

    private fun read(candidates: List<Path>): String {
        return candidates
            .firstOrNull(Files::exists)
            ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
            ?: error("source not found: $candidates")
    }

    private companion object {
        val WORKER: List<Path> = listOf(
            Path.of("src/main/java/io/zer0/muse/miniphone/work/MpProactiveChatWorker.kt"),
            Path.of("miniphone/src/main/java/io/zer0/muse/miniphone/work/MpProactiveChatWorker.kt"),
            Path.of("../miniphone/src/main/java/io/zer0/muse/miniphone/work/MpProactiveChatWorker.kt"),
        )

        val SCHEDULER: List<Path> = listOf(
            Path.of("src/main/java/io/zer0/muse/miniphone/work/MiniPhoneProactiveChatScheduler.kt"),
            Path.of("miniphone/src/main/java/io/zer0/muse/miniphone/work/MiniPhoneProactiveChatScheduler.kt"),
            Path.of("../miniphone/src/main/java/io/zer0/muse/miniphone/work/MiniPhoneProactiveChatScheduler.kt"),
        )

        val MOMENT_SCHEDULER: List<Path> = listOf(
            Path.of("src/main/java/io/zer0/muse/miniphone/work/MiniPhoneMomentScheduler.kt"),
            Path.of("miniphone/src/main/java/io/zer0/muse/miniphone/work/MiniPhoneMomentScheduler.kt"),
            Path.of("../miniphone/src/main/java/io/zer0/muse/miniphone/work/MiniPhoneMomentScheduler.kt"),
        )
    }
}
