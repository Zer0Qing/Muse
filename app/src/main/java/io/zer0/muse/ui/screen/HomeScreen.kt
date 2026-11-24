package io.zer0.muse.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.agentbot.AgentModelRouter
import io.zer0.muse.data.agentbot.BotTaskStatus
import io.zer0.muse.ui.agentbot.AgentBotViewModel
import io.zer0.muse.ui.agentbot.AgentWorkbenchTab
import io.zer0.muse.ui.chat.WorkspaceQuickPickDialog
import io.zer0.muse.ui.common.MuseFloatingActionItem
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.horizontalGestureExclusion
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.DesktopShortcuts
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberDesktopShortcutsEnabled
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.navigation.MuseTopBarIconButton
import io.zer0.muse.ui.common.navigation.MuseTopBarMenu
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.groupchat.GroupChatListScreen
import io.zer0.muse.ui.theme.GlassIsland
import io.zer0.muse.ui.theme.LocalGlassIsBackgroundDark
import io.zer0.muse.ui.theme.LocalLayerBackdrop
import io.zer0.muse.ui.theme.LocalLiquidGlass
import io.zer0.muse.ui.theme.LocalWaterGlassState
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.statusColors
import io.zer0.muse.ui.theme.titleSmallStrong
import io.zer0.muse.update.UpdateChecker
import io.zer0.muse.update.UpdateNotifier
import io.zer0.muse.util.ShareIntentHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import io.zer0.muse.ui.assistant.ModelSwitchSheet
import io.zer0.muse.ui.chat.ChatScreen
import io.zer0.muse.ui.chat.ChatViewModel
import io.zer0.muse.ui.chat.CommandPalette
import io.zer0.muse.ui.chat.ChatListScreen

// M-CS7: tabs 列表提为文件级 val,避免每次 recomposition 重新创建(原在 Composable 内 listOf)
// 存资源 id(Int) 与页面序号,UI 层统一用 stringResource 解析。
private val HomeTabs = listOf(R.string.home_tab_tasks to 0, R.string.home_tab_agent to 1, R.string.home_tab_group_chat to 2)

