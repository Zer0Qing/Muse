package io.zer0.muse.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.ai.core.ProviderConfig
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.MessageDao
import io.zer0.muse.data.session.SessionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import io.zer0.muse.R

/**
 * v1.104 U8: 统计页时间范围筛选枚举。
 *
 *  - [ALL_TIME]:全部历史(默认)
 *  - [THIS_MONTH]:本月 1 日 00:00 起
 *  - [THIS_WEEK]:本周一 00:00 起
 *  - [TODAY]:今日 00:00 起
 *
 * 概览卡片(总数 / AI 回复 / 用户消息 / 平均每日 / 最活跃 / 每周趋势)响应筛选;
 * 图表卡片(模型/助手/小时/Top 会话/热力图)保持全量历史,以保证趋势完整性。
 */
enum class StatsTimeRange {
    ALL_TIME, THIS_MONTH, THIS_WEEK, TODAY
}

/**
 * v0.46: 统计页 ViewModel。
 *
 * 数据源:
 *  - [MessageDao.getAllUserMessageTimestamps]:user 消息时间戳 → 每日 user 消息数(热力图 /
 *    最活跃一天 / 连续活跃天数用,沿用旧逻辑保持热力图口径不变)。
 *  - [MessageDao.getAllMessageTimestamps]:全部消息时间戳 → 总消息数 / 本周 / 本月 /
 *    平均每日 / 每周趋势。
 *  - [MessageDao.countByRole]:按角色分组计数 → AI 回复数。
 *  - [SessionDao.count]:会话总数 → 总对话数(修复旧版 totalSessions 恒为 0 的 bug)。
 *
 * v0.47 扩展(零迁移):
 *  - [MessageDao.countByModel]:按 modelId 分组 → 模型使用占比环形图。
 *  - [MessageDao.countByAssistant]:按 assistantId 分组 → 助手使用占比横向条形图。
 *  - [MessageDao.countByHour]:按小时分组 → 24 根柱状图。
 *  - [MessageDao.topSessionsByMessageCount]:Top 10 活跃会话列表。
 *
 * 时间戳按系统时区转 LocalDate 后 groupBy 统计。
 */
