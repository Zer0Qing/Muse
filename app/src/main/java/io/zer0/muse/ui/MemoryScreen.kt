@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.zer0.muse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import io.zer0.muse.ui.common.media.WindowWidthClass
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.material3.Button
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
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseLoadingState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.common.surface.CardGroup
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

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
    // v1.0.51: 存量记忆迁移进度(升级后首次启动补跑历史 session 摘要时显示)
    val backfillProgress by viewModel.backfillProgress.collectAsStateWithLifecycle()
    // v8: 作用域筛选状态(从 ViewModel 直接 collect,与 state 同级更新)
    val selectedScope by viewModel.selectedScope.collectAsStateWithLifecycle()
    val availableScopes by viewModel.availableScopes.collectAsStateWithLifecycle()
    // v1.0.52 P2-2: 记忆空间切换状态(与 Scope 正交:Space 按场景隔离)
    val selectedSpaceId by viewModel.selectedSpaceId.collectAsStateWithLifecycle()
    val availableSpaces by viewModel.availableSpaces.collectAsStateWithLifecycle()
    // Phase 2 2D: Export dialog state (declared before Scaffold for topbar access)
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    // P2-1: 大屏(Expanded)下内容区居中限宽 720dp
    val widthClass = rememberWindowWidthClass()
    // v1.0.51: 记忆 Tab 切换 — 0=当下 1=短期 2=长期 3=事实
    var selectedMemoryTab by rememberSaveable { mutableStateOf(3) }
    val memoryTabTitles = listOf(
        stringResource(R.string.memory_tab_today),
        stringResource(R.string.memory_tab_week),
        stringResource(R.string.memory_tab_longterm),
        stringResource(R.string.memory_tab_facts),
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

                // v1.0.51: 当下/短期/长期 Tab — 直接展示编译产物,支持编辑
                if (selectedMemoryTab != 3) {
                    // 立即编译按钮(Tab 0-2 共用)
                    OutlinedButton(
                        onClick = { viewModel.compileNow() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MusePaddings.screen),
                        shape = MuseShapes.large,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.memory_screen_compile_now))
                    }

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
                                OutlinedButton(
                                    onClick = { viewModel.compileNow() },
                                    modifier = Modifier.weight(1f),
                                    shape = MuseShapes.large,
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.memory_screen_compile_now_cd), modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.memory_screen_compile_now))
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
                            initialCategory = "通用",
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
                            initialCategory = item.category ?: "通用",
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
                                                shareText(context, md, "memory_export.md")
                                                showExportDialog = false
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = MuseShapes.large,
                                        ) { Text("Markdown") }
                                        OutlinedButton(
                                            onClick = {
                                                val json = buildMemoryJson(state.factItems, state.summaryItems)
                                                shareText(context, json, "memory_export.json")
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
 * iOS 风格搜索栏 — Surface + BasicTextField,surfaceVariant 背景,圆角。
 */
@Composable
private fun MemorySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = stringResource(R.string.memory_screen_search_cd),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.memory_screen_search_fact_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

/**
 * v9: 根据分类筛选记忆条目(null=全部)。
 */
private fun filterMemoryItemsByCategory(
    items: List<MemoryItem>,
    category: String?,
): List<MemoryItem> {
    return if (category == null) items else items.filter {
        it.category.equals(category, ignoreCase = true)
    }
}

private fun isToday(createdAt: String?): Boolean {
    if (createdAt == null) return false
    return try {
        val date = java.time.Instant.parse(createdAt)
        val today = java.time.LocalDate.now()
        val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        itemDate == today
    } catch (e: Exception) { false }
}

private fun isThisWeek(createdAt: String?): Boolean {
    if (createdAt == null) return false
    return try {
        val date = java.time.Instant.parse(createdAt)
        val today = java.time.LocalDate.now()
        val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val weekStart = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        !itemDate.isBefore(weekStart) && !itemDate.isAfter(today)
    } catch (e: Exception) { false }
}

private fun isThisMonth(createdAt: String?): Boolean {
    if (createdAt == null) return false
    return try {
        val date = java.time.Instant.parse(createdAt)
        val today = java.time.LocalDate.now()
        val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        itemDate.year == today.year && itemDate.month == today.month
    } catch (e: Exception) { false }
}

/**
 * v9: 分类筛选胶囊 — 全部 / 核心事实 / 偏好 / 经历 / 关系 / 目标 / 医疗。
 *
 * 参考图风格:横向滚动,选中项用 inverseSurface 深色药丸,未选中用浅灰。
 */
@Composable
private fun CategoryFilterChipRow(
    selectedCategory: String?,
    onSelect: (String?) -> Unit,
) {
    val scrollState = rememberScrollState()
    val categories = listOf(
        null to stringResource(R.string.memory_category_all),
        "identity" to stringResource(R.string.memory_category_identity),
        "preference" to stringResource(R.string.memory_category_preference),
        "event" to stringResource(R.string.memory_category_event),
        "relationship" to stringResource(R.string.memory_category_relationship),
        "goal" to stringResource(R.string.memory_category_goal),
        "medical" to stringResource(R.string.memory_category_medical),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = MusePaddings.screen, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        categories.forEach { (category, label) ->
            val selected = selectedCategory == category
            CategoryChip(
                label = label,
                selected = selected,
                onClick = { onSelect(category) },
            )
        }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.pill,
        color = if (selected) MaterialTheme.colorScheme.inverseSurface
            else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.inverseOnSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * v8: 作用域筛选行 — 横向滚动 MuseChip。
 */
@Composable
private fun ScopeFilterChipRow(
    options: List<ScopeOption>,
    selectedScope: String?,
    onSelect: (String?) -> Unit,
) {
    val scrollState = rememberScrollState()

    Text(
        text = stringResource(R.string.memory_scope_label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = 2.dp),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = MusePaddings.screen, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = selectedScope == option.id
            MuseChip(
                selected = isSelected,
                onClick = { onSelect(option.id) },
                label = option.displayName,
            )
        }
    }
}

/**
 * v8: 单条 Fact 项的 scope 徽章。
 */
@Composable
private fun ScopeBadge(scope: String?) {
    if (scope.isNullOrBlank() || scope == "main") return
    val label = stringResource(R.string.memory_scope_assistant) + " · " + scope.take(6)
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * 搜索结果列表 — CardGroup 容器。
 */
@Composable
private fun SearchResultsList(
    results: List<MemoryItem>,
    isSearching: Boolean,
    onDelete: (String) -> Unit,
    headerContent: @Composable () -> Unit = {},
) {
    if (isSearching) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(MusePaddings.emptyStateGap),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        }
        return
    }
    if (results.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(MusePaddings.emptyStateGap),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.memory_screen_no_match),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = MusePaddings.screen,
            vertical = MusePaddings.contentGap,
        ),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        item { headerContent() }
        item {
            Text(
                text = stringResource(R.string.memory_screen_search_results, results.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            CardGroup {
                results.forEach { item ->
                    item(
                        key = item.id,
                        headlineContent = { MemoryRowHeadline(item) },
                        supportingContent = { MemoryRowSupporting(item) },
                        trailingContent = {
                            MemoryRowTrailing(
                            item = item,
                            onDelete = { item -> onDelete(item.id) },
                            onEdit = null,
                            onSetImportance = null,
                        )
                        },
                    )
                }
            }
        }
    }
}

/**
 * v9: 按分类分组展示记忆条目(参考图风格)。
 *
 * 当未选择具体分类时,按 category 分组,每个分组一个小标题 + 独立卡片组。
 * 当选中某个分类时,该分类下条目直接展示,不再重复分组标题。
 */
private fun LazyListScope.categoryGroupedMemoryListItems(
    items: List<MemoryItem>,
    selectedCategory: String?,
    onDelete: (String) -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onSetImportance: (MemoryItem) -> Unit,
    onTogglePin: (MemoryItem) -> Unit,
) {
    if (items.isEmpty()) {
        item {
            EmptyHint(text = stringResource(R.string.memory_screen_no_fact))
        }
        return
    }

    // 分类顺序(按参考图):identity / preference / event / relationship / goal / medical / other
    val categoryOrder = listOf("identity", "preference", "event", "relationship", "goal", "medical", "other")

    // 命中 selectedCategory 时,直接平铺列表
    if (selectedCategory != null) {
        item {
            MemoryCardGroup(
                items = items,
                onDelete = onDelete,
                onEdit = onEdit,
                onSetImportance = onSetImportance,
                onTogglePin = onTogglePin,
            )
        }
        return
    }

    // 未选中分类:按 category 分组
    val grouped = items.groupBy {
        it.category?.lowercase()?.takeIf { c -> categoryOrder.contains(c) } ?: "other"
    }

    categoryOrder.forEach { category ->
        val groupItems = grouped[category] ?: emptyList()
        if (groupItems.isEmpty()) return@forEach

        item {
            CategorySectionHeader(
                title = categoryDisplayName(category),
                count = groupItems.size,
            )
        }
        item {
            MemoryCardGroup(
                items = groupItems,
                onDelete = onDelete,
                onEdit = onEdit,
                onSetImportance = onSetImportance,
                onTogglePin = onTogglePin,
            )
        }
    }
}

@Composable
private fun categoryDisplayName(category: String): String {
    return when (category) {
        "identity" -> stringResource(R.string.memory_category_identity)
        "preference" -> stringResource(R.string.memory_category_preference)
        "event" -> stringResource(R.string.memory_category_event)
        "relationship" -> stringResource(R.string.memory_category_relationship)
        "goal" -> stringResource(R.string.memory_category_goal)
        "medical" -> stringResource(R.string.memory_category_medical)
        else -> stringResource(R.string.memory_category_other)
    }
}

/**
 * 分类分组标题 — iOS 风格居中/左对齐小字。
 */
@Composable
private fun CategorySectionHeader(
    title: String,
    count: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * 单条记忆卡片组 — 参考图样式:白色圆角卡片,内容 + 日期/作用域 + 重要性标签。
 */
@Composable
private fun MemoryCardGroup(
    items: List<MemoryItem>,
    onDelete: (String) -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onSetImportance: (MemoryItem) -> Unit,
    onTogglePin: (MemoryItem) -> Unit,
) {
    CardGroup {
        items.forEach { item ->
            item(
                key = item.id,
                headlineContent = { MemoryCardContent(item) },
                trailingContent = {
                    MemoryCardTrailing(
                        item = item,
                        onDelete = { onDelete(item.id) },
                        onEdit = onEdit,
                        onSetImportance = onSetImportance,
                        onTogglePin = onTogglePin,
                    )
                },
            )
        }
    }
}

/**
 * 记忆卡片主体内容 — 与参考图一致:正文 + 日期/作用域 meta + 重要性标签。
 */
@Composable
private fun MemoryCardContent(item: MemoryItem) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = item.content,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = buildMetaText(item),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (item.importance > 0) {
                ImportanceTag(importance = item.importance)
            }
        }
    }
}

