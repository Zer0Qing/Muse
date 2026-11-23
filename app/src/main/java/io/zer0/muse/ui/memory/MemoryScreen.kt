@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:Suppress("LongMethod")

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
    val organizeResult by viewModel.organizeResult.collectAsStateWithLifecycle()
    val dreamStatus by viewModel.dreamStatus.collectAsStateWithLifecycle()
    val dreamRunning by viewModel.dreamRunning.collectAsStateWithLifecycle()
    val dreamResult by viewModel.dreamResult.collectAsStateWithLifecycle()
    // 测试宿主可能没装配 Dream：没有服务就不显示入口，避免「点了没反应」。
    val dreamServiceAvailable = remember { viewModel.hasDreamService() }
    // 多选合并：草稿 / 执行中 / 错误都由 ViewModel 持有，界面只负责展示与收集
    val mergeDraft by viewModel.mergeDraft.collectAsStateWithLifecycle()
    val mergeRunning by viewModel.mergeRunning.collectAsStateWithLifecycle()
    val mergeError by viewModel.mergeError.collectAsStateWithLifecycle()
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

    // 时间轴卡片长按出来的动作面板目标。
    // 时间轴卡片直接删不了东西,长按先出面板(删除/多选),删除再走一次确认。
    var timelineActionTarget by remember { mutableStateOf<TimelineItem?>(null) }

    // 多选批量删除的二次确认目标;非 null 时弹确认框。
    // 批量删一次带走多行且不可撤销,点一下就直接落库没有任何抢救余地。
    var pendingBulkDelete by remember { mutableStateOf<List<MemoryItem>?>(null) }

    // v2.6.10: 多选删除。
    // 记忆会乱记、重复记，逐条删又每删一条就被刷回列表顶部、要再滑下来找下一条，
    // 所以长按进入多选，选完一次批量删。
    var selectionMode by remember { mutableStateOf(false) }
    val selectedFactKeys = remember { androidx.compose.runtime.mutableStateListOf<String>() }

    /** 选中项的稳定标识：主库与各子助手分库的 fact.id 会撞，必须带上 scope。 */
    fun factKeyOf(item: MemoryItem): String = "${item.scope.orEmpty()}|${item.id}"

    fun toggleFactSelection(item: MemoryItem) {
        val key = factKeyOf(item)
        if (selectedFactKeys.contains(key)) selectedFactKeys.remove(key) else selectedFactKeys.add(key)
    }

    fun exitSelection() {
        selectionMode = false
        selectedFactKeys.clear()
    }

    /**
     * 合并确认已经发出。
     *
     * 成功时 ViewModel 把草稿清空，失败时保留草稿并给出 mergeError，
     * 所以只有「草稿消失 + 这面旗标为 true」才代表真的合并成功、该退出多选。
     */
    var mergeConfirmPending by remember { mutableStateOf(false) }

    /**
     * v2.6.7: 经验库新增/编辑弹窗目标。
     *
     * ExperienceLibraryCard 与 ExperienceEditDialog 早已写好但从未被引用，
     * 导致经验只能被 AI 写、用户看不了也改不了。这里把两个组件接进记忆流。
     */
    var experienceTarget by remember { mutableStateOf<MemoryItem?>(null) }

    var showAddExperience by remember { mutableStateOf(false) }

    /** v2.6.7: 群聊记忆单条删除的二次确认目标。 */
    var groupChatMemoryTarget by remember { mutableStateOf<GroupChatMemoryUiItem?>(null) }

    /** v2.6.7: 清空全部群聊记忆的二次确认。 */
    var showClearGroupChatConfirm by remember { mutableStateOf(false) }

    // v2.6.7: 切到「群聊记忆」tab 时加载（此前 loadGroupChatMemories 零调用）
    LaunchedEffect(tab) {
        if (tab == 3) {
            viewModel.loadGroupChatMemories()
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
            // 有错误就说明这次确认没落库（草稿会被保留供重试）：把 pending 复位，
            // 否则标记会停在「已发出确认」的中间态，下一次有东西把草稿清掉时跟着误退出多选。
            mergeConfirmPending = false
            MuseToast.show(it)
            viewModel.consumeMergeError()
        }
    }
    // 草稿消失有两个来源：确认成功（落库后清空）和用户取消。
    // 只有前者退出多选；取消要停在多选态，方便改选后再合并。
    LaunchedEffect(mergeDraft) {
        if (mergeDraft == null && mergeConfirmPending) {
            mergeConfirmPending = false
            exitSelection()
        }
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.memory_screen_title),
                onBack = onBack,
                largeTitle = true,
                actions = {
                    if (selectionMode) {
                        // 多选态：只保留「取消」「合并」「删除」，避免误触其他入口
                        MemorySelectionActions(
                            selectedCount = selectedFactKeys.size,
                            mergeRunning = mergeRunning,
                            onCancel = { exitSelection() },
                            onMerge = {
                                val picked =
                                    (state.factItems + state.searchResults)
                                        .distinctBy { factKeyOf(it) }
                                        .filter { factKeyOf(it) in selectedFactKeys }
                                viewModel.prepareMerge(picked)
                            },
                            onDelete = {
                                val picked =
                                    (state.factItems + state.searchResults)
                                        .distinctBy { factKeyOf(it) }
                                        .filter { factKeyOf(it) in selectedFactKeys }
                                // 先弹确认再落库;确认后仍在多选态里把选中清掉(见下方弹窗)
                                if (picked.isNotEmpty()) pendingBulkDelete = picked
                            },
                        )
                    } else {
                        MuseTactileButton(
                            icon = MuseIcons.brain,
                            onClick = onOpenSettings,
                            contentDescription = stringResource(R.string.settings_memory_page_title),
                        )
                    }
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
                            ?: stringResource(R.string.common_all),
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
                val dreamStatus = dreamStatus
                if (dreamServiceAvailable) {
                    item(key = "memory_dream") {
                        MemoryDreamCard(
                            running = dreamRunning,
                            lastRevisionId = dreamStatus?.lastRevisionId,
                            lastError = dreamStatus?.lastError,
                            onStart = viewModel::startDream,
                            onRestore = { id -> viewModel.restoreDream(id) },
                        )
                    }
                }
                item(key = "memory_tabs") {
                    MemoryCapsuleTabs(
                        selected = tab,
                        onSelect = { next ->
                            tab = next
                            // 2026-11-20(P2-25):错误只对产生它的那个标签有意义,切走就清掉,
                            // 否则上一个标签的错误会一直挡着新标签的列表与搜索框。
                            viewModel.clearErrorTrace()
                        },
                    )
                }
                val memoryError = state.errorTrace
                if (memoryError != null) {
                    item(key = "memory_error") {
                        MuseErrorStateBox(
                            message =
                            memoryError.lineSequence().firstOrNull { it.isNotBlank() }?.take(240)
                                ?: stringResource(R.string.memory_graph_load_failed),
                            onRetry = { viewModel.loadAll() },
                            // 可关闭:不必靠重新加载或退出整页才能恢复
                            onDismiss = { viewModel.clearErrorTrace() },
                            modifier = Modifier.fillMaxWidth(),
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
                                // 时间轴卡片长按:先出动作面板,删除再走二次确认
                                onTimelineActions = { timelineActionTarget = it },
                                onDedupPinned = { viewModel.dedupPinnedExact() },
                                selectionMode = selectionMode,
                                isSelected = { factKeyOf(it) in selectedFactKeys },
                                onLongPressItem = {
                                    selectionMode = true
                                    toggleFactSelection(it)
                                },
                                onToggleSelect = { toggleFactSelection(it) },
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
                                onDismissConflict = viewModel::dismissConflict,
                                onResolveConflict = { conflict, keepId ->
                                    viewModel.resolveConflict(keepId, conflict.scope)
                                },
                                selectionMode = selectionMode,
                                isSelected = { factKeyOf(it) in selectedFactKeys },
                                onLongPressItem = {
                                    selectionMode = true
                                    toggleFactSelection(it)
                                },
                                onToggleSelect = { toggleFactSelection(it) },
                            )
                        2 ->
                            item(key = "memory_constellation") {
                                MemoryConstellationTab(
                                    scope = selectedScope,
                                    spaceId = selectedSpace,
                                    factCount = state.factCount,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 560.dp),
                                )
                            }
                        // v2.6.7: 群聊记忆 tab（此前 ViewModel 已实现但从未有入口）
                        // 不用 else：将来再加 tab 会被 else 误接，这里显式写 3。
                        3 ->
                            groupChatMemoryItems(
                                memories = state.groupChatMemories,
                                isLoading = state.groupChatMemoriesLoading,
                                onDelete = { groupChatMemoryTarget = it },
                                onClearAll = { showClearGroupChatConfirm = true },
                            )
                    }
                    // v2.6.7: 接上两张早已写好、却从未被引用的卡。
                    // 放在 when 之后、而不是 memoryStreamItems 内部 ——
                    // 那个函数在加载态/空态会提前 return，放在里面会一并被跳过。
                    if (tab == 0) {
                        // “AI 对你的理解”：state.compiledMarkdown 早就读出来了，
                        // 此前从未显示过（引用次数 0），等于把注入给模型的记忆藏起来了。
                        if (state.compiledMarkdown.isNotBlank()) {
                            item(key = "memory_ai_understanding") {
                                MemorySummaryCard(markdown = state.compiledMarkdown)
                            }
                        }
                        // 经验库：CRUD + 两张卡片组件全都写好了，就是没接进来，
                        // 导致经验只能被 AI 写、用户看不了也改不了。
                        item(key = "memory_experience_library") {
                            ExperienceLibraryCard(
                                items = state.experienceItems,
                                onAdd = { showAddExperience = true },
                                onEdit = { experienceTarget = it },
                                onDelete = { deleteTarget = it },
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
                if (item.source == "Summary") {
                    R.string.memory_screen_edit_summary
                } else {
                    R.string.memory_screen_edit_fact
                },
            ),
            initialContent = item.content,
            // 列表里的徽章只排得下一个主因,点开后把命中的状态逐条说清
            statusNote = memoryStatusNote(item),
            onDismiss = { editItem = null },
            onConfirm = { content, onSaved ->
                routeMemoryItemAction(
                    item = item,
                    onFact = { factId, scope -> viewModel.editFact(factId, content, scope, onSaved) },
                    onSummary = { sessionId -> viewModel.editSummary(sessionId, content, onSaved) },
                    // v2.6.7: 编译产物（facts/today/week/longterm）现在可编辑
                    onCompile = { sectionKey ->
                        viewModel.editCompile(sectionKey, content)
                        onSaved(true)
                    },
                    // v2.6.7: 经验条目走专用弹窗（要改标题/分类/标签，不只是内容），
                    // 这里只兜底：编辑弹窗入口由 ExperienceLibraryCard 自己处理。
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
    // v2.6.7: 经验库新增/编辑。ExperienceEditDialog 早已写好但从未被调用，
    // 这里接上。它带标题/分类/标签三个字段，所以不能复用 FactEditDialog（只有内容）。
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
    // v2.6.7: 群聊记忆单条删除确认
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
            dismissText = stringResource(R.string.common_cancel),
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
                    // v2.6.7: 清空该段编译产物
                    onCompile = { sectionKey -> viewModel.deleteCompile(sectionKey) },
                    // v2.6.7: 删除经验条目
                    onExperience = { id -> viewModel.deleteExperience(id) },
                    onUnsupported = {},
                )
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
    // 时间轴卡片长按：面板 → 二次确认 → 按底层条目走同一套删除分派。
    // 取消（面板或确认框）只关弹层，不动列表。
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
    // 多选批量删除：先确认条数再落库。取消停在多选态，改选后可以再点删除。
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
    // 多选合并：草稿非空即弹出预览。取消只清草稿（停在多选态），确认成功后才退出多选。
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
        MuseBottomSheet(onDismissRequest = { showFilter = false }) {
            Column(
                // UI-FIX(贴边): 面板本身已给 screen 边距，这里再加 20dp 会变成双倍缩进。
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.memory_center_filter_title),
                    style = MaterialTheme.typography.titleMedium,
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
                        style = MaterialTheme.typography.labelLargeStrong,
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
                                label = memorySpaceDisplayName(space.name, stringResource(R.string.memory_space_default_name)),
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
                    label = stringResource(R.string.common_all),
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
    // P1-3: 恢复记忆星座 tab（真实星座图，经 MemoryGraphViewModel 加载）
    // v2.6.7: 新增「群聊记忆」tab。loadGroupChatMemories / deleteGroupChatMemory /
    // clearAllGroupChatMemories 与 state.groupChatMemories 早就实现好了（注释里就写着
    // 「展示在记忆中心‘群聊’Tab」），但从未建这个 Tab —— 群聊记忆存了却看不见。
    val tabs =
        listOf(
            stringResource(R.string.memory_center_tab_stream),
            stringResource(R.string.memory_tab_facts),
            stringResource(R.string.memory_center_tab_constellation),
            stringResource(R.string.memory_center_tab_group_chat),
        )
    io.zer0.muse.ui.common.form.MuseCapsuleTab(
        tabs = tabs,
        selectedIndex = selected,
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 12.dp),
    )
}

/**
 * Dream 入口卡片。
 *
 * 只做三件事：说明它会改写分段、给一个开始按钮、给一个回滚按钮。
 * 回滚按钮只在存在上次 revision 时出现 —— 没有可回滚内容时摆一个灰按钮，
 * 用户会以为功能坏了。
 */
@Composable
private fun MemoryDreamCard(
    running: Boolean,
    lastRevisionId: String?,
    lastError: String?,
    onStart: () -> Unit,
    onRestore: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Text(
                text = stringResource(R.string.memory_dream_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.memory_dream_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!lastError.isNullOrBlank()) {
                    Text(
                    text = stringResource(R.string.memory_dream_skipped, lastError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                if (running) {
                    MuseSpinner(size = MuseIconSizes.iconSmall)
                } else {
                    MuseCapsuleButton(
                        text = stringResource(R.string.memory_dream_start),
                        onClick = onStart,
                        variant = IosCapsuleButtonVariant.Secondary,
                        size = io.zer0.muse.ui.theme.MuseButtonSize.Compact,
                        fillWidth = false,
                    )
                }
                if (!lastRevisionId.isNullOrBlank() && !running) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.memory_dream_rollback),
                        onClick = { onRestore(lastRevisionId) },
                        variant = IosCapsuleButtonVariant.Text,
                        size = io.zer0.muse.ui.theme.MuseButtonSize.Compact,
                        fillWidth = false,
                    )
                }
            }
        }
    }
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
                    style = MaterialTheme.typography.headlineLarge,
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
private fun MemoryArchiveRecoveryCard(info: MemoryDbArchiveRecovery.ArchiveInfo, running: Boolean, onRestore: () -> Unit) {
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
    onTimelineActions: (TimelineItem) -> Unit,
    /** 置顶记忆精确去重（由 ViewModel 执行，完成后重载列表）。 */
    onDedupPinned: () -> Unit,
    selectionMode: Boolean,
    isSelected: (MemoryItem) -> Boolean,
    onLongPressItem: (MemoryItem) -> Unit,
    onToggleSelect: (MemoryItem) -> Unit,
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
                onUnpin = { id -> items.firstOrNull { it.id == id }?.let(onPin) },
                // 删除：真正从记忆库移除（onDelete 已在作用域内）。
                // 此前这里调的是 onPin（切换置顶），条目并未删除，与垃圾桶图标的语义相反。
                onDelete = { id -> items.firstOrNull { it.id == id }?.let(onDelete) },
                onDedup = onDedupPinned,
            )
        }
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
        memoryStreamTimeline(
            items = items,
            timelineFilter = timelineFilter,
            onTimelineFilter = onTimelineFilter,
            onToggleTimeline = onToggleTimeline,
            onOpenActions = onTimelineActions,
            selection =
            MemorySelection(
                active = selectionMode,
                isSelected = isSelected,
                onLongPress = onLongPressItem,
                onToggle = onToggleSelect,
            ),
        )
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
                    val spaceName = memorySpaceDisplayName(
                        spaces.firstOrNull { it.id == item.spaceId }?.name ?: item.spaceId.orEmpty(),
                        stringResource(R.string.memory_space_default_name),
                    )
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
                    // 只有 Fact 可删，所以只有 Fact 能进多选
                    selectionMode = selectionMode,
                    isSelected = isSelected(item),
                    onLongPress = if (item.source == "Fact") ({ onLongPressItem(item) }) else null,
                    onToggleSelect = if (item.source == "Fact") ({ onToggleSelect(item) }) else null,
                )
            }
        }
    }
}

