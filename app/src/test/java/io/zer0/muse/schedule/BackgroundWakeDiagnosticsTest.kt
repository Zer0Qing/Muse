package io.zer0.muse.schedule

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台唤醒诊断的纯逻辑测试:状态枚举映射、条目组装、快照拼装。
 *
 * 这些函数都不碰 WorkManager 实例,所以能在纯 JVM 里跑(不依赖 Robolectric)。
 * `WorkInfo.State` 本身只是枚举,直接取它的名字即可把「映射表」钉在真实枚举上。
 */
class BackgroundWakeDiagnosticsTest {

    private fun snapshot(
        state: WorkInfo.State,
        nextRunAtMillis: Long? = null,
        runAttemptCount: Int = 0,
        stopReason: WakeStopReason = WakeStopReason.NOT_STOPPED,
        constrained: Boolean = false,
    ) = WakeWorkSnapshot(
        uuid = "uuid-${state.name}",
        stateName = state.name,
        nextRunAtMillis = nextRunAtMillis,
        runAttemptCount = runAttemptCount,
        stopReason = stopReason,
        constrained = constrained,
    )

    @Test
    fun `every WorkInfo State maps to a distinct wake state`() {
        val expected = mapOf(
            WorkInfo.State.ENQUEUED to WakeState.ENQUEUED,
            WorkInfo.State.RUNNING to WakeState.RUNNING,
            WorkInfo.State.BLOCKED to WakeState.BLOCKED,
            WorkInfo.State.SUCCEEDED to WakeState.SUCCEEDED,
            WorkInfo.State.FAILED to WakeState.FAILED,
            WorkInfo.State.CANCELLED to WakeState.CANCELLED,
        )
        // WorkInfo.State 的每个值都必须有映射,新增系统状态时这条会先失败
        assertEquals(WorkInfo.State.values().toSet(), expected.keys)
        expected.forEach { (systemState, wakeState) ->
            assertEquals(
                "系统状态 ${systemState.name} 的映射",
                wakeState,
                BackgroundWakeDiagnostics.mapWakeState(systemState.name),
            )
        }
    }

    @Test
    fun `unknown and missing state names fall back instead of throwing`() {
        // null = 这条任务在系统里查不到(未注册),与「查到了但不认识」区分开
        assertEquals(WakeState.NOT_REGISTERED, BackgroundWakeDiagnostics.mapWakeState(null))
        assertEquals(WakeState.UNKNOWN, BackgroundWakeDiagnostics.mapWakeState("SOMETHING_NEW"))
        assertEquals(WakeState.UNKNOWN, BackgroundWakeDiagnostics.mapWakeState("  "))
        // 大小写不敏感:系统侧序列化形态变化时不至于整片掉到 UNKNOWN
        assertEquals(WakeState.ENQUEUED, BackgroundWakeDiagnostics.mapWakeState("enqueued"))
    }

    @Test
    fun `empty work info list produces a not-registered entry instead of crashing`() {
        val entries = BackgroundWakeDiagnostics.toEntries(
            WakeWorkKind.SCHEDULED_TASK,
            mapOf(ScheduledTaskWorker.UNIQUE_WORK_NAME to emptyList()),
        )
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals(WakeState.NOT_REGISTERED, entry.state)
        assertEquals(ScheduledTaskWorker.UNIQUE_WORK_NAME, entry.uniqueName)
        assertNull(entry.nextRunAtMillis)
        assertEquals(0, entry.historyCount)
    }

    @Test
    fun `failed query is reported as unknown rather than not-registered`() {
        val entries = BackgroundWakeDiagnostics.toEntries(
            WakeWorkKind.PROACTIVE,
            mapOf(io.zer0.muse.proactive.ProactiveWorker.UNIQUE_WORK_NAME to null),
        )
        assertEquals(WakeState.UNKNOWN, entries.single().state)
    }

    @Test
    fun `no candidate at all yields no entry`() {
        assertTrue(BackgroundWakeDiagnostics.toEntries(WakeWorkKind.PROACTIVE, emptyMap()).isEmpty())
    }

    @Test
    fun `current entry prefers live state over finished history`() {
        val snapshots = listOf(
            snapshot(WorkInfo.State.SUCCEEDED),
            snapshot(WorkInfo.State.ENQUEUED, nextRunAtMillis = 1_700_000_000_000L),
        )
        val entry = BackgroundWakeDiagnostics.toEntries(
            WakeWorkKind.SCHEDULED_TASK,
            mapOf(ScheduledTaskWorker.UNIQUE_WORK_NAME to snapshots),
        ).single()
        assertEquals(WakeState.ENQUEUED, entry.state)
        assertEquals(1_700_000_000_000L, entry.nextRunAtMillis)
        // 历史条数保留下来,用户才知道这个 unique work 被重建过几次
        assertEquals(2, entry.historyCount)
    }

