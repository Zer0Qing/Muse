package io.zer0.muse.data.agentbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 装配缺失必须留痕。
 *
 * 中枢里多处依赖是可空的（为了纯 JVM 测试），为 null 时跳过对应动作。
 * 跳过本身没问题，**静默跳过**会把装配错误伪装成"功能莫名其妙没反应"。
 *
 * 第六十九到七十轮修了 TranscriptRestorer 的两条路径，这里补上调度器的。
 */
class AssemblyGapLoggingTest {

    private val scheduler: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/BotTaskScheduler.kt").readText()
    }

    @Test
    fun `missing applier is logged at error level`() {
        // 没有 applier 就没人审核，任务会永久停在 REVIEWING。
        val at = scheduler.indexOf("val reviewer = applier");
        assertTrue(at > 0);
        val body = scheduler.substring(at, minOf(at + 600, scheduler.length));
        assertTrue("必须记 error 级", body.contains("Logger.e("));
        assertTrue("日志要说明后果", body.contains("REVIEWING"));
        assertTrue("日志要带任务 id，否则不知道是哪个任务卡住了", body.contains("task.taskId"));
    }

    @Test
    fun `the log explains why the task will stall`() {
        val at = scheduler.indexOf("val reviewer = applier");
        val body = scheduler.substring(at, minOf(at + 600, scheduler.length));
        assertFalse(
            "不能只写 skipped 之类，要写清楚任务会停在哪",
            body.contains("skip") && !body.contains("REVIEWING"),
        );
    }
}
