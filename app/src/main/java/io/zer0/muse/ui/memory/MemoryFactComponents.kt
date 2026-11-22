package io.zer0.muse.ui.memory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.memory.fact.MemoryLegacyReset
import io.zer0.memory.space.MemorySpaceEntity
import io.zer0.memory.summary.MemoryDbArchiveRecovery
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseActionSheetRow
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.memory.MemoryGraphSettingsDialog
import io.zer0.muse.ui.memory.MemoryGraphView
import io.zer0.muse.ui.memory.MemoryGraphViewModel
import io.zer0.muse.ui.memory.MemoryTimelineFilterRow
import io.zer0.muse.ui.memory.MonthHeader
import io.zer0.muse.ui.memory.PinnedMemorySection
import io.zer0.muse.ui.memory.TimelineEventCard
import io.zer0.muse.ui.memory.TimelineItem
import io.zer0.muse.ui.memory.TimelineItemActions
import io.zer0.muse.ui.memory.memorySpaceDisplayName
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.bodySmallEmphasis
import io.zer0.muse.ui.theme.labelLargeStrong
import io.zer0.muse.ui.theme.titleSmallStrong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import io.zer0.muse.ui.chat.ChatScreen

/**
 * 记忆条目行的渲染与批量操作组件：
 * `MemoryFactRow` 及其状态徽章、证据行、底部操作，以及选中操作条、
 * 批量删除与合并对话框。
 *
 * 从 `MemoryScreen.kt` 切出——它们与页面骨架无关，可单独预览与测试。
 */
// F-9: 行内操作文本组与回调透传:与既有列表行惯例一致
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun MemoryFactRow(
    item: MemoryItem,
    sourceMeta: String? = null,
    onOpenSession: ((String) -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onPin: (() -> Unit)? = null,
    onImportance: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
    // v2.6.10: 多选删除 — 记忆会乱记/重复记，逐条删又要反复滑回原位，
    // 所以支持长按进入多选，一次性清掉。
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onToggleSelect: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onLongPress == null) {
                    Modifier
                } else {
                    Modifier.combinedClickable(
                        onClick = { if (selectionMode) onToggleSelect?.invoke() },
                        onLongClick = onLongPress,
                    )
                },
            ),
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        // v1.0.92: 去掉 tonalElevation — 浅色模式下 tonal 会让卡片整体偏灰,
        // 与页面背景形成"莫名阴影/灰块拼接"的观感(用户真机反馈);
        // 对齐 CardGroup 的既有方向(背景用纯 surface、取消阴影)。
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (selectionMode) {
                Icon(
                    imageVector = if (isSelected) MuseIcons.circleCheck else MuseIcons.circle,
                    contentDescription = null,
                    tint =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.padding(start = 14.dp, top = 20.dp).size(20.dp),
                )
            }
            Column(
                modifier = Modifier.padding(16.dp).weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // v2.2.1: 长记忆点击展开 — 点标题/正文或「展开/收起」即可查看全文
                var expanded by remember(item.id) { mutableStateOf(false) }
                val titleText = item.title.ifBlank { item.content }
                val bodyText = item.content.takeIf { item.title != item.content }
                val canExpand =
                    titleText.length > EXPAND_TEXT_THRESHOLD || titleText.contains('\n') ||
                        bodyText?.let { it.length > EXPAND_TEXT_THRESHOLD || it.contains('\n') } == true
                val toggleExpand = { if (canExpand) expanded = !expanded }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                        Modifier
                            .weight(1f)
                            .clickable(enabled = canExpand) { toggleExpand() },
                    )
                    if (onOpenSession != null && item.sessionId != null) {
                        MuseTactileButton(
                            icon = MuseIcons.chat,
                            onClick = { onOpenSession(item.sessionId) },
                            contentDescription = stringResource(R.string.memory_open_conversation),
                        )
                    }
                    // LAYOUT-01: 纯装饰分隔符 — 只限行，保证永不被压成多行
                    if (item.pinnedAt != null) {
                        Text(
                            "•",
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                bodyText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = canExpand) { toggleExpand() },
                    )
                }
                if (canExpand) {
                    Text(
                        text = stringResource(if (expanded) R.string.memory_screen_collapse else R.string.common_expand),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                        Modifier
                            .clickable { toggleExpand() }
                            .padding(vertical = 2.dp),
                    )
                }
                // v2.6.23(M1): 「依据」——这条事实出处的用户原话(写证据才显示;早期数据无)。
                // 判定分支收进组件内部:MemoryFactRow 的复杂度按签名登记在 detekt 基线,
                // 在这里内联 ?.let 会把整卡函数顶过阈值。
                MemoryEvidenceLine(item = item, expanded = expanded)
                MemoryFactRowFooter(
                    item = item,
                    sourceMeta = sourceMeta,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onPin = onPin,
                    onImportance = onImportance,
                    onHistory = onHistory,
                )
            }
        }
    }
}