class StatsViewModel(
    application: Application,
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
    private val settingsRepository: SettingsRepository,
    private val assistantRepository: AssistantRepository,
) : AndroidViewModel(application) {

    data class StatsUiState(
        val isLoading: Boolean = true,
        /** v1.104 U8: 当前时间范围筛选(默认全部历史)。 */
        val timeRange: StatsTimeRange = StatsTimeRange.ALL_TIME,
        /** 每日 user 消息数,key = LocalDate(yyyy-MM-dd),value = 消息数(热力图用)。 */
        val messagesPerDay: Map<LocalDate, Int> = emptyMap(),
        /** 总对话数(查 SessionDao.count())。 */
        val totalSessions: Int = 0,
        /** 总消息数(全部角色消息)。 */
        val totalMessages: Int = 0,
        /** AI 回复数(ASSISTANT 角色)。 */
        val totalAiMessages: Int = 0,
        /** 用户消息数(USER 角色,与热力图数据源一致)。 */
        val totalUserMessages: Int = 0,
        /** 最活跃的一天(USER 消息数最多)。 */
        val mostActiveDay: Pair<LocalDate, Int>? = null,
        /** 连续活跃天数(从今天往回数,有 user 消息的连续天数)。 */
        val streakDays: Int = 0,
        /** 平均每日消息数(总消息 / 自首条消息至今的天数,含首尾)。 */
        val avgMessagesPerDay: Double = 0.0,
        /** 本周消息数(本周一 00:00 起的全部消息)。 */
        val messagesThisWeek: Int = 0,
        /** 本月消息数(本月 1 日 00:00 起的全部消息)。 */
        val messagesThisMonth: Int = 0,
        /** 最近 7 天每日消息数(用于每周趋势柱状图, oldest → today)。 */
        val weeklyTrend: List<Pair<LocalDate, Int>> = emptyList(),
        /** v0.47: 按模型使用占比(环形图),按数量降序。 */
        val modelCounts: List<ModelUsage> = emptyList(),
        /** v0.47: 按助手使用占比(横向条形图),按数量降序。 */
        val assistantCounts: List<AssistantUsage> = emptyList(),
        /** v0.47: 按小时活跃分布(24 元素,索引 0-23 对应小时)。 */
        val hourlyDistribution: List<Int> = emptyList(),
        /** v0.47: Top 10 活跃会话(按消息数降序)。 */
        val topSessions: List<TopSessionInfo> = emptyList(),
    )

    /** v0.47: 模型使用占比单项(显示名 + 数量 + 百分比)。 */
    data class ModelUsage(
        val modelName: String,
        val count: Int,
        val percentage: Float,
    )

    /** v0.47: 助手使用占比单项(显示名 + 数量)。 */
    data class AssistantUsage(
        val assistantName: String,
        val count: Int,
    )

    /** v0.47: Top 活跃会话单项(标题 + 消息数)。 */
    data class TopSessionInfo(
        val sessionId: String,
        val title: String,
        val count: Int,
    )

    /** 从 DAO 一次性拉取的原始数据(在 IO 线程内填充)。 */
    private class RawStats(
        val userTimestamps: List<Long>,
        val assistantTimestamps: List<Long>,
        val allTimestamps: List<Long>,
        val sessionCount: Int,
    )

    /** v0.47: 扩展原始数据(4 个新统计维度的 DAO 结果)。 */
    private class RawExtendedStats(
        val modelCounts: List<io.zer0.muse.data.session.ModelCount>,
        val assistantCounts: List<io.zer0.muse.data.session.AssistantCount>,
        val hourCounts: List<io.zer0.muse.data.session.HourCount>,
        val topSessions: List<io.zer0.muse.data.session.SessionMessageCount>,
    )

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    init {
        loadStats()
    }

    /**
     * v1.104 U8: 切换时间范围筛选,触发重新加载。
     *
     * 同值不重复加载。切换时把 isLoading 置 true,避免 UI 显示旧数据。
     */
    fun setTimeRange(range: StatsTimeRange) {
        if (_state.value.timeRange == range) return
        _state.update { it.copy(timeRange = range, isLoading = true) }
        loadStats()
    }

    /** 加载统计数据(从 DAO 拉时间戳 → groupBy 日期 → 计算衍生指标)。 */
    fun loadStats() {
        viewModelScope.launch {
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now()
            val currentRange = _state.value.timeRange

            // 用一个数据类把 IO 线程内拉取的全部原始结果打包回主线程上下文
            data class IoResult(
                val raw: RawStats,
                val ext: RawExtendedStats,
                val providers: List<ProviderConfig>,
                val assistantNames: Map<String, String>,
                val sessionTitles: Map<String, String>,
            )

            val io = withContext(Dispatchers.IO) {
                val userTs = resultOf { messageDao.getAllUserMessageTimestamps() }.getOrNull() ?: emptyList()
                // v1.104 U8: 改用 timestamps 替代 countByRole 聚合,以便按 timeRange 在 ViewModel 内存过滤
                val assistantTs = resultOf { messageDao.getAllAssistantMessageTimestamps() }.getOrNull() ?: emptyList()
                val allTs = resultOf { messageDao.getAllMessageTimestamps() }.getOrNull() ?: emptyList()
                val sessions = resultOf { sessionDao.count() }.getOrNull() ?: 0
                val raw = RawStats(userTs, assistantTs, allTs, sessions)

                // v0.47: 拉取 4 个新统计维度(保持全量,不响应 timeRange — 图表卡片用)
                val modelCounts = resultOf { messageDao.countByModel() }.getOrNull() ?: emptyList()
                val assistantCounts = resultOf { messageDao.countByAssistant() }.getOrNull() ?: emptyList()
                val hourCounts = resultOf { messageDao.countByHour() }.getOrNull() ?: emptyList()
                val topSessions = resultOf { messageDao.topSessionsByMessageCount(10) }.getOrNull() ?: emptyList()
                val ext = RawExtendedStats(modelCounts, assistantCounts, hourCounts, topSessions)

                // 反查名称用:providers(含 model 列表)+ 助手名映射
                val providers = resultOf { settingsRepository.providersFlow.first() }.getOrNull() ?: emptyList()
                val assistantNames = (resultOf { assistantRepository.getAll() }
                    .getOrNull() ?: emptyList())
                    .associate { it.id to it.name }

                // 反查 Top 会话标题(sessionDao.getById 是 suspend,在 IO 内顺序调用,最多 10 次无 N+1 顾虑)
                val sessionTitles = ext.topSessions.associate { it.sessionId to (sessionDao.getById(it.sessionId)?.title ?: "") }

                IoResult(raw, ext, providers, assistantNames, sessionTitles)
            }

            val raw = io.raw

            // v1.104 U8: 根据 timeRange 计算过滤起点(毫秒),null = 全部历史
            val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val monthStart = today.withDayOfMonth(1)
            val rangeStartMs: Long? = when (currentRange) {
                StatsTimeRange.ALL_TIME -> null
                StatsTimeRange.THIS_MONTH -> monthStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
                StatsTimeRange.THIS_WEEK -> weekStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
                StatsTimeRange.TODAY -> today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            }

            // 过滤后的 timestamps(概览卡片用);null 时直接用原始列表(避免无谓 copy)
            val filteredUserTs = if (rangeStartMs == null) raw.userTimestamps else raw.userTimestamps.filter { it >= rangeStartMs }
            val filteredAssistantTs = if (rangeStartMs == null) raw.assistantTimestamps else raw.assistantTimestamps.filter { it >= rangeStartMs }
            val filteredAllTs = if (rangeStartMs == null) raw.allTimestamps else raw.allTimestamps.filter { it >= rangeStartMs }

            // user 消息按日分组(热力图用全量,保留 53 周全景;最活跃/连续活跃也基于全量)
            val userPerDay = raw.userTimestamps
                .map { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
                .groupBy { it }
                .mapValues { it.value.size }

            // 全部消息按日分组(本周/本月/每周趋势/平均每日 用,基于过滤后)
            val allPerDay = filteredAllTs
                .map { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
                .groupBy { it }
                .mapValues { it.value.size }

            val totalUser = filteredUserTs.size
            val totalAll = filteredAllTs.size
            val totalAi = filteredAssistantTs.size
            // 最活跃一天:基于全量 userPerDay,但只考虑 timeRange 范围内的日期
            val mostActive = userPerDay
                .filterKeys { rangeStartMs == null || it.atStartOfDay(zoneId).toInstant().toEpochMilli() >= rangeStartMs }
                .maxByOrNull { it.value }?.let { it.key to it.value }
            // 连续活跃天数:历史指标,保持全量(否则筛选"今天"时若今天还没消息,streak=0 反而误导)
            val streak = calculateStreak(userPerDay)

            // 本周消息数(固定窗口,不响应 timeRange — 否则筛选"今天"时本周=今天,语义重叠)
            val weekStartMs = weekStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val messagesThisWeek = raw.allTimestamps.count { it >= weekStartMs }

            // 本月消息数(固定窗口)
            val monthStartMs = monthStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val messagesThisMonth = raw.allTimestamps.count { it >= monthStartMs }

            // 最近 7 天趋势(基于过滤后的 allPerDay;若筛选"本月",趋势仍显示最近 7 天)
            val weeklyTrend = (0..6).map { offset ->
                val date = today.minusDays((6 - offset).toLong())
                date to (allPerDay[date] ?: 0)
            }

            // 平均每日:过滤后总消息 / 时间范围天数
            val avgPerDay = if (totalAll == 0) {
                0.0
            } else {
                val days = when (currentRange) {
                    StatsTimeRange.ALL_TIME -> {
                        val firstTs = filteredAllTs.min()
                        val firstDate = Instant.ofEpochMilli(firstTs).atZone(zoneId).toLocalDate()
                        ChronoUnit.DAYS.between(firstDate, today) + 1
                    }
                    StatsTimeRange.THIS_MONTH -> ChronoUnit.DAYS.between(monthStart, today) + 1
                    StatsTimeRange.THIS_WEEK -> ChronoUnit.DAYS.between(weekStart, today) + 1
                    StatsTimeRange.TODAY -> 1
                }
                if (days > 0) totalAll.toDouble() / days else 0.0
            }

            // ── v0.47: 组装 4 个新统计维度(保持全量) ──

            // 模型使用占比:反查 providers 得到 "供应商 / 模型名",反查失败显示 modelId
            val modelNameById = buildMap {
                io.providers.forEach { provider ->
                    provider.models.forEach { model ->
                        put(model.id, "${provider.displayName} / ${model.name}")
                    }
                }
            }
            val modelTotal = io.ext.modelCounts.sumOf { it.cnt }
            val modelUsages = io.ext.modelCounts
                .sortedByDescending { it.cnt }
                .map { mc ->
                    val name = modelNameById[mc.modelId]
                        ?: mc.modelId.ifBlank { getApplication<Application>().getString(R.string.stats_unknown_model) }
                    val pct = if (modelTotal > 0) mc.cnt.toFloat() / modelTotal else 0f
                    ModelUsage(modelName = name, count = mc.cnt, percentage = pct)
                }

            // 助手使用占比:反查 assistantNames,失败显示 "默认助手"
            val assistantUsages = io.ext.assistantCounts
                .sortedByDescending { it.cnt }
                .map { ac ->
                    AssistantUsage(
                        assistantName = io.assistantNames[ac.assistantId] ?: getApplication<Application>().getString(R.string.stats_default_assistant),
                        count = ac.cnt,
                    )
                }

            // 小时活跃分布:补齐 0-23 共 24 个槽位,缺失的补 0
            val hourlyArray = IntArray(24)
            io.ext.hourCounts.forEach { hc ->
                val hour = hc.hour.toIntOrNull() ?: return@forEach
                if (hour in 0..23) hourlyArray[hour] = hc.cnt
            }
            val hourlyDistribution = hourlyArray.toList()

            // Top 10 活跃会话:反查标题,失败显示 "已删除会话"
            val topSessionInfos = io.ext.topSessions.map { smc ->
                TopSessionInfo(
                    sessionId = smc.sessionId,
                    title = io.sessionTitles[smc.sessionId]?.ifBlank { null } ?: getApplication<Application>().getString(R.string.stats_deleted_session),
                    count = smc.cnt,
                )
            }

            _state.update { StatsUiState(
                isLoading = false,
                timeRange = currentRange,
                messagesPerDay = userPerDay,
                totalSessions = raw.sessionCount,
                totalMessages = totalAll,
                totalAiMessages = totalAi,
                totalUserMessages = totalUser,
                mostActiveDay = mostActive,
                streakDays = streak,
                avgMessagesPerDay = avgPerDay,
                messagesThisWeek = messagesThisWeek,
                messagesThisMonth = messagesThisMonth,
                weeklyTrend = weeklyTrend,
                modelCounts = modelUsages,
                assistantCounts = assistantUsages,
                hourlyDistribution = hourlyDistribution,
                topSessions = topSessionInfos,
            ) }
        }
    }

    /** 计算从今天往回数的连续活跃天数(今天有消息才计 1,否则直接 0)。 */
    private fun calculateStreak(perDay: Map<LocalDate, Int>): Int {
        var streak = 0
        var date = LocalDate.now()
        while (perDay.containsKey(date)) {
            streak++
            date = date.minusDays(1)
        }
        return streak
    }
}
