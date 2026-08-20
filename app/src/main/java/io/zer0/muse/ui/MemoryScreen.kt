@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.zer0.muse.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import io.zer0.muse.ui.common.media.WindowWidthClass
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseLoadingState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.experience.DEFAULT_EXPERIENCE_CATEGORY
import io.zer0.memory.fact.MemoryLegacyReset
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.memory.MemoryGraphPreview
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

/**
 * 阶段 6: 记忆页 — iOS / MANUS 风格重写。
 *
 * 设计要点:
 *  - 背景 warm-paper (#FAFAF8),卡片纯白圆角 20dp + 0.5dp 描边 + 极淡阴影。
 *  - 顶部 [MuseTopBar] 使用 largeTitle,标题为「记忆」。
 *  - 搜索框固定顶部,iOS 风格圆角搜索栏(surfaceVariant 背景)。
 *  - 筛选胶囊 [MuseChip] 横向滚动。
 *  - 概览统计卡片:3 列大数字 + 细分割线。
 *  - 分组标题采用 iOS 风格小字 muted 色。
 *  - 记忆行统一使用 [CardGroup] 容器,行与行之间自动 divider。
 *
 * 数据来源:[MemoryViewModel] 主动 pull memory 模块的 suspend Repository。
 */
@Composable
fun MemoryScreen(
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: MemoryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // R-DB-03: 早期 facts 库被归档重建时给用户可见提示(仅一次)
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (MemoryLegacyReset.consume(context)) {
            MuseToast.show(context.getString(R.string.memory_legacy_reset_hint))
        }
    }
    // v1.x: 手动去重结果提示(合并 N 条重复记忆)
    LaunchedEffect(viewModel.dedupResult) {
        val result = viewModel.dedupResult.value ?: return@LaunchedEffect
        if (result.startsWith("merged:")) {
            MuseToast.show(context.getString(R.string.memory_dedup_result, result.removePrefix("merged:")))
        } else {
            MuseToast.show(context.getString(R.string.memory_dedup_failed))
        }
        viewModel.consumeDedupResult()
    }
    // v1.x: 立即编译结果提示
    LaunchedEffect(viewModel.compileResult) {
        val result = viewModel.compileResult.value ?: return@LaunchedEffect
        MuseToast.show(
            context.getString(
                if (result == "done") R.string.memory_compile_done else R.string.memory_compile_failed,
            ),
        )
        viewModel.consumeCompileResult()
    }
    LaunchedEffect(viewModel.organizeResult) {
        val result = viewModel.organizeResult.value ?: return@LaunchedEffect
        if (result.startsWith("done:")) {
            MuseToast.show(context.getString(R.string.memory_organize_stage_complete, result.removePrefix("done:").toIntOrNull() ?: 0))
        } else {
            MuseToast.show(context.getString(R.string.memory_organize_stage_failed))
        }
        viewModel.consumeOrganizeResult()
    }
    // v1.0.51: 存量记忆迁移进度(升级后首次启动补跑历史 session 摘要时显示)
    val backfillProgress by viewModel.backfillProgress.collectAsStateWithLifecycle()
    // v8: 作用域筛选状态(从 ViewModel 直接 collect,与 state 同级更新)
    val selectedScope by viewModel.selectedScope.collectAsStateWithLifecycle()
    val availableScopes by viewModel.availableScopes.collectAsStateWithLifecycle()
    // v1.0.52 P2-2: 记忆空间切换状态(与 Scope 正交:Space 按场景隔离)
    val selectedSpaceId by viewModel.selectedSpaceId.collectAsStateWithLifecycle()
    val availableSpaces by viewModel.availableSpaces.collectAsStateWithLifecycle()
    val organizeRunning by viewModel.organizeRunning.collectAsStateWithLifecycle()
    val organizeStage by viewModel.organizeStage.collectAsStateWithLifecycle()
    // Phase 2 2D: Export dialog state (declared before Scaffold for topbar access)
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    // P2-1: 大屏(Expanded)下内容区居中限宽 720dp
    val widthClass = rememberWindowWidthClass()
    // v1.0.51: 记忆 Tab 切换 — 0=当下 1=短期 2=长期 3=事实 4=群聊(v1.0.72) 5=星图(Phase 0)
    var selectedMemoryTab by rememberSaveable { mutableStateOf(3) }
    val memoryTabTitles = listOf(
        stringResource(R.string.memory_tab_today),
        stringResource(R.string.memory_tab_week),
        stringResource(R.string.memory_tab_longterm),
        stringResource(R.string.memory_tab_facts),
        stringResource(R.string.memory_tab_group_chat),
        stringResource(R.string.memory_tab_graph),
    )

    Scaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.memory_screen_title),
                onBack = onBack,
                largeTitle = true,
                actions = {
                    // v1.0.51: 记忆参数配置入口(齿轮)
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings_memory_page_title),
                        )
                    }
                    // Phase 2 2D: Export button
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = stringResource(R.string.memory_stats_export_title),
                        )
                    }
                },
            )
        },
    ) { padding ->
        // P2-1: Box 包裹,Expanded 模式下居中限宽
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (widthClass == WindowWidthClass.Expanded) {
                            Modifier.widthIn(max = 720.dp)
                        } else {
                            Modifier
                        },
                    )
                    .padding(padding),
            ) {
                // v1.0.51: 存量记忆迁移进度条(迁移中或刚完成时显示)
                backfillProgress?.let { bp ->
                    // done=true 时延迟 5 秒自动清除,避免用户离开页面后进度条残留
                    LaunchedEffect(bp.done) {
                        if (bp.done) {
                            delay(5000)
                            viewModel.clearBackfillProgress()
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = 12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (!bp.done) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(
                                    text = if (bp.done) {
                                        stringResource(R.string.memory_backfill_done, bp.succeeded)
                                    } else {
                                        stringResource(
                                            R.string.memory_backfill_in_progress,
                                            bp.processed + 1,
                                            bp.total,
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            LinearProgressIndicator(
                                progress = { if (bp.total > 0) bp.processed.toFloat() / bp.total else 0f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            )
                        }
                    }
                }
                // v1.0.51: 记忆 Tab 切换栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    memoryTabTitles.forEachIndexed { index, title ->
                        FilterChip(
                            selected = selectedMemoryTab == index,
                            onClick = { selectedMemoryTab = index },
                            label = { Text(title) },
                        )
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.organizeMemory() },
                    enabled = !organizeRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MusePaddings.screen),
                    shape = MuseShapes.large,
                ) {
                    if (organizeRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (organizeStage) {
                            "prepare" -> stringResource(R.string.memory_organize_stage_prepare)
                            "compile" -> stringResource(R.string.memory_organize_stage_compile)
                            "dedup" -> stringResource(R.string.memory_organize_stage_dedup)
                            "complete" -> stringResource(R.string.memory_organize_stage_complete, 0)
                            else -> stringResource(R.string.memory_organize_action)
                        },
                    )
                }

                // v1.0.51: 当下/短期/长期 Tab — 直接展示编译产物,支持编辑
                if (selectedMemoryTab != 3 && selectedMemoryTab != 5) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = MusePaddings.screen,
                            vertical = MusePaddings.contentGap,
                        ),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                    ) {
                        // 健康状态卡片
                        item {
                            MemoryHealthCard(
                                healthMap = state.healthMap.mapValues { (_, h) ->
                                    StepHealthInfo(
                                        lastSuccessAt = h.lastSuccessAt,
                                        lastErrorAt = h.lastErrorAt,
                                        lastErrorMsg = h.lastErrorMsg,
                                        failCount = h.failCount,
                                    )
                                },
                            )
                        }
                        // 根据 Tab 展示对应段
                        when (selectedMemoryTab) {
                            0 -> item {
                                MemorySectionView(
                                    title = memoryTabTitles[0],
                                    content = state.compileItems.find { it.id == "today" }?.content ?: "",
                                    onEdit = { newContent -> viewModel.editCompile("today", newContent) },
                                )
                            }
                            1 -> item {
                                MemoryWeekView(
                                    content = state.compileItems.find { it.id == "week" }?.content ?: "",
                                    onEditDay = { date, newContent ->
                                        // week 段编辑:整体替换(简化处理,不按日单独编辑)
                                        viewModel.editCompile("week", newContent)
                                    },
                                )
                            }
                            2 -> item {
                                MemorySectionView(
                                    title = memoryTabTitles[2],
                                    content = state.compileItems.find { it.id == "longterm" }?.content ?: "",
                                    onEdit = { newContent -> viewModel.editCompile("longterm", newContent) },
                                )
                            }
                        }
                    }
                    return@Column
                }

                // ════════ v1.0.72: "群聊"Tab(群聊记忆管理) ════════
                if (selectedMemoryTab == 4) {
                    LaunchedEffect(Unit) { viewModel.loadGroupChatMemories() }
                    var showClearAllConfirm by remember { mutableStateOf(false) }
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (state.groupChatMemories.isEmpty()) {
                                    stringResource(R.string.memory_group_chat_empty)
                                } else {
                                    stringResource(R.string.memory_group_chat_count, state.groupChatMemories.size)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (state.groupChatMemories.isNotEmpty()) {
                                TextButton(onClick = { showClearAllConfirm = true }) {
                                    Text(stringResource(R.string.settings_common_clear_all), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        if (state.groupChatMemoriesLoading && state.groupChatMemories.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                MuseLoadingState()
                            }
                        } else if (state.groupChatMemories.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(MusePaddings.emptyStateGap),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.memory_group_chat_empty_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    horizontal = MusePaddings.screen,
                                    vertical = MusePaddings.contentGap,
                                ),
                                verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                            ) {
                                items(
                                    items = state.groupChatMemories,
                                    key = { it.id },
                                ) { item ->
                                    GroupChatMemoryCard(
                                        item = item,
                                        onDelete = { viewModel.deleteGroupChatMemory(item.id) },
                                    )
                                }
                            }
                        }
                    }
                    if (showClearAllConfirm) {
                        MuseDialog(
                            onDismissRequest = { showClearAllConfirm = false },
                            title = stringResource(R.string.memory_group_chat_clear_title),
                            content = {
                                Text(
                                    text = stringResource(R.string.memory_group_chat_clear_content),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            confirmText = stringResource(R.string.settings_common_clear),
                            onConfirm = {
                                showClearAllConfirm = false
                                viewModel.clearAllGroupChatMemories()
                            },
                            dismissText = stringResource(R.string.action_cancel),
                            onDismiss = { showClearAllConfirm = false },
                        )
                    }
                    return@Column
                }

                // 记忆树:接入真实 memory_links 和 facts 数据。
                if (selectedMemoryTab == 5) {
                    val graphViewModel: io.zer0.muse.ui.memory.MemoryGraphViewModel = koinViewModel()
                    val graphState by graphViewModel.state.collectAsStateWithLifecycle()
                    val currentScope = selectedScope ?: "main"
                    val currentSpace = selectedSpaceId ?: "default"
                    LaunchedEffect(currentScope, currentSpace) {
                        graphViewModel.load(currentScope, currentSpace)
                    }
                    io.zer0.muse.ui.memory.MemoryGraphView(
                        state = graphState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                    )
                    return@Column
                }

                // ════════ 以下为"事实"Tab(原有逻辑) ════════

                // v1.78 (#1): 搜索防抖 — 本地 state + 300ms delay,避免每次按键都查库
                var searchQuery by rememberSaveable { mutableStateOf("") }
                LaunchedEffect(searchQuery) {
                    delay(300)
                    viewModel.search(searchQuery)
                }

                // 顶部 iOS 风格搜索框
                MemorySearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    enabled = !state.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                )

                // Phase 2 2A: 视图模式状态(列表 vs 时间轴)
                var showTimelineView by rememberSaveable { mutableStateOf(false) }

                // v1.0.4: 把"列表/时间轴切换 + 作用域筛选"从 Column 顶部固定改为
                // 各分支 LazyColumn 顶部 item,实现"搜索框固定 + 下方整体滚动"。
                val headerContent: @Composable () -> Unit = {
                    // Phase 2 2A: View mode toggle (列表 / 时间轴)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInnerTight),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MuseChip(
                            selected = !showTimelineView,
                            onClick = { showTimelineView = false },
                            label = stringResource(R.string.memory_stats_view_list),
                        )
                        MuseChip(
                            selected = showTimelineView,
                            onClick = { showTimelineView = true },
                            label = stringResource(R.string.memory_stats_view_timeline),
                        )
                    }
                    // v8: 作用域筛选器(横向滚动 MuseChip)
                    if (availableScopes.isNotEmpty()) {
                        ScopeFilterChipRow(
                            options = availableScopes,
                            selectedScope = selectedScope,
                            onSelect = viewModel::selectScope,
                        )
                    }
                    // v1.0.52 P2-2: 记忆空间切换器(横向滚动 MuseChip)
                    // 与 Scope 正交:Space 按场景隔离(工作/生活/学习),Scope 按 Agent 隔离
                    if (availableSpaces.isNotEmpty()) {
                        io.zer0.muse.ui.memory.SpaceSwitcherRow(
                            spaces = availableSpaces,
                            selectedSpaceId = selectedSpaceId,
                            onSelect = viewModel::selectSpace,
                        )
                    }
                }

                if (state.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(MusePaddings.emptyStateGap),
                        contentAlignment = Alignment.Center,
                    ) {
                        MuseLoadingState()
                    }
                    return@Column
                }

                // 错误展示框(可上下滚动 + 复制按钮)
                state.errorTrace?.let { trace ->
                    ErrorTraceBox(
                        trace = trace,
                        onRetry = { viewModel.loadAll() },
                    )
                    return@Column
                }

                // 搜索结果模式 vs 4 层浏览模式
                if (state.query.isNotBlank()) {
                    SearchResultsList(
                        results = state.searchResults,
                        isSearching = state.isSearching,
                        onDelete = viewModel::deleteFact,
                        headerContent = headerContent,
                    )
                } else if (showTimelineView) {
                    // Phase 2 2A: 时间轴视图
                    val timelineItems = remember(state.factItems, state.summaryItems) {
                        (state.factItems + state.summaryItems).map { item ->
                            io.zer0.muse.ui.memory.TimelineItem(
                                id = item.id,
                                content = item.content,
                                source = item.source,
                                importance = item.importance,
                                createdAt = item.createdAt,
                                tags = item.tags,
                            )
                        }.sortedByDescending { it.createdAt }
                    }
                    io.zer0.muse.ui.memory.MemoryTimelineView(
                        items = timelineItems,
                        modifier = Modifier.fillMaxSize(),
                        headerContent = headerContent,
                    )
                } else {
                    // v9: 删除确认 + 编辑弹窗状态(仅 Fact 层)
                    var editItem by remember { mutableStateOf<MemoryItem?>(null) }
                    // v4: 重要程度选择弹窗状态(仅 Fact 层)
                    var importanceItem by remember { mutableStateOf<MemoryItem?>(null) }

                    // 手动新增元事实弹窗状态
                    var showAddFactDialog by remember { mutableStateOf(false) }

                    // v1.98: 经验库弹窗状态
                    var showAddExperienceDialog by remember { mutableStateOf(false) }
                    var editExperienceItem by remember { mutableStateOf<MemoryItem?>(null) }
                    var pendingDeleteExperience by remember { mutableStateOf<MemoryItem?>(null) }

                    // v9: 按分类筛选(仅 Fact 层有 category)
                    val filteredFactItems = remember(state.factItems, state.categoryFilter) {
                        filterMemoryItemsByCategory(state.factItems, state.categoryFilter)
                    }

                    PullToRefreshBox(
                        isRefreshing = state.isLoading,
                        onRefresh = { viewModel.loadAll() },
                    ) {

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = MusePaddings.screen,
                            vertical = MusePaddings.contentGap,
                        ),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                    ) {
                        // v1.0.4: 顶部"列表/时间轴切换 + 作用域筛选"作为 item 一起滚动
                        item { headerContent() }
                        // v9: 分类筛选胶囊(全部/核心事实/偏好/经历/关系)
                        if (state.query.isBlank()) {
                            item {
                                CategoryFilterChipRow(
                                    selectedCategory = state.categoryFilter,
                                    onSelect = viewModel::setCategoryFilter,
                                )
                            }
                        }
                        // 概览统计卡片(3 列大数字)
                        item { OverviewStatCard(state = state) }
                        // 手动新增元事实 + 立即编译入口
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 0.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { showAddFactDialog = true },
                                    modifier = Modifier.weight(1f),
                                    shape = MuseShapes.large,
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.memory_screen_add_fact_cd), modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.memory_add_fact))
                                }
                                val compiling by viewModel.compilingState.collectAsStateWithLifecycle()
                                OutlinedButton(
                                    onClick = { viewModel.compileNow() },
                                    enabled = !compiling,
                                    modifier = Modifier.weight(1f),
                                    shape = MuseShapes.large,
                                ) {
                                    if (compiling) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.memory_screen_compile_now_cd), modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (compiling) stringResource(R.string.memory_compile_running) else stringResource(R.string.memory_screen_compile_now))
                                }
                            }
                        }
                        // v1.98: 经验库卡片
                        if (state.experienceEnabled) {
                            item {
                                ExperienceLibraryCard(
                                    items = state.experienceItems,
                                    onAdd = { showAddExperienceDialog = true },
                                    onEdit = { editExperienceItem = it },
                                    onDelete = { pendingDeleteExperience = it },
                                )
                            }
                        }
                        // v9: 按分类分组展示记忆条目(直观列表)
                        categoryGroupedMemoryListItems(
                            items = filteredFactItems,
                            selectedCategory = state.categoryFilter,
                            onDelete = viewModel::deleteFact,
                            onEdit = { item -> editItem = item },
                            onSetImportance = { item -> importanceItem = item },
                            onTogglePin = { item -> viewModel.toggleFactPinned(item.id) },
                        )
                    }
                    }

                    // v9: 删除确认弹窗(仅 Fact 层)
                    editItem?.let { item ->
                        FactEditDialog(
                            title = stringResource(R.string.memory_screen_edit_fact),
                            initialContent = item.content,
                            onDismiss = { editItem = null },
                            onConfirm = { newContent ->
                                viewModel.editFact(item.id, newContent)
                                editItem = null
                            },
                        )
                    }

                    // 新增元事实弹窗
                    if (showAddFactDialog) {
                        AddFactDialog(
                            onDismiss = { showAddFactDialog = false },
                            onConfirm = { content ->
                                viewModel.addFact(content)
                                showAddFactDialog = false
                            },
                        )
                    }

                    // v4: 重要程度选择弹窗(仅 Fact 层)
                    importanceItem?.let { item ->
                        ImportanceSelectDialog(
                            currentImportance = item.importance,
                            onDismiss = { importanceItem = null },
                            onSelect = { importance ->
                                viewModel.setFactImportance(item.id, importance)
                                importanceItem = null
                            },
                        )
                    }

                    // v1.98: 经验库 — 新增弹窗
                    if (showAddExperienceDialog) {
                        ExperienceEditDialog(
                            title = stringResource(R.string.memory_screen_experience_add_dialog_title),
                            initialTitle = "",
                            initialContent = "",
                            initialCategory = DEFAULT_EXPERIENCE_CATEGORY,
                            initialTags = "",
                            onDismiss = { showAddExperienceDialog = false },
                            onConfirm = { t, c, cat, tags ->
                                viewModel.addExperience(t, c, cat, tags)
                                showAddExperienceDialog = false
                            },
                        )
                    }

                    // v1.98: 经验库 — 编辑弹窗
                    editExperienceItem?.let { item ->
                        val tagText = item.tags.joinToString(", ")
                        ExperienceEditDialog(
                            title = stringResource(R.string.memory_screen_experience_edit_dialog_title),
                            initialTitle = item.title,
                            initialContent = item.content,
                            initialCategory = item.category ?: DEFAULT_EXPERIENCE_CATEGORY,
                            initialTags = tagText,
                            onDismiss = { editExperienceItem = null },
                            onConfirm = { t, c, cat, tags ->
                                viewModel.editExperience(item.id, t, c, cat, tags)
                                editExperienceItem = null
                            },
                        )
                    }

                    // v1.98: 经验库 — 删除确认弹窗
                    pendingDeleteExperience?.let { item ->
                        MuseDialog(
                            onDismissRequest = { pendingDeleteExperience = null },
                            title = stringResource(R.string.memory_screen_experience_title),
                            content = {
                                Text(stringResource(R.string.memory_screen_experience_delete_confirm))
                            },
                            confirmText = stringResource(R.string.memory_screen_delete),
                            onConfirm = {
                                viewModel.deleteExperience(item.id)
                                pendingDeleteExperience = null
                            },
                            dismissText = stringResource(R.string.memory_screen_cancel),
                            onDismiss = { pendingDeleteExperience = null },
                            destructive = true,
                        )
                    }

                    // Phase 2 2D: 导出弹窗
                    if (showExportDialog) {
                        val context = LocalContext.current
                        MuseDialog(
                            onDismissRequest = { showExportDialog = false },
                            title = stringResource(R.string.memory_stats_export_title),
                            content = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.memory_stats_export_format_hint))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                val md = buildMemoryMarkdown(state.factItems, state.summaryItems)
                                                MemoryExportHelpers.shareText(context, md, "memory_export.md")
                                                showExportDialog = false
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = MuseShapes.large,
                                        ) { Text("Markdown") }
                                        OutlinedButton(
                                            onClick = {
                                                val json = buildMemoryJson(state.factItems, state.summaryItems)
                                                MemoryExportHelpers.shareText(context, json, "memory_export.json")
                                                showExportDialog = false
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = MuseShapes.large,
                                        ) { Text("JSON") }
                                    }
                                }
                            },
                            confirmText = "",
                            onConfirm = null,
                            dismissText = stringResource(R.string.memory_screen_cancel),
                            onDismiss = { showExportDialog = false },
                        )
                    }
                }
            }
        }
    }
}

/**
 * v1.0.72: 群聊记忆卡片(记忆中心"群聊"Tab 列表项)。
 *
 * 展示:群聊名 + 发言助手 + 摘要 + 时间 + 删除按钮。
 * 摘要里可能残留历史测试期的欠揍语气,删除后该条不再注入 system prompt。
 */
@Composable
private fun GroupChatMemoryCard(
    item: GroupChatMemoryUiItem,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.groupChatName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${item.assistantName} · ${item.timeText}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = TablerIcons.Trash,
                        contentDescription = stringResource(R.string.memory_group_chat_delete_cd),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
