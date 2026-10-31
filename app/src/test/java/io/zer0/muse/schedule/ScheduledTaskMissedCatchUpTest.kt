package io.zer0.muse.schedule

import io.zer0.muse.data.schedule.ScheduledTaskDao
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.data.schedule.ScheduledTaskExecutionEntity
import io.zer0.muse.data.schedule.TaskIdName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 补跑补偿:错过的计划轮次怎么算、记在哪、只补跑几次。
 *
 * 关键约定:任务执行完后推进调度**锚定上次计划时刻**(执行前的 next_run_at),
 * 而不是从「现在」重新起算 —— 以 now 为基准的话,错过的那一轮被永久跳过、
 * 而且每次晚跑都会把下一次往后推一点,墙钟不断漂移。
 */
class ScheduledTaskMissedCatchUpTest {

    private val hour = 3_600_000L
    private val day = 86_400_000L

    // ── 锚点推进 ──────────────────────────────────────────────────────

    @Test
    fun `one missed round advances from last planned time not from now`() {
        // 每小时任务,上次计划 09:00,设备 10:30 才醒:10:00 那一轮错过了。
        val scheduledAt = 1_700_000_000_000L
        val now = scheduledAt + hour + 30 * 60_000L

        val advance = ScheduledTaskRunner.computeNextRunAnchored("hourly", "", scheduledAt, now)

        assertEquals("下一次仍是原计划的整点,不是「现在+1 小时」", scheduledAt + 2 * hour, advance.nextRunAt)
        assertTrue("从 now 起算会得到 11:30,说明没锚定", advance.nextRunAt != now + hour)
        assertEquals("10:00 那一轮被跳过,记 1 次 missed", 1, advance.missedCount)
    }

    @Test
    fun `five missed rounds run once and record four missed`() {
        // 每天任务,上次计划 T,设备 T+4 天才被拉起:T..T+4d 共 5 个计划时刻都已过去,
        // 本次只补跑最近一次(T+4d),更早的 4 轮不重跑、只记数。
        val scheduledAt = 1_700_000_000_000L
        val now = scheduledAt + 4 * day

        val advance = ScheduledTaskRunner.computeNextRunAnchored("daily", "", scheduledAt, now)

        assertEquals("只补跑最近一次:下一次是 T+5d", scheduledAt + 5 * day, advance.nextRunAt)
        assertTrue("补跑完不会立刻再次到期", advance.nextRunAt > now)
        assertEquals("其余 4 轮记为 missed", 4, advance.missedCount)
    }

    @Test
    fun `no miss keeps the same schedule as before`() {
        // 准时执行:now == 上次计划时刻,与「从现在算」的口径完全一致。
        val scheduledAt = 1_700_000_000_000L

        val advance = ScheduledTaskRunner.computeNextRunAnchored("hourly", "", scheduledAt, scheduledAt)

        assertEquals(0, advance.missedCount)
        assertEquals(
            "没错过时下一次与原有口径一致",
            ScheduledTaskRunner.computeNextRun("hourly", "", scheduledAt),
            advance.nextRunAt,
        )
    }

    @Test
    fun `poll jitter does not drift the phase`() {
        // 轮询晚 60 秒才轮到执行:不算错过,下一次仍落在原计划的整点,
        // 与「现在+周期」最多差一个轮询周期,不会一轮一轮往后漂。
        val scheduledAt = 1_700_000_000_000L
        val now = scheduledAt + 60_000L

        val advance = ScheduledTaskRunner.computeNextRunAnchored("daily", "", scheduledAt, now)

        assertEquals(0, advance.missedCount)
        assertEquals(scheduledAt + day, advance.nextRunAt)
        assertTrue(
            "与从现在算的口径差不超过一个轮询周期",
            ScheduledTaskRunner.computeNextRun("daily", "", now) - advance.nextRunAt <= 60_000L,
        )
    }

    @Test
    fun `once and unknown interval have no next run and no missed count`() {
        val scheduledAt = 1_700_000_000_000L
        val now = scheduledAt + 10 * day

        val once = ScheduledTaskRunner.computeNextRunAnchored("once", "", scheduledAt, now)
        assertEquals("once 执行完就没有下一次", 0L, once.nextRunAt)
        assertEquals(0, once.missedCount)

        val unknown = ScheduledTaskRunner.computeNextRunAnchored("every-other-day", "", scheduledAt, now)
        assertEquals(0L, unknown.nextRunAt)
        assertEquals(0, unknown.missedCount)
    }

