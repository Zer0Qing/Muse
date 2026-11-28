@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")

package io.zer0.muse.ui.memory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private const val TAB_MEMORIES = 0
private const val TAB_CONSTELLATION = 1
private const val TAB_GROUP_CHAT = 2

private const val SUBVIEW_ALL = "all"
private const val SUBVIEW_FACTS = "fact"
private const val SUBVIEW_SUMMARIES = "summary"
private const val SUBVIEW_TIMELINE = "timeline"
private const val SUBVIEW_EXPERIENCE = "experience"

/**
 * 记忆中心（v3.0 清晰分层版）：
 *
 *  - 顶部统一精简状态条 + 3 个清晰一级分段（记忆 / 星座 / 群聊）
 *  - 「记忆」页内含搜索框、5 档视图芯片（全部 / 事实 / 对话摘要 / 时间轴 / 经验库）与分类快速过滤
 *  - 「星座」页脱离 LazyColumn 独占全部剩余视口，消除手势冲突并支持全屏星图模式
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
    val importanceFilter by viewModel.importanceFilter.collectAsStateWithLifecycle()
    val timeRangeFilter by viewModel.timeRangeFilter.collectAsStateWithLifecycle()
    val organizing by viewModel.organizeRunning.collectAsStateWithLifecycle()
    val organizeStage by viewModel.organizeStage.collectAsStateWithLifecycle()
    val organizeResult by viewModel.organizeResult.collectAsStateWithLifecycle()
    val dreamStatus by viewModel.dreamStatus.collectAsStateWithLifecycle()
    val dreamRunning by viewModel.dreamRunning.collectAsStateWithLifecycle()
    val dreamResult by viewModel.dreamResult.collectAsStateWithLifecycle()
    val dreamServiceAvailable = remember { viewModel.hasDreamService() }
    val mergeDraft by viewModel.mergeDraft.collectAsStateWithLifecycle()
    val mergeRunning by viewModel.mergeRunning.collectAsStateWithLifecycle()
    val mergeError by viewModel.mergeError.collectAsStateWithLifecycle()

    val archiveRecovery: MemoryDbArchiveRecovery = koinInject()
    val archiveRecoveryScope = rememberCoroutineScope()
    var archiveInfo by remember { mutableStateOf<MemoryDbArchiveRecovery.ArchiveInfo?>(null) }
    var showArchiveRecoveryConfirm by remember { mutableStateOf(false) }
    var archiveRecoveryRunning by remember { mutableStateOf(false) }

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
    LaunchedEffect(Unit) {
        val recovered = MemoryLegacyReset.consumeRecovered(context)
        val hadArchive = MemoryLegacyReset.consume(context)
        when {
            recovered != null && recovered > 0 ->
                MuseToast.show(context.getString(R.string.memory_archive_recovered, recovered))
            hadArchive -> MuseToast.show(context.getString(R.string.memory_archive_rebuilt_hint))
        }
        archiveInfo = archiveRecovery.availableArchives().maxByOrNull { it.storedVersion }
    }

    var tab by rememberSaveable { mutableIntStateOf(TAB_MEMORIES) }
    var subView by rememberSaveable { mutableStateOf(SUBVIEW_ALL) }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var timelineFilter by rememberSaveable { mutableStateOf("all") }
    var constellationFullscreen by remember { mutableStateOf(false) }

    var query by remember { mutableStateOf("") }
    var editItem by remember { mutableStateOf<MemoryItem?>(null) }
    var showAddFact by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var showMaintenance by remember { mutableStateOf(false) }
    var importanceItem by remember { mutableStateOf<MemoryItem?>(null) }
    var deleteTarget by remember { mutableStateOf<MemoryItem?>(null) }
    var timelineActionTarget by remember { mutableStateOf<TimelineItem?>(null) }
    var pendingBulkDelete by remember { mutableStateOf<List<MemoryItem>?>(null) }

    var selectionMode by remember { mutableStateOf(false) }
    val selectedFactKeys = remember { androidx.compose.runtime.mutableStateListOf<String>() }

    fun factKeyOf(item: MemoryItem): String = "${item.scope.orEmpty()}|${item.id}"

    fun toggleFactSelection(item: MemoryItem) {
        val key = factKeyOf(item)
        if (selectedFactKeys.contains(key)) selectedFactKeys.remove(key) else selectedFactKeys.add(key)
    }

    fun exitSelection() {
        selectionMode = false
        selectedFactKeys.clear()
    }

    var mergeConfirmPending by remember { mutableStateOf(false) }
    var experienceTarget by remember { mutableStateOf<MemoryItem?>(null) }
    var showAddExperience by remember { mutableStateOf(false) }
    var groupChatMemoryTarget by remember { mutableStateOf<GroupChatMemoryUiItem?>(null) }
    var showClearGroupChatConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(tab) {
        if (tab == TAB_GROUP_CHAT) {
            viewModel.loadGroupChatMemories()
        }
        if (tab != TAB_CONSTELLATION && constellationFullscreen) {
            constellationFullscreen = false
        }
    }

    LaunchedEffect(query) {
        delay(300)
        viewModel.search(query)
    }

    LaunchedEffect(organizeResult) {
        val result = organizeResult ?: return@LaunchedEffect
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

    LaunchedEffect(dreamResult) {
        val result = dreamResult ?: return@LaunchedEffect
        when {
            result == "done" -> MuseToast.show(
                "${context.getString(R.string.memory_dream_title)} · ${context.getString(R.string.common_done)}",
            )
            result == "restored" -> MuseToast.show(
                "${context.getString(R.string.memory_dream_rollback)} · ${context.getString(R.string.common_done)}",
            )
            result.startsWith("rejected:") -> {
                val reason = result.removePrefix("rejected:")
                MuseToast.show(context.getString(R.string.memory_dream_skipped, reason))
            }
        }
        viewModel.consumeDreamResult()
    }

    LaunchedEffect(mergeError) {
        mergeError?.let {
            mergeConfirmPending = false
            MuseToast.show(it)
            viewModel.consumeMergeError()
        }
    }

    LaunchedEffect(mergeDraft) {
        if (mergeDraft == null && mergeConfirmPending) {
            mergeConfirmPending = false
            exitSelection()
        }
    }

    val listState = rememberLazyListState()
    val uiScope = rememberCoroutineScope()
    val activeFilterCount = listOfNotNull(
        selectedScope,
        selectedSpace.takeIf { it != "default" },
        importanceFilter,
        timeRangeFilter?.takeIf { it != MemoryTimeRange.ALL },
    ).size
    val pinnedItems = remember(state.factItems) { state.factItems.filter { it.pinnedAt != null } }
    val emptySearchText = stringResource(R.string.memory_screen_empty_content)

    MusePageScaffold(
        topBar = {
            if (!(constellationFullscreen && tab == TAB_CONSTELLATION)) {
                MuseTopBar(
                    title = stringResource(R.string.memory_screen_title),
                    onBack = onBack,
                    largeTitle = false,
                    actions = {
                        if (selectionMode) {
                            MemorySelectionActions(
                                selectedCount = selectedFactKeys.size,
                                mergeRunning = mergeRunning,
                                onCancel = { exitSelection() },
                                onMerge = {
                                    val picked = (state.factItems + state.searchResults)
                                        .distinctBy { factKeyOf(it) }
                                        .filter { factKeyOf(it) in selectedFactKeys }
                                    viewModel.prepareMerge(picked)
                                },
                                onDelete = {
                                    val picked = (state.factItems + state.searchResults)
                                        .distinctBy { factKeyOf(it) }
                                        .filter { factKeyOf(it) in selectedFactKeys }
                                    if (picked.isNotEmpty()) pendingBulkDelete = picked
                                },
                            )
                        } else {
                            MuseTactileButton(
                                icon = MuseIcons.plus,
                                onClick = {
                                    if (subView == SUBVIEW_EXPERIENCE) {
                                        showAddExperience = true
                                    } else {
                                        showAddFact = true
                                    }
                                },
                                contentDescription = stringResource(R.string.memory_hub_add_cd),
                            )
                            MuseTactileButton(
                                icon = MuseIcons.wand,
                                onClick = { showMaintenance = true },
                                contentDescription = stringResource(R.string.memory_hub_maintenance_title),
                                tint = if (organizing || dreamRunning || archiveInfo != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            MuseTactileButton(
                                icon = MuseIcons.sliders,
                                onClick = onOpenSettings,
                                contentDescription = stringResource(R.string.settings_memory_page_title),
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (widthClass == WindowWidthClass.Expanded && !(constellationFullscreen && tab == TAB_CONSTELLATION)) {
                            Modifier.widthIn(max = 820.dp)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                AnimatedVisibility(visible = !(constellationFullscreen && tab == TAB_CONSTELLATION)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        MemoryCompactHeaderStrip(
                            factCount = state.factCount,
                            pinnedCount = pinnedItems.size,
                            conflictCount = state.contradictions.size,
                            activeFilterCount = activeFilterCount,
                            onOpenFilter = { showFilter = true },
                            onConflictClick = {
                                tab = TAB_MEMORIES
                                subView = SUBVIEW_ALL
                                query = ""
                                uiScope.launch { listState.animateScrollToItem(0) }
                            },
                        )
                        MemoryCapsuleTabs(
                            selected = tab,
                            onSelect = { next ->
                                tab = next
                                if (selectionMode && next != TAB_MEMORIES) exitSelection()
                                viewModel.clearErrorTrace()
                            },
                        )
                        val scopeChipLabel = selectedScope?.let { s ->
                            scopes.firstOrNull { it.id == s }?.displayName ?: s
                        }
                        val defaultSpaceName = stringResource(R.string.memory_space_default_name)
                        val spaceChipLabel = selectedSpace.takeIf { it != "default" }?.let { sid ->
                            spaces.firstOrNull { it.id == sid }?.name?.let { memorySpaceDisplayName(it, defaultSpaceName) }
                                ?: sid
                        }
                        if (tab != TAB_GROUP_CHAT) {
                            MemoryActiveFiltersRow(
                                scopeLabel = scopeChipLabel,
                                spaceLabel = spaceChipLabel,
                                importance = importanceFilter,
                                timeRange = timeRangeFilter,
                                onClearScope = { viewModel.selectScope(null) },
                                onClearSpace = { viewModel.selectSpace("default") },
                                onClearImportance = { viewModel.selectImportanceFilter(null) },
                                onClearTimeRange = { viewModel.selectTimeRangeFilter(null) },
                                onClearAll = {
                                    viewModel.selectScope(null)
                                    viewModel.selectSpace("default")
                                    viewModel.selectImportanceFilter(null)
                                    viewModel.selectTimeRangeFilter(null)
                                },
                            )
                        }
                    }
                }

                val memoryError = state.errorTrace
                if (memoryError != null && tab != TAB_CONSTELLATION) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(MusePaddings.screen),
                    ) {
                        MuseErrorStateBox(
                            message = memoryError.lineSequence().firstOrNull { it.isNotBlank() }?.take(240)
                                ?: stringResource(R.string.memory_graph_load_failed),
                            onRetry = { viewModel.loadAll() },
                            onDismiss = { viewModel.clearErrorTrace() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    when (tab) {
                        TAB_MEMORIES -> Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    bottom = if (selectionMode) 92.dp else MuseSpacing.xxxl,
                                ),
                                verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                            ) {
                                item(key = "memory_search") {
                                    MemorySearchBar(
                                        query = query,
                                        onQueryChange = { query = it },
                                        enabled = !state.isLoading,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.xs),
                                    )
                                }

                                item(key = "memory_subviews") {
                                    MemorySubViewStrip(
                                        selected = subView,
                                        onSelect = {
                                            subView = it
                                            if (selectionMode && it != SUBVIEW_ALL && it != SUBVIEW_FACTS && it != SUBVIEW_TIMELINE) {
                                                exitSelection()
                                            }
                                        },
                                    )
                                }

                                if (query.isBlank() && (subView == SUBVIEW_ALL || subView == SUBVIEW_FACTS)) {
                                    item(key = "memory_category_chips") {
                                        MemoryCategoryFilterRow(
                                            selectedCategory = selectedCategory,
                                            onSelect = { selectedCategory = it },
                                        )
                                    }
                                }

                                if (state.contradictions.isNotEmpty() && query.isBlank()) {
                                    item(key = "memory_contradictions") {
                                        MemoryContradictionsCard(
                                            contradictions = state.contradictions,
                                            onResolveConflict = { conflict, keepId ->
                                                viewModel.resolveConflict(keepId, conflict.scope)
                                            },
                                            onDismissConflict = viewModel::dismissConflict,
                                        )
                                    }
                                }

                                if (query.isNotBlank()) {
                                    memorySearchOrFilteredList(
                                        items = state.searchResults,
                                        isLoading = state.isLoading || state.isSearching,
                                        scopes = scopes,
                                        spaces = spaces,
                                        emptyText = emptySearchText,
                                        onAdd = { showAddFact = true },
                                        onOpenSession = onOpenSession,
                                        onEdit = { editItem = it },
                                        onDelete = { deleteTarget = it },
                                        onHistory = {
                                            viewModel.loadFactRevisions(it.id, it.scope)
                                            revisionTarget = it
                                        },
                                        onPin = { viewModel.toggleFactPinned(it.id, it.scope) },
                                        onImportance = { importanceItem = it },
                                        selectionMode = selectionMode,
                                        isSelected = { factKeyOf(it) in selectedFactKeys },
                                        onLongPress = {
                                            selectionMode = true
                                            toggleFactSelection(it)
                                        },
                                        onToggleSelect = { toggleFactSelection(it) },
                                    )
                                } else {
                                    when (subView) {
                                        SUBVIEW_ALL -> {
                                            if (state.compiledMarkdown.isNotBlank()) {
                                                item(key = "memory_ai_understanding") {
                                                    Box(modifier = Modifier.padding(horizontal = MusePaddings.screen)) {
                                                        MemorySummaryCard(markdown = state.compiledMarkdown)
                                                    }
                                                }
                                            }
                                            if (pinnedItems.isNotEmpty()) {
                                                item(key = "memory_pinned_section") {
                                                    PinnedMemorySection(
                                                        pinnedEntries = pinnedItems.map { item ->
                                                            io.zer0.memory.pin.PinnedMemoryStore.PinnedEntry(
                                                                id = item.id,
                                                                content = item.content,
                                                                createdAt = item.createdAt ?: item.time.orEmpty(),
                                                                updatedAt = item.pinnedAt ?: item.createdAt ?: item.time.orEmpty(),
                                                            )
                                                        },
                                                        onUnpin = { id ->
                                                            pinnedItems.firstOrNull { it.id == id }
                                                                ?.let { viewModel.toggleFactPinned(it.id, it.scope) }
                                                        },
                                                        onDelete = { id ->
                                                            pinnedItems.firstOrNull { it.id == id }
                                                                ?.let { deleteTarget = it }
                                                        },
                                                        onDedup = { viewModel.dedupPinnedExact() },
                                                    )
                                                }
                                            }
                                            val allItems = filterByCategory(
                                                buildMemoryStreamItems(state.factItems, state.summaryItems),
                                                selectedCategory,
                                            )
                                            memoryDateGroupedList(
                                                items = allItems,
                                                isLoading = state.isLoading,
                                                scopes = scopes,
                                                spaces = spaces,
                                                keyPrefix = "all",
                                                onAdd = { showAddFact = true },
                                                onOpenSession = onOpenSession,
                                                onEdit = { editItem = it },
                                                onDelete = { deleteTarget = it },
                                                onHistory = {
                                                    viewModel.loadFactRevisions(it.id, it.scope)
                                                    revisionTarget = it
                                                },
                                                onPin = { viewModel.toggleFactPinned(it.id, it.scope) },
                                                onImportance = { importanceItem = it },
                                                selectionMode = selectionMode,
                                                isSelected = { factKeyOf(it) in selectedFactKeys },
                                                onLongPress = {
                                                    selectionMode = true
                                                    toggleFactSelection(it)
                                                },
                                                onToggleSelect = { toggleFactSelection(it) },
                                            )
                                        }
                                        SUBVIEW_FACTS -> {
                                            if (pinnedItems.isNotEmpty()) {
                                                item(key = "facts_pinned_section") {
                                                    PinnedMemorySection(
                                                        pinnedEntries = pinnedItems.map { item ->
                                                            io.zer0.memory.pin.PinnedMemoryStore.PinnedEntry(
                                                                id = item.id,
                                                                content = item.content,
                                                                createdAt = item.createdAt ?: item.time.orEmpty(),
                                                                updatedAt = item.pinnedAt ?: item.createdAt ?: item.time.orEmpty(),
                                                            )
                                                        },
                                                        onUnpin = { id ->
                                                            pinnedItems.firstOrNull { it.id == id }
                                                                ?.let { viewModel.toggleFactPinned(it.id, it.scope) }
                                                        },
                                                        onDelete = { id ->
                                                            pinnedItems.firstOrNull { it.id == id }
                                                                ?.let { deleteTarget = it }
                                                        },
                                                        onDedup = { viewModel.dedupPinnedExact() },
                                                    )
                                                }
                                            }
                                            val facts = filterByCategory(state.factItems, selectedCategory)
                                            memoryDateGroupedList(
                                                items = facts,
                                                isLoading = state.isLoading,
                                                scopes = scopes,
                                                spaces = spaces,
                                                keyPrefix = "facts",
                                                onAdd = { showAddFact = true },
                                                onOpenSession = onOpenSession,
                                                onEdit = { editItem = it },
                                                onDelete = { deleteTarget = it },
                                                onHistory = {
                                                    viewModel.loadFactRevisions(it.id, it.scope)
                                                    revisionTarget = it
                                                },
                                                onPin = { viewModel.toggleFactPinned(it.id, it.scope) },
                                                onImportance = { importanceItem = it },
                                                selectionMode = selectionMode,
                                                isSelected = { factKeyOf(it) in selectedFactKeys },
                                                onLongPress = {
                                                    selectionMode = true
                                                    toggleFactSelection(it)
                                                },
                                                onToggleSelect = { toggleFactSelection(it) },
                                            )
                                        }
                                        SUBVIEW_SUMMARIES -> {
                                            memoryDateGroupedList(
                                                items = state.summaryItems,
                                                isLoading = state.isLoading,
                                                scopes = scopes,
                                                spaces = spaces,
                                                keyPrefix = "summary",
                                                onAdd = null,
                                                onOpenSession = onOpenSession,
                                                onEdit = { editItem = it },
                                                onDelete = { deleteTarget = it },
                                                onHistory = {},
                                                onPin = {},
                                                onImportance = {},
                                                selectionMode = false,
                                                isSelected = { false },
                                                onLongPress = {},
                                                onToggleSelect = {},
                                            )
                                        }
                                        SUBVIEW_TIMELINE -> {
                                            val items = buildMemoryStreamItems(state.factItems, state.summaryItems)
                                            if (state.isLoading) {
                                                item(key = "timeline_loading") {
                                                    Box(
                                                        Modifier.fillMaxWidth().heightIn(min = 280.dp),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        MuseLoadingState()
                                                    }
                                                }
                                            } else if (items.isEmpty()) {
                                                item(key = "timeline_empty") {
                                                    Box(
                                                        Modifier.fillMaxWidth().heightIn(min = 280.dp),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        MuseEmptyState(
                                                            title = stringResource(R.string.memory_center_empty_title),
                                                            subtitle = stringResource(R.string.memory_center_empty_subtitle),
                                                            actionText = stringResource(R.string.memory_add_fact),
                                                            onAction = { showAddFact = true },
                                                        )
                                                    }
                                                }
                                            } else {
                                                memoryStreamTimeline(
                                                    items = items,
                                                    timelineFilter = timelineFilter,
                                                    onTimelineFilter = { timelineFilter = it },
                                                    onToggleTimeline = { subView = SUBVIEW_ALL },
                                                    onOpenActions = { timelineActionTarget = it },
                                                    selection = MemorySelection(
                                                        active = selectionMode,
                                                        isSelected = { factKeyOf(it) in selectedFactKeys },
                                                        onLongPress = {
                                                            selectionMode = true
                                                            toggleFactSelection(it)
                                                        },
                                                        onToggle = { toggleFactSelection(it) },
                                                    ),
                                                )
                                            }
                                        }
                                        SUBVIEW_EXPERIENCE -> {
                                            item(key = "memory_experience_library") {
                                                Box(modifier = Modifier.padding(horizontal = MusePaddings.screen)) {
                                                    ExperienceLibraryCard(
                                                        items = state.experienceItems,
                                                        onAdd = { showAddExperience = true },
                                                        onEdit = { experienceTarget = it },
                                                        onDelete = { deleteTarget = it },
                                                        defaultExpanded = true,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (selectionMode) {
                                MemoryFloatingSelectionBar(
                                    selectedCount = selectedFactKeys.size,
                                    mergeRunning = mergeRunning,
                                    onMerge = {
                                        val picked = (state.factItems + state.searchResults)
                                            .distinctBy { factKeyOf(it) }
                                            .filter { factKeyOf(it) in selectedFactKeys }
                                        viewModel.prepareMerge(picked)
                                    },
                                    onDelete = {
                                        val picked = (state.factItems + state.searchResults)
                                            .distinctBy { factKeyOf(it) }
                                            .filter { factKeyOf(it) in selectedFactKeys }
                                        if (picked.isNotEmpty()) pendingBulkDelete = picked
                                    },
                                    onCancel = { exitSelection() },
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                )
                            }
                        }

                        TAB_CONSTELLATION -> MemoryConstellationTab(
                            scope = selectedScope,
                            spaceId = selectedSpace,
                            factCount = state.factCount,
                            isFullscreen = constellationFullscreen,
                            onToggleFullscreen = { constellationFullscreen = !constellationFullscreen },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )

                        TAB_GROUP_CHAT -> LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(bottom = MuseSpacing.xxxl),
                            verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                        ) {
                            groupChatMemoryItems(
                                memories = state.groupChatMemories,
                                isLoading = state.groupChatMemoriesLoading,
                                onDelete = { groupChatMemoryTarget = it },
                                onClearAll = { showClearGroupChatConfirm = true },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearGroupChatConfirm) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.memory_group_chat_clear_confirm_title),
            itemName = stringResource(R.string.memory_group_chat_clear_item_name),
            consequence = stringResource(R.string.memory_group_chat_clear_consequence),
            onConfirm = {
                showClearGroupChatConfirm = false
                viewModel.clearAllGroupChatMemories()
            },
            onDismiss = { showClearGroupChatConfirm = false },
        )
    }

    editItem?.let { item ->
        FactEditDialog(
            title = stringResource(
                if (item.source == "Summary") R.string.memory_screen_edit_summary else R.string.memory_screen_edit_fact,
            ),
            initialContent = item.content,
            statusNote = memoryStatusNote(item),
            onDismiss = { editItem = null },
            onConfirm = { content, onSaved ->
                routeMemoryItemAction(
                    item = item,
                    onFact = { factId, scope -> viewModel.editFact(factId, content, scope, onSaved) },
                    onSummary = { sessionId -> viewModel.editSummary(sessionId, content, onSaved) },
                    onCompile = { sectionKey ->
                        viewModel.editCompile(sectionKey, content)
                        onSaved(true)
                    },
                    onExperience = { onSaved(false) },
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

    if (showAddExperience) {
        ExperienceEditDialog(
            title = stringResource(R.string.memory_screen_experience_add_dialog_title),
            initialTitle = "",
            initialContent = "",
            initialCategory = "",
            initialTags = "",
            onDismiss = { showAddExperience = false },
            onConfirm = { newTitle, content, category, tags ->
                viewModel.addExperience(newTitle, content, category, tags)
                showAddExperience = false
            },
        )
    }

    experienceTarget?.let { item ->
        ExperienceEditDialog(
            title = stringResource(R.string.memory_screen_experience_edit_title),
            initialTitle = item.title,
            initialContent = item.content,
            initialCategory = item.category.orEmpty(),
            initialTags = item.tags.joinToString(", "),
            onDismiss = { experienceTarget = null },
            onConfirm = { newTitle, content, category, tags ->
                viewModel.editExperience(item.id, newTitle, content, category, tags)
                experienceTarget = null
            },
        )
    }

    groupChatMemoryTarget?.let { mem ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.memory_group_chat_delete_confirm_title),
            itemName = mem.groupChatName,
            consequence = stringResource(R.string.memory_group_chat_delete_consequence),
            onConfirm = {
                groupChatMemoryTarget = null
                viewModel.deleteGroupChatMemory(mem.id)
            },
            onDismiss = { groupChatMemoryTarget = null },
        )
    }

    if (showArchiveRecoveryConfirm) {
        MuseDialog(
            onDismissRequest = { if (!archiveRecoveryRunning) showArchiveRecoveryConfirm = false },
            title = stringResource(R.string.memory_archive_recovery_confirm_title),
            content = { Text(stringResource(R.string.memory_archive_recovery_confirm)) },
            confirmText = stringResource(R.string.memory_archive_recovery_action),
            onConfirm = {
                showArchiveRecoveryConfirm = false
                archiveRecoveryRunning = true
                archiveRecoveryScope.launch {
                    val result = archiveRecovery.restoreLatestIfEmpty()
                    result.onSuccess { report ->
                        archiveInfo = null
                        viewModel.loadAll(silent = true)
                        MuseToast.show(context.getString(R.string.memory_archive_recovery_done, report.totalRows))
                    }.onFailure { error ->
                        MuseToast.show(context.getString(R.string.memory_archive_recovery_failed, error.message ?: ""))
                    }
                    archiveRecoveryRunning = false
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { if (!archiveRecoveryRunning) showArchiveRecoveryConfirm = false },
        )
    }

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
                    onCompile = { sectionKey -> viewModel.deleteCompile(sectionKey) },
                    onExperience = { id -> viewModel.deleteExperience(id) },
                    onUnsupported = {},
                )
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    timelineActionTarget?.let { target ->
        TimelineItemActions(
            target = target,
            onSelectMultiple = if (target.source == "Fact") {
                { routable ->
                    timelineActionTarget = null
                    selectionMode = true
                    toggleFactSelection(routable)
                }
            } else {
                null
            },
            onDelete = { routable ->
                routeMemoryItemAction(
                    item = routable,
                    onFact = viewModel::deleteFact,
                    onSummary = viewModel::deleteSummary,
                    onCompile = { sectionKey -> viewModel.deleteCompile(sectionKey) },
                    onExperience = { id -> viewModel.deleteExperience(id) },
                    onUnsupported = {},
                )
                timelineActionTarget = null
            },
            onDismiss = { timelineActionTarget = null },
        )
    }

    pendingBulkDelete?.let { targets ->
        MemoryBulkDeleteDialog(
            count = targets.size,
            onConfirm = {
                pendingBulkDelete = null
                viewModel.deleteFacts(targets)
                exitSelection()
            },
            onDismiss = { pendingBulkDelete = null },
        )
    }

    mergeDraft?.let { draft ->
        MemoryMergeDialog(
            draft = draft,
            running = mergeRunning,
            onCancel = {
                mergeConfirmPending = false
                viewModel.cancelMerge()
            },
            onConfirm = { edited ->
                mergeConfirmPending = true
                viewModel.confirmMerge(edited)
            },
        )
    }

    if (showFilter) {
        MemoryMultiFilterSheet(
            scopes = scopes,
            spaces = spaces,
            selectedScope = selectedScope,
            selectedSpace = selectedSpace,
            importanceFilter = importanceFilter,
            timeRangeFilter = timeRangeFilter,
            onApply = { scopeVal, spaceVal, impVal, timeVal ->
                if (scopeVal != selectedScope) viewModel.selectScope(scopeVal)
                if (spaceVal != selectedSpace) viewModel.selectSpace(spaceVal)
                if (impVal != importanceFilter) viewModel.selectImportanceFilter(impVal)
                if (timeVal != timeRangeFilter) viewModel.selectTimeRangeFilter(timeVal)
            },
            onDismiss = { showFilter = false },
        )
    }

    if (showMaintenance) {
        MemoryMaintenanceSheet(
            organizing = organizing,
            organizeStage = organizeStage,
            dreamAvailable = dreamServiceAvailable,
            dreamRunning = dreamRunning,
            dreamLastRevisionId = dreamStatus?.lastRevisionId,
            dreamLastError = dreamStatus?.lastError,
            archiveInfo = archiveInfo,
            archiveRunning = archiveRecoveryRunning,
            onOrganize = viewModel::organizeMemory,
            onStartDream = viewModel::startDream,
            onRestoreDream = { id -> viewModel.restoreDream(id) },
            onOpenArchiveConfirm = { showArchiveRecoveryConfirm = true },
            onDismiss = { showMaintenance = false },
        )
    }
}

@Composable
private fun MemoryCapsuleTabs(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf(
        stringResource(R.string.memory_hub_tab_memories),
        stringResource(R.string.memory_hub_tab_constellation),
        stringResource(R.string.memory_hub_tab_group),
    )
    MuseCapsuleTab(
        tabs = tabs,
        selectedIndex = selected,
        onSelect = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.sm),
    )
}

@Composable
private fun MemorySubViewStrip(selected: String, onSelect: (String) -> Unit) {
    val views = listOf(
        SUBVIEW_ALL to R.string.memory_hub_view_all,
        SUBVIEW_FACTS to R.string.memory_hub_view_facts,
        SUBVIEW_SUMMARIES to R.string.memory_hub_view_summaries,
        SUBVIEW_TIMELINE to R.string.memory_hub_view_timeline,
        SUBVIEW_EXPERIENCE to R.string.memory_hub_view_experience,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
    ) {
        views.forEach { (key, labelRes) ->
            MuseChip(
                selected = selected == key,
                onClick = { onSelect(key) },
                label = stringResource(labelRes),
            )
        }
    }
}

@Composable
private fun MemoryCategoryFilterRow(selectedCategory: String?, onSelect: (String?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
    ) {
        MuseChip(
            selected = selectedCategory == null,
            onClick = { onSelect(null) },
            label = stringResource(R.string.memory_hub_view_all),
        )
        MEMORY_STANDARD_CATEGORIES.forEach { cat ->
            MuseChip(
                selected = selectedCategory == cat,
                onClick = { onSelect(if (selectedCategory == cat) null else cat) },
                label = memoryCategoryLabel(cat),
            )
        }
    }
}

private fun filterByCategory(items: List<MemoryItem>, category: String?): List<MemoryItem> {
    if (category.isNullOrBlank()) return items
    val target = category.lowercase()
    return items.filter { (it.category?.lowercase() ?: "general") == target }
}

private fun LazyListScope.memorySearchOrFilteredList(
    items: List<MemoryItem>,
    isLoading: Boolean,
    scopes: List<ScopeOption>,
    spaces: List<MemorySpaceEntity>,
    emptyText: String,
    onAdd: () -> Unit,
    onOpenSession: (String) -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onDelete: (MemoryItem) -> Unit,
    onHistory: (MemoryItem) -> Unit,
    onPin: (MemoryItem) -> Unit,
    onImportance: (MemoryItem) -> Unit,
    selectionMode: Boolean,
    isSelected: (MemoryItem) -> Boolean,
    onLongPress: (MemoryItem) -> Unit,
    onToggleSelect: (MemoryItem) -> Unit,
) {
    if (isLoading) {
        item(key = "search_loading") {
            Box(Modifier.fillMaxWidth().heightIn(min = 240.dp), contentAlignment = Alignment.Center) {
                MuseLoadingState()
            }
        }
        return
    }
    if (items.isEmpty()) {
        item(key = "search_empty") {
            Box(Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(MuseSpacing.xxl), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MuseSpacing.md)) {
                    Text(
                        text = emptyText,
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
        return
    }
    memoryDateGroupedList(
        items = items,
        isLoading = false,
        scopes = scopes,
        spaces = spaces,
        keyPrefix = "search",
        onAdd = onAdd,
        onOpenSession = onOpenSession,
        onEdit = onEdit,
        onDelete = onDelete,
        onHistory = onHistory,
        onPin = onPin,
        onImportance = onImportance,
        selectionMode = selectionMode,
        isSelected = isSelected,
        onLongPress = onLongPress,
        onToggleSelect = onToggleSelect,
    )
}

private fun LazyListScope.memoryDateGroupedList(
    items: List<MemoryItem>,
    isLoading: Boolean,
    scopes: List<ScopeOption>,
    spaces: List<MemorySpaceEntity>,
    keyPrefix: String,
    onAdd: (() -> Unit)?,
    onOpenSession: (String) -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onDelete: (MemoryItem) -> Unit,
    onHistory: (MemoryItem) -> Unit,
    onPin: (MemoryItem) -> Unit,
    onImportance: (MemoryItem) -> Unit,
    selectionMode: Boolean,
    isSelected: (MemoryItem) -> Boolean,
    onLongPress: (MemoryItem) -> Unit,
    onToggleSelect: (MemoryItem) -> Unit,
) {
    if (isLoading) {
        item(key = "${keyPrefix}_loading") {
            Box(Modifier.fillMaxWidth().heightIn(min = 260.dp), contentAlignment = Alignment.Center) {
                MuseLoadingState()
            }
        }
        return
    }
    if (items.isEmpty()) {
        item(key = "${keyPrefix}_empty") {
            Box(Modifier.fillMaxWidth().heightIn(min = 260.dp), contentAlignment = Alignment.Center) {
                MuseEmptyState(
                    title = stringResource(R.string.memory_center_empty_title),
                    subtitle = stringResource(R.string.memory_center_empty_subtitle),
                    actionText = if (onAdd != null) stringResource(R.string.memory_add_fact) else null,
                    onAction = onAdd,
                )
            }
        }
        return
    }

    items.groupBy { (it.createdAt ?: it.time.orEmpty()).take(10) }.forEach { (day, dayItems) ->
        if (day.isNotBlank()) {
            item(key = "${keyPrefix}_day_$day") {
                Text(
                    text = day,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.xs),
                )
            }
        }
        items(dayItems, key = { "${keyPrefix}_${it.source}_${it.scope.orEmpty()}_${it.id}" }) { item ->
            Box(modifier = museAnimateItem().padding(horizontal = MusePaddings.screen)) {
                val defaultSpaceName = stringResource(R.string.memory_space_default_name)
                val sourceMeta = if (item.source == "Summary") {
                    val assistantName = scopes.firstOrNull { it.id == item.scope }?.displayName ?: item.scope.orEmpty()
                    val spaceName = memorySpaceDisplayName(
                        spaces.firstOrNull { it.id == item.spaceId }?.name ?: item.spaceId.orEmpty(),
                        defaultSpaceName,
                    )
                    stringResource(R.string.memory_conversation_meta, assistantName, spaceName)
                } else {
                    null
                }
                val isFact = item.source == "Fact"
                MemoryFactRow(
                    item = item,
                    sourceMeta = sourceMeta,
                    onOpenSession = if (item.sessionId != null) onOpenSession else null,
                    onEdit = { onEdit(item) },
                    onDelete = { onDelete(item) },
                    onHistory = if (isFact) ({ onHistory(item) }) else null,
                    onPin = if (isFact) ({ onPin(item) }) else null,
                    onImportance = if (isFact) ({ onImportance(item) }) else null,
                    selectionMode = selectionMode,
                    isSelected = isSelected(item),
                    onLongPress = if (isFact) ({ onLongPress(item) }) else null,
                    onToggleSelect = if (isFact) ({ onToggleSelect(item) }) else null,
                )
            }
        }
    }
}

private fun LazyListScope.groupChatMemoryItems(
    memories: List<GroupChatMemoryUiItem>,
    isLoading: Boolean,
    onDelete: (GroupChatMemoryUiItem) -> Unit,
    onClearAll: () -> Unit,
) {
    if (isLoading && memories.isEmpty()) {
        item(key = "group_chat_memory_loading") {
            Box(Modifier.fillMaxWidth().heightIn(min = 240.dp), contentAlignment = Alignment.Center) {
                MuseLoadingState()
            }
        }
        return
    }
    if (memories.isEmpty()) {
        item(key = "group_chat_memory_empty") {
            Box(Modifier.fillMaxWidth().heightIn(min = 240.dp), contentAlignment = Alignment.Center) {
                MuseEmptyState(
                    title = stringResource(R.string.memory_group_chat_empty_title),
                    subtitle = stringResource(R.string.memory_group_chat_empty_subtitle),
                )
            }
        }
        return
    }
    item(key = "group_chat_memory_header") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.smTight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.memory_group_chat_count, memories.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.memory_group_chat_clear_all),
                onClick = onClearAll,
                variant = IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
            )
        }
    }
    items(memories, key = { "gc_mem_${it.id}" }) { mem ->
        Box(
            modifier = museAnimateItem().padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.xs),
        ) {
            MuseSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = MuseShapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mem.groupChatName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(R.string.memory_group_chat_meta, mem.assistantName, mem.timeText),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        MuseTactileButton(
                            icon = MuseIcons.trash,
                            onClick = { onDelete(mem) },
                            contentDescription = stringResource(R.string.common_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        text = mem.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = MuseSpacing.smTight),
                    )
                }
            }
        }
    }
}

/** 记忆流「列表 / 时间轴」切换行（保留给外部调用或测试兼容）。 */
@Composable
internal fun TimelineModeToggleRow(timelineMode: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
    ) {
        listOf(
            false to stringResource(R.string.memory_stream_mode_list),
            true to stringResource(R.string.memory_stream_mode_timeline),
        ).forEach { (mode, label) ->
            MuseChip(
                selected = timelineMode == mode,
                onClick = { if (timelineMode != mode) onToggle() },
                label = label,
            )
        }
    }
}

