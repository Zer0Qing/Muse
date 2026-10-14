@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.zer0.muse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberCoroutineScope
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
import io.zer0.memory.fact.MemoryLegacyReset
import io.zer0.memory.space.MemorySpaceEntity
import io.zer0.memory.summary.MemoryDbArchiveRecovery
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseActionSheetRow
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.memory.MemoryGraphView
import io.zer0.muse.ui.memory.MemoryGraphViewModel
import io.zer0.muse.ui.memory.MemoryTimelineFilterRow
import io.zer0.muse.ui.memory.MonthHeader
import io.zer0.muse.ui.memory.PinnedMemorySection
import io.zer0.muse.ui.memory.TimelineEventCard
import io.zer0.muse.ui.memory.TimelineItem
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId

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
    onOpenSession: (String) -> Unit = {},
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
    val archiveRecovery: MemoryDbArchiveRecovery = koinInject()
    val archiveRecoveryScope = rememberCoroutineScope()
    var archiveInfo by remember { mutableStateOf<MemoryDbArchiveRecovery.ArchiveInfo?>(null) }
    var showArchiveRecoveryConfirm by remember { mutableStateOf(false) }
    var archiveRecoveryRunning by remember { mutableStateOf(false) }

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
    // v2.2.1: 记忆库归档/恢复提示 — 版本守卫缺陷曾把真库误归档;修复后带回一次性恢复结果
    LaunchedEffect(Unit) {
        val recovered = MemoryLegacyReset.consumeRecovered(context)
        val hadArchive = MemoryLegacyReset.consume(context)
        when {
            recovered != null && recovered > 0 ->
                MuseToast.show(context.getString(R.string.memory_archive_recovered, recovered))
            hadArchive -> MuseToast.show(context.getString(R.string.memory_archive_rebuilt_hint))
        }
        archiveInfo = archiveRecovery.availableArchives()
            .maxByOrNull { it.storedVersion }
    }
    var tab by remember { mutableIntStateOf(0) } // 0=记忆流 1=事实库 2=星座
    var query by remember { mutableStateOf("") }
    var editItem by remember { mutableStateOf<MemoryItem?>(null) }
    var showAddFact by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    // P1-3: 记忆流视图模式(false=列表, true=时间轴)
    var streamTimelineMode by remember { mutableStateOf(false) }
    // P1-3: 时间轴内部筛选(全部/事实/摘要/里程碑)
    var timelineFilter by remember { mutableStateOf("all") }
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
                        icon = MuseIcons.brain,
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
                modifier =
                Modifier
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
                        scopeLabel =
                        scopes.firstOrNull { it.id == selectedScope }?.displayName
                            ?: stringResource(R.string.memory_center_scope_all),
                        spaceLabel =
                        spaces.firstOrNull { it.id == selectedSpace }?.name
                            ?: stringResource(R.string.memory_center_space_default),
                        onOrganize = viewModel::organizeMemory,
                        onOpenFilter = { showFilter = true },
                    )
                }
                archiveInfo?.let { info ->
                    item(key = "memory_archive_recovery") {
                        MemoryArchiveRecoveryCard(
                            info = info,
                            running = archiveRecoveryRunning,
                            onRestore = { showArchiveRecoveryConfirm = true },
                        )
                    }
                }
                item(key = "memory_tabs") {
                    MemoryCapsuleTabs(selected = tab, onSelect = { tab = it })
                }
                val memoryError = state.errorTrace
                if (memoryError != null) {
                    item(key = "memory_error") {
                        MuseErrorStateBox(
                            message =
                            memoryError.lineSequence().firstOrNull { it.isNotBlank() }?.take(240)
                                ?: stringResource(R.string.memory_graph_load_failed),
                            onRetry = { viewModel.loadAll() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                        )
                    }
                } else {
                    when (tab) {
                        0 ->
                            memoryStreamItems(
                                state = state,
                                scopes = scopes,
                                spaces = spaces,
                                timelineMode = streamTimelineMode,
                                timelineFilter = timelineFilter,
                                onTimelineFilter = { timelineFilter = it },
                                onToggleTimeline = { streamTimelineMode = !streamTimelineMode },
                                onOpenFacts = { tab = 1 },
                                onOpenSession = onOpenSession,
                                onHistory = {
                                    viewModel.loadFactRevisions(it.id, it.scope)
                                    revisionTarget = it
                                },
                                onEdit = { editItem = it },
                                onDelete = { deleteTarget = it },
                                onPin = { viewModel.toggleFactPinned(it.id, it.scope) },
                                onImportance = { importanceItem = it },
                            )
                        1 ->
                            memoryFactsItems(
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
                        else ->
                            item(key = "memory_constellation") {
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
            title = stringResource(
                if (item.source == "Summary") {
                    R.string.memory_screen_edit_summary
                } else {
                    R.string.memory_screen_edit_fact
                },
            ),
            initialContent = item.content,
            onDismiss = { editItem = null },
            onConfirm = { content, onSaved ->
                routeMemoryItemAction(
                    item = item,
                    onFact = { factId, scope -> viewModel.editFact(factId, content, scope, onSaved) },
                    onSummary = { sessionId -> viewModel.editSummary(sessionId, content, onSaved) },
                    onUnsupported = { onSaved(false) },
                )
            },
        )
    }
    if (showAddFact) {
        AddFactDialog(
            onDismiss = { showAddFact = false },
            onConfirm = viewModel::addFact,
        )
    }
    if (showArchiveRecoveryConfirm) {
        MuseDialog(
            onDismissRequest = { if (!archiveRecoveryRunning) showArchiveRecoveryConfirm = false },
            title = stringResource(R.string.memory_archive_recovery_confirm_title),
            content = {
                Text(stringResource(R.string.memory_archive_recovery_confirm))
            },
            confirmText = stringResource(R.string.memory_archive_recovery_action),
            onConfirm = {
                showArchiveRecoveryConfirm = false
                archiveRecoveryRunning = true
                archiveRecoveryScope.launch {
                    val result = archiveRecovery.restoreLatestIfEmpty()
                    result.onSuccess { report ->
                        archiveInfo = null
                        viewModel.loadAll(silent = true)
                        MuseToast.show(
                            context.getString(
                                R.string.memory_archive_recovery_done,
                                report.totalRows,
                            ),
                        )
                    }.onFailure { error ->
                        MuseToast.show(
                            context.getString(
                                R.string.memory_archive_recovery_failed,
                                error.message ?: "",
                            ),
                        )
                    }
                    archiveRecoveryRunning = false
                }
            },
            dismissText = stringResource(R.string.memory_screen_cancel),
            onDismiss = { if (!archiveRecoveryRunning) showArchiveRecoveryConfirm = false },
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
                routeMemoryItemAction(
                    item = item,
                    onFact = viewModel::deleteFact,
                    onSummary = viewModel::deleteSummary,
                    onUnsupported = {},
                )
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
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { advancedFilterExpanded = !advancedFilterExpanded }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.memory_center_filter_advanced),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        // LAYOUT-01: 已取宽的折叠标题补限行，剩余宽度极小时不再逐字换行
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (advancedFilterExpanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                        contentDescription = stringResource(R.string.memory_center_filter_advanced),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                AnimatedVisibility(
                    visible = advancedFilterExpanded,
                    // v2.x: 补令牌 spec(原为默认 spec)
                    enter =
                    fadeIn(animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS)) +
                        expandVertically(
                            animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS),
                        ),
                    exit =
                    fadeOut(animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS)) +
                        shrinkVertically(
                            animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS),
                        ),
                ) {
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
                                onClick = {
                                    viewModel.selectScope(option.id)
                                    showFilter = false
                                },
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
                                onClick = {
                                    viewModel.selectSpace(space.id)
                                    showFilter = false
                                },
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
                    onClick = {
                        viewModel.selectImportanceFilter(null)
                        showFilter = false
                    },
                )
                listOf(
                    0 to R.string.memory_importance_normal,
                    1 to R.string.memory_importance_important,
                    2 to R.string.memory_importance_critical,
                ).forEach {
                        (level, labelRes) ->
                    MemoryFilterRow(
                        label = stringResource(labelRes),
                        selected = importanceFilter == level,
                        onClick = {
                            viewModel.selectImportanceFilter(level)
                            showFilter = false
                        },
                    )
                }
                // F-7: 时间范围筛选
                Text(
                    text = stringResource(R.string.memory_center_filter_time),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val timeOptions =
                    listOf(
                        null to R.string.memory_center_filter_time_all,
                        MemoryTimeRange.LAST_7 to R.string.memory_center_filter_time_7,
                        MemoryTimeRange.LAST_30 to R.string.memory_center_filter_time_30,
                        MemoryTimeRange.LAST_90 to R.string.memory_center_filter_time_90,
                    )
                timeOptions.forEach { (range, labelRes) ->
                    MemoryFilterRow(
                        label = stringResource(labelRes),
                        selected = (timeRangeFilter == null && range == null) || (timeRangeFilter != null && timeRangeFilter == range),
                        onClick = {
                            viewModel.selectTimeRangeFilter(range)
                            showFilter = false
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun MemoryCapsuleTabs(selected: Int, onSelect: (Int) -> Unit) {
    // P1-3: 恢复记忆星座 tab(真实星座图,经 MemoryGraphViewModel 加载)
    val tabs =
        listOf(
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
                // LAYOUT-01: 数字徽标只限行、不取宽（本行已有 Spacer(weight(1f))，
                // 再给数字加 weight 会与它平分空间、把右侧过滤入口挤走）
                Text(
                    text = factCount.toString(),
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                // LAYOUT-01: 说明文字取剩余宽度 — 与行尾 Spacer(weight(1f)) 分摊剩余空间，
                // 视觉上仍紧贴数字、过滤入口仍在最右；放大字号下不再逐字换行
                Text(
                    text = stringResource(R.string.memory_center_fact_count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
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
                text =
                when (stage) {
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

@Composable
private fun MemoryArchiveRecoveryCard(
    info: MemoryDbArchiveRecovery.ArchiveInfo,
    running: Boolean,
    onRestore: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen),
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.memory_archive_recovery_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = stringResource(
                    R.string.memory_archive_recovery_message,
                    info.sessionSummaries,
                    info.compiledSections + info.scopedCompiledSections,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.memory_archive_recovery_action),
                onClick = onRestore,
                enabled = !running,
                loading = running,
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
    scopes: List<ScopeOption>,
    spaces: List<MemorySpaceEntity>,
    timelineMode: Boolean,
    timelineFilter: String,
    onTimelineFilter: (String) -> Unit,
    onToggleTimeline: () -> Unit,
    onOpenFacts: () -> Unit,
    onOpenSession: (String) -> Unit,
    onHistory: (MemoryItem) -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onDelete: (MemoryItem) -> Unit,
    onPin: (MemoryItem) -> Unit,
    onImportance: (MemoryItem) -> Unit,
) {
    val items = buildMemoryStreamItems(state.factItems, state.summaryItems)
    // P1-3: 置顶区接线 — 置顶事实集中展示,可一键取消置顶
    val pinned = state.factItems.filter { it.pinnedAt != null }
    if (pinned.isNotEmpty()) {
        item(key = "memory_stream_pinned") {
            PinnedMemorySection(
                pinnedEntries =
                pinned.map { item ->
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
    // P1-3: 时间轴模式 — 按月分组的时间线视图。
    // v2.1.x 修复「点时间轴即崩溃」: 内容拍平进外层 LazyColumn(不再嵌套内层 LazyColumn,
    // 避免 "infinity maximum height constraints" 崩溃); 筛选项随之由外层状态承载。
    if (timelineMode) {
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
                    // v2.x: 动效补齐 — 时间轴拍平条目统一入场动画(与列表模式同规格)
                    Box(museAnimateItem()) {
                        TimelineEventCard(
                            item =
                            TimelineItem(
                                id = "${item.source}_${item.id}",
                                content = item.content,
                                source = item.source,
                                importance = item.importance,
                                createdAt = item.createdAt ?: item.time,
                                tags = item.tags,
                            ),
                        )
                    }
                }
            }
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
        // 审计修复: 同上 — 跨分库 id 会重复,key 补 scope。
        items(dayItems, key = { "stream_${it.source}_${it.scope.orEmpty()}_${it.id}" }) { item ->
            // v2.x: 动效补齐 — 记忆流列表条目入场/位移过渡
            Box(modifier = museAnimateItem().padding(horizontal = MusePaddings.screen)) {
                val sourceMeta = if (item.source == "Summary") {
                    val assistantName = scopes.firstOrNull { it.id == item.scope }?.displayName
                        ?: item.scope.orEmpty()
                    val spaceName = spaces.firstOrNull { it.id == item.spaceId }?.name
                        ?: item.spaceId.orEmpty()
                    stringResource(R.string.memory_conversation_meta, assistantName, spaceName)
                } else {
                    null
                }
                MemoryFactRow(
                    item = item,
                    sourceMeta = sourceMeta,
                    onOpenSession = if (item.source == "Summary") onOpenSession else null,
                    onEdit = { onEdit(item) },
                    onDelete = { onDelete(item) },
                    onHistory = if (item.source == "Fact") ({ onHistory(item) }) else null,
                    onPin = if (item.source == "Fact") ({ onPin(item) }) else null,
                    onImportance = if (item.source == "Fact") ({ onImportance(item) }) else null,
                )
            }
        }
    }
}

/** P1-3: 记忆流「列表 / 时间轴」切换行。 */
@Composable
private fun TimelineModeToggleRow(timelineMode: Boolean, onToggle: () -> Unit) {
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
                modifier =
                Modifier
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
            // LAYOUT-01: 分区标题取剩余宽度（右侧仅一个固定宽新增按钮），长标题不再逐字换行
            Text(
                text = stringResource(R.string.memory_center_library_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
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
                        text =
                        if (query.isBlank()) {
                            stringResource(R.string.memory_center_empty_subtitle)
                        } else {
                            stringResource(R.string.memory_screen_empty_content)
                        },
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
        // 审计修复: 同上 — 「全部」视图下主库与子助手分库的 fact.id 会撞,key 必须带 scope。
        items(items, key = { "lib_${it.scope.orEmpty()}_${it.id}" }) { item ->
            // v2.x: 动效补齐 — 事实库列表条目入场/位移过渡
            Box(modifier = museAnimateItem().padding(horizontal = MusePaddings.screen)) {
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
private fun MemoryConstellationTab(scope: String?, spaceId: String, factCount: Int, modifier: Modifier = Modifier) {
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
            modifier =
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(MuseShapes.extraLarge),
        ) {
            // v2.x: 动效补齐 — 加载/空态/图谱三态切换走 Crossfade(NORMAL_MS, 同 ChatScreen 规格)。
            // 切换记忆空间(spaceId)或作用域(scope)时星座重新加载,也会经此过渡淡入淡出。
            val constellationState =
                when {
                    graphState.isLoading -> 0
                    graphState.nodes.isEmpty() -> 1
                    else -> 2
                }
            Crossfade(
                targetState = constellationState,
                animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS),
                label = "memoryConstellationContent",
                modifier = Modifier.fillMaxSize(),
            ) { kind ->
                when (kind) {
                    0 ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            io.zer0.muse.ui.common.state.MuseLoadingState()
                        }
                    1 -> {
                        // v2.0: 空态改为贴顶居中 — 星座容器高 560dp 起,超过首屏高度时
                        // 垂直居中点会落在屏幕外,文案被底部截断(实测第二行不可见)。
                        Box(
                            Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 72.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Text(
                                text =
                                stringResource(
                                    if (factCount == 0) {
                                        R.string.memory_center_constellation_empty
                                    } else {
                                        R.string.memory_center_filter_empty
                                    },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    else -> MemoryGraphView(state = graphState, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

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
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
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
                    text = stringResource(if (expanded) R.string.memory_screen_collapse else R.string.memory_screen_expand),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                    Modifier
                        .clickable { toggleExpand() }
                        .padding(vertical = 2.dp),
                )
            }
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
                                text = stringResource(R.string.memory_menu_edit),
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
                                text = stringResource(R.string.memory_menu_delete),
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

/** v2.2.1: 记忆条目"可展开"判定阈值(字符数);超过或含换行即提供全文展开。 */
private const val EXPAND_TEXT_THRESHOLD = 80

@Composable
private fun MemoryFilterRow(label: String, selected: Boolean, onClick: () -> Unit) {
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
