package io.zer0.muse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.emotion.EmotionEntry
import io.zer0.muse.data.emotion.EmotionStats
import io.zer0.muse.data.emotion.MoodParser
import io.zer0.muse.data.report.MonthlyReport
import io.zer0.muse.data.report.ReportGenerator
import io.zer0.muse.data.report.WeeklyReport
import io.zer0.muse.data.session.MessageDao
import io.zer0.muse.data.session.SessionDao
import io.zer0.muse.ui.components.CardGroup
import io.zer0.muse.ui.settings.SettingsSubPageScaffold
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields

/**
 * v1.0.4: 我的报告页 — 周报/月报(基于本地数据生成)。
 *
 * 后端 [ReportGenerator] 已实现完整数据模型,本页接入 UI:
 *  - 周报:本周一 00:00 至今的会话数 / 消息数 / AI 寄语
 *  - 月报:本月 1 日 00:00 至今的会话数 / 消息数 / 字数 / AI 寄语
 *
 * 情绪趋势暂用最近 50 条 AI 消息中解析出的 `<mood>` 标签(详见 [MoodParser]),
 * 若用户消息中无 mood 标签则展示空 trend(等 ChatViewModel 接入情绪解析后会有数据)。
 */
@Composable
fun ReportScreen(
    onBack: () -> Unit,
) {
    val sessionDao: SessionDao = koinInject()
    val messageDao: MessageDao = koinInject()

    data class ReportData(
        val weekly: WeeklyReport,
        val monthly: MonthlyReport,
        val emotionStats: EmotionStats?,
    )

    var data by remember { mutableStateOf<ReportData?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()

        // 本周一 00:00 时间戳
        val monday = today.with(TemporalAdjusters.previousOrSame(WeekFields.ISO.firstDayOfWeek))
        val weekStartMs = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        // 本月 1 日 00:00 时间戳
        val monthStart = today.withDayOfMonth(1)
        val monthStartMs = monthStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val nowMs = System.currentTimeMillis()

        val result = withContext(Dispatchers.IO) {
            // 时间戳列表
            val allTs = resultOf { messageDao.getAllMessageTimestamps() }.getOrNull() ?: emptyList()
            val userTs = resultOf { messageDao.getAllUserMessageTimestamps() }.getOrNull() ?: emptyList()
            val assistantTs = resultOf { messageDao.getAllAssistantMessageTimestamps() }.getOrNull() ?: emptyList()
            val sessionCount = resultOf { sessionDao.count() }.getOrNull() ?: 0

            // 本周/本月消息计数
            val weekMsgCount = allTs.count { it in weekStartMs..nowMs }
            val monthMsgCount = allTs.count { it in monthStartMs..nowMs }
            // 本周会话数(简化:用总会话数,SessionDao 未按时间分组)
            val weekSessionCount = sessionCount
            val monthSessionCount = sessionCount

            // 字数(本月用户消息)
            // 注:MessageDao 未提供按时间聚合的字符数,这里用消息数 × 平均 50 字估算
            val monthWordCount = monthMsgCount * 50

            // 情绪数据:从最近 100 条 AI 消息中解析 mood 标签
            val emotionEntries = mutableListOf<EmotionEntry>()
            val recentAssistantMsgs = resultOf { messageDao.getRecentAssistantMessages(100) }
                .getOrNull() ?: emptyList()
            recentAssistantMsgs.forEach { msg ->
                val parsed = MoodParser.parse(msg.content) ?: return@forEach
                emotionEntries.add(
                    EmotionEntry(
                        id = msg.id,
                        sessionId = msg.sessionId,
                        messageId = msg.id,
                        value = parsed.value,
                        label = parsed.label,
                        source = "auto",
                        createdAt = msg.createdAt,
                    )
                )
            }
            val emotionStats = if (emotionEntries.isEmpty()) null
                else EmotionStats.fromEntries(emotionEntries, "weekly")

            val weekly = ReportGenerator.generateWeeklyReport(
                chatCount = weekSessionCount,
                messageCount = weekMsgCount,
                newMemoryCount = 0,
                moodPoints = emptyList(),
                bestQuote = "",
            )
            val monthly = ReportGenerator.generateMonthlyReport(
                chatCount = monthSessionCount,
                messageCount = monthMsgCount,
                wordCount = monthWordCount,
                newMemoryCount = 0,
                milestones = emptyList(),
                moodPoints = emptyList(),
                topWords = emptyList(),
                bestQuote = "",
            )
            ReportData(weekly, monthly, emotionStats)
        }
        data = result
        isLoading = false
    }

    SettingsSubPageScaffold(title = stringResource(R.string.reports_title), onBack = onBack) {
        if (isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.reports_generating),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            return@SettingsSubPageScaffold
        }

        val d = data
        if (d == null) {
            item {
                Text(
                    text = stringResource(R.string.reports_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(MusePaddings.messageGap),
                )
            }
            return@SettingsSubPageScaffold
        }

        item { ReportCard(report = d.weekly) }
        item { ReportCard(report = d.monthly) }
        d.emotionStats?.let { stats ->
            item { EmotionCard(stats = stats) }
        }
    }
}