/**
 * v0.22 首页 — 顶部双分页导航。
 *
 * 架构(极简功能主义设计语言):
 *  - 顶部固定栏: 左侧头像(→设置) | 中间胶囊Tab(任务/Agent) | 右侧搜索
 *  - 内容区: HorizontalPager(2页),两 Tab 完全独立,不互相跳转
 *    - Tab 0 "任务": 任务列表(用户日常工作调研)
 *    - Tab 1 "Agent": 长效日常聊天搭子(独立聊天区域)
 *
 * 设计要点(极简功能主义):
 *  - 暖灰背景(WarmPaper LightBg #FAFAF8),不纯白
 *  - 胶囊Tab: 灰色凹槽容器,选中项白色凸起
 *  - 品牌绿只做点缀(发送按钮/FAB/选中态)
 *  - 大圆角(16-18dp)
 *  - 极简:无装饰元素,内容是唯一视觉焦点
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenAssistants: () -> Unit = {},
    onOpenScheduledTasks: () -> Unit = {},
    onOpenQuickNotes: () -> Unit = {},
    /** 文件夹独立详情页：点文件夹进该页（由 HomeRoute 接导航）。 */
    onOpenFolder: (String) -> Unit = {},
    /** v1.0.27: 打开快速翻译页。 */
    onOpenQuickTranslate: () -> Unit = {},
    /** v1.0.27: 打开知识库页。 */
    onOpenKnowledgeBase: () -> Unit = {},
    /** v0.27: 点击任务项 / 新建任务时 push 到独立聊天详情页。 */
    onOpenChat: () -> Unit = {},
    /** v0.45: 打开独立全局搜索页。 */
    onOpenSearch: () -> Unit = {},
    /**
     * 打开工作区到指定相对目录（顶栏「更多 → 工作区」用）。
     *
     * 为什么首页也要这一项：Agent 标签页模式下 `ChatScreen` 自带顶栏是隐藏的
     * （`isAgentMode` / `ChatSurface.AGENT`），三点菜单由本文件提供 ——
     * 只做在聊天页里，这个模式就完全点不到。
     */
    onOpenWorkspace: (String?) -> Unit = {},
    /** v1.30: 群聊 Tab 中点击群聊卡片 → 跳转群聊详情页(参数为 chatId)。 */
    onOpenGroupChat: (String) -> Unit = {},
    /** v2.0: 打开最近删除页。 */
    onOpenRecentlyDeleted: () -> Unit = {},
    /** v0.45: 打开归档聊天列表页。 */
    onOpenArchivedChats: () -> Unit = {},
    /** 2026-11-20: 打开已归档群聊列表页（群聊归档后此前没有任何恢复入口）。 */
    onOpenArchivedGroupChats: () -> Unit = {},
    /** v1.0.72: 打开小手机 + AI 朋友圈沉浸页。 */
    onOpenMiniPhone: () -> Unit = {},
    /** HTML/SVG 代码块全屏预览回调(由 Tab 1 ChatScreen 触发)。 */
    onHtmlPreview: (String) -> Unit = {},
    /** 加号菜单 → 技能入口。 */
    onOpenSkills: () -> Unit = {},
    /** B0-07: 打开提示词模板管理页。 */
    onOpenPromptTemplateManager: () -> Unit = {},
    /**
     * 打开供应商设置页。
     *
     * 首页与 Agent Tab 的 ChatScreen 都靠这个回调提供"去配置模型"的出口；
     * 此前 ChatScreen 的该参数默认是空 lambda 且这两处都没传，
     * 于是「未配置模型」横幅上唯一的自救按钮点了没有任何反应。
     */
    onOpenProviderSettings: () -> Unit = {},
    /** 打开离线资源 / 运行环境设置页。 */
    onOpenRuntimeInstall: () -> Unit = {},
    // v1.131: ChatViewModel 在 AppKoinModule 中以 `single { }` 注册(见 v1.92 注释),
    // 全应用共享同一实例,故用 koinInject() 直接取单例即可。
    // koinViewModel() 对 single 注册也会返回同一实例,但语义上 koinInject 更准确。
    viewModel: ChatViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // P2-13: 桌面端快捷键总开关(Expanded 窗口 + 物理键盘)
    val desktopShortcutsEnabled = rememberDesktopShortcutsEnabled()
    // v1.95: 启动默认页由设置决定(0=任务, 1=Agent, 2=群聊),initialPage 仅首次组合生效
    val settings: SettingsRepository = koinInject()
    val defaultPage by settings.defaultHomePageFlow.collectAsStateWithLifecycle(initialValue = 0)
    // v1.133: 订阅缓存的 ReleaseInfo,有值则渲染 Banner
    val releaseJson by settings.latestReleaseInfoFlow.collectAsStateWithLifecycle(initialValue = null)
    val pagerState = rememberPagerState(initialPage = defaultPage, pageCount = { 3 })
    var initialPageSynced by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!initialPageSynced) {
            val savedPage = runCatching { settings.defaultHomePageFlow.first() }.getOrDefault(0).coerceIn(0, 2)
            if (savedPage != pagerState.currentPage) {
                pagerState.scrollToPage(savedPage)
            }
            initialPageSynced = true
        }
    }
    // 01①: 更多菜单触发任务列表的编辑/新建文件夹，ChatListScreen 消费后回执清零。
    var pendingListAction by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // v1.41: Agent Tab 模型切换面板展开状态
    var showModelSheet by remember { mutableStateOf(false) }
    // v1.133: 用户主动关闭 Banner 后,本次会话不再展示(下次冷启动恢复)
    var bannerDismissed by remember { mutableStateOf(false) }
    // v1.136 T8: 首页右下快捷工具栏展开/收起状态(长按 Plus 切换)
    var capsuleExpanded by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current
    // v1.137 B3: 区分用户拖拽和点击动画 — 点击时设 clickAnimating=true,
    // isScrollInProgress 结束后清除,使 MuseCapsuleTab 在拖拽时用连续跟踪、点击时用 tween。
    var clickAnimating by remember { mutableStateOf(false) }
    val reducedMotion = MuseMotion.isReducedMotion()
    // C1: 全局命令面板可见性(搜索按钮 / Ctrl+K 唤起)
    var showCommandPalette by rememberSaveable { mutableStateOf(false) }
    var showWorkspacePick by rememberSaveable { mutableStateOf(false) }
    // C4: 宽屏自适应双栏 — Expanded(≥840dp) 时任务 tab 会话列表+消息同屏,
    // 窄屏保持原单栏 + push 详情页交互
    val widthClass = rememberWindowWidthClass()
    val isWideTasks = widthClass == WindowWidthClass.Expanded
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) clickAnimating = false
    }

    // v1.28: 进入/离开 Agent Tab 时切换 Agent 模式(独立会话,不依赖任务)
    // v1.104: 用 settledPage 替代 currentPage,只在 ViewPager 完全停稳后触发,
    // 避免从 page 0→2 滑过 page 1 中间态时误触发 setAgentMode(会重载消息、停 TTS/ASR)
    LaunchedEffect(pagerState.settledPage) {
        viewModel.setAgentMode(pagerState.settledPage == 1)
    }

    // v1.0.16: 新建任务/新会话统一入口 — 被 tab 下方卡片和右下角悬浮胶囊复用
    // C4: 宽屏双栏时只创建会话(右栏 ChatScreen 跟随 currentSessionId 就地显示),不 push 详情页
    val onCreateNewTask: () -> Unit = {
        if (isWideTasks) {
            viewModel.createNewSession()
        } else {
            viewModel.createNewSession(onReady = onOpenChat)
        }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    var homeTopBarHeightPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val homeTopBarHeightDp = with(density) { homeTopBarHeightPx.toDp() }
    val glassConfig = LocalLiquidGlass.current
    val glassActive = glassConfig.enabled &&
        io.zer0.muse.ui.theme.isRealGlassSupported() &&
        !io.zer0.muse.ui.theme.LocalInsideBackdropRecorder.current
    val homeLayerBackdrop = if (glassActive && glassConfig.style == io.zer0.muse.ui.theme.GlassStyle.FROST) {
        rememberLayerBackdrop()
    } else {
        null
    }
    val homeWaterState = if (glassActive && glassConfig.style == io.zer0.muse.ui.theme.GlassStyle.WATER) {
        rememberLiquidState()
    } else {
        null
    }
    val homeBackgroundIsDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val botViewModel: AgentBotViewModel? = if (io.zer0.muse.feature.FeatureFlags.AGENT_BOT) {
        koinInject()
    } else {
        null
    }
    var requestedWorkbenchTab by rememberSaveable { mutableStateOf<AgentWorkbenchTab?>(null) }
    var requestedBriefingTick by remember { mutableIntStateOf(0) }
    val botTaskBoardState by (botViewModel?.taskBoard ?: kotlinx.coroutines.flow.flowOf(
        io.zer0.muse.data.agentbot.AgentBotUiState.TaskBoard(),
    )).collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.data.agentbot.AgentBotUiState.TaskBoard(),
    )
    val botModelRoutesState by (botViewModel?.modelRoutes ?: kotlinx.coroutines.flow.flowOf(
        AgentModelRouter.Snapshot(),
    )).collectAsStateWithLifecycle(initialValue = AgentModelRouter.Snapshot())
    val botActiveBadgeCount = botTaskBoardState.tasks.count { it.status in BotTaskStatus.NON_TERMINAL } +
        botTaskBoardState.approvals.size
    val botHasPendingApproval = botTaskBoardState.approvals.isNotEmpty()
    val effectiveAgentForegroundBinding = botModelRoutesState.foreground
    val effectiveAgentProviderId = effectiveAgentForegroundBinding.providerId.ifBlank {
        state.currentAssistant?.providerId?.takeIf { it.isNotBlank() } ?: state.activeProviderId
    }
    val effectiveAgentModelId = effectiveAgentForegroundBinding.modelId.ifBlank {
        state.currentAssistant?.modelId?.takeIf { it.isNotBlank() } ?: state.selectedModelId
    }

    val floatingTopBar: @Composable () -> Unit = {
        // 顶部透明镂空三岛（Agent 页含右上工作台入口）：左侧头像(→设置) | 中间胶囊 Tab(任务/Agent/群聊) | 右侧工作台+更多
        // 两侧与按钮间隙完全透明镂空，Agent 页上下滑动时对话气泡可直接穿行至按钮旁。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .statusBarsPadding()
                .padding(horizontal = MusePaddings.inputPadding, vertical = MusePaddings.contentGap),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MuseTopBarIconButton(
                icon = MuseIcons.user,
                contentDescription = stringResource(R.string.home_settings_cd),
                onClick = onOpenSettings,
                glassActive = glassActive,
                glassConfig = glassConfig,
            )

            val tabLabels = HomeTabs.map { (labelResId, _) ->
                stringResource(labelResId)
            }
            MuseCapsuleTab(
                tabs = tabLabels,
                selectedIndex = pagerState.currentPage,
                onSelect = { page ->
                    clickAnimating = true
                    scope.launch {
                        if (reducedMotion) {
                            pagerState.scrollToPage(page)
                        } else {
                            pagerState.animateScrollToPage(page)
                        }
                    }
                },
                pageOffset = pagerState.currentPageOffsetFraction,
                isDragging = pagerState.isScrollInProgress && !clickAnimating,
                glassActive = glassActive,
                glassConfig = glassConfig,
                modifier = Modifier.widthIn(max = 172.dp),
            )

            val showMiniPhone = io.zer0.muse.feature.FeatureFlags.MINI_PHONE_ENTRY
            val searchLabel = stringResource(R.string.home_search_cd)
            val miniPhoneLabel = stringResource(R.string.home_miniphone_cd)
            val workspaceLabel = stringResource(R.string.workspace_title)
            val archivedEntryLabel = stringResource(R.string.chat_list_archived_entry, state.archivedSessions.size)
            val archivedGroupChatsLabel = stringResource(R.string.groupchat_archived_title)
            val newFolderLabel = stringResource(R.string.chat_list_new_folder)
            val editListLabel = stringResource(R.string.common_edit)
            val providerLabel = stringResource(R.string.chat_select_provider)
            val switchAssistantLabel = stringResource(R.string.agent_hub_switch_bot)
            val briefingLabel = stringResource(R.string.agent_hub_quick_briefing)
            val teachLabel = stringResource(R.string.agent_hub_quick_teach)
            val topMenuItems = buildList {
                add(
                    MuseFloatingActionItem(
                        key = "search",
                        icon = MuseIcons.search,
                        label = searchLabel,
                        onClick = { showCommandPalette = true },
                    ),
                )
                if (pagerState.currentPage == 1 && botViewModel != null) {
                    add(
                        MuseFloatingActionItem(
                            key = "switch_assistant",
                            icon = MuseIcons.robot,
                            label = switchAssistantLabel,
                            onClick = { requestedWorkbenchTab = AgentWorkbenchTab.SQUAD },
                        ),
                    )
                }
                if (pagerState.currentPage == 0 || pagerState.currentPage == 1) {
                    add(
                        MuseFloatingActionItem(
                            key = "provider",
                            icon = MuseIcons.memoryChip,
                            label = providerLabel,
                            onClick = { showModelSheet = true },
                        ),
                    )
                }
                if (pagerState.currentPage == 1 && botViewModel != null) {
                    add(
                        MuseFloatingActionItem(
                            key = "briefing",
                            icon = MuseIcons.sun,
                            label = briefingLabel,
                            onClick = { requestedBriefingTick++ },
                        ),
                    )
                    add(
                        MuseFloatingActionItem(
                            key = "teach",
                            icon = MuseIcons.handFinger,
                            label = teachLabel,
                            onClick = {
                                botViewModel.startTeachRecording()
                                requestedWorkbenchTab = AgentWorkbenchTab.ROUTINES
                            },
                        ),
                    )
                }
                if (showMiniPhone) {
                    add(
                        MuseFloatingActionItem(
                            key = "miniphone",
                            icon = MuseIcons.deviceMobile,
                            label = miniPhoneLabel,
                            onClick = onOpenMiniPhone,
                        ),
                    )
                }
                add(
                    MuseFloatingActionItem(
                        key = "workspace",
                        icon = MuseIcons.folderOpen,
                        label = workspaceLabel,
                        onClick = { showWorkspacePick = true },
                    ),
                )
                if (pagerState.currentPage == 2) {
                    add(
                        MuseFloatingActionItem(
                            key = "archived_group_chats",
                            icon = MuseIcons.archive,
                            label = archivedGroupChatsLabel,
                            onClick = onOpenArchivedGroupChats,
                        ),
                    )
                }
                if (pagerState.currentPage == 0) {
                    add(
                        MuseFloatingActionItem(
                            key = "archived",
                            icon = MuseIcons.archive,
                            label = archivedEntryLabel,
                            onClick = onOpenArchivedChats,
                        ),
                    )
                    add(
                        MuseFloatingActionItem(
                            key = "new_folder",
                            icon = MuseIcons.folderPlus,
                            label = newFolderLabel,
                            onClick = { pendingListAction = "create_folder" },
                        ),
                    )
                    add(
                        MuseFloatingActionItem(
                            key = "edit_list",
                            icon = MuseIcons.edit,
                            label = editListLabel,
                            onClick = { pendingListAction = "edit" },
                        ),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                if (pagerState.currentPage == 1 && botViewModel != null) {
                    Box {
                        MuseTopBarIconButton(
                            icon = MuseIcons.computer,
                            contentDescription = stringResource(R.string.agent_hub_open_workbench),
                            onClick = { requestedWorkbenchTab = AgentWorkbenchTab.LIVE },
                            tint = if (botHasPendingApproval) {
                                MaterialTheme.statusColors.warning
                            } else {
                                Color.Unspecified
                            },
                            glassActive = glassActive,
                            glassConfig = glassConfig,
                        )
                        if (botActiveBadgeCount > 0) {
                            Surface(
                                shape = MuseShapes.pill,
                                color = if (botHasPendingApproval) {
                                    MaterialTheme.statusColors.warning
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 2.dp, end = 2.dp),
                            ) {
                                Text(
                                    text = if (botActiveBadgeCount > 9) "9+" else botActiveBadgeCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(
                                        horizontal = MusePaddings.labelVerticalGap,
                                        vertical = MusePaddings.compactChipVertical,
                                    ),
                                )
                            }
                        }
                    }
                }
                MuseTopBarMenu(
                    contentDescription = stringResource(R.string.action_more),
                    items = topMenuItems,
                    glassActive = glassActive,
                    glassConfig = glassConfig,
                )
            }
            if (showWorkspacePick) {
                WorkspaceQuickPickDialog(
                    onOpen = { pickPath ->
                        showWorkspacePick = false
                        onOpenWorkspace(pickPath)
                    },
                    onDismiss = { showWorkspacePick = false },
                )
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalLayerBackdrop provides (homeLayerBackdrop ?: LocalLayerBackdrop.current),
        LocalWaterGlassState provides (homeWaterState ?: LocalWaterGlassState.current),
        LocalGlassIsBackgroundDark provides homeBackgroundIsDark,
    ) {
    MusePageScaffold(
        topBarHandlesInsets = true,
        floatingActionButton = {
            if (pagerState.currentPage == 0 && !isWideTasks) {
                HomeQuickActionCapsule(
                    onOpenScheduledTasks = onOpenScheduledTasks,
                    onOpenQuickNotes = onOpenQuickNotes,
                    onOpenQuickTranslate = onOpenQuickTranslate,
                    onCreateNewTask = onCreateNewTask,
                    expanded = capsuleExpanded,
                    onToggleExpanded = { capsuleExpanded = !capsuleExpanded },
                    glassActive = glassActive,
                    glassConfig = glassConfig,
                )
            }
        },
        topBar = {},
        containerColor = MaterialTheme.colorScheme.background,
    ) { _ ->
        BackHandler(enabled = pagerState.currentPage != 0) {
            scope.launch { pagerState.animateScrollToPage(0) }
        }
        val newChatAction: () -> Unit = {
            viewModel.createNewSession(onReady = onOpenChat)
        }
        val ignoredUpdateVersion by settings.ignoredUpdateVersionFlow.collectAsStateWithLifecycle(initialValue = null)
        val currentVersionName = remember { UpdateNotifier.getCurrentVersionName(context) }
        val release = remember(releaseJson, bannerDismissed, ignoredUpdateVersion, currentVersionName) {
            if (bannerDismissed || releaseJson.isNullOrBlank()) {
                null
            } else {
                parseReleaseInfo(releaseJson)?.takeIf { info ->
                    info.tagName != ignoredUpdateVersion &&
                        UpdateNotifier.compareVersions(currentVersionName, info.tagName) < 0
                }
            }
        }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onKeyEvent { event ->
                        if (!desktopShortcutsEnabled) return@onKeyEvent false
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when {
                            event.key == DesktopShortcuts.FOCUS_SEARCH && event.isCtrlPressed -> {
                                showCommandPalette = true
                                true
                            }
                            event.key == DesktopShortcuts.NEW_CHAT && event.isCtrlPressed &&
                                pagerState.currentPage == 0 -> {
                                newChatAction()
                                true
                            }
                            else -> false
                        }
                    },
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalGestureExclusion()
                        .then(if (homeLayerBackdrop != null) Modifier.layerBackdrop(homeLayerBackdrop) else Modifier)
                        .then(if (homeWaterState != null) Modifier.liquefiable(homeWaterState) else Modifier),
                    userScrollEnabled = true,
                ) { page ->
                    // 关键防崩隔离：HorizontalPager 正在被 homeLayerBackdrop / homeWaterState 录制，
                    // 内部子树必须清空外层 backdrop 并标记已处于录制器内，
                    // 杜绝 AgentInlineIdentityBar 等子组件反向采样外层 backdrop 或嵌套第二层 RenderNode 导致 RenderThread SIGSEGV。
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalLayerBackdrop provides null,
                        LocalWaterGlassState provides null,
                        io.zer0.muse.ui.theme.LocalInsideBackdropRecorder provides
                            (homeLayerBackdrop != null || homeWaterState != null),
                    ) {
                when (page) {
                    0 -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = homeTopBarHeightDp),
                        ) {
                            if (release != null) {
                                val openDownloadPage: () -> Unit = {
                                    val intent = UpdateNotifier.buildOfficialDownloadIntent()
                                    runCatching {
                                        ShareIntentHelper.startChooserSafely(context, intent)
                                    }.onFailure { Logger.w("HomeScreen", "startActivity failed: ${it.message}") }
                                }
                                UpdateAvailableBanner(
                                    release = release,
                                    onClose = {
                                        bannerDismissed = true
                                        scope.launch { settings.saveIgnoredUpdateVersion(release.tagName) }
                                    },
                                    onViewRelease = openDownloadPage,
                                    onDownloadApk = openDownloadPage,
                                )
                            }
                            if (isWideTasks) {
                                Row(modifier = Modifier.fillMaxSize()) {
                                    ChatListScreen(
                                    sessions = state.sessions,
                                    folders = state.folders,
                                    currentSessionId = state.currentSessionId,
                                    onSelect = { id ->
                                        // C4: 宽屏右栏 ChatScreen 跟随 currentSessionId 就地显示,不 push
                                        viewModel.switchSession(id)
                                    },
                                    onCreate = {
                                        viewModel.createNewSession()
                                    },
                                    onDelete = viewModel::deleteSession,
                                    onRename = { session ->
                                        viewModel.renameSession(session.id, session.title)
                                    },
                                    onRenameTo = { session, newName ->
                                        viewModel.renameSession(session.id, newName)
                                    },
                                    onTogglePinned = viewModel::togglePinned,
                                    onReorderPinned = viewModel::reorderPinnedSessions,
                                    onMoveSessionToFolder = viewModel::moveSessionToFolder,
                                    onCreateFolder = viewModel::createFolder,
                                    onRenameFolder = viewModel::renameFolder,
                                    onDeleteFolder = viewModel::deleteFolder,
                                    onToggleFolderExpanded = viewModel::toggleFolderExpanded,
                                    onOpenFolder = onOpenFolder,
                                    assistants = state.assistants,
                                    currentAssistant = state.currentAssistant,
                                    showListToolbar = false,
                                    listAction = pendingListAction,
                                    onListActionConsumed = { pendingListAction = null },
                                    onArchive = { id -> viewModel.setSessionArchived(id, true) },
                                    onOpenScheduledTasks = onOpenScheduledTasks,
                                    onOpenQuickNotes = onOpenQuickNotes,
                                    onOpenQuickTranslate = onOpenQuickTranslate,
                                    onOpenKnowledgeBase = onOpenKnowledgeBase,
                                    onOpenRecentlyDeleted = onOpenRecentlyDeleted,
                                    onOpenAssistants = onOpenAssistants,
                                    // v2.x: 伙伴横排 — 以指定助手开新聊天(宽屏双栏就地切新会话)
                                    onOpenAssistantChat = { id ->
                                        viewModel.createNewSession(assistantIdOverride = id)
                                    },
                                    isSessionsLoading = state.isSessionsLoading,
                                    sessionsError = state.sessionsError,
                                    onRetryLoadSessions = viewModel::retryLoadSessions,
                                    // 2026-11-21:fillMaxSize 先撑满宽度,后面的 width(400.dp) 不生效,
                                    // 右栏 weight(1f) 只能得到 0 宽。改为先定宽再撑高。
                                    modifier = Modifier.width(400.dp).fillMaxHeight(),
                                )
                                VerticalDivider(
                                    modifier = Modifier.fillMaxHeight().width(1.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                )
                                // C4: 右栏消息 — onBack=null 不显示返回按钮,复用共享 ChatViewModel 单例
                                ChatScreen(
                                    onOpenAssistants = onOpenAssistants,
                                    onOpenProviderSettings = onOpenProviderSettings,
                                    isAgentMode = false,
                                    onHtmlPreview = onHtmlPreview,
                                    onOpenSkills = onOpenSkills,
                                    onOpenPromptTemplateManager = onOpenPromptTemplateManager,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                        } else {
                            ChatListScreen(
                                sessions = state.sessions,
                                folders = state.folders,
                                currentSessionId = state.currentSessionId,
                                onSelect = { id ->
                                    // v0.27: 点击任务项 → 切换会话 + push 到聊天详情页
                                    viewModel.switchSession(id)
                                    onOpenChat()
                                },
                                onCreate = {
                                    // v0.27: 新任务 → 创建会话 + push 到聊天详情页
                                    viewModel.createNewSession(onReady = onOpenChat)
                                },
                                onDelete = viewModel::deleteSession,
                                onRename = { session ->
                                    viewModel.renameSession(session.id, session.title)
                                },
                                // v1.48: 修复会话重命名失效 bug — 旧实现传 session.title(原名),改为传用户输入的 newName
                                onRenameTo = { session, newName ->
                                    viewModel.renameSession(session.id, newName)
                                },
                                onTogglePinned = viewModel::togglePinned,
                                onReorderPinned = viewModel::reorderPinnedSessions,
                                onMoveSessionToFolder = viewModel::moveSessionToFolder,
                                onCreateFolder = viewModel::createFolder,
                                onRenameFolder = viewModel::renameFolder,
                                onDeleteFolder = viewModel::deleteFolder,
                                onToggleFolderExpanded = viewModel::toggleFolderExpanded,
                                onOpenFolder = onOpenFolder,
                                assistants = state.assistants,
                                currentAssistant = state.currentAssistant,
                                showListToolbar = false,
                                listAction = pendingListAction,
                                onListActionConsumed = { pendingListAction = null },
                                onArchive = { id -> viewModel.setSessionArchived(id, true) },
                                onOpenScheduledTasks = onOpenScheduledTasks,
                                onOpenQuickNotes = onOpenQuickNotes,
                                onOpenQuickTranslate = onOpenQuickTranslate,
                                onOpenKnowledgeBase = onOpenKnowledgeBase,
                                onOpenRecentlyDeleted = onOpenRecentlyDeleted,
                                onOpenAssistants = onOpenAssistants,
                                // v2.x: 伙伴横排 — 以指定助手开新聊天并进入聊天页
                                onOpenAssistantChat = { id ->
                                    viewModel.createNewSession(onReady = onOpenChat, assistantIdOverride = id)
                                },
                                isSessionsLoading = state.isSessionsLoading,
                                sessionsError = state.sessionsError,
                                onRetryLoadSessions = viewModel::retryLoadSessions,
                                modifier = Modifier.fillMaxSize(),
                            )
                            }
                        }
                    }
                    // Tab 1 "Agent": 长效日常聊天搭子
                    1 -> {
                        if (io.zer0.muse.feature.FeatureFlags.AGENT_BOT && botViewModel != null) {
                            LaunchedEffect(state.currentAssistant?.id) {
                                botViewModel.bind(
                                    botId = state.currentAssistant?.id ?: "default",
                                    originSessionId = state.agentSessionId.orEmpty(),
                                )
                            }
                            io.zer0.muse.ui.agentbot.AgentBotScreen(
                                viewModel = botViewModel,
                                assistantName = state.currentAssistant?.name.orEmpty(),
                                avatarUri = state.currentAssistant?.avatarImageUrl?.takeIf { it.isNotBlank() },
                                modelName = effectiveAgentModelId.orEmpty(),
                                providers = state.providers,
                                onOpenModel = { showModelSheet = true },
                                onOpenProviderSettings = onOpenProviderSettings,
                                onOpenRuntimeInstall = onOpenRuntimeInstall,
                                onSwitchAssistant = { assistantId ->
                                    viewModel.switchAgentAssistant(assistantId)
                                },
                                onOpenGroupChats = {
                                    scope.launch { pagerState.animateScrollToPage(2) }
                                },
                                topOverlayInset = homeTopBarHeightDp,
                                externalWorkbenchTab = requestedWorkbenchTab,
                                onExternalWorkbenchTabConsumed = { requestedWorkbenchTab = null },
                                externalBriefingTick = requestedBriefingTick,
                            )
                        } else {
                            ChatScreen(
                                onOpenAssistants = onOpenAssistants,
                                onOpenProviderSettings = onOpenProviderSettings,
                                isAgentMode = true,
                                onHtmlPreview = onHtmlPreview,
                                onOpenSkills = onOpenSkills,
                                onOpenPromptTemplateManager = onOpenPromptTemplateManager,
                                modifier = Modifier.padding(top = homeTopBarHeightDp),
                            )
                        }
                    }
                    // Tab 2 "群聊": 多 Agent 群聊列表
                    2 -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = homeTopBarHeightDp),
                        ) {
                            GroupChatListScreen(
                                onOpenChat = onOpenGroupChat,
                            )
                        }
                    }
                }
                    }
            }

            // 顶部镂空三岛悬浮层：叠在 HorizontalPager 上方，Agent 页消息可直接滑到三颗按钮两侧镂空区
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onSizeChanged { homeTopBarHeightPx = it.height },
            ) {
                floatingTopBar()
            }
        }

        // v1.41: 模型切换面板（在 Agent Tab 时同时同步 Agent 前台模型路由）
        if (showModelSheet) {
            val isAgentTab = pagerState.currentPage == 1 && botViewModel != null
            val sheetProviderId = if (isAgentTab) effectiveAgentProviderId else state.activeProviderId
            val sheetModelId = if (isAgentTab) effectiveAgentModelId else state.selectedModelId
            ModelSwitchSheet(
                providers = state.providers,
                activeProviderId = sheetProviderId,
                selectedModelId = sheetModelId,
                onPickProvider = { pid ->
                    viewModel.setActiveProvider(pid)
                    if (isAgentTab && botViewModel != null) {
                        val firstModel = state.providers.firstOrNull { it.id == pid }?.models?.firstOrNull()?.id
                        if (!firstModel.isNullOrBlank()) {
                            botViewModel.setModelRoute(AgentModelRouter.Role.FOREGROUND, pid, firstModel)
                        } else {
                            botViewModel.clearModelRoute(AgentModelRouter.Role.FOREGROUND)
                        }
                    }
                },
                onPickModel = { mid ->
                    viewModel.setSelectedModel(mid)
                    if (isAgentTab && botViewModel != null) {
                        val ownerProviderId = mid?.let { target ->
                            state.providers.firstOrNull { it.id == sheetProviderId && it.models.any { m -> m.id == target } }?.id
                                ?: state.providers.firstOrNull { it.models.any { m -> m.id == target } }?.id
                        } ?: sheetProviderId
                        if (!mid.isNullOrBlank() && !ownerProviderId.isNullOrBlank()) {
                            botViewModel.setModelRoute(AgentModelRouter.Role.FOREGROUND, ownerProviderId, mid)
                        } else {
                            botViewModel.clearModelRoute(AgentModelRouter.Role.FOREGROUND)
                        }
                    }
                },
                onRefreshModels = viewModel::refreshModels,
                isFetchingModels = state.isFetchingModels,
                fetchModelsError = state.fetchModelsError,
                onDismiss = { showModelSheet = false },
            )
        }
        } // 关闭 CompositionLocalProvider
    }
    // C1: 全局命令面板(独立 Dialog 窗口,覆盖任意 Tab)
    if (showCommandPalette) {
        CommandPalette(
            onDismiss = { showCommandPalette = false },
            onNewChat = {
                viewModel.createNewSession(onReady = onOpenChat)
            },
            onOpenSettings = onOpenSettings,
            onOpenSearch = onOpenSearch,
            onOpenChat = onOpenChat,
        )
    }
}