/** 记忆星座全屏容器：脱离 LazyColumn 独占剩余视口，支持全文检视与连线确认/删除。 */
@Composable
private fun MemoryConstellationTab(
    scope: String?,
    spaceId: String,
    factCount: Int,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val graphViewModel: MemoryGraphViewModel = koinViewModel()
    val graphState by graphViewModel.state.collectAsStateWithLifecycle()
    val rebuildStartedText = stringResource(R.string.memory_graph_rebuild_started)
    val settings = koinInject<io.zer0.muse.data.SettingsRepository>()
    val graphConfig by settings.memoryGraphConfigFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.data.MemoryGraphConfig(),
    )
    var showGraphSettings by remember { mutableStateOf(false) }
    var graphEditTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val ioScope = rememberCoroutineScope()

    LaunchedEffect(scope, spaceId) { graphViewModel.load(scope, spaceId) }
    LaunchedEffect(scope, spaceId, graphConfig.edgeBuildFrequency) {
        if (graphConfig.edgeBuildFrequency == io.zer0.muse.data.EdgeBuildFrequency.ON_OPEN) {
            graphViewModel.rebuildEdges(scope, spaceId)
        }
    }

    val containerModifier = if (isFullscreen) {
        modifier
    } else {
        modifier.padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.xs)
    }

    Box(modifier = containerModifier) {
        when {
            graphState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MuseLoadingState()
            }
            graphState.nodes.isEmpty() -> Box(Modifier.fillMaxSize().padding(MuseSpacing.xl), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(
                        if (factCount == 0) R.string.memory_center_constellation_empty else R.string.memory_center_filter_empty,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            else -> MemoryGraphView(
                state = graphState,
                modifier = Modifier.fillMaxSize(),
                onNodeAction = { node, action ->
                    when (action) {
                        NodeAction.EDIT -> graphViewModel.loadFactContent(node.factId, scope) { full ->
                            graphEditTarget = full?.let { node.factId to it }
                        }
                        NodeAction.DELETE -> graphViewModel.deleteNode(node.factId, scope, spaceId)
                        NodeAction.PIN -> graphViewModel.togglePin(node.factId, scope, spaceId)
                        NodeAction.LINK -> Unit
                    }
                },
                onEdgeAction = { edge, action ->
                    when (action) {
                        EdgeAction.CONFIRM -> graphViewModel.confirmEdge(edge.id, scope, spaceId)
                        EdgeAction.DELETE -> graphViewModel.deleteEdge(edge.id, scope, spaceId)
                    }
                },
                onManualLink = { src, tgt, type ->
                    graphViewModel.addManualEdge(src.factId, tgt.factId, type, scope, spaceId)
                },
                onLoadFullContent = { factId, onLoaded ->
                    graphViewModel.loadFactContent(factId, scope, onLoaded)
                },
                onRebuildEdges = {
                    graphViewModel.rebuildEdges(scope, spaceId)
                    MuseToast.show(rebuildStartedText)
                },
                onOpenSettings = { showGraphSettings = true },
                isFullscreen = isFullscreen,
                onToggleFullscreen = onToggleFullscreen,
            )
        }
    }

    if (showGraphSettings) {
        MemoryGraphSettingsDialog(
            config = graphConfig,
            onDismiss = { showGraphSettings = false },
            onSave = { updated ->
                ioScope.launch { settings.saveMemoryGraphConfig(updated) }
                showGraphSettings = false
            },
            onRebuildNow = {
                graphViewModel.rebuildEdges(scope, spaceId)
                showGraphSettings = false
                MuseToast.show(rebuildStartedText)
            },
        )
    }

    graphEditTarget?.let { (factId, content) ->
        FactEditDialog(
            title = stringResource(R.string.memory_screen_edit_fact),
            initialContent = content,
            onDismiss = { graphEditTarget = null },
            onConfirm = { text, onSaved ->
                graphViewModel.editNode(factId, text, scope, spaceId) { saved -> onSaved(saved) }
            },
        )
    }
}