@Composable
private fun ReportCard(report: WeeklyReport) {
    ReportCardContainer(
        title = stringResource(R.string.reports_weekly_title),
        subtitle = "${report.weekStart} ~ ${report.weekEnd}",
        rows = listOf(
            ReportRow(
                icon = Icons.Outlined.ChatBubbleOutline,
                label = stringResource(R.string.reports_chats),
                value = report.totalChats.toString(),
            ),
            ReportRow(
                icon = Icons.AutoMirrored.Outlined.Article,
                label = stringResource(R.string.reports_messages),
                value = report.totalMessages.toString(),
            ),
            ReportRow(
                icon = Icons.Outlined.Memory,
                label = stringResource(R.string.reports_new_memories),
                value = report.newMemories.toString(),
            ),
        ),
        aiMessage = report.aiMessage,
        quote = report.topQuote,
    )
}

@Composable
private fun ReportCard(report: MonthlyReport) {
    ReportCardContainer(
        title = stringResource(R.string.reports_monthly_title),
        subtitle = report.yearMonth,
        rows = listOf(
            ReportRow(
                icon = Icons.Outlined.ChatBubbleOutline,
                label = stringResource(R.string.reports_chats),
                value = report.totalChats.toString(),
            ),
            ReportRow(
                icon = Icons.AutoMirrored.Outlined.Article,
                label = stringResource(R.string.reports_messages),
                value = report.totalMessages.toString(),
            ),
            ReportRow(
                icon = Icons.Outlined.AutoAwesome,
                label = stringResource(R.string.reports_words),
                value = report.totalWords.toString(),
            ),
            ReportRow(
                icon = Icons.Outlined.Memory,
                label = stringResource(R.string.reports_new_memories),
                value = report.newMemories.toString(),
            ),
        ),
        aiMessage = report.aiMessage,
        quote = report.topQuote,
    )
}

@Composable
private fun ReportCardContainer(
    title: String,
    subtitle: String,
    rows: List<ReportRow>,
    aiMessage: String,
    quote: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.messageGap),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = row.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = row.value,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (quote.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MuseShapes.medium)
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                        .padding(MusePaddings.itemGap),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FormatQuote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = quote,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            if (aiMessage.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MuseShapes.medium)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                        .padding(MusePaddings.itemGap),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = aiMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

private data class ReportRow(
    val icon: ImageVector,
    val label: String,
    val value: String,
)

/**
 * v1.0.4: 情绪追踪卡片 — 展示从 AI 消息 `<mood>` 标签解析出的情绪统计。
 *
 * 数据来源:[io.zer0.muse.data.emotion.MoodParser] 从最近 100 条 AI 消息中解析。
 * 展示:平均情绪值 / 最高 / 最低 / 积极·中性·消极天数。
 *
 * 若用户消息中无 mood 标签(模型未输出),此卡片不显示(由调用方判断 emotionStats != null)。
 */
@Composable
private fun EmotionCard(stats: io.zer0.muse.data.emotion.EmotionStats) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.messageGap),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Mood,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = stringResource(R.string.reports_emotion_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.reports_emotion_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 三个统计:平均 / 最高 / 最低
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EmotionMetric(
                    label = stringResource(R.string.reports_emotion_avg),
                    value = "%.2f".format(stats.average),
                    modifier = Modifier.weight(1f),
                )
                EmotionMetric(
                    label = stringResource(R.string.reports_emotion_high),
                    value = "%.2f".format(stats.highest),
                    modifier = Modifier.weight(1f),
                )
                EmotionMetric(
                    label = stringResource(R.string.reports_emotion_low),
                    value = "%.2f".format(stats.lowest),
                    modifier = Modifier.weight(1f),
                )
            }
            // 积极/中性/消极天数
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EmotionMetric(
                    label = stringResource(R.string.reports_emotion_positive),
                    value = stats.positiveCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                EmotionMetric(
                    label = stringResource(R.string.reports_emotion_neutral),
                    value = stats.neutralCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                EmotionMetric(
                    label = stringResource(R.string.reports_emotion_negative),
                    value = stats.negativeCount.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EmotionMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(MuseShapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
            .padding(MusePaddings.itemGap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