/**
 * v1.133: "新版本可用"Banner — 在 HomeScreen 顶部展示。
 *
 * 设计:
 *  - Surface 容器用 primaryContainer 色(暖色调高亮,与品牌一致)
 *  - 左侧:Close 图标(关闭 Banner,本次会话不再显示)
 *  - 中间:标题"<tagName> 已发布" + 版本名
 *  - 右侧:两个 TextButton("查看详情" / "下载 APK"),均打开官网下载页
 *
 * @param release 最新版本信息
 * @param onClose 关闭 Banner 回调
 * @param onViewRelease 打开官网下载页(版本与更新日志)
 * @param onDownloadApk 打开官网下载页(下载入口)
 */
@Composable
private fun UpdateAvailableBanner(
    release: UpdateChecker.ReleaseInfo,
    onClose: () -> Unit,
    onViewRelease: () -> Unit,
    onDownloadApk: () -> Unit,
) {
    val closeCd = stringResource(R.string.update_banner_close_cd)
    val viewDetailText = stringResource(R.string.update_banner_view_detail)
    val downloadApkText = stringResource(R.string.update_banner_download_apk)
    val publishedText = stringResource(R.string.update_banner_published, release.tagName)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = publishedText,
                    style = MaterialTheme.typography.titleSmallStrong,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (release.name.isNotBlank()) {
                    Text(
                        text = release.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            MuseCapsuleButton(
                text = viewDetailText,
                onClick = onViewRelease,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
            )
            MuseCapsuleButton(
                text = downloadApkText,
                onClick = onDownloadApk,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
            )
            MuseTactileButton(
                icon = MuseIcons.x,
                onClick = onClose,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.semantics { contentDescription = closeCd },
            )
        }
    }
}