    @Test
    fun `manual run before the due time does not shift the schedule`() {
        // 每天 09:00 的任务,下次计划是明天 09:00;用户今天 12:00 手动跑一次。
        // 下一次必须还是明天 09:00(不是明天 12:00)—— 手动多跑一次不改节奏。
        val now = 1_700_000_000_000L
        val nextPlanned = now + 21 * hour

        val advance = ScheduledTaskRunner.computeNextRunAnchored("daily", "", nextPlanned, now)

        assertEquals("手动执行不动原定计划时刻", nextPlanned, advance.nextRunAt)
        assertEquals(0, advance.missedCount)
    }

    @Test
    fun `manual run after the due time keeps the wall clock too`() {
        // 同一任务但计划时刻已过(09:00 到期,12:00 才手动点):锚定推进到明天 09:00,
        // 既不是「今天 12:00 + 1 天」,也不算漏跑(还没满一个周期)。
        val scheduledAt = 1_700_000_000_000L
        val now = scheduledAt + 3 * hour

        val advance = ScheduledTaskRunner.computeNextRunAnchored("daily", "", scheduledAt, now)

        assertEquals(scheduledAt + day, advance.nextRunAt)
        assertEquals(0, advance.missedCount)
    }

    @Test
    fun `missing anchor falls back to from-now arithmetic`() {
        // 旧数据没有计划时刻(next_run_at = 0):按首跑逻辑从现在算,不凭空记 missed。
        val now = 1_700_000_000_000L

        val advance = ScheduledTaskRunner.computeNextRunAnchored("weekly", "", 0L, now)

        assertEquals(now + 7 * day, advance.nextRunAt)
        assertEquals(0, advance.missedCount)
    }

    @Test
    fun `cron anchor counts skipped slots`() {
        // 每天 9:00 的 cron,上次计划 8/6 09:00,8/7 10:00 才跑:8/7 09:00 那一格错过。
        val yesterdayNine = LocalDateTime.of(2026, 8, 6, 9, 0).atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val todayTen = LocalDateTime.of(2026, 8, 7, 10, 0).atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val tomorrowNine = LocalDateTime.of(2026, 8, 8, 9, 0).atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()

        val advance = ScheduledTaskRunner.computeNextRunAnchored("cron", "0 9 * * *", yesterdayNine, todayTen)

        assertEquals("cron 下一次仍在墙钟整点上", tomorrowNine, advance.nextRunAt)
        assertEquals("8/7 09:00 那一格记为 missed", 1, advance.missedCount)
    }

    @Test
    fun `cron without a miss is unchanged`() {
        // cron 表达式本身已锚定墙钟,准时执行时结果与原口径一致。
        val base = LocalDateTime.of(2026, 8, 6, 9, 0).atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()

        val advance = ScheduledTaskRunner.computeNextRunAnchored("cron", "0 9 * * *", base, base)

        assertEquals(0, advance.missedCount)
        assertEquals(ScheduledTaskRunner.computeNextRun("cron", "0 9 * * *", base), advance.nextRunAt)
    }

    // ── missed 记录落库 ────────────────────────────────────────────────

    @Test
    fun `missed record is tagged with its own status`() {
        // 文案由 strings.xml 的 schedule_missed_summary 拼好(与 success/failed/skipped 同一条路),
        // 这里只要确认记录本身带 missed 状态、指向正确的任务与时间。
        val now = 1_700_000_000_000L

        val record = ScheduledTaskRunner.missedExecutionEntity("task-1", "summary-from-resource", now)

        assertEquals("missed 是独立状态,与 success/failed/skipped 并列", "missed", record.status)
        assertEquals("task-1", record.taskId)
        assertEquals(now, record.executedAt)
        assertEquals("summary-from-resource", record.replySummary)
    }

