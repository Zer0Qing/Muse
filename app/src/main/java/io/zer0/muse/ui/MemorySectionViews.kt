package io.zer0.muse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge
import java.time.LocalDate

/**
 * 记忆系统各段查看 + 编辑 UI。
 *
 * 三层记忆的统一展示组件:
 *  - [MemorySectionView]:today / longterm 单段查看 + 编辑。
 *  - [MemoryWeekView]:week 段按日拆分(`## YYYY-MM-DD` 装配格式),每天独立编辑。
 *  - [MemoryHealthCard]:记忆系统各步骤健康状态(绿/红圆点 + 错误详情)。
 *
 * 设计令牌:[MuseShapes.large] 圆角、[MusePaddings.cardInner] 内边距、
 * [MusePaddings.itemGap] 条目间距。颜色统一取自 [MaterialTheme.colorScheme]。
 */

/**
 * 健康状态信息(本地图层,避免直接依赖 MemoryViewModel 造成循环依赖)。
 *
 * 字段与 `MemoryTicker.StepHealth` 一一对应,由调用方转换传入。
 *
 * @param lastSuccessAt 最近一次成功时间(ISO 8601),null 表示从未成功
 * @param lastErrorAt   最近一次失败时间(ISO 8601),null 表示无失败
 * @param lastErrorMsg  最近一次失败错误信息,null 表示无错误
 * @param failCount     累计失败次数,0 表示正常
 */
data class StepHealthInfo(
    val lastSuccessAt: String? = null,
    val lastErrorAt: String? = null,
    val lastErrorMsg: String? = null,
    val failCount: Int = 0,
)

/**
 * today / longterm 段通用查看 + 编辑组件。
 *
 * 默认展示模式:用 Text 展示 content(支持多行),content 为空显示"暂无内容"占位符。
 * 点击右上角编辑按钮切换到编辑模式:OutlinedTextField(多行)+ 底部"保存"/"取消"按钮。
 * 保存调用 [onEdit](newContent),取消恢复原内容。编辑模式下 TextField 自动获取焦点。
 *
 * @param title   段标题(如"当下记忆"/"长期记忆")
 * @param content 段内容(markdown 友好,多行)
 * @param onEdit  保存回调,参数为新内容
 * @param modifier 修饰符
 */
@Composable
fun MemorySectionView(
    title: String,
    content: String,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isEditing by remember { mutableStateOf(false) }
    var draft by remember(content) { mutableStateOf(content) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
            // 标题行 + 编辑按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!isEditing) {
                    IconButton(onClick = {
                        draft = content
                        isEditing = true
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.memory_screen_edit_cd),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Spacer(Modifier.size(MusePaddings.contentGap))

            if (isEditing) {
                // 编辑模式:多行 OutlinedTextField,自动获取焦点
                // v1.0.74 fix: 等一帧再请求 — LazyColumn item 在 measure 阶段才组合,
                // 立即 requestFocus 会因 modifier 未 attach 崩溃(华为 Android 10 实测)
                val focusRequester = remember { FocusRequester() }
                io.zer0.muse.ui.common.focus.SafeAutoFocusEffect(focusRequester)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                        .focusRequester(focusRequester),
                    placeholder = { Text(stringResource(R.string.memory_screen_input_hint)) },
                    shape = MuseShapes.semiLarge,
                )
                Spacer(Modifier.size(MusePaddings.contentGap))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = {
                        draft = content
                        isEditing = false
                    }) { Text(stringResource(R.string.memory_screen_cancel)) }
                    Spacer(Modifier.size(MusePaddings.contentGap))
                    FilledTonalButton(onClick = {
                        onEdit(draft)
                        isEditing = false
                    }) { Text(stringResource(R.string.memory_screen_save)) }
                }
            } else {
                // 展示模式:多行 Text,空内容显示占位符
                if (content.isBlank()) {
                    Text(
                        text = stringResource(R.string.memory_screen_empty_content),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * week 段查看 + 编辑组件(按日拆分)。
 *
 * content 格式:多段 markdown,每段以 `## YYYY-MM-DD` 开头(daily 文件装配格式)。
 * 解析后按天渲染为独立 Card,每天有独立编辑按钮,编辑时切换为 OutlinedTextField。
 *
 * 回退路径:若 content 不含 `## ` 标题(压缩摘要),直接当单段只读展示。
 * 空 content 显示"暂无短期记忆"占位符。
 *
 * @param content   week 段原始内容
 * @param onEditDay 单日编辑回调,参数为 (date, newContent),date 为 `## ` 后的标题文本
 * @param modifier  修饰符
 */
@Composable
fun MemoryWeekView(
    content: String,
    onEditDay: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 空 content:占位符
    if (content.isBlank()) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = MuseShapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Text(
                text = stringResource(R.string.memory_screen_empty_short_term),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(MusePaddings.cardInner),
            )
        }
        return
    }

    // 解析按天拆分:`## ` 开头的行作为日期标题,后续到下一个 `## ` 之间为当天正文
    val daySections = remember(content) { parseWeekSections(content) }

    if (daySections.isNullOrEmpty()) {
        // 回退:不含 `## ` 标题的压缩摘要,直接当单段只读展示
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = MuseShapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(MusePaddings.cardInner),
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap)) {
        daySections.forEach { section ->
            WeekDayCard(
                date = section.date,
                body = section.body,
                onEditDay = onEditDay,
            )
        }
    }
}