@Composable
private fun buildMetaText(item: MemoryItem): String {
    val dateText = item.time?.take(10)
        ?: item.createdAt?.take(10)
        ?: ""
    val scopeText = if (!item.scope.isNullOrBlank() && item.scope != "main") {
        stringResource(R.string.memory_scope_assistant) + " · " + item.scope.take(6)
    } else ""
    return when {
        dateText.isNotBlank() && scopeText.isNotBlank() -> "$dateText · $scopeText"
        dateText.isNotBlank() -> dateText
        scopeText.isNotBlank() -> scopeText
        else -> ""
    }
}

/**
 * 重要性小标签(右侧绿色/红色药丸)。
 */
@Composable
private fun ImportanceTag(importance: Int) {
    if (importance <= 0) return
    val text = if (importance >= 2) stringResource(R.string.memory_importance_critical)
        else stringResource(R.string.memory_importance_important)
    val color = if (importance >= 2) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = MuseShapes.pill,
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * 记忆卡片操作区 — 编辑 / 删除 / 设置重要程度。
 */
@Composable
private fun MemoryCardTrailing(
    item: MemoryItem,
    onDelete: (() -> Unit)?,
    onEdit: ((MemoryItem) -> Unit)?,
    onSetImportance: ((MemoryItem) -> Unit)?,
    onTogglePin: ((MemoryItem) -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onSetImportance != null) {
            IconButton(onClick = { onSetImportance(item) }) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = stringResource(R.string.memory_sync_set_importance_cd),
                    tint = when (item.importance) {
                        2 -> MaterialTheme.colorScheme.error
                        1 -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (onEdit != null) {
        if (onTogglePin != null) {
            IconButton(onClick = { onTogglePin(item) }) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = stringResource(
                        if (item.pinnedAt == null) R.string.memory_pin_cd else R.string.memory_unpin_cd,
                    ),
                    tint = if (item.pinnedAt != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
            IconButton(onClick = { onEdit(item) }) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.memory_screen_edit_cd),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (onDelete != null) {
            IconButton(onClick = { onDelete() }) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.memory_screen_delete_cd),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * 记忆行标题区 — 重要性徽章 + 标题。
 */
@Composable
private fun MemoryRowHeadline(item: MemoryItem) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (item.importance > 0) {
            ImportanceBadge(importance = item.importance)
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 记忆行内容区 — markdown 内容 + 标签/时间/作用域 meta。
 */
@Composable
private fun MemoryRowSupporting(item: MemoryItem) {
    Column {
        if (item.content.isNotBlank()) {
            Spacer(Modifier.size(4.dp))
            MarkdownText(
                text = item.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val showScopeBadge = !item.scope.isNullOrBlank() && item.scope != "main"
        if (item.tags.isNotEmpty() || item.time != null || showScopeBadge) {
            Spacer(Modifier.size(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.tags.forEach { tag ->
                    Text(
                        text = "#$tag",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (item.time != null) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = item.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                ScopeBadge(scope = item.scope)
            }
        }
        val createdAtText = formatCreatedAtText(item.createdAt)
        if ((item.source == "Fact" && item.sessionId != null) || createdAtText != null) {
            Spacer(Modifier.size(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (item.source == "Fact" && item.sessionId != null) {
                    Text(
                        text = stringResource(R.string.memory_source_session, item.sessionId.take(8)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (createdAtText != null) {
                    Text(
                        text = createdAtText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/**
 * 记忆行操作区 — 设置重要程度 / 编辑 / 删除。
 */
@Composable
private fun MemoryRowTrailing(
    item: MemoryItem,
    onDelete: ((MemoryItem) -> Unit)?,
    onEdit: ((MemoryItem) -> Unit)?,
    onSetImportance: ((MemoryItem) -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onSetImportance != null) {
            IconButton(
                onClick = { onSetImportance(item) },
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = stringResource(R.string.memory_sync_set_importance_cd),
                    tint = when (item.importance) {
                        2 -> MaterialTheme.colorScheme.error
                        1 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (onEdit != null) {
            IconButton(
                onClick = { onEdit(item) },
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.memory_screen_edit_cd),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (onDelete != null) {
            IconButton(
                onClick = { onDelete(item) },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.memory_screen_delete_cd),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * v4: 重要程度徽章。
 */
@Composable
private fun ImportanceBadge(importance: Int) {
    if (importance <= 0) return
    val icon = if (importance >= 2) Icons.Filled.Warning else Icons.Filled.PriorityHigh
    val text = if (importance >= 2) {
        stringResource(R.string.memory_importance_critical)
    } else {
        stringResource(R.string.memory_importance_important)
    }
    val color = if (importance >= 2) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * v4: 根据 createdAt 计算相对时间文案。
 */
@Composable
private fun formatCreatedAtText(createdAt: String?): String? {
    if (createdAt == null) return null
    val days = try {
        Duration.between(Instant.parse(createdAt), Instant.now()).toDays()
    } catch (e: Exception) {
        return null
    }
    return when {
        days <= 0L -> stringResource(R.string.memory_created_today)
        days == 1L -> stringResource(R.string.memory_created_yesterday)
        else -> stringResource(R.string.memory_created_days_ago, days.toInt())
    }
}

/**
 * 错误堆栈展示框。
 */
@Composable
private fun ErrorTraceBox(
    trace: String,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MusePaddings.cardInnerSpaced),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.memory_screen_load_failed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            IconButton(onClick = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("Muse Error Trace", trace)
                )
                MuseToast.show(context.getString(R.string.memory_screen_copied_trace))
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.memory_screen_copy_trace_cd))
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
            shape = MuseShapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Text(
                text = trace,
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(MusePaddings.itemGap),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MuseMonoFontFamily,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            shape = MuseShapes.large,
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.memory_screen_retry))
        }
    }
}

/**
 * 空状态提示。
 */
@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.largeGap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/**
 * P2: 通用内容编辑对话框。
 */
@Composable
private fun FactEditDialog(
    title: String,
    initialContent: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(initialContent) { mutableStateOf(initialContent) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            MuseTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 240.dp),
                placeholder = { Text(stringResource(R.string.memory_screen_input_content)) },
            )
        },
        confirmText = stringResource(R.string.memory_screen_save),
        onConfirm = {
            if (text.isNotBlank()) {
                onConfirm(text)
            }
        },
        dismissText = stringResource(R.string.memory_screen_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * 新增元事实对话框。
 */
@Composable
private fun AddFactDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    FactEditDialog(
        title = stringResource(R.string.memory_add_fact_dialog_title),
        initialContent = "",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/**
 * v4: 重要程度选择对话框。
 */
@Composable
private fun ImportanceSelectDialog(
    currentImportance: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.memory_importance_set_title),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ImportanceOptionRow(
                    title = stringResource(R.string.memory_importance_normal),
                    desc = stringResource(R.string.memory_importance_normal_desc),
                    selected = currentImportance == 0,
                    onClick = { onSelect(0) },
                )
                ImportanceOptionRow(
                    title = stringResource(R.string.memory_importance_important),
                    desc = stringResource(R.string.memory_importance_important_desc),
                    selected = currentImportance == 1,
                    onClick = { onSelect(1) },
                )
                ImportanceOptionRow(
                    title = stringResource(R.string.memory_importance_critical),
                    desc = stringResource(R.string.memory_importance_critical_desc),
                    selected = currentImportance == 2,
                    onClick = { onSelect(2) },
                )
            }
        },
        onConfirm = null,
        dismissText = stringResource(R.string.memory_screen_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * v4: 重要程度选项行。
 */
@Composable
private fun ImportanceOptionRow(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = MusePaddings.contentGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * iOS/MANUS 概览统计卡片 — 3 列大数字 + 细竖线分隔。
 */
@Composable
private fun OverviewStatCard(state: MemoryUiState) {
    val highImportanceCount = remember(state.factItems) {
        state.factItems.count { it.importance >= 1 }
    }

    CardGroup {
        item(
            headlineContent = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OverviewStatColumn(
                        value = state.totalFactCount.toString(),
                        label = stringResource(R.string.memory_overview_total),
                        modifier = Modifier.weight(1f),
                    )
                    VerticalHairline()
                    OverviewStatColumn(
                        value = highImportanceCount.toString(),
                        label = stringResource(R.string.memory_overview_important),
                        valueColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    VerticalHairline()
                    OverviewStatColumn(
                        value = "4",
                        label = stringResource(R.string.memory_overview_layers),
                        modifier = Modifier.weight(1f),
                    )
                }
            },
        )
    }
}

@Composable
private fun OverviewStatColumn(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            fontFamily = MuseMonoFontFamily,
        )
        Spacer(Modifier.size(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun VerticalHairline() {
    Box(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .width(0.5.dp)
            .height(40.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    )
}

/**
 * v0.51: 记忆 dashboard 概览卡片。
 *
 * 使用 [CardGroup] 作为容器,保持统计图表与健康详情。
 */
@Composable
private fun MemoryDashboardCard(state: MemoryUiState) {
    val hasAnyError = state.healthMap.values.any { it.failCount > 0 }
    val healthText = if (state.healthMap.isEmpty()) {
        stringResource(R.string.memory_screen_not_running)
    } else if (hasAnyError) {
        stringResource(R.string.memory_screen_has_error)
    } else {
        stringResource(R.string.memory_screen_normal)
    }
    val healthColor = if (hasAnyError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    var healthExpanded by rememberSaveable { mutableStateOf(false) }
    var statsExpanded by rememberSaveable { mutableStateOf(true) }

    CardGroup {
        item(
            headlineContent = {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.memory_screen_overview),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(12.dp))
                    DashboardMetricRow(label = stringResource(R.string.memory_screen_fact_total_label), value = state.factCount.toString())
                    DashboardMetricRow(
                        label = stringResource(R.string.memory_screen_last_compile),
                        value = state.lastCompileTime ?: stringResource(R.string.memory_screen_no_compile_time),
                    )
                    DashboardMetricRow(label = stringResource(R.string.memory_screen_health), value = healthText, valueColor = healthColor)

                    if (state.syncStatus.isNotBlank()) {
                        val isStale = state.syncStatus.contains("可能还未进入记忆")
                        Spacer(Modifier.size(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = if (isStale) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = state.syncStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isStale) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                            )
                        }
                    }

                    Spacer(Modifier.size(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { statsExpanded = !statsExpanded }
                            .semantics { role = Role.Button }
                            .padding(vertical = MusePaddings.tightGap),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.TrendingUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.memory_stats_section_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (statsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    if (statsExpanded) {
                        Spacer(Modifier.size(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            StatChip(label = stringResource(R.string.memory_stats_total), value = state.totalFactCount.toString())
                            StatChip(label = stringResource(R.string.memory_filter_week), value = state.weekNewCount.toString())
                            StatChip(label = stringResource(R.string.memory_filter_month), value = state.monthNewCount.toString())
                        }
                        Spacer(Modifier.size(12.dp))
                        if (state.importanceDistribution.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.memory_stats_importance_distribution),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.size(4.dp))
                            ImportancePieChart(distribution = state.importanceDistribution)
                        }
                        if (state.topSessions.isNotEmpty()) {
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = stringResource(R.string.memory_stats_top_sessions),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.size(4.dp))
                            state.topSessions.take(3).forEach { (sid, count) ->
                                DashboardMetricRow(label = sid.take(12), value = stringResource(R.string.memory_stats_session_count, count))
                            }
                        }
                        if (state.dailyTrend.size >= 2) {
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = stringResource(R.string.memory_stats_trend_30_days),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.size(4.dp))
                            TrendLineChart(dailyData = state.dailyTrend)
                        }
                    }

                    if (state.healthMap.isNotEmpty()) {
                        Spacer(Modifier.size(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { healthExpanded = !healthExpanded }
                                .padding(vertical = MusePaddings.tightGap),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = if (healthExpanded) stringResource(R.string.memory_screen_collapse_steps) else stringResource(R.string.memory_screen_view_steps),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (healthExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (healthExpanded) stringResource(R.string.memory_screen_collapse) else stringResource(R.string.memory_screen_expand),
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        AnimatedVisibility(
                            visible = healthExpanded,
                            enter = expandVertically(),
                            exit = shrinkVertically(),
                        ) {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                state.healthMap.forEach { (step, health) ->
                                    HealthStepRow(stepKey = step, health = health)
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

/**
 * v5: 统计小标签。
 */
@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = MuseShapes.pill,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(MusePaddings.cardInnerTight),
            )
        }
        Spacer(Modifier.size(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * v5: 重要度分布饼图。
 */
@Composable
private fun ImportancePieChart(distribution: Map<Int, Int>) {
    val total = distribution.values.sum().coerceAtLeast(1)
    val normalColor = MaterialTheme.colorScheme.onSurfaceVariant
    val importantColor = MaterialTheme.colorScheme.tertiary
    val criticalColor = MaterialTheme.colorScheme.error
    val segments = listOf(
        0 to normalColor,
        1 to importantColor,
        2 to criticalColor,
    ).map { (key, color) ->
        val count = distribution[key] ?: 0
        Triple(key, color, count.toFloat() / total)
    }
    val surfaceColor = MaterialTheme.colorScheme.surface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
            Canvas(modifier = Modifier.size(80.dp).semantics { contentDescription = "记忆类型分布环形图" }) {
            var startAngle = -90f
            segments.forEach { (key, color, ratio) ->
                if (ratio > 0f) {
                    drawArc(
                        color = color,
                        startAngle = startAngle,
                        sweepAngle = 360f * ratio,
                        useCenter = true,
                    )
                    startAngle += 360f * ratio
                }
            }
            drawCircle(color = surfaceColor, radius = size.minDimension * 0.3f)
        }
        Column {
            segments.forEach { (key, color, ratio) ->
                val label = when (key) {
                    2 -> stringResource(R.string.memory_importance_critical)
                    1 -> stringResource(R.string.memory_importance_important)
                    else -> stringResource(R.string.memory_importance_normal)
                }
                val percent = (ratio * 100).toInt()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(modifier = Modifier.size(8.dp).drawBehind { drawCircle(color = color) })
                    Text(
                        text = "$label $percent%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * v5: 记忆增长趋势折线图。
 */
@Composable
private fun TrendLineChart(dailyData: List<Pair<String, Int>>) {
    val maxVal = dailyData.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val baselineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    Box(modifier = Modifier.fillMaxWidth().height(100.dp)) {
        Canvas(modifier = Modifier.fillMaxSize().semantics { contentDescription = "每日记忆趋势图" }) {
            val stepX = size.width / (dailyData.size - 1).coerceAtLeast(1)
            val points = dailyData.mapIndexed { index, (_, count) ->
                Offset(
                    x = index * stepX,
                    y = size.height - (count.toFloat() / maxVal) * size.height * 0.85f - size.height * 0.05f,
                )
            }
            if (points.size >= 2) {
                for (i in 0 until points.size - 1) {
                    drawLine(
                        color = lineColor,
                        start = points[i],
                        end = points[i + 1],
                        strokeWidth = 2.dp.toPx(),
                    )
                }
                points.forEach { point ->
                    drawCircle(color = lineColor, radius = 2.dp.toPx(), center = point)
                }
            }
            drawLine(
                color = baselineColor,
                start = Offset(0f, size.height - 1.dp.toPx()),
                end = Offset(size.width, size.height - 1.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
}

/**
 * dashboard 单行指标。
 */
@Composable
private fun DashboardMetricRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tightGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 单个记忆步骤的健康行。
 */
@Composable
private fun HealthStepRow(
    stepKey: String,
    health: io.zer0.memory.ticker.MemoryTicker.StepHealth,
) {
    Column(modifier = Modifier.padding(vertical = MusePaddings.labelVerticalGap)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stepKey,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (health.failCount > 0) stringResource(R.string.memory_screen_failed_times, health.failCount) else stringResource(R.string.memory_screen_normal),
                style = MaterialTheme.typography.labelSmall,
                color = if (health.failCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            )
        }
        val lastSuccessAt = health.lastSuccessAt
        if (lastSuccessAt != null) {
            Text(
                text = stringResource(R.string.memory_screen_last_success, lastSuccessAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        val lastErrorMsg = health.lastErrorMsg
        if (lastErrorMsg != null) {
            Text(
                text = stringResource(R.string.memory_screen_error, lastErrorMsg),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * v0.51: "AI 对你的理解" 摘要卡片。
 */
@Composable
private fun MemorySummaryCard(markdown: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val hasSummary = markdown.isNotBlank()

    CardGroup {
        item(
            onClick = { expanded = !expanded },
            headlineContent = {
                Text(
                    text = stringResource(R.string.memory_screen_ai_understanding),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            trailingContent = {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.memory_screen_collapse) else stringResource(R.string.memory_screen_expand),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        if (expanded || !hasSummary) {
            item(
                headlineContent = {
                    if (!hasSummary) {
                        Text(
                            text = stringResource(R.string.memory_screen_no_summary_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    } else {
                        Column {
                            Text(
                                text = stringResource(R.string.memory_screen_click_collapse),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.size(8.dp))
                            MarkdownText(
                                text = markdown,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                },
            )
        }
    }
}

/**
 * v1.98: 经验库卡片。
 */
@Composable
private fun ExperienceLibraryCard(
    items: List<MemoryItem>,
    onAdd: () -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onDelete: (MemoryItem) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    CardGroup {
        item(
            onClick = { expanded = !expanded },
            headlineContent = {
                Column {
                    Text(
                        text = stringResource(R.string.memory_screen_experience_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = stringResource(R.string.memory_screen_experience_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = MuseShapes.pill,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = stringResource(R.string.memory_screen_experience_count, items.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) stringResource(R.string.memory_screen_collapse) else stringResource(R.string.memory_screen_expand),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )

        if (expanded) {
            if (items.isEmpty()) {
                item(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.memory_screen_experience_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    },
                )
            } else {
                items.forEach { item ->
                    item(
                        key = item.id,
                        headlineContent = { MemoryRowHeadline(item) },
                        supportingContent = { MemoryRowSupporting(item) },
                        trailingContent = {
                            MemoryRowTrailing(
                                item = item,
                                onDelete = { onDelete(item) },
                                onEdit = { onEdit(item) },
                                onSetImportance = null,
                            )
                        },
                    )
                }
            }
            item(
                headlineContent = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = onAdd,
                            shape = MuseShapes.large,
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.memory_screen_experience_add_cd), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.memory_screen_experience_add_dialog_title))
                        }
                    }
                },
            )
        }
    }
}

/**
 * v1.98: 经验库新增/编辑对话框。
 */
@Composable
private fun ExperienceEditDialog(
    title: String,
    initialTitle: String,
    initialContent: String,
    initialCategory: String,
    initialTags: String,
    onDismiss: () -> Unit,
    onConfirm: (title: String, content: String, category: String, tags: List<String>) -> Unit,
) {
    var titleText by remember(initialTitle) { mutableStateOf(initialTitle) }
    var contentText by remember(initialContent) { mutableStateOf(initialContent) }
    var categoryText by remember(initialCategory) { mutableStateOf(initialCategory) }
    var tagsText by remember(initialTags) { mutableStateOf(initialTags) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MuseTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.memory_screen_experience_title_label)) },
                    placeholder = { Text(stringResource(R.string.memory_screen_experience_title_hint)) },
                    singleLine = true,
                )
                MuseTextField(
                    value = contentText,
                    onValueChange = { contentText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp),
                    label = { Text(stringResource(R.string.memory_screen_experience_content_label)) },
                    placeholder = { Text(stringResource(R.string.memory_screen_experience_content_hint)) },
                )
                MuseTextField(
                    value = categoryText,
                    onValueChange = { categoryText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.memory_screen_experience_category_label)) },
                    singleLine = true,
                )
                MuseTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.memory_screen_experience_tags_label)) },
                    singleLine = true,
                )
            }
        },
        confirmText = stringResource(R.string.memory_screen_save),
        onConfirm = {
            if (titleText.isNotBlank() && contentText.isNotBlank()) {
                val tags = tagsText.split(",", "，", ";", "；")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                onConfirm(titleText, contentText, categoryText, tags)
            }
        },
        dismissText = stringResource(R.string.memory_screen_cancel),
        onDismiss = onDismiss,
    )
}

// ── Phase 2 2D: Export helpers ──────────────────────────────────────────

private fun buildMemoryMarkdown(
    facts: List<MemoryItem>,
    summaries: List<MemoryItem>,
): String {
    val sb = StringBuilder()
    sb.appendLine("# Muse Memory Export")
    sb.appendLine()
    sb.appendLine("Exported at: ${java.time.Instant.now()}")
    sb.appendLine()
    sb.appendLine("## Facts (${facts.size})")
    sb.appendLine()
    facts.forEach { f ->
        val stars = "⭐".repeat(f.importance)
        sb.appendLine("- $stars ${f.content}")
        if (f.tags.isNotEmpty()) sb.appendLine("  Tags: ${f.tags.joinToString(", ")}")
        f.createdAt?.let { sb.appendLine("  Created: $it") }
        sb.appendLine()
    }
    sb.appendLine("## Summaries (${summaries.size})")
    sb.appendLine()
    summaries.forEach { s ->
        sb.appendLine("### ${s.title}")
        sb.appendLine(s.content)
        sb.appendLine()
    }
    return sb.toString()
}

private fun buildMemoryJson(
    facts: List<MemoryItem>,
    summaries: List<MemoryItem>,
): String {
    val sb = StringBuilder()
    sb.appendLine("{")
    sb.appendLine("  \"exportedAt\": \"${java.time.Instant.now()}\",")
    sb.appendLine("  \"facts\": [")
    facts.forEachIndexed { i, f ->
        val comma = if (i < facts.size - 1) "," else ""
        val escaped = f.content.replace("\"", "\\\"").replace("\n", "\\n")
        sb.appendLine("    {\"id\": \"${f.id}\", \"content\": \"$escaped\", \"importance\": ${f.importance}, \"tags\": [${f.tags.joinToString(",") { "\"$it\"" }}], \"createdAt\": \"${f.createdAt ?: ""}\"}$comma")
    }
    sb.appendLine("  ],")
    sb.appendLine("  \"summaries\": [")
    summaries.forEachIndexed { i, s ->
        val comma = if (i < summaries.size - 1) "," else ""
        val escaped = s.content.replace("\"", "\\\"").replace("\n", "\\n")
        sb.appendLine("    {\"id\": \"${s.id}\", \"title\": \"${s.title}\", \"content\": \"$escaped\"}$comma")
    }
    sb.appendLine("  ]")
    sb.appendLine("}")
    return sb.toString()
}

private fun shareText(context: android.content.Context, text: String, fileName: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = if (fileName.endsWith(".json")) "application/json" else "text/markdown"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
        putExtra(android.content.Intent.EXTRA_SUBJECT, fileName)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Share Memory Export"))
}
