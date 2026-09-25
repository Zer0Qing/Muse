@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.zer0.muse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.memory.MemoryGraphView
import io.zer0.muse.ui.memory.MemoryGraphViewModel
import io.zer0.muse.ui.memory.MemoryTimelineView
import io.zer0.muse.ui.memory.PinnedMemorySection
import io.zer0.muse.ui.memory.TimelineItem
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

/**
 * 记忆观测站。
 *
 * 顶部概览卡 + 三段紧凑分段切换（记忆流 / 事实库 / 记忆星座），
 * 内容区只显示当前分段，避免单页无限长列表，也避免横向滚动的筛选胶囊。
 */
@Composable
fun MemoryScreen(
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: MemoryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scopes by viewModel.availableScopes.collectAsStateWithLifecycle()
    val spaces by viewModel.availableSpaces.collectAsStateWithLifecycle()
    val selectedScope by viewModel.selectedScope.collectAsStateWithLifecycle()
    val selectedSpace by viewModel.selectedSpaceId.collectAsStateWithLifecycle()
    // F-7: 重要程度 / 时间范围筛选状态
    val importanceFilter by viewModel.importanceFilter.collectAsStateWithLifecycle()
    val timeRangeFilter by viewModel.timeRangeFilter.collectAsStateWithLifecycle()
    val organizing by viewModel.organizeRunning.collectAsStateWithLifecycle()
    val organizeStage by viewModel.organizeStage.collectAsStateWithLifecycle()

    // 来龙去脉：正在查看修订历史的那条事实（非 null 时弹出修订面板）
    var revisionTarget by remember { mutableStateOf<MemoryItem?>(null) }
    val factRevisions by viewModel.factRevisions.collectAsStateWithLifecycle()
    revisionTarget?.let { target ->
        FactRevisionsSheet(
            revisions = factRevisions,
            factContent = target.content,
            onRevert = { revisionId -> viewModel.revertFactToRevision(target.id, revisionId, target.scope) },
            onDismiss = { revisionTarget = null },
        )
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val widthClass = rememberWindowWidthClass()
    var tab by remember { mutableIntStateOf(0) } // 0=记忆流 1=事实库 2=星座
    var query by remember { mutableStateOf("") }
    var editItem by remember { mutableStateOf<MemoryItem?>(null) }
    var showAddFact by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    // P1-3: 记忆流视图模式(false=列表, true=时间轴)
    var streamTimelineMode by remember { mutableStateOf(false) }
    // F-10: 重要程度选择对话框的目标条目
    var importanceItem by remember { mutableStateOf<MemoryItem?>(null) }
    // U-3: 删除前需二次确认的目标条目(未选中时为 null,不弹窗)
    var deleteTarget by remember { mutableStateOf<MemoryItem?>(null) }

    LaunchedEffect(query) {
        delay(300)
        viewModel.search(query)
    }
    LaunchedEffect(viewModel.organizeResult) {
        val result = viewModel.organizeResult.value ?: return@LaunchedEffect
        if (result.startsWith("done:")) {
            val merged = result.removePrefix("done:").toIntOrNull() ?: 0
            MuseToast.show(
                if (merged == 0) {
                    context.getString(R.string.memory_organize_stage_no_duplicates)
                } else {
                    context.getString(R.string.memory_organize_stage_complete, merged)
                },
            )
        } else {
            MuseToast.show(context.getString(R.string.memory_organize_stage_failed))
        }
        viewModel.consumeOrganizeResult()
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.memory_screen_title),
                onBack = onBack,
                largeTitle = true,
                actions = {
                    MuseTactileButton(
                        icon = MuseIcons.sliders,
                        onClick = onOpenSettings,
                        contentDescription = stringResource(R.string.settings_memory_page_title),
                    )
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (widthClass == WindowWidthClass.Expanded) Modifier.widthIn(max = 760.dp) else Modifier),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "memory_overview") {
                    MemoryOverviewCard(
                        factCount = state.factCount,
                        organizing = organizing,
                        stage = organizeStage,
                        scopeLabel = scopes.firstOrNull { it.id == selectedScope }?.displayName
                            ?: stringResource(R.string.memory_center_scope_all),
                        spaceLabel = spaces.firstOrNull { it.id == selectedSpace }?.name
                            ?: stringResource(R.string.memory_center_space_default),
                        onOrganize = viewModel::organizeMemory,
                        onOpenFilter = { showFilter = true },
                    )
                }
                item(key = "memory_tabs") {
                    MemoryCapsuleTabs(selected = tab, onSelect = { tab = it })
                }
                val memoryError = state.errorTrace
                if (memoryError != null) {
                    item(key = "memory_error") {
                        MuseErrorStateBox(
                            message = memoryError.lineSequence().firstOrNull { it.isNotBlank() }?.take(240)
                                ?: stringResource(R.string.memory_graph_load_failed),
                            onRetry = { viewModel.loadAll() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                        )
                    }
                } else {
                    when (tab) {
                        0 -> memoryStreamItems(
                            state = state,
                            timelineMode = streamTimelineMode,
                            onToggleTimeline = { streamTimelineMode = !streamTimelineMode },
                            onOpenFacts = { tab = 1 },
                            onHistory = {
                                viewModel.loadFactRevisions(it.id, it.scope)
                                revisionTarget = it
                            },
                            onEdit = { editItem = it },
                            onDelete = { deleteTarget = it },
                            onPin = { viewModel.toggleFactPinned(it.id, it.scope) },
                            onImportance = { importanceItem = it },
                        )
                        1 -> memoryFactsItems(
                            state = state,
                            query = query,
                            onQuery = { query = it },
                            onAdd = { showAddFact = true },
                            onHistory = {
                                viewModel.loadFactRevisions(it.id, it.scope)
                                revisionTarget = it
                            },
                            onEdit = { editItem = it },
                            onDelete = { deleteTarget = it },
                            onPin = { viewModel.toggleFactPinned(it.id, it.scope) },
                            onImportance = { importanceItem = it },
                            onDismissContradiction = viewModel::dismissContradiction,
                        )
                        else -> item(key = "memory_constellation") {
                            MemoryConstellationTab(
                                scope = selectedScope,
                                spaceId = selectedSpace,
                                factCount = state.factCount,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 560.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    editItem?.let { item ->
        FactEditDialog(
            title = stringResource(R.string.memory_screen_edit_fact),
            initialContent = item.content,
            onDismiss = { editItem = null },
            onConfirm = { content ->
                viewModel.editFact(item.id, content, item.scope)
                editItem = null
            },
        )
    }
    if (showAddFact) {
        AddFactDialog(
            onDismiss = { showAddFact = false },
            onConfirm = { content ->
                viewModel.addFact(content)
                showAddFact = false
            },
        )
    }
    // F-10: 重要程度选择(0=普通, 1=重要, 2=关键 — 关键事实永不衰减)
    importanceItem?.let { item ->
        ImportanceSelectDialog(
            currentImportance = item.importance,
            onDismiss = { importanceItem = null },
            onSelect = { importance ->
                viewModel.setFactImportance(item.id, importance, item.scope)
                importanceItem = null
            },
        )
    }
    // U-3: 删除前二次确认,确认后删除目标记忆(仅当选中目标时触发)
    // MEM-04: 改用全站统一的 ConfirmDeleteDialog(点名记忆内容 + 说明后果 + destructive 主键),
    // 不再单独维护 memory_delete_confirm_message 那套措辞。
    deleteTarget?.let { item ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.memory_delete_confirm_title),
            itemName = item.title.ifBlank { item.content },
            consequence = stringResource(R.string.memory_delete_consequence),
            onConfirm = {
                viewModel.deleteFact(item.id, item.scope)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
    if (showFilter) {
        MuseBottomSheet(onDismissRequest = { showFilter = false }) {
            Column(
                // UI-FIX(贴边): 面板本身已给 screen 边距，这里再加 20dp 会变成双倍缩进。
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.memory_center_filter_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                // U-11: "作用域/记忆空间"为技术化筛选,整体收进可展开的"高级筛选"折叠区(默认收起)
                var advancedFilterExpanded by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advancedFilterExpanded = !advancedFilterExpanded }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.memory_center_filter_advanced),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (advancedFilterExpanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                        contentDescription = stringResource(R.string.memory_center_filter_advanced),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                AnimatedVisibility(visible = advancedFilterExpanded) {
                    Column {
                        Text(
                            text = stringResource(R.string.memory_center_filter_scope),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        scopes.forEach { option ->
                            MemoryFilterRow(
                                label = option.displayName,
                                selected = option.id == selectedScope,
                                onClick = { viewModel.selectScope(option.id); showFilter = false },
                            )
                        }
                        Text(
                            text = stringResource(R.string.memory_center_filter_space),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        spaces.forEach { space ->
                            MemoryFilterRow(
                                label = space.name,
                                selected = space.id == selectedSpace,
                                onClick = { viewModel.selectSpace(space.id); showFilter = false },
                            )
                        }
                    }
                }
                // F-7: 重要程度筛选
                Text(
                    text = stringResource(R.string.memory_center_filter_importance),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                MemoryFilterRow(
                    label = stringResource(R.string.memory_center_filter_importance_all),
                    selected = importanceFilter == null,
                    onClick = { viewModel.selectImportanceFilter(null); showFilter = false },
                )
                listOf(0 to R.string.memory_importance_normal, 1 to R.string.memory_importance_important, 2 to R.string.memory_importance_critical).forEach { (level, labelRes) ->
                    MemoryFilterRow(
                        label = stringResource(labelRes),
                        selected = importanceFilter == level,
                        onClick = { viewModel.selectImportanceFilter(level); showFilter = false },
                    )
                }
                // F-7: 时间范围筛选
                Text(
                    text = stringResource(R.string.memory_center_filter_time),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val timeOptions = listOf(
                    null to R.string.memory_center_filter_time_all,
                    MemoryTimeRange.LAST_7 to R.string.memory_center_filter_time_7,
                    MemoryTimeRange.LAST_30 to R.string.memory_center_filter_time_30,
                    MemoryTimeRange.LAST_90 to R.string.memory_center_filter_time_90,
                )
                timeOptions.forEach { (range, labelRes) ->
                    MemoryFilterRow(
                        label = stringResource(labelRes),
                        selected = (timeRangeFilter == null && range == null) || (timeRangeFilter != null && timeRangeFilter == range),
                        onClick = { viewModel.selectTimeRangeFilter(range); showFilter = false },
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun MemoryCapsuleTabs(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    // P1-3: 恢复记忆星座 tab(真实星座图,经 MemoryGraphViewModel 加载)
    val tabs = listOf(
        stringResource(R.string.memory_center_tab_stream),
        stringResource(R.string.memory_tab_facts),
        stringResource(R.string.memory_center_tab_constellation),
    )
    io.zer0.muse.ui.common.form.MuseCapsuleTab(
        tabs = tabs,
        selectedIndex = selected,
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 12.dp),
    )
}

@Composable
private fun MemoryOverviewCard(
    factCount: Int,
    organizing: Boolean,
    stage: String?,
    scopeLabel: String,
    spaceLabel: String,
    onOrganize: () -> Unit,
    onOpenFilter: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = factCount.toString(),
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.memory_center_fact_count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(start = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    modifier = Modifier.clip(MuseShapes.medium).clickable(onClick = onOpenFilter),
                    shape = MuseShapes.medium,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.memory_center_scope_line, scopeLabel, spaceLabel),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = MuseIcons.filter,
                            contentDescription = stringResource(R.string.memory_center_filter_title),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            MuseCapsuleButton(
                text = when (stage) {
                    "prepare" -> stringResource(R.string.memory_organize_stage_prepare)
                    "compile" -> stringResource(R.string.memory_organize_stage_compile)
                    "dedup" -> stringResource(R.string.memory_organize_stage_dedup)
                    else -> stringResource(R.string.memory_organize_action)
                },
                onClick = onOrganize,
                enabled = !organizing,
                loading = organizing,
                variant = IosCapsuleButtonVariant.Secondary,
                leadingIcon = MuseIcons.refresh,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// F-10/F-6 回调透传使参数增多:屏幕级 helper 固有结构,与既有 ListScope helper 惯例一致
@Suppress("LongParameterList")
private fun LazyListScope.memoryStreamItems(
    state: MemoryUiState,
    timelineMode: Boolean,
    onToggleTimeline: () -> Unit,
    onOpenFacts: () -> Unit,
    onHistory: (MemoryItem) -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onDelete: (MemoryItem) -> Unit,
    onPin: (MemoryItem) -> Unit,
    onImportance: (MemoryItem) -> Unit,
) {
    val items = state.factItems.sortedByDescending { it.createdAt ?: it.time.orEmpty() }
    // P1-3: 置顶区接线 — 置顶事实集中展示,可一键取消置顶
    val pinned = items.filter { it.pinnedAt != null }
    if (pinned.isNotEmpty()) {
        item(key = "memory_stream_pinned") {
            PinnedMemorySection(
                pinnedEntries = pinned.map { item ->
                    io.zer0.memory.pin.PinnedMemoryStore.PinnedEntry(
                        id = item.id,
                        content = item.content,
                        createdAt = item.createdAt ?: item.time.orEmpty(),
                        updatedAt = item.pinnedAt ?: item.createdAt ?: item.time.orEmpty(),
                    )
                },
                onRemove = { id -> items.firstOrNull { it.id == id }?.let(onPin) },
            )
        }
    }
    // MEM-01 (D2): 记忆流 = 按发生时间聚合的时间轴 — 顶部说明与时间语义
    item(key = "memory_stream_subtitle") {
        Text(
            text = stringResource(R.string.memory_center_stream_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 2.dp),
        )
    }
    if (state.isLoading) {
        item(key = "memory_stream_loading") {
            Box(Modifier.fillMaxWidth().heightIn(min = 320.dp), contentAlignment = Alignment.Center) {
                io.zer0.muse.ui.common.state.MuseLoadingState()
            }
        }
        return
    }
    if (items.isEmpty()) {
        item(key = "memory_stream_empty") {
            Box(Modifier.fillMaxWidth().heightIn(min = 320.dp), contentAlignment = Alignment.Center) {
                // ST-03: 空态统一 MuseEmptyState
                MuseEmptyState(
                    title = stringResource(R.string.memory_center_empty_title),
                    subtitle = stringResource(R.string.memory_center_empty_subtitle),
                    actionText = stringResource(R.string.memory_center_open_facts),
                    onAction = onOpenFacts,
                )
            }
        }
        return
    }
    // P1-3: 时间轴模式 — 按月分组的时间线视图(MemoryTimelineView 接线)
    if (timelineMode) {
        item(key = "memory_stream_timeline") {
            MemoryTimelineView(
                items = items.map { item ->
                    TimelineItem(
                        id = item.id,
                        content = item.content,
                        source = item.source,
                        importance = item.importance,
                        createdAt = item.createdAt ?: item.time,
                        tags = item.tags,
                    )
                },
                headerContent = {
                    TimelineModeToggleRow(timelineMode = true, onToggle = onToggleTimeline)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }
    // 列表模式(默认):按发生日期分组,插入日期小节标题,让「时间轴」语义可感知
    item(key = "memory_stream_list_toggle") {
        TimelineModeToggleRow(timelineMode = false, onToggle = onToggleTimeline)
    }
    items.groupBy { (it.createdAt ?: it.time.orEmpty()).take(10) }.forEach { (day, dayItems) ->
        item(key = "stream_day_$day") {
            Text(
                text = day,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 4.dp),
            )
        }
        items(dayItems, key = { "stream_${it.id}" }) { item ->
            Box(modifier = Modifier.padding(horizontal = MusePaddings.screen)) {
                MemoryFactRow(
                    item = item,
                    onEdit = { onEdit(item) },
                    onDelete = { onDelete(item) },
                    onHistory = { onHistory(item) },
                    onPin = { onPin(item) },
                    onImportance = { onImportance(item) },
                )
            }
        }
    }
}

/** P1-3: 记忆流「列表 / 时间轴」切换行。 */
@Composable
private fun TimelineModeToggleRow(
    timelineMode: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            false to stringResource(R.string.memory_stream_mode_list),
            true to stringResource(R.string.memory_stream_mode_timeline),
        ).forEach { (mode, label) ->
            io.zer0.muse.ui.common.form.MuseChip(
                selected = timelineMode == mode,
                onClick = { if (timelineMode != mode) onToggle() },
                label = label,
            )
        }
    }
}

@Suppress("LongParameterList")
private fun LazyListScope.memoryFactsItems(
    state: MemoryUiState,
    query: String,
    onQuery: (String) -> Unit,
    onAdd: () -> Unit,
    onHistory: (MemoryItem) -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onDelete: (MemoryItem) -> Unit,
    onPin: (MemoryItem) -> Unit,
    onImportance: (MemoryItem) -> Unit,
    onDismissContradiction: (io.zer0.memory.reflection.MemoryContradictionStore.ContradictionPair) -> Unit,
) {
    val items = if (query.isBlank()) state.factItems else state.searchResults
    item(key = "memory_fact_search") {
        MemorySearchBar(
            query = query,
            onQueryChange = onQuery,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 4.dp),
        )
    }
    // P2-32: 矛盾记忆清单(每日反思检测落库) — 非空时展示,用户逐对确认/清除
    if (state.contradictions.isNotEmpty() && query.isBlank()) {
        item(key = "memory_contradictions") {
            androidx.compose.material3.ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MusePaddings.screen, vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.memory_contradictions_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    state.contradictions.forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pair.a,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "↔",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = pair.b,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            MuseTactileButton(
                                icon = MuseIcons.x,
                                onClick = { onDismissContradiction(pair) },
                                contentDescription = stringResource(R.string.memory_contradictions_dismiss_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    item(key = "memory_fact_header") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.memory_center_library_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            MuseTactileButton(
                icon = MuseIcons.plus,
                onClick = onAdd,
                contentDescription = stringResource(R.string.memory_screen_add_fact_cd),
            )
        }
        // MEM-01 (D2): 条目库语义说明 — 与「记忆流」按时间聚合区分
        Text(
            text = stringResource(R.string.memory_center_facts_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // UI-FIX(贴边): 上方标题用了 screen 边距，副标题却没跟，文字直接顶到屏幕左缘。
            modifier = Modifier.padding(horizontal = MusePaddings.screen),
        )
    }
        if (state.isLoading) {
            item(key = "memory_facts_loading") {
                Box(Modifier.fillMaxWidth().heightIn(min = 320.dp), contentAlignment = Alignment.Center) {
                io.zer0.muse.ui.common.state.MuseLoadingState()
                }
            }
        } else if (items.isEmpty()) {
            item(key = "memory_facts_empty") {
                Box(Modifier.fillMaxWidth().heightIn(min = 320.dp).padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = if (query.isBlank()) stringResource(R.string.memory_center_empty_subtitle)
                            else stringResource(R.string.memory_screen_empty_content),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        MuseCapsuleButton(
                            text = stringResource(R.string.memory_add_fact),
                            onClick = onAdd,
                            variant = IosCapsuleButtonVariant.Secondary,
                            leadingIcon = MuseIcons.plus,
                            fillWidth = false,
                        )
                    }
                }
            }
        } else {
            items(items, key = { "lib_${it.id}" }) { item ->
                Box(modifier = Modifier.padding(horizontal = MusePaddings.screen)) {
                    MemoryFactRow(
                        item = item,
                        onEdit = { onEdit(item) },
                        onDelete = { onDelete(item) },
                        onHistory = { onHistory(item) },
                        onPin = { onPin(item) },
                        onImportance = { onImportance(item) },
                    )
                }
            }
        }
}

@Composable
private fun MemoryConstellationTab(
    scope: String?,
    spaceId: String,
    factCount: Int,
    modifier: Modifier = Modifier,
) {
    val graphViewModel: MemoryGraphViewModel = koinViewModel()
    val graphState by graphViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(scope, spaceId) { graphViewModel.load(scope, spaceId) }
    Column(modifier = modifier.padding(horizontal = MusePaddings.screen, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.memory_center_constellation_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(MuseShapes.extraLarge),
        ) {
            if (graphState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    io.zer0.muse.ui.common.state.MuseLoadingState()
                }
            } else if (graphState.nodes.isEmpty()) {
                // v2.0: 空态改为贴顶居中 — 星座容器高 560dp 起,超过首屏高度时
                // 垂直居中点会落在屏幕外,文案被底部截断(实测第二行不可见)。
                Box(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 72.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        text = stringResource(
                            if (factCount == 0) R.string.memory_center_constellation_empty
                            else R.string.memory_center_filter_empty,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                MemoryGraphView(state = graphState, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

// F-9: 行内操作文本组与回调透传:与既有列表行惯例一致
@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun MemoryFactRow(
    item: MemoryItem,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onPin: (() -> Unit)? = null,
    onImportance: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        // v1.0.92: 去掉 tonalElevation — 浅色模式下 tonal 会让卡片整体偏灰,
        // 与页面背景形成"莫名阴影/灰块拼接"的观感(用户真机反馈);
        // 对齐 CardGroup 的既有方向(背景用纯 surface、取消阴影)。
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title.ifBlank { item.content },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.pinnedAt != null) Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
            }
            if (item.title != item.content) {
                Text(item.content, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.category?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                item.time?.takeIf { it.isNotBlank() }?.let { Text(it.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
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
                    ) {

                        onImportance?.let {
                            MuseListItem(
                                onClick = { showMore = false; it() },
                                headlineContent = { Text(stringResource(R.string.memory_menu_importance)) },
                            )
                        }
                        onPin?.let {
                            MuseListItem(
                                onClick = { showMore = false; it() },
                                headlineContent = { Text(stringResource(if (item.pinnedAt != null) R.string.memory_menu_unpin else R.string.memory_menu_pin)) },
                            )
                        }
                        onEdit?.let {
                            MuseListItem(
                                onClick = { showMore = false; it() },
                                headlineContent = { Text(stringResource(R.string.memory_menu_edit)) },
                            )
                        }
                        onHistory?.let {
                            MuseListItem(
                                onClick = { showMore = false; it() },
                                headlineContent = { Text(stringResource(R.string.memory_menu_history)) },
                            )
                        }
                        onDelete?.let {
                            MuseListItem(
                                onClick = { showMore = false; it() },
                                headlineContent = { Text(stringResource(R.string.memory_menu_delete), color = MaterialTheme.colorScheme.error) },
                            )
                        }
                    
                    }
                }
            }
        }
    }
}

// F-9: 来源会话 id 展示的最大长度(避免长 id 撑爆行布局)。
private const val MEMORY_FACT_ROW_SESSION_ID_MAX = 10

@Composable
private fun MemoryFilterRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
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