    @Test
    fun `finished-only chain reports the last finished state`() {
        // WorkManager 按入队先后返回同一条 unique work 的历史链,最后一条才是当前这条
        val snapshots = listOf(
            snapshot(WorkInfo.State.SUCCEEDED),
            snapshot(WorkInfo.State.FAILED, runAttemptCount = 3, stopReason = WakeStopReason.TIMEOUT),
        )
        val entry = BackgroundWakeDiagnostics.toEntries(
            WakeWorkKind.SCHEDULED_TASK,
            mapOf(ScheduledTaskWorker.UNIQUE_WORK_NAME to snapshots),
        ).single()
        assertEquals(WakeState.FAILED, entry.state)
        assertEquals(3, entry.runAttemptCount)
        assertEquals(WakeStopReason.TIMEOUT, entry.stopReason)
        // 终态不携带下次运行时间,避免把 Long.MAX_VALUE 之类的哨兵值当成时间戳显示
        assertNull(entry.nextRunAtMillis)
    }

    @Test
    fun `daily summary only reports slots that exist in the system queue`() {
        val entries = BackgroundWakeDiagnostics.toEntries(
            WakeWorkKind.DAILY_SUMMARY,
            mapOf(
                DailySummaryWorker.uniqueWorkName(9, 0) to listOf(snapshot(WorkInfo.State.ENQUEUED, 1L)),
                DailySummaryWorker.uniqueWorkName(21, 0) to emptyList(),
            ),
        )
        assertEquals(1, entries.size)
        assertEquals(DailySummaryWorker.uniqueWorkName(9, 0), entries.single().uniqueName)
    }

    @Test
    fun `candidate names are non-empty for every kind`() {
        WakeWorkKind.values().forEach { kind ->
            assertTrue("$kind 没有候选 unique work 名", BackgroundWakeDiagnostics.candidateWorkNames(kind).isNotEmpty())
        }
        // 每日总结的时段可配置到任意整点,候选必须是 24 个
        assertEquals(24, BackgroundWakeDiagnostics.candidateWorkNames(WakeWorkKind.DAILY_SUMMARY).size)
    }

    @Test
    fun `snapshot text keeps state and names machine readable`() {
        val report = WakeDiagnostics(
            entries = listOf(
                WakeWorkEntry(
                    kind = WakeWorkKind.SCHEDULED_TASK,
                    uniqueName = ScheduledTaskWorker.UNIQUE_WORK_NAME,
                    state = WakeState.ENQUEUED,
                    nextRunAtMillis = 1_700_000_000_000L,
                    runAttemptCount = 2,
                    stopReason = WakeStopReason.APP_STANDBY,
                    constrained = true,
                    historyCount = 1,
                ),
            ),
            backgroundWorkEnabled = true,
            batteryOptimizationExempt = false,
            exportedAtMillis = 1_700_000_000_000L,
            appVersion = "2.6.10 (2610)",
            androidVersion = "15 (API 35)",
            device = "Google Pixel 7",
        )
        val text = BackgroundWakeDiagnostics.formatSnapshot(report)
        assertTrue(text.contains("background_work_enabled=true"))
        assertTrue(text.contains("battery_optimization_exempt=false"))
        assertTrue(text.contains("work_count=1"))
        assertTrue(text.contains("${ScheduledTaskWorker.UNIQUE_WORK_NAME} | kind=SCHEDULED_TASK | state=ENQUEUED"))
        assertTrue(text.contains("attempts=2"))
        assertTrue(text.contains("stop_reason=APP_STANDBY"))
    }

    @Test
    fun `snapshot marks a missing next run as none instead of a sentinel`() {
        // 终态/未注册的任务没有下次运行时间。快照里绝不能出现 0 或 Long.MAX_VALUE:
        // 阅读快照的人会把它们当成真的时间戳。
        val report = WakeDiagnostics(
            entries = listOf(
                WakeWorkEntry(
                    kind = WakeWorkKind.SCHEDULED_TASK,
                    uniqueName = ScheduledTaskWorker.UNIQUE_WORK_NAME,
                    state = WakeState.NOT_REGISTERED,
                    nextRunAtMillis = null,
                    runAttemptCount = 0,
                    stopReason = WakeStopReason.NOT_STOPPED,
                    constrained = false,
                    historyCount = 0,
                ),
            ),
            backgroundWorkEnabled = true,
            batteryOptimizationExempt = true,
            exportedAtMillis = 1_700_000_000_000L,
            appVersion = "2.6.10 (2610)",
            androidVersion = "15 (API 35)",
            device = "Google Pixel 7",
        )
        val text = BackgroundWakeDiagnostics.formatSnapshot(report)
        assertTrue("缺值必须写成 next_run=none", text.contains("next_run=none"))
        assertFalse("不能把 Long.MAX_VALUE 当成时间戳输出", text.contains(Long.MAX_VALUE.toString()))
        assertFalse("不能把 0 当成时间戳输出", text.contains("next_run=0\n"))
    }

    @Test
    fun `snapshot text of an empty report still renders`() {
        val report = WakeDiagnostics(
            entries = emptyList(),
            backgroundWorkEnabled = null,
            batteryOptimizationExempt = true,
            exportedAtMillis = 0L,
            appVersion = "0 (0)",
            androidVersion = "0 (API 0)",
            device = "unknown",
            error = "WorkManager not initialized",
        )
        val text = BackgroundWakeDiagnostics.formatSnapshot(report)
        assertTrue(text.contains("work_count=0"))
        assertTrue(text.contains("background_work_enabled=unknown"))
        assertTrue(text.contains("work_manager_error=WorkManager not initialized"))
    }
}
