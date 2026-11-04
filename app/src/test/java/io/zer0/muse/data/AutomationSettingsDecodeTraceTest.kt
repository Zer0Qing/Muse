package io.zer0.muse.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import io.zer0.common.Logger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「设过但解不动」必须留痕，「从来没设过」不许刷屏。
 *
 * 这个类原先五处解码失败里只有一处打日志，其余直接 `.getOrNull()` 把原因丢掉。
 * 而这些失败对用户意味着**不同**的事：
 *
 *  - 授权解不动 → 当成没授权，用户会看到"之前允许的自主行动又停下来问我"；
 *  - 当日账本解不动 → 计数从 1 重启，当日上限因此可能多放行几次；
 *  - 窗口配置解不动 → 回到默认时段，用户会觉得"我明明开过"；
 *  - 清理授权时解不动 → 不敢删（怕删掉新任务的确认），旧值留着等下次覆盖。
 *
 * 所以断言分两面：**降级值不变**（宁可不放行是对的），**但要说得出后果**。
 * 日志按 tag 过滤再断言 —— `Logger.sink` 是进程级全局，别的测试类遗留协程也在往里写。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationSettingsDecodeTraceTest {

    private val seen = mutableListOf<Pair<String, String>>()

    private val grantKey = stringPreferencesKey("autonomous_accessibility_grant")
    private val ledgerKey = stringPreferencesKey("autonomous_accessibility_ledger")
    private val windowKey = stringPreferencesKey("autonomous_accessibility_window")

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        seen.clear()
        Logger.sink = { level, tag, msg, _ -> seen += level to "$tag|$msg" }
        // 三个键都清干净：起点必须是「从来没设过」，不然上一条用例的残留会伪装成损坏
        runTest {
            context.museSettingsDataStore.edit { prefs ->
                prefs.remove(grantKey)
                prefs.remove(ledgerKey)
                prefs.remove(windowKey)
            }
        }
    }

    @After
    fun tearDown() {
        Logger.sink = null
        seen.clear()
        runTest {
            context.museSettingsDataStore.edit { prefs ->
                prefs.remove(grantKey)
                prefs.remove(ledgerKey)
                prefs.remove(windowKey)
            }
        }
    }

    /** 只取本模块的日志。 */
    private fun ourLogs() = seen.filter { it.second.startsWith("AutomationSettings|") }

    private suspend fun putCorrupt(key: androidx.datastore.preferences.core.Preferences.Key<String>) {
        context.museSettingsDataStore.edit { it[key] = "{ 这不是合法 JSON " }
    }

    @Test
    fun `从来没设过时不给日志`() = runTest {
        val store = AutomationSettingsStore(context)

        assertNull(store.autonomousAccessibilityGrantFlow.first())
        assertEquals(0, store.autonomousAccessibilityLedgerFlow.first().runCount)
        assertNotNull(store.autonomousAccessibilityWindowFlow.first())

        assertTrue(
            "「没设过」是正常状态，每次读设置都刷一条日志会把真信号淹掉：${ourLogs()}",
            ourLogs().isEmpty(),
        )
    }

    @Test
    fun `授权解不动时按未授权降级但要说清后果`() = runTest {
        putCorrupt(grantKey)
        val store = AutomationSettingsStore(context)

        assertNull("降级方向必须是「不放行」", store.autonomousAccessibilityGrantFlow.first())

        val log = ourLogs().singleOrNull()
        assertNotNull("授权丢了却一句日志都没有，正是这次要修的静默：$seen", log)
        assertTrue("要带上键名，便于定位是哪项设置：${log!!.second}", log.second.contains("autonomous_accessibility_grant"))
        assertTrue("要点明用户会看到什么：${log.second}", log.second.contains("未授权"))
        assertEquals("WARN", log.first)
    }

    @Test
    fun `账本解不动时按今日零次降级并说明上限会重新起算`() = runTest {
        putCorrupt(ledgerKey)
        val store = AutomationSettingsStore(context)

        assertEquals(0, store.autonomousAccessibilityLedgerFlow.first().runCount)
        val log = ourLogs().singleOrNull()
        assertNotNull(log)
        assertTrue("要说清当日上限会重新起算：${log!!.second}", log.second.contains("今日 0 次"))
    }

    @Test
    fun `计数从一起跳时留下原因日志`() = runTest {
        putCorrupt(ledgerKey)
        val store = AutomationSettingsStore(context)

        val count = store.incrementAutonomousRunCount("2026-11-04")

        assertEquals("损坏的账本会让今天从 1 重新开始", 1, count)
        assertTrue(
            "这条重置必须能被日志解释（否则当日上限为何没生效无从查起）：${ourLogs()}",
            ourLogs().any { it.second.contains("从 1 重新开始") },
        )
    }

    @Test
    fun `清理授权时解不动就不删并说明旧值留着`() = runTest {
        putCorrupt(grantKey)
        val store = AutomationSettingsStore(context)

        store.clearAutonomousAccessibilityGrant(
            io.zer0.muse.automation.core.AutonomousAccessibilityGrant(
                sessionId = "s1",
                packageName = "com.xingin.xhs",
                taskFingerprint = "task:one",
                confirmedAtMillis = 100,
            ),
        )

        // 行为保持不变：解不动 → 不敢删（避免抹掉另一条任务的确认），旧值等下次保存覆盖
        assertEquals("{ 这不是合法 JSON ", context.museSettingsDataStore.data.first()[grantKey])
        assertTrue(
            "但必须说明「本次不删除」，否则排查时会以为授权已经被清了：${ourLogs()}",
            ourLogs().any { it.second.contains("不删除") },
        )
    }
}