/**
 * v2.6.7: 群聊记忆列表。
 *
 * ViewModel 里的 loadGroupChatMemories / deleteGroupChatMemory /
 * clearAllGroupChatMemories 与 state.groupChatMemories 早就实现好了，
 * 但一直没有渲染入入口 —— 群聊记了东西，用户既看不到也清不掉。
 */
private fun LazyListScope.groupChatMemoryItems(
    memories: List<GroupChatMemoryUiItem>,
    isLoading: Boolean,
    onDelete: (GroupChatMemoryUiItem) -> Unit,
    onClearAll: () -> Unit,
) {
    if (isLoading && memories.isEmpty()) {
        item(key = "group_chat_memory_loading") {
            Box(Modifier.fillMaxWidth().heightIn(min = 240.dp), contentAlignment = Alignment.Center) {
                io.zer0.muse.ui.common.state.MuseLoadingState()
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 6.dp),
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
            // 清空全部：破坏性动作，走二次确认
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
            modifier = museAnimateItem().padding(horizontal = MusePaddings.screen, vertical = 4.dp),
        ) {
            io.zer0.muse.ui.common.surface.MuseSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = MuseShapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
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
                                text = stringResource(
                                    R.string.memory_group_chat_meta,
                                    mem.assistantName,
                                    mem.timeText,
                                ),
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
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** P1-3: 记忆流「列表 / 时间轴」切换行。 */
@Composable
internal fun TimelineModeToggleRow(timelineMode: Boolean, onToggle: () -> Unit) {
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
    /** v2.6.23(M2): 「暂不处理」一对矛盾(清 pending,不动内容)。 */
    onDismissConflict: (MemoryConflictUi) -> Unit,
    /** v2.6.23(M2): 裁决 —— 保留指定 id 那句,另一方按 supersedes 让位。 */
    onResolveConflict: (MemoryConflictUi, Long) -> Unit,
    selectionMode: Boolean,
    isSelected: (MemoryItem) -> Boolean,
    onLongPressItem: (MemoryItem) -> Unit,
    onToggleSelect: (MemoryItem) -> Unit,
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
                        style = MaterialTheme.typography.titleSmallStrong,
                    )
                    state.contradictions.forEach { conflict ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = conflict.a,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "↔",
                                    style = MaterialTheme.typography.bodySmallEmphasis,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = conflict.b,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // v2.6.23(M2): 保留哪句,另一句让位(行保留、可回退,见 supersedes 语义)
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    MuseCapsuleButton(
                                        text = stringResource(R.string.memory_contradictions_keep_a),
                                        onClick = { onResolveConflict(conflict, conflict.idA) },
                                        modifier = Modifier.weight(1f),
                                    )
                                    MuseCapsuleButton(
                                        text = stringResource(R.string.memory_contradictions_keep_b),
                                        onClick = { onResolveConflict(conflict, conflict.idB) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            MuseTactileButton(
                                icon = MuseIcons.x,
                                onClick = { onDismissConflict(conflict) },
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
                style = MaterialTheme.typography.titleMedium,
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
                    selectionMode = selectionMode,
                    isSelected = isSelected(item),
                    onLongPress = if (item.source == "Fact") ({ onLongPressItem(item) }) else null,
                    onToggleSelect = if (item.source == "Fact") ({ onToggleSelect(item) }) else null,
                )
            }
        }
    }
}

@Composable
private fun MemoryConstellationTab(scope: String?, spaceId: String, factCount: Int, modifier: Modifier = Modifier) {
    val graphViewModel: MemoryGraphViewModel = koinViewModel()
    val graphState by graphViewModel.state.collectAsStateWithLifecycle()
    // v2.6.2: 预先解析重建提示文案（避免在 lambda 里用 LocalContext 取资源值触发 lint）。
    val rebuildStartedText = androidx.compose.ui.res.stringResource(R.string.memory_graph_rebuild_started)
    val settings = org.koin.compose.koinInject<io.zer0.muse.data.SettingsRepository>()
    val graphConfig by settings.memoryGraphConfigFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.data.MemoryGraphConfig(),
    )
    var showGraphSettings by remember { mutableStateOf(false) }
    // v2.6.11: 星座节点编辑 — 先取全文再弹窗。之前直接把 node.title（截断到 24 字）当新内容落库，
    // 点一下就把长事实永久截断。
    var graphEditTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val ioScope = rememberCoroutineScope()
    LaunchedEffect(scope, spaceId) { graphViewModel.load(scope, spaceId) }
    // v2.6.2: 「打开星座页时」频率 —— 每次进入时补一次边（已有边会跳过）。
    LaunchedEffect(scope, spaceId, graphConfig.edgeBuildFrequency) {
        if (graphConfig.edgeBuildFrequency == io.zer0.muse.data.EdgeBuildFrequency.ON_OPEN) {
            graphViewModel.rebuildEdges(scope, spaceId)
        }
    }
    Column(modifier = modifier.padding(horizontal = MusePaddings.screen, vertical = 4.dp)) {
        // 原来是一行解释文字 + 右侧一个不显眼的小按钮。
        // 解释文字讲的是「重要事实靠近中心、关系没整理时节点单独显示」——
        // 这些看图就懂，写出来只是把图形又描述一遍。
        // 改成按钮自带文字标签，用户直接知道这里能设置。
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            io.zer0.muse.ui.common.form.MuseCapsuleButton(
                text = stringResource(R.string.memory_graph_settings_title),
                onClick = { showGraphSettings = true },
                variant = io.zer0.muse.ui.common.form.IosCapsuleButtonVariant.Text,
                fillWidth = false,
            )
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
                    io.zer0.muse.ui.common.feedback.MuseToast.show(rebuildStartedText)
                },
            )
        }
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
                    else -> MemoryGraphView(
                        state = graphState,
                        modifier = Modifier.fillMaxSize(),
                        onNodeAction = { node, action ->
                            when (action) {
                                io.zer0.muse.ui.memory.NodeAction.EDIT ->
                                    // v2.6.11: 先拉全文，拿到后弹窗编辑；取不到（事实已不存在）则不弹。
                                    graphViewModel.loadFactContent(node.factId, scope) { full ->
                                        graphEditTarget = full?.let { node.factId to it }
                                    }
                                io.zer0.muse.ui.memory.NodeAction.DELETE -> graphViewModel.deleteNode(node.factId, scope, spaceId)
                                io.zer0.muse.ui.memory.NodeAction.PIN -> graphViewModel.togglePin(node.factId, scope, spaceId)
                                io.zer0.muse.ui.memory.NodeAction.LINK -> Unit
                            }
                        },
                        onManualLink = { src, tgt, type ->
                            graphViewModel.addManualEdge(src.factId, tgt.factId, type, scope, spaceId)
                        },
                    )
                }
            }
        }
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

