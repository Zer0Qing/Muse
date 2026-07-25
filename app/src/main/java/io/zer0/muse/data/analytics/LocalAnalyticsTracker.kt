package io.zer0.muse.data.analytics

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.zer0.common.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Context.analyticsDataStore: DataStore<Preferences> by preferencesDataStore(name = "muse_analytics")

/**
 * Phase 6 6E: 本地优先分析 — 追踪 DAU/MAU、对话完成率、每日聊天数等核心指标。
 *
 * 隐私: 所有数据本地存储,仅匿名聚合数据可上传。
 *
 * 追踪指标:
 * - DAU/MAU: 日活/月活
 * - 首次对话完成率: 新用户完成首次对话的比例
 * - 每日聊天数: 每天发送的消息数
 * - 记忆系统采用率: 使用记忆功能的用户比例
 * - 崩溃率: 崩溃次数/启动次数
 * - 功能使用分布: 各功能使用频次
 * - 留存: D1/D7/D30
 */
class LocalAnalyticsTracker(
    private val context: Context,
) {
    private val store get() = context.analyticsDataStore

    companion object {
        private const val TAG = "Analytics"
        // v1.131: SimpleDateFormat 改为 ThreadLocal 缓存,避免每次 todayStr/monthStr 调用都新建。
        // SimpleDateFormat 非线程安全,不能直接提为静态 val;ThreadLocal 保证每线程一份独立实例。
        // 埋点是高频路径(每条消息发送/会话切换都触发),旧实现 GC 压力明显。
        private val DAY_FMT = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
        private val MONTH_FMT = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM", Locale.getDefault()) }
        private val KEY_DAU_TODAY = intPreferencesKey("dau_today")
        private val KEY_MAU_MONTH = intPreferencesKey("mau_month")
        private val KEY_TOTAL_SESSIONS = intPreferencesKey("total_sessions")
        private val KEY_TOTAL_MESSAGES = intPreferencesKey("total_messages")
        private val KEY_FIRST_CHAT_COMPLETED = intPreferencesKey("first_chat_completed")
        private val KEY_CRASH_COUNT = intPreferencesKey("crash_count")
        private val KEY_LAUNCH_COUNT = intPreferencesKey("launch_count")
        private val KEY_LAST_ACTIVE_DATE = stringPreferencesKey("last_active_date")
        private val KEY_LAST_ACTIVE_MONTH = stringPreferencesKey("last_active_month")
        private val KEY_FIRST_LAUNCH_DATE = longPreferencesKey("first_launch_date")
        private val KEY_D1_RETENTION = intPreferencesKey("d1_retention")
        private val KEY_D7_RETENTION = intPreferencesKey("d7_retention")
        private val KEY_D30_RETENTION = intPreferencesKey("d30_retention")
        private val KEY_FEATURE_USAGE = stringPreferencesKey("feature_usage_json")
        private val KEY_MEMORY_ADOPTED = intPreferencesKey("memory_adopted")
    }

    /** 分析数据 Flow。 */
    val analyticsFlow: Flow<AnalyticsSnapshot> = store.data.map { prefs ->
        AnalyticsSnapshot(
            dauToday = prefs[KEY_DAU_TODAY] ?: 0,
            mauMonth = prefs[KEY_MAU_MONTH] ?: 0,
            totalSessions = prefs[KEY_TOTAL_SESSIONS] ?: 0,
            totalMessages = prefs[KEY_TOTAL_MESSAGES] ?: 0,
            firstChatCompleted = (prefs[KEY_FIRST_CHAT_COMPLETED] ?: 0) > 0,
            crashCount = prefs[KEY_CRASH_COUNT] ?: 0,
            launchCount = prefs[KEY_LAUNCH_COUNT] ?: 0,
            lastActiveDate = prefs[KEY_LAST_ACTIVE_DATE] ?: "",
            d1Retention = prefs[KEY_D1_RETENTION] ?: 0,
            d7Retention = prefs[KEY_D7_RETENTION] ?: 0,
            d30Retention = prefs[KEY_D30_RETENTION] ?: 0,
            memoryAdopted = (prefs[KEY_MEMORY_ADOPTED] ?: 0) > 0,
        )
    }

    /** 记录 App 启动(每日首次 + 累计启动次数)。 */
    suspend fun trackLaunch() {
        val today = todayStr()
        val month = monthStr()
        store.edit { prefs ->
            // 首次启动日期
            if (prefs[KEY_FIRST_LAUNCH_DATE] == null) {
                prefs[KEY_FIRST_LAUNCH_DATE] = System.currentTimeMillis()
            }
            // DAU: 如果今天还没记录,说明是新一天
            if (prefs[KEY_LAST_ACTIVE_DATE] != today) {
                prefs[KEY_DAU_TODAY] = 1
                prefs[KEY_LAST_ACTIVE_DATE] = today
                // 检查 D1/D7/D30 留存
                checkRetention(prefs)
            }
            // MAU: 如果本月还没记录
            if (prefs[KEY_LAST_ACTIVE_MONTH] != month) {
                prefs[KEY_MAU_MONTH] = 1
                prefs[KEY_LAST_ACTIVE_MONTH] = month
            }
            // 累计启动
            prefs[KEY_LAUNCH_COUNT] = (prefs[KEY_LAUNCH_COUNT] ?: 0) + 1
        }
    }

    /** 记录发送消息。 */
    suspend fun trackMessageSent() {
        store.edit { prefs ->
            prefs[KEY_TOTAL_MESSAGES] = (prefs[KEY_TOTAL_MESSAGES] ?: 0) + 1
        }
    }

    /** 记录创建新会话。 */
    suspend fun trackSessionCreated() {
        store.edit { prefs ->
            prefs[KEY_TOTAL_SESSIONS] = (prefs[KEY_TOTAL_SESSIONS] ?: 0) + 1
            // 首次对话完成
            if ((prefs[KEY_TOTAL_SESSIONS] ?: 0) == 1) {
                prefs[KEY_FIRST_CHAT_COMPLETED] = 1
            }
        }
    }

    /** 记录崩溃。 */
    suspend fun trackCrash() {
        store.edit { prefs ->
            prefs[KEY_CRASH_COUNT] = (prefs[KEY_CRASH_COUNT] ?: 0) + 1
        }
    }

    /** 记录记忆系统使用。 */
    suspend fun trackMemoryUsed() {
        store.edit { prefs ->
            if ((prefs[KEY_MEMORY_ADOPTED] ?: 0) == 0) {
                prefs[KEY_MEMORY_ADOPTED] = 1
            }
        }
    }

    /** 记录功能使用(按功能名计数)。 */
    suspend fun trackFeatureUsage(featureName: String) {
        store.edit { prefs ->
            val raw = prefs[KEY_FEATURE_USAGE] ?: "{}"
            val mapSerializer = MapSerializer(String.serializer(), Int.serializer())
            val usage = try {
                io.zer0.common.AppJson.decodeFromString(mapSerializer, raw).toMutableMap()
            } catch (_: Exception) {
                mutableMapOf<String, Int>()
            }
            usage[featureName] = (usage[featureName] ?: 0) + 1
            prefs[KEY_FEATURE_USAGE] = io.zer0.common.AppJson.encodeToString(mapSerializer, usage)
        }
    }

    /** 获取当前分析快照。 */
    suspend fun getSnapshot(): AnalyticsSnapshot = analyticsFlow.first()

    /**
     * P3-2: 获取功能使用计数 Map(featureName → count),按 count 降序返回。
     *
     * 用于本地数据分析面板展示 Top N,无任何上报。
     */
    suspend fun getFeatureUsage(): List<Pair<String, Int>> {
        val raw = store.data.first()[KEY_FEATURE_USAGE] ?: "{}"
        val mapSerializer = MapSerializer(String.serializer(), Int.serializer())
        val usage = try {
            io.zer0.common.AppJson.decodeFromString(mapSerializer, raw)
        } catch (_: Exception) {
            emptyMap()
        }
        return usage.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }

    /** 检查留存(D1/D7/D30)。 */
    private fun checkRetention(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        val firstLaunch = prefs[KEY_FIRST_LAUNCH_DATE] ?: return
        val daysSinceFirst = (System.currentTimeMillis() - firstLaunch) / 86_400_000L
        when {
            daysSinceFirst >= 1 && (prefs[KEY_D1_RETENTION] ?: 0) == 0 -> {
                prefs[KEY_D1_RETENTION] = 1
            }
            daysSinceFirst >= 7 && (prefs[KEY_D7_RETENTION] ?: 0) == 0 -> {
                prefs[KEY_D7_RETENTION] = 1
            }
            daysSinceFirst >= 30 && (prefs[KEY_D30_RETENTION] ?: 0) == 0 -> {
                prefs[KEY_D30_RETENTION] = 1
            }
        }
    }

    private fun todayStr(): String = DAY_FMT.get()?.format(Date()) ?: ""
    private fun monthStr(): String = MONTH_FMT.get()?.format(Date()) ?: ""
}

/** 分析数据快照。 */
@Serializable
data class AnalyticsSnapshot(
    val dauToday: Int = 0,
    val mauMonth: Int = 0,
    val totalSessions: Int = 0,
    val totalMessages: Int = 0,
    val firstChatCompleted: Boolean = false,
    val crashCount: Int = 0,
    val launchCount: Int = 0,
    val lastActiveDate: String = "",
    val d1Retention: Int = 0,
    val d7Retention: Int = 0,
    val d30Retention: Int = 0,
    val memoryAdopted: Boolean = false,
) {
    val crashRate: Float
        get() = if (launchCount > 0) crashCount.toFloat() / launchCount else 0f
}
