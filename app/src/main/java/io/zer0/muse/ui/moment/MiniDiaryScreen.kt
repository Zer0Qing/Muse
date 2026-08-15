package io.zer0.muse.ui.moment

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch

/**
 * v1.0.74: AI 日记本 — 月视图日历 + 日记查看。
 * 数据由 LLM 基于当天动态/记忆生成,打开当天日记时懒生成并持久化。
 */
@Composable
fun MiniDiaryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 当前浏览的年月
    val today = java.time.LocalDate.now()
    // 前端修复 (持久化-5): viewYear/viewMonth/selectedDate 均为 Int/String,改 rememberSaveable,旋转不丢浏览位置
    var viewYear by rememberSaveable { mutableStateOf(today.year) }
    var viewMonth by rememberSaveable { mutableStateOf(today.monthValue) }
    var selectedDate by rememberSaveable { mutableStateOf(today.toString()) }
    var monthDiaries by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var diaryContent by remember { mutableStateOf<String?>(null) }
    var loadingDiary by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val repository: io.zer0.muse.data.diary.DiaryRepository by lazy {
        org.koin.java.KoinJavaComponent.get(io.zer0.muse.data.diary.DiaryRepository::class.java)
    }
    val generator: io.zer0.muse.data.diary.DiaryGenerator by lazy {
        org.koin.java.KoinJavaComponent.get(io.zer0.muse.data.diary.DiaryGenerator::class.java)
    }

    // 加载当月日历标记 + 选中日期日记
    fun loadDiary(date: String) {
        scope.launch {
            loadingDiary = true
            val existing = repository.getByDate(date)
            if (existing != null) {
                diaryContent = existing.content
                loadingDiary = false
            } else {
                // 无日记:LLM 生成(只对今天;过去日期无日记显示"这一天没有日记")
                if (date == today.toString()) {
                    val generated = generator.generateFor(date)
                    if (generated != null) {
                        repository.save(date, generated)
                        diaryContent = generated
                        // 刷新月标记
                        monthDiaries = repository.getByMonth(viewYear, viewMonth)
                    } else {
                        diaryContent = "日记生成失败,稍后再试"
                    }
                } else {
                    diaryContent = null
                }
                loadingDiary = false
            }
        }
    }

    fun loadMonth() {
        scope.launch {
            monthDiaries = repository.getByMonth(viewYear, viewMonth)
            loadDiary(selectedDate)
        }
    }

    LaunchedEffect(Unit) { loadMonth() }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Muse 的日记",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.screen),
        ) {
            // ── 月视图日历表 ──
            DiaryMonthCalendar(
                year = viewYear,
                month = viewMonth,
                markedDates = monthDiaries.keys,
                selectedDate = selectedDate,
                onSelectDate = { date ->
                    selectedDate = date
                    loadDiary(date)
                },
                onPrevMonth = {
                    val d = java.time.LocalDate.of(viewYear, viewMonth, 1).minusMonths(1)
                    viewYear = d.year
                    viewMonth = d.monthValue
                    // v1.0.74 fix (前端审计 1.2): 翻月重置选中日期为当月 1 日,
                    // 否则 selectedDate 停留在旧月份,详情区"新月份标题 + 旧月份内容"错位。
                    selectedDate = d.toString()
                    loadMonth()
                },
                onNextMonth = {
                    val d = java.time.LocalDate.of(viewYear, viewMonth, 1).plusMonths(1)
                    viewYear = d.year
                    viewMonth = d.monthValue
                    // v1.0.74 fix (前端审计 1.2): 同上,重置选中日期
                    selectedDate = d.toString()
                    loadMonth()
                },
            )

            Spacer(Modifier.height(16.dp))

            // ── 选中日期日记 ──
            when {
                loadingDiary -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                diaryContent != null -> {
                    io.zer0.muse.ui.common.surface.MuseIsland(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundAlpha = 1f,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(
                                text = "$viewYear 年 $viewMonth 月 ${selectedDate.substring(8)} 日",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = diaryContent ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                else -> {
                    io.zer0.muse.ui.common.surface.MuseIsland(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundAlpha = 1f,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = if (selectedDate == today.toString()) "今天还没有日记" else "这一天没有日记",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            if (selectedDate == today.toString()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "打开日记页会自动生成今天的日记",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 月视图日历表(周日开头,与微信一致)。 */
@Composable
private fun DiaryMonthCalendar(
    year: Int,
    month: Int,
    markedDates: Set<String>,
    selectedDate: String,
    onSelectDate: (String) -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    val firstDay = java.time.LocalDate.of(year, month, 1)
    val daysInMonth = firstDay.lengthOfMonth()
    // 周日=0...周六=6(ISO 周一=1,转周日开头)
    val firstWeekday = (firstDay.dayOfWeek.value % 7)
    val todayStr = java.time.LocalDate.now().toString()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(12.dp),
    ) {
        // 月份切换
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevMonth, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "上个月",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = "$year 年 $month 月",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = onNextMonth, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "下个月",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 周几表头
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEach { wd ->
                Text(
                    text = wd,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // 日期格子(7 列)
        val cells = (0 until firstWeekday + daysInMonth).toList()
        val weeks = cells.chunked(7)
        weeks.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                (0 until 7).forEach { col ->
                    val idx = week.getOrNull(col)
                    if (idx != null && idx >= firstWeekday) {
                        val day = idx - firstWeekday + 1
                        val date = java.time.LocalDate.of(year, month, day).toString()
                        val hasDiary = markedDates.contains(date)
                        val isSelected = date == selectedDate
                        val isToday = date == todayStr
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                        hasDiary -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                        else -> androidx.compose.ui.graphics.Color.Transparent
                                    },
                                )
                                .clickable { onSelectDate(date) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "$day",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.onPrimary
                                        isToday -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                ),
                            )
                            // 有日记的小圆点(选中/今天除外)
                            if (hasDiary && !isSelected && !isToday) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 2.dp)
                                        .size(4.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
