package io.zer0.muse.diagnostics

import io.zer0.memory.fact.FactAuditReport
import io.zer0.muse.data.session.LastMigrationRun
import io.zer0.muse.schedule.WakeDiagnostics
import io.zer0.muse.schedule.WakeState
import io.zer0.muse.schedule.WakeStopReason
import io.zer0.muse.schedule.WakeWorkEntry
import io.zer0.muse.schedule.WakeWorkKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.23(S4): 导出 JSON 的脱敏契约。
 *
 * 这份测试是**安全边界**,不是展示测试:导出物是给开发者看的,会被粘贴到聊天里。
 * 锁两件事 ——
 *  1. 顶层键白名单固定,新增字段必须显式加白(忘了加会被测出来,而不是静默外流);
 *  2. 任何值里不许出现凭据/路径/消息内容的字段名(以键名为代理;值级内容
 *     由各采集器"只出数字与枚举"的构造约束保证)。
 */
class DiagnosticsExportTest {

    private fun sample() = DiagnosticsSnapshot(
        collectedAt = "2026-11-06T00:00:00Z",
        app = DiagnosticsSnapshot.AppSection("2.6.23", 286, 124, 20, 5),
        storage = DiagnosticsSnapshot.StorageSection(
            databases = listOf(
                DatabaseFootprintEntry("muse.db", 1_000_000, 200_000, 32_000, true),
                DatabaseFootprintEntry("facts_default.db", 500_000, 0, 0, true),
                DatabaseFootprintEntry("mystery.db", 1, 0, 0, false),
            ),
            logBytes = 1_048_576,
            totalBytes = 1_732_000,
        ),
        mainThread = DiagnosticsSnapshot.MainThreadSection(3, 1, 0, 700L, 299),
        webHost = DiagnosticsSnapshot.WebHostSection(120, 41L, true),
        background = WakeDiagnostics(
            entries = listOf(
                WakeWorkEntry(
                    kind = WakeWorkKind.PROACTIVE,
                    uniqueName = "proactive_cycle",
                    state = WakeState.ENQUEUED,
                    nextRunAtMillis = null,
                    runAttemptCount = 0,
                    stopReason = WakeStopReason.NOT_STOPPED,
                    constrained = false,
                    historyCount = 2,
                ),
            ),
            backgroundWorkEnabled = true,
            batteryOptimizationExempt = false,
            exportedAtMillis = 0L,
            appVersion = "",
            androidVersion = "",
            device = "",
        ),
        memory = FactAuditReport(
            databasesChecked = 3,
            totalFacts = 128,
            withoutEvidence = 17,
            duplicateEntities = 2,
            danglingSupersedes = 1,
            danglingMergedInto = 0,
            pendingConflicts = 4,
            expiredNotSwept = 9,
        ),
        migration = LastMigrationRun(
            fromVersion = 96,
            toVersion = 124,
            steps = 28,
            totalMillis = 3_200L,
            slowestStepMillis = 900L,
            slowestFromVersion = 112,
            failedAtVersion = null,
        ),
    )

    @Test
    fun `导出顶层键固定为白名单`() {
        val json = sample().sanitizedJson()
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(json)
            .let { it as kotlinx.serialization.json.JsonObject }
        assertEqualsKeys(
            setOf("collectedAt", "app", "databases", "logBytes", "jank", "web", "background", "memory", "migration"),
            parsed.keys.toSet(),
        )
    }

    @Test
    fun `记忆审计只以计数出现在导出里`() {
        // M4:审计是内部设施。导出里必须给得出「有几个库、多少条没依据」,
        // 但绝不该出现任何事实文本 —— 这份 JSON 是被粘贴给人看的。
        val json = sample().sanitizedJson()
        for (field in listOf("databasesChecked", "totalFacts", "withoutEvidence", "duplicateEntities")) {
            assertTrue("导出缺审计字段 $field\n$json", json.contains("\"$field\""))
        }
        assertFalse("审计不该带出事实原文\n$json", json.contains("手冲"))
    }

    @Test
    fun `导出不含凭据路径与消息内容字段`() {
        val json = sample().sanitizedJson()
        for (forbidden in listOf("password", "pin", "jwt", "token", "secret", "/data/", "content", "message")) {
            assertFalse("导出出现敏感字段名: $forbidden\n$json", json.contains(forbidden))
        }
        // 数据库名允许(不含用户内容),但必须逐条带 known 标记
        assertTrue(json.contains("mystery.db"))
        assertTrue(json.contains("\"known\":false"))
    }

    private fun assertEqualsKeys(expected: Set<String>, actual: Set<String>) {
        assertTrue("顶层键不符: 期望 $expected 实际 $actual", expected == actual)
    }

    /**
     * S3:迁移结果的导出。**没记录 ≠ 0 毫秒**,这两件事必须能被区分开。
     */
    @Test
    fun `迁移结果缺席与失败都能被读出`() {
        val failed = sample().copy(migration = sample().migration?.copy(failedAtVersion = 118))
        val failedJson = failed.sanitizedJson()
        assertTrue("失败步骤的版本号必须导出: $failedJson", failedJson.contains("\"failedAtVersion\":118"))

        val none = sample().copy(migration = null).sanitizedJson()
        assertTrue("没有记录要显式标 recorded=false: $none", none.contains("\"recorded\":false"))
        assertFalse("不许用 0 毫秒的假记录冒充「迁移很快」: $none", none.contains("\"totalMillis\":3200"))
        assertTrue("键仍然在场: $none", none.contains("\"migration\""))
    }
}