/** 单日数据(date 为 `## ` 后的标题,body 为当天正文)。 */
private data class WeekDaySection(val date: String, val body: String)

/**
 * 解析 week 段 content 为按天分节列表。
 *
 * 以 `(?m)^## (.+)$` 匹配每个日期标题行,取该行之后到下一个标题行之前的内容为正文。
 * 若匹配数为 0 返回 null(调用方走回退路径)。
 */
private fun parseWeekSections(content: String): List<WeekDaySection>? {
    val regex = Regex("(?m)^## (.+)$")
    val matches = regex.findAll(content).toList()
    if (matches.isEmpty()) return null
    return matches.mapIndexed { index, match ->
        val date = match.groupValues[1].trim()
        val start = match.range.last + 1
        val end = if (index + 1 < matches.size) matches[index + 1].range.first else content.length
        val body = if (start <= end) content.substring(start, end).trim() else ""
        WeekDaySection(date = date, body = body)
    }
}

/**
 * 格式化日期标题:尝试用 ISO_LOCAL_DATE 解析后重新格式化为更友好的 `M-d` 形式,
 * 解析失败则原样返回。
 */
private fun formatDayTitle(date: String): String {
    return try {
        val parsed = LocalDate.parse(date)
        "${parsed.monthValue}-${parsed.dayOfMonth}"
    } catch (_: Exception) {
        date
    }
}

/**
 * week 单日卡片:日期标题 + 正文 + 独立编辑按钮。
 */
