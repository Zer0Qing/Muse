package io.zer0.muse.data.agentbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 计划 V16 / V17 的两条边界。
 *
 * V16：可选闲聊不能吞掉任务与审批的额度。
 * V17：备份恢复期间不能有新任务写入。
 */
class BotQuotaAndRestoreGateTest {

    private val governor = BotResourceGovernor()

    @Test
    fun `optional chat yields when any task is running`() {
        val verdict = governor.admit(runningCount = 1, kind = BotResourceGovernor.Kind.OPTIONAL)
        assertTrue(
            "有任务在跑时，主动搭话要让路 —— 任务卡住是用户能感觉到的",
            verdict is BotResourceGovernor.Verdict.Queue,
        )
    }

    @Test
    fun `required work still runs while another task runs`() {
        val verdict = governor.admit(runningCount = 1, kind = BotResourceGovernor.Kind.REQUIRED)
        assertTrue(
            "任务不能因为另一个任务在跑就被吞掉",
            verdict is BotResourceGovernor.Verdict.Go,
        )
    }

    @Test
    fun `optional chat is allowed when nothing else runs`() {
        val verdict = governor.admit(runningCount = 0, kind = BotResourceGovernor.Kind.OPTIONAL)
        assertTrue(verdict is BotResourceGovernor.Verdict.Go)
    }

    @Test
    fun `default kind is required so existing callers keep priority`() {
        // 老调用点不传 kind 时必须按必需处理，否则会被静默降级。
        assertTrue(governor.admit(runningCount = 1) is BotResourceGovernor.Verdict.Go)
    }

    @Test
    fun `restore window blocks the scheduler`() {
        val scheduler = File("src/main/java/io/zer0/muse/data/agentbot/BotTaskScheduler.kt").readText()
        val tickAt = scheduler.indexOf("suspend fun tick()");
        assertTrue(tickAt > 0);
        val body = scheduler.substring(tickAt, minOf(tickAt + 1200, scheduler.length));
        assertTrue(
            "恢复期间库文件正在被替换，此时写库会产生在途写丢失",
            body.contains("ProcessWriteGate.restoring"),
        )
    }

    @Test
    fun `overdue sweep also respects the restore window`() {
        val scheduler = File("src/main/java/io/zer0/muse/data/agentbot/BotTaskScheduler.kt").readText()
        val sweepAt = scheduler.indexOf("suspend fun sweepOverdue");
        assertTrue(sweepAt > 0);
        val body = scheduler.substring(sweepAt, minOf(sweepAt + 800, scheduler.length));
        assertTrue(
            "巡检会读库，恢复窗口内读到的可能是即将被替换的旧库",
            body.contains("ProcessWriteGate.restoring"),
        )
    }

    @Test
    fun `governor keeps its three verdicts distinct`() {
        // 三种判定对用户是不同的话，合并成一个布尔会让"等一下"听起来像"不行"。
        assertEquals(3, BotResourceGovernor.Verdict::class.sealedSubclasses.size + 0);
    }
}