/**
 * 记忆状态徽章 —— 已合并 / 已出场 / 被取代。
 *
 * 三种状态在数据上互相独立,一条记忆可能同时命中多个。行内同时排三个徽章会把
 * 底部那行（来源/分类/时间/会话）挤满,所以按「被取代 > 已合并 > 已出场」排序:
 * 最要紧的那个用完整色,其余照旧渲染但降淡 —— 既不会漏掉真实原因,
 * 也不会让一屏徽章抢走正文的注意力。完整解释在详情弹层里逐条列出。
 */
@Composable
private fun MemoryStatusBadges(status: MemoryFactStatus) {
    if (!status.isInactive) return
    val colors = MaterialTheme.colorScheme
    val badges = listOfNotNull(
        if (status.superseded) {
            Triple(stringResource(R.string.memory_status_superseded), colors.errorContainer, colors.onErrorContainer)
        } else {
            null
        },
        if (status.merged) {
            Triple(stringResource(R.string.memory_status_merged), colors.surfaceVariant, colors.onSurfaceVariant)
        } else {
            null
        },
        if (status.suppressed) {
            Triple(stringResource(R.string.memory_status_suppressed), colors.secondaryContainer, colors.onSecondaryContainer)
        } else {
            null
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        badges.forEachIndexed { index, badge ->
            val (label, container, contentColor) = badge
            val muted = index > 0
            Surface(
                shape = MuseShapes.large,
                color = if (muted) container.copy(alpha = MEMORY_STATUS_MUTED_ALPHA) else container,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (muted) contentColor.copy(alpha = MEMORY_STATUS_MUTED_ALPHA) else contentColor,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * 详情弹层里的状态说明：与列表徽章同一套判定，按「被取代 > 已合并 > 已出场」逐条列出。
 *
 * 用户点开一条带徽章的记忆，要能知道它为什么不生效、而不是只看到一个词。
 */
@Composable
internal fun memoryStatusNote(item: MemoryItem): String? {
    val status = item.status
    val lines = listOfNotNull(
        if (status.superseded) stringResource(R.string.memory_status_superseded_detail) else null,
        if (status.merged) stringResource(R.string.memory_status_merged_detail) else null,
        if (status.suppressed) stringResource(R.string.memory_status_suppressed_detail) else null,
    )
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/**
 * v2.6.23(M1): 记忆卡片的「依据」行 —— 该事实出处的用户原话摘录。
 *
 * 只有写入时匹配到原话的事实才有这行(见 UserEvidenceMatcher);无证据时整行不出现,
 * 不是显示"依据:无" —— 缺证据是数据状态,不该占用每条记忆的视觉。
 * 折叠 1 行、展开至多 3 行,与正文同一交互心智,不另设弹层。
 */
@Composable
private fun MemoryEvidenceLine(item: MemoryItem, expanded: Boolean) {
    val quote = item.evidenceQuote?.takeIf { it.isNotBlank() } ?: return
    Text(
        text = stringResource(R.string.memory_evidence_line, quote),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        maxLines = if (expanded) 3 else 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 记忆卡片的底部一行：来源/分类/时间 + 「更多」操作菜单。
 *
 * 从 [MemoryFactRow] 里拆出来的：卡片主体只负责「这条记忆是什么」，
 * 这一行负责「能对它做什么」，两者改动频率不一样。
 */
@Composable
private fun MemoryFactRowFooter(
    item: MemoryItem,
    sourceMeta: String?,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onPin: (() -> Unit)?,
    onImportance: (() -> Unit)?,
    onHistory: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sourceMeta?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 状态徽章（已合并/已出场/被取代）:放在来源之后、分类之前 ——
        // “这条还生效吗”比“它属于哪个分类”更需要被一眼看到。
        MemoryStatusBadges(status = item.status)
        item.category?.takeIf {
            it.isNotBlank()
        }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        item.time?.takeIf {
            it.isNotBlank()
        }?.let { Text(it.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
        // F-9: 来源会话可追溯
        item.sessionId?.takeIf { it.isNotBlank() }?.let { sid ->
            Text(
                text = sid.take(MEMORY_FACT_ROW_SESSION_ID_MAX),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        // MEM-06: 操作收进「更多」菜单 — 不再一行挤 4 个文字按钮;菜单项触摸区满足 48dp
        var showMore by remember { mutableStateOf(false) }
        Box {
            MuseTactileButton(
                icon = MuseIcons.chevronDown,
                onClick = { showMore = true },
                contentDescription = stringResource(R.string.action_more),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = MuseIconSizes.touchTarget,
                iconSize = MuseIconSizes.iconSmall,
            )
            MuseAnchoredMenu(
                expanded = showMore,
                onDismissRequest = { showMore = false },
                alignEnd = true,
                minWidth = 196.dp,
                maxWidth = 240.dp,
            ) {
                onImportance?.let {
                    MuseActionSheetRow(
                        icon = MuseIcons.star,
                        onClick = {
                            showMore = false
                            it()
                        },
                        text = stringResource(R.string.memory_menu_importance),
                    )
                }
                onPin?.let {
                    MuseActionSheetRow(
                        icon = MuseIcons.pin,
                        onClick = {
                            showMore = false
                            it()
                        },
                        text = stringResource(
                            if (item.pinnedAt != null) R.string.memory_menu_unpin else R.string.memory_menu_pin,
                        ),
                    )
                }
                onEdit?.let {
                    MuseActionSheetRow(
                        icon = MuseIcons.edit,
                        onClick = {
                            showMore = false
                            it()
                        },
                        text = stringResource(R.string.common_edit),
                    )
                }
                onHistory?.let {
                    MuseActionSheetRow(
                        icon = MuseIcons.history,
                        onClick = {
                            showMore = false
                            it()
                        },
                        text = stringResource(R.string.memory_menu_history),
                    )
                }
                onDelete?.let {
                    MuseActionSheetRow(
                        icon = MuseIcons.trash,
                        contentColor = MaterialTheme.colorScheme.error,
                        onClick = {
                            showMore = false
                            it()
                        },
                        text = stringResource(R.string.common_delete),
                    )
                }
            }
        }
    }
}

/**
 * 记忆流的多选状态与回调。
 *
 * 打包传递：这四个值总是同进同出，拆成四个参数会把每个列表函数的签名拉得很长。
 */
internal class MemorySelection(
    val active: Boolean,
    val isSelected: (MemoryItem) -> Boolean,
    val onLongPress: (MemoryItem) -> Unit,
    val onToggle: (MemoryItem) -> Unit,
)

/**
 * 记忆流的时间轴视图：按月分组，组内可再按来源筛选。
 *
 * 从 [memoryStreamItems] 拆出来 —— 那里同时管置顶区、说明、加载/空态与两种视图，
 * 分支堆在一起很难看出「这条流到底怎么长出来的」。
 */
internal fun LazyListScope.memoryStreamTimeline(
    items: List<MemoryItem>,
    timelineFilter: String,
    onTimelineFilter: (String) -> Unit,
    onToggleTimeline: () -> Unit,
    onOpenActions: (TimelineItem) -> Unit,
    selection: MemorySelection,
) {
    // 多选状态来自同一个包，取短名后正文不用每行都写 selection.xxx。
    val selectionMode = selection.active
    val isSelected = selection.isSelected
    val onToggleSelect = selection.onToggle

    item(key = "memory_stream_timeline_toggle") {
        TimelineModeToggleRow(timelineMode = true, onToggle = onToggleTimeline)
    }
    item(key = "memory_stream_timeline_filters") {
        MemoryTimelineFilterRow(selected = timelineFilter, onSelect = onTimelineFilter)
    }
    val grouped =
        items.groupBy { item ->
            try {
                val instant = Instant.parse(item.createdAt ?: item.time.orEmpty())
                val dt = instant.atZone(ZoneId.systemDefault())
                "${dt.year}-${dt.monthValue.toString().padStart(2, '0')}"
            } catch (_: Exception) {
                "Unknown"
            }
        }.toSortedMap(compareByDescending { it })
    grouped.forEach { (month, monthItems) ->
        val filtered =
            when (timelineFilter) {
                "all" -> monthItems
                "fact" -> monthItems.filter { it.source == "Fact" }
                "summary" -> monthItems.filter { it.source == "Summary" }
                "milestone" -> monthItems.filter { it.source == "Milestone" }
                else -> emptyList()
            }
        if (filtered.isNotEmpty()) {
            item(key = "timeline_month_$month") {
                MonthHeader(month = month)
            }
            // 审计修复: key 需带 source + scope — 「全部」视图合并主库与各子助手分库后,
            // 各库 fact.id 独立自增会重复,仅用 id 作 key 会触发 LazyColumn
            // "Key was already used" 崩溃。
            items(filtered, key = { "timeline_${it.source}_${it.scope.orEmpty()}_${it.id}" }) { item ->
                val timelineItem = TimelineItem(
                    id = "${item.source}_${item.id}",
                    content = item.content,
                    source = item.source,
                    importance = item.importance,
                    createdAt = item.createdAt ?: item.time,
                    tags = item.tags,
                    // 删除要按底层 id + 分库定位:摘要的 itemId 就是 sessionId
                    itemId = item.id,
                    sessionId = item.sessionId,
                    scope = item.scope,
                )
                // 多选态下长按仍是勾选;平时长按出动作面板(删除/多选)。
                // 摘要卡片不进多选,它在多选态下不接管手势。
                val cardLongPress: (() -> Unit)? = if (!selectionMode) {
                    { onOpenActions(timelineItem) }
                } else if (item.source == "Fact") {
                    { onToggleSelect(item) }
                } else {
                    null
                }
                // v2.x: 动效补齐 — 时间轴拍平条目统一入场动画(与列表模式同规格)
                Box(museAnimateItem()) {
                    TimelineEventCard(
                        item = timelineItem,
                        selectionMode = selectionMode,
                        isSelected = isSelected(item),
                        onLongPress = cardLongPress,
                        onToggleSelect = if (item.source == "Fact") ({ onToggleSelect(item) }) else null,
                    )
                }
            }
        }
    }
}

// F-9: 来源会话 id 展示的最大长度(避免长 id 撑爆行布局)。
private const val MEMORY_FACT_ROW_SESSION_ID_MAX = 10

/** 同一行命中多个状态时，次要徽章的淡出程度(最强那个保持完整色)。 */
private const val MEMORY_STATUS_MUTED_ALPHA = 0.55f

/** v2.2.1: 记忆条目"可展开"判定阈值(字符数);超过或含换行即提供全文展开。 */
private const val EXPAND_TEXT_THRESHOLD = 80

@Composable
internal fun MemoryFilterRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(MuseShapes.medium).clickable(onClick = onClick),
        shape = MuseShapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                // LAYOUT-01: 筛选项名已取宽，补限行以免剩余宽度极小时逐字换行
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 多选态动作栏：取消 / 合并 / 删除。
 *
 * 合并进行中时合并入口换成 spinner，删除一并禁用：用户在弹窗里等模型那几秒最容易去点别处，
 * 一旦源行被删掉，落库时找不到源就只能报「合并失败」，而这期间他什么都没改。
 */
@Composable
internal fun MemorySelectionActions(
    selectedCount: Int,
    mergeRunning: Boolean,
    onCancel: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    MuseTactileButton(
        icon = MuseIcons.x,
        onClick = onCancel,
        contentDescription = stringResource(R.string.common_cancel),
    )
    // 生成草稿要等模型几秒：把入口换成固定 48dp 的 spinner。
    // 只置灰的话几秒内看不到任何反应，用户会以为没点上而连点。
    if (mergeRunning) {
        val runningDesc = stringResource(R.string.memory_center_merge_running)
        Box(
            modifier = Modifier.size(MuseIconSizes.touchTarget).semantics { contentDescription = runningDesc },
            contentAlignment = Alignment.Center,
        ) {
            MuseSpinner(
                size = MuseIconSizes.icon,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    } else {
        // 合并要至少两条：不足时按钮留着但置灰，避免工具栏跟着条目数忽隐忽现
        MuseTactileButton(
            icon = MuseIcons.gitMerge,
            onClick = onMerge,
            contentDescription = stringResource(R.string.memory_center_merge_action),
            enabled = selectedCount >= 2,
        )
    }
    MuseTactileButton(
        icon = MuseIcons.trash,
        onClick = onDelete,
        contentDescription = stringResource(R.string.common_delete),
        enabled = !mergeRunning,
        tint = MaterialTheme.colorScheme.error,
    )
}

/**
 * 多选批量删除的二次确认。
 *
 * 批量删一次会带走多行,且落库后不可撤销,所以先把条数摆出来再删 ——
 * 之前点一下就直推 deleteFacts,误触没有任何挽回余地。
 */
@Composable
internal fun MemoryBulkDeleteDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDeleteDialog(
        title = stringResource(R.string.memory_bulk_delete_confirm_title),
        itemName = stringResource(R.string.memory_bulk_delete_item_name, count),
        consequence = stringResource(R.string.memory_bulk_delete_consequence, count),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * 多选合并的预览弹窗。
 *
 * 上半列出被合并的原文（只读），用户能看清「合的是哪几条」；
 * 下半是可编辑的合并结果——草稿是模型写的，措辞需要人改一遍才落库。
 * 内容可能很长，但不做另一层滚动：MuseDialog 的正文区本身已按可视高度滚动。
 */
@Composable
internal fun MemoryMergeDialog(draft: MemoryMergeDraft, running: Boolean, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var edited by remember(draft) { mutableStateOf(draft.mergedText) }
    MuseDialog(
        onDismissRequest = { if (!running) onCancel() },
        title = stringResource(R.string.memory_center_merge_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Text(
                    text = stringResource(R.string.memory_center_merge_sources_title, draft.sources.size),
                    style = MaterialTheme.typography.labelLargeStrong,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                draft.sources.forEach { source ->
                    Surface(
                        shape = MuseShapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = source,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(MusePaddings.cardInner),
                        )
                    }
                }
                Spacer(Modifier.height(MusePaddings.labelVerticalGap))
                MuseTextField(
                    value = edited,
                    onValueChange = { edited = it },
                    enabled = !running,
                    label = { Text(stringResource(R.string.memory_center_merge_result_label)) },
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp),
                )
                if (running) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                    ) {
                        MuseSpinner(
                            size = MuseIconSizes.iconSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.memory_center_merge_running),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.memory_center_merge_confirm),
        // 空白正文会被 ViewModel 拒掉，提前置灰比点了没反应干净
        confirmEnabled = edited.isNotBlank() && !running,
        onConfirm = { onConfirm(edited) },
        // 落库期间不给退出口，避免草稿已被清空、协程还在写
        dismissText = if (running) null else stringResource(R.string.common_cancel),
        onDismiss = onCancel,
        properties = mergeDialogProperties(running),
    )
}

/**
 * 合并预览弹窗的窗口属性。
 *
 * 点空白一律不关：长文编到一半误触会把草稿扔掉。
 * 返回键在空闲时可用、落库中失效——与取消按钮用同一个开关，避免两条退出口行为不一致。
 */
internal fun mergeDialogProperties(running: Boolean): DialogProperties = DialogProperties(
    dismissOnClickOutside = false,
    dismissOnBackPress = !running,
    decorFitsSystemWindows = false,
    usePlatformDefaultWidth = false,
)