/**
 * 解析缓存的 ReleaseInfo JSON。
 * 解析失败返回 null(Banner 不展示,避免损坏数据让 UI 崩溃)。
 */
private fun parseReleaseInfo(json: String?): UpdateChecker.ReleaseInfo? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        AppJson.decodeFromString(UpdateChecker.ReleaseInfo.serializer(), json)
    }.onFailure {
        Logger.w("HomeScreen", "parseReleaseInfo failed: ${it.message}")
    }.getOrNull()
}

/**
 * v1.0.16: 首页右下角悬浮胶囊 — 定时任务 / 快速记录 / 快速翻译 / 加号(新建任务)。
 *
 * 设计参考用户提供的右下角按钮布局:
 *  - 长椭圆胶囊,与主流输入形态一致
 *  - 4 个图标按钮等分排列,中间用细竖线分隔
 *  - 背景使用 surfaceContainer,漂浮在内容之上
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HomeQuickActionCapsule(
    onOpenScheduledTasks: () -> Unit,
    onOpenQuickNotes: () -> Unit,
    onOpenQuickTranslate: () -> Unit,
    onCreateNewTask: () -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
    glassActive: Boolean = false,
    glassConfig: io.zer0.muse.ui.theme.LiquidGlassConfig = io.zer0.muse.ui.theme.LiquidGlassConfig(),
) {
    val capsuleContent: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // v1.136 T8: 收起时仅显示 Plus 按钮;展开时显示全部 4 个按钮(长按 Plus 切换)
            AnimatedVisibility(
                visible = expanded,
                enter = MuseMotion.horizontalExpandEnter(),
                exit = MuseMotion.horizontalExpandExit(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HomeCapsuleButton(
                        icon = MuseIcons.calendarTime,
                        contentDescription = stringResource(R.string.chat_list_scheduled_tasks),
                        onClick = onOpenScheduledTasks,
                    )
                    HomeCapsuleDivider()
                    HomeCapsuleButton(
                        icon = MuseIcons.edit,
                        contentDescription = stringResource(R.string.chat_list_quick_notes),
                        onClick = onOpenQuickNotes,
                    )
                    HomeCapsuleDivider()
                    HomeCapsuleButton(
                        icon = MuseIcons.languages,
                        contentDescription = stringResource(R.string.chat_list_quick_translate),
                        onClick = onOpenQuickTranslate,
                    )
                    HomeCapsuleDivider()
                }
            }
            // Plus 按钮始终显示;长按切换展开/收起
            // CHAT-16: 弱化 FAB 主按钮 — 由实心 primary 改为 primaryContainer,
            // 避免与首页输入栏发送键「两个大圆」抢焦点。
            HomeCapsuleButton(
                icon = MuseIcons.plus,
                contentDescription = stringResource(R.string.chat_list_new_task),
                onClick = onCreateNewTask,
                onLongClick = onToggleExpanded,
                // UI-FIX A: 主操作改实心容器（浅色近黑/深色近白）+ 反相图标
                containerColor = MuseActionColors.container,
                contentColor = MuseActionColors.content,
            )
        }
    }
    if (glassActive && glassConfig.enabled) {
        GlassIsland(
            config = glassConfig,
            shape = MuseShapes.pill,
            solidColor = MaterialTheme.colorScheme.surfaceContainer,
            modifier = modifier
                .padding(end = 8.dp, bottom = 16.dp)
                .navigationBarsPadding(),
        ) {
            capsuleContent()
        }
    } else {
        Surface(
            modifier = modifier
                .padding(end = 8.dp, bottom = 16.dp)
                .navigationBarsPadding(),
            shape = MuseShapes.pill,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
        ) {
            capsuleContent()
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HomeCapsuleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    containerColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // 图标 + 文字：此前只有图标，虽然读屏能读语义标签，但视觉上完全靠猜。
    Box(
        modifier = modifier
            .heightIn(min = 56.dp)
            .background(containerColor)
            // 自绘胶囊按钮保留触觉/颜色反馈,禁止默认 ripple 遮罩。
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = contentDescription,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HomeCapsuleDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(26.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}