    @Test
    fun `transaction records missed row and advances schedule once`() = runBlocking {
        // 一次补跑落库 = 1 条本次执行记录 + 1 条 missed 计数记录 + next_run_at 落回锚点后的时刻。
        val scheduledAt = 1_700_000_000_000L
        val now = scheduledAt + 4 * day
        val advance = ScheduledTaskRunner.computeNextRunAnchored("daily", "", scheduledAt, now)
        val dao = FakeTaskDao(ScheduledTaskEntity(id = "task-1", name = "日报", nextRunAt = scheduledAt))

        assertTrue("领取成功", dao.claimTask("task-1", scheduledAt, now, Long.MAX_VALUE) == 1)
        dao.recordExecutionAndScheduleNext(
            execution = ScheduledTaskExecutionEntity(id = "exec-run", taskId = "task-1", executedAt = now),
            taskId = "task-1",
            nextRunAt = advance.nextRunAt,
            lastRunAt = now,
            retryCount = 0,
            missedExecutions = listOf(
                ScheduledTaskRunner.missedExecutionEntity("task-1", "missed-summary", now),
            ),
        )

        assertEquals("执行历史两条:本次执行 + missed 计数", 2, dao.executions.size)
        assertEquals("missed", dao.executions.first().status)
        assertEquals("exec-run", dao.executions.last().id)
        assertEquals("next_run_at 落到锚点后的下一次", scheduledAt + 5 * day, dao.task.nextRunAt)
        assertTrue("任务继续启用", dao.task.enabled)
        assertFalse("补跑不会让下一次立刻到期", dao.task.nextRunAt <= now)
    }

    /**
     * 只实现本测试用到的写路径的假 DAO。
     *
     * `recordExecutionAndScheduleNext` 用的是接口里的默认实现(@Transaction 的组合方法),
     * 这里检验的是那段组合逻辑:missed 记录与 next_run_at 推进一起落库。
     */
    private class FakeTaskDao(initial: ScheduledTaskEntity) : ScheduledTaskDao {
        var task: ScheduledTaskEntity = initial
        val executions = mutableListOf<ScheduledTaskExecutionEntity>()

        override fun observeAll(): Flow<List<ScheduledTaskEntity>> = flowOf(listOf(task))

        override suspend fun getDueTasks(timeMillis: Long): List<ScheduledTaskEntity> =
            listOf(task).filter { it.enabled && it.nextRunAt <= timeMillis }

        override suspend fun getById(id: String): ScheduledTaskEntity? = task.takeIf { it.id == id }

        override suspend fun upsert(task: ScheduledTaskEntity) {
            this.task = task
        }

        override suspend fun delete(id: String) {
            task = task.copy(enabled = false)
        }

        override suspend fun claimTask(id: String, expectedNextRunAt: Long, claimedAt: Long, claimNextRun: Long): Int {
            if (task.id != id || task.nextRunAt != expectedNextRunAt) return 0
            task = task.copy(nextRunAt = claimNextRun, lastRunAt = claimedAt, updatedAt = claimedAt)
            return 1
        }

        override suspend fun setEnabled(id: String, enabled: Boolean, now: Long) {
            task = task.copy(enabled = enabled, updatedAt = now)
        }

        override suspend fun updateRunState(id: String, nextRunAt: Long, lastRunAt: Long) {
            task = task.copy(nextRunAt = nextRunAt, lastRunAt = lastRunAt)
        }

        override suspend fun recoverStuckClaim(sentinel: Long, staleBefore: Long, now: Long): Int = 0

        override suspend fun updateDedicatedSessionId(id: String, sessionId: String, now: Long) {
            task = task.copy(dedicatedSessionId = sessionId)
        }

        override suspend fun insertExecution(execution: ScheduledTaskExecutionEntity) {
            executions += execution
        }

        override suspend fun getAll(): List<ScheduledTaskEntity> = listOf(task)

        override suspend fun deleteAll() {
            task = task.copy(enabled = false)
        }

        override suspend fun triggerNextTasks(ids: List<String>, now: Long) = Unit

        override suspend fun updateRetryCount(id: String, retryCount: Int) {
            task = task.copy(retryCount = retryCount)
        }

        override suspend fun listChainCandidates(excludeId: String): List<TaskIdName> = emptyList()
    }
}