@Composable
private fun WeekDayCard(
    date: String,
    body: String,
    onEditDay: (String, String) -> Unit,
) {
    var isEditing by remember { mutableStateOf(false) }
    var draft by remember(body) { mutableStateOf(body) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatDayTitle(date),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!isEditing) {
                    IconButton(onClick = {
                        draft = body
                        isEditing = true
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.memory_screen_edit_cd),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Spacer(Modifier.size(MusePaddings.contentGap))

            if (isEditing) {
                // v1.0.74 fix: 等一帧再请求焦点(同上,防 LazyColumn 时序崩溃)
                val focusRequester = remember { FocusRequester() }
                io.zer0.muse.ui.common.focus.SafeAutoFocusEffect(focusRequester)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp)
                        .focusRequester(focusRequester),
                    placeholder = { Text(stringResource(R.string.memory_screen_input_day_hint)) },
                    shape = MuseShapes.semiLarge,
                )
                Spacer(Modifier.size(MusePaddings.contentGap))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = {
                        draft = body
                        isEditing = false
                    }) { Text(stringResource(R.string.memory_screen_cancel)) }
                    Spacer(Modifier.size(MusePaddings.contentGap))
                    FilledTonalButton(onClick = {
                        onEditDay(date, draft)
                        isEditing = false
                    }) { Text(stringResource(R.string.memory_screen_save)) }
                }
            } else {
                if (body.isBlank()) {
                    Text(
                        text = stringResource(R.string.memory_screen_empty_content),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 记忆系统健康状态卡片。
 *
 * 展示各步骤(rollingSummary/compileDaily/compileToday/rollDailyWindow/compileFacts/deepMemory)
 * 的健康状态:绿色圆点表示正常(failCount == 0),红色圆点表示有失败。
 * 失败的步骤展开显示错误信息与失败次数。
 *
 * 用 [StepHealthInfo] 本地数据类承接,避免直接依赖 MemoryViewModel 造成循环依赖。
 * 调用方负责将 `MemoryTicker.StepHealth` 转为 [StepHealthInfo] 传入。
 *
 * @param healthMap 步骤名 → 健康信息
 * @param modifier  修饰符
 */
@Composable
fun MemoryHealthCard(
    healthMap: Map<String, StepHealthInfo>,
    modifier: Modifier = Modifier,
) {
    // v1.0.51: 默认收起,避免占位过多。标题行可点击切换展开/收起。
    var expanded by rememberSaveable { mutableStateOf(false) }
    val hasAnyError = healthMap.values.any { it.failCount > 0 }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
            // 标题行(可点击折叠/展开)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 状态指示圆点:有错误显红色,否则显绿色(收起时也能一眼看出状态)
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = if (hasAnyError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                            ),
                    )
                    Spacer(Modifier.size(MusePaddings.contentGap))
                    Text(
                        text = stringResource(R.string.memory_screen_system_status),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(modifier = Modifier.padding(top = MusePaddings.contentGap)) {
                    if (healthMap.isEmpty()) {
                        Text(
                            text = stringResource(R.string.memory_screen_no_status_data),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                            healthMap.forEach { (step, health) ->
                                HealthStepRow(stepKey = step, health = health)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个步骤健康行:圆点 + 步骤名 + 状态文案,失败时展开显示错误信息与失败次数。
 */
@Composable
private fun HealthStepRow(
    stepKey: String,
    health: StepHealthInfo,
) {
    val hasError = health.failCount > 0
    val dotColor = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 状态圆点:绿色=正常,红色=有失败
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(color = dotColor, shape = CircleShape),
                )
                Spacer(Modifier.size(MusePaddings.contentGap))
                Text(
                    text = stepKey,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = if (hasError) {
                    stringResource(R.string.memory_screen_failed_times, health.failCount)
                } else {
                    stringResource(R.string.memory_screen_normal)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            )
        }
        // 失败步骤展开显示错误信息
        if (hasError) {
            val msg = health.lastErrorMsg
            if (!msg.isNullOrBlank()) {
                Spacer(Modifier.size(MusePaddings.tightGap))
                Text(
                    text = stringResource(R.string.memory_screen_error_compact, msg),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val lastErrorAt = health.lastErrorAt
            if (!lastErrorAt.isNullOrBlank()) {
                Spacer(Modifier.size(MusePaddings.tinyGap))
                Text(
                    text = stringResource(R.string.memory_screen_recent_error, lastErrorAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            val lastSuccessAt = health.lastSuccessAt
            if (!lastSuccessAt.isNullOrBlank()) {
                Spacer(Modifier.size(MusePaddings.tinyGap))
                Text(
                    text = stringResource(R.string.memory_screen_recent_success, lastSuccessAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
