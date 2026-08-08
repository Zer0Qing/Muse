package io.zer0.muse.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.schedule.ProactiveMessageRunner
import io.zer0.muse.ui.common.media.DesktopShortcuts
import io.zer0.muse.ui.common.media.rememberDesktopShortcutsEnabled
import io.zer0.muse.ui.groupchat.GroupChatListScreen
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.update.UpdateChecker
import io.zer0.muse.update.UpdateNotifier
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

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
    /** v1.0.27: 打开快速翻译页。 */
    onOpenQuickTranslate: () -> Unit = {},
    /** v1.0.27: 打开知识库页。 */
    onOpenKnowledgeBase: () -> Unit = {},
    /** v0.27: 点击任务项 / 新建任务时 push 到独立聊天详情页。 */
    onOpenChat: () -> Unit = {},
    /** v0.45: 打开独立全局搜索页。 */
    onOpenSearch: () -> Unit = {},
    /** v1.30: 群聊 Tab 中点击群聊卡片 → 跳转群聊详情页(参数为 chatId)。 */
    onOpenGroupChat: (String) -> Unit = {},
    /** v2.0: 打开最近删除页。 */
    onOpenRecentlyDeleted: () -> Unit = {},
    /** v0.45: 打开归档聊天列表页。 */
    onOpenArchivedChats: () -> Unit = {},
    /** HTML/SVG 代码块全屏预览回调(由 Tab 1 ChatScreen 触发)。 */
    onHtmlPreview: (String) -> Unit = {},
    /** 加号菜单 → 技能入口。 */
    onOpenSkills: () -> Unit = {},
    /** B0-07: 打开提示词模板管理页。 */
    onOpenPromptTemplateManager: () -> Unit = {},
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
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) clickAnimating = false
    }

    // v2.0 5.6: 注入 ProactiveMessageRunner,在 onResume 时触发事件巡检
    val proactiveRunner: ProactiveMessageRunner = koinInject()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // 用户打开 App 时触发主动消息巡检(受 2 分钟冷却 + 时间窗口 + 每日上限约束)
                    scope.launch {
                        try {
                            proactiveRunner.triggerByEvent(ProactiveMessageRunner.TRIGGER_SOURCE_RESUME)
                        } catch (e: Exception) {
                            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                            Logger.w("HomeScreen", "onResume 触发主动消息失败: ${e.message}")
                        }
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // v1.28: 进入/离开 Agent Tab 时切换 Agent 模式(独立会话,不依赖任务)
    // v1.104: 用 settledPage 替代 currentPage,只在 ViewPager 完全停稳后触发,
    // 避免从 page 0→2 滑过 page 1 中间态时误触发 setAgentMode(会重载消息、停 TTS/ASR)
    LaunchedEffect(pagerState.settledPage) {
        viewModel.setAgentMode(pagerState.settledPage == 1)
    }

    // v1.0.16: 新建任务/新会话统一入口 — 被 tab 下方卡片和右下角悬浮胶囊复用
    val onCreateNewTask: () -> Unit = {
        viewModel.createNewSession()
        onOpenChat()
    }

    Scaffold(
        floatingActionButton = {
            // v1.0.17: 悬浮胶囊仅在"任务"Tab(首页)显示,Agent/群聊页不显示
            if (pagerState.currentPage == 0) {
                HomeQuickActionCapsule(
                    onOpenScheduledTasks = onOpenScheduledTasks,
                    onOpenQuickNotes = onOpenQuickNotes,
                    onOpenQuickTranslate = onOpenQuickTranslate,
                    onCreateNewTask = onCreateNewTask,
                    expanded = capsuleExpanded,
                    onToggleExpanded = { capsuleExpanded = !capsuleExpanded },
                )
            }
        },
        topBar = {
            // v1.24: 自定义紧凑顶部栏 — 取消 TopAppBar 默认高度,让 Tab 切换器紧贴下方内容
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // v1.99: 大R角/曲面屏横向安全区避让
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                    .statusBarsPadding()
                    .padding(horizontal = MusePaddings.inputPadding, vertical = MusePaddings.contentGap),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧:头像 → 设置
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = TablerIcons.User,
                        contentDescription = stringResource(R.string.home_settings_cd),
                        modifier = Modifier.size(24.dp),
                    )
                }

                // 中间:胶囊 Tab 切换器(iOS 风格 MuseCapsuleTab 组件)
                val tabLabels = HomeTabs.map { (labelResId, _) ->
                    stringResource(labelResId)
                }
                io.zer0.muse.ui.common.form.MuseCapsuleTab(
                    tabs = tabLabels,
                    selectedIndex = pagerState.currentPage,
                    onSelect = { page ->
                        clickAnimating = true
                        scope.launch { pagerState.animateScrollToPage(page) }
                    },
                    // v1.137 B3: 拖拽时连续跟踪手指,点击时用 tween 平滑过渡
                    pageOffset = pagerState.currentPageOffsetFraction,
                    isDragging = pagerState.isScrollInProgress && !clickAnimating,
                    // 顶部 Tab 收窄,右侧腾出空间给全局搜索按钮
                    modifier = Modifier.width(172.dp),
                )

                // v1.0.17 顶部右侧:全局搜索按钮(所有 Tab 都显示)。
                // 移动端无物理键盘,Ctrl+K 不可用,搜索入口不能藏太深。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    // v1.0.72: 归档聊天入口已移除 — 移到 设置 → 数据与隐私 → 归档聊天(主页顶栏更简洁)
                    // 全局搜索入口(对话/翻译/快速记录)— 三 Tab 右侧常驻
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            imageVector = TablerIcons.Search,
                            contentDescription = stringResource(R.string.home_search_cd),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        // 拦截返回:非 Tab 0 时先切回 Tab 0,避免直接退出 App
        BackHandler(enabled = pagerState.currentPage != 0) {
            scope.launch { pagerState.animateScrollToPage(0) }
        }
        // P2-13: 桌面端快捷键拦截 — Ctrl+K 打开搜索,Ctrl+N 新建对话
        // 仅在物理键盘 + Expanded 窗口下生效;Ctrl+N 复用 Tab 0 既有"新建会话 + 跳转"逻辑
        val newChatAction: () -> Unit = {
            viewModel.createNewSession()
            onOpenChat()
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .onKeyEvent { event ->
                    if (!desktopShortcutsEnabled) return@onKeyEvent false
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when {
                        // Ctrl+K: 聚焦搜索 / 打开独立搜索页(HomeScreen 无内嵌搜索框)
                        event.key == DesktopShortcuts.FOCUS_SEARCH && event.isCtrlPressed -> {
                            onOpenSearch()
                            true
                        }
                        // Ctrl+N: 新建对话(仅在 Tab 0 任务列表响应,与既有 onCreate 一致)
                        event.key == DesktopShortcuts.NEW_CHAT && event.isCtrlPressed &&
                            pagerState.currentPage == 0 -> {
                            newChatAction()
                            true
                        }
                        else -> false
                    }
                },
        ) {
            // v1.0.16: 新任务/新会话入口 — 放在 3 大切换 tab 栏正下方,
            // 从原来的 ChatListScreen 顶部上提到这里,方便所有 Tab 可见。
            // 仅在"任务"Tab 显示(Agent 页自己就能输入,群聊页有群聊列表入口)。
            // v1.133: 顶部"新版本可用"Banner — 有缓存 ReleaseInfo 且未被关闭时显示
            // v1.0.72: 用户点过忽略的版本不再展示(持久化,下次冷启动也不弹)
            // v1.0.72: 升级到同版本/更高版本后即使缓存未清也不显示(24h 检查间隔内缓存会残留)
            val ignoredUpdateVersion by settings.ignoredUpdateVersionFlow.collectAsStateWithLifecycle(initialValue = null)
            val currentVersionName = remember { UpdateNotifier.getCurrentVersionName(context) }
            val release = remember(releaseJson, bannerDismissed, ignoredUpdateVersion, currentVersionName) {
                if (bannerDismissed || releaseJson.isNullOrBlank()) null
                else parseReleaseInfo(releaseJson)?.takeIf { info ->
                    info.tagName != ignoredUpdateVersion &&
                        UpdateNotifier.compareVersions(currentVersionName, info.tagName) < 0
                }
            }
            if (release != null) {
                UpdateAvailableBanner(
                    release = release,
                    onClose = {
                        bannerDismissed = true
                        // v1.0.72: 忽略后持久化,该版本不再提示(用户可在设置-更新里手动检查)
                        scope.launch { settings.saveIgnoredUpdateVersion(release.tagName) }
                    },
                    onViewRelease = { url ->
                        val intent = UpdateNotifier.buildViewReleaseIntent(url)
                        runCatching {
                            context.startActivity(Intent.createChooser(intent, null))
                        }.onFailure { Logger.w("HomeScreen", "startActivity failed: ${it.message}") }
                    },
                    onDownloadApk = { url ->
                        val intent = UpdateNotifier.buildDownloadApkIntent(url)
                        runCatching {
                            context.startActivity(Intent.createChooser(intent, null))
                        }.onFailure { Logger.w("HomeScreen", "startActivity failed: ${it.message}") }
                    },
                )
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().weight(1f),
                userScrollEnabled = true,
            ) { page ->
            when (page) {
                // Tab 0 "任务": 任务列表(用户日常工作调研)
                // 点击会话只切换 currentSessionId,不跳转到 Agent Tab
                0 -> ChatListScreen(
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
                        viewModel.createNewSession()
                        onOpenChat()
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
                    assistants = state.assistants,
                    currentAssistant = state.currentAssistant,
                    archivedSessions = state.archivedSessions,
                    onArchive = { id -> viewModel.setSessionArchived(id, true) },
                    onUnarchive = { id -> viewModel.setSessionArchived(id, false) },
                    onOpenScheduledTasks = onOpenScheduledTasks,
                    onOpenQuickNotes = onOpenQuickNotes,
                    onOpenQuickTranslate = onOpenQuickTranslate,
                    onOpenKnowledgeBase = onOpenKnowledgeBase,
                    onOpenRecentlyDeleted = onOpenRecentlyDeleted,
                    onOpenAssistants = onOpenAssistants,
                    onCreateWithText = { text ->
                        viewModel.sendToNewChat(text)
                        onOpenChat()
                    },
                    isSessionsLoading = state.isSessionsLoading,
                    sessionsError = state.sessionsError,
                    onRetryLoadSessions = viewModel::retryLoadSessions,
                    modifier = Modifier.fillMaxSize(),
                )
                // Tab 1 "Agent": 长效日常聊天搭子
                // 独立聊天区域,不依赖任务 Tab 的会话选择
                // M-CS6: 移除多余的 Column 包裹 — ChatScreen 内部已用 fillMaxSize 自撑,Column 仅增加一层无意义嵌套
                1 -> ChatScreen(
                    onOpenAssistants = onOpenAssistants,
                    isAgentMode = true,
                    onHtmlPreview = onHtmlPreview,
                    onOpenSkills = onOpenSkills,
                    onOpenPromptTemplateManager = onOpenPromptTemplateManager,
                )
                // Tab 2 "群聊": 多 Agent 群聊列表
                // 点击群聊卡片 → 跳转到群聊详情页(通过 NavHost 路由)
                2 -> GroupChatListScreen(
                    onOpenChat = onOpenGroupChat,
                )
            }
        }
        }

        // v1.41: Agent Tab 模型切换面板
        if (showModelSheet) {
            ModelSwitchSheet(
                providers = state.providers,
                activeProviderId = state.activeProviderId,
                selectedModelId = state.selectedModelId,
                onPickProvider = viewModel::setActiveProvider,
                onPickModel = viewModel::setSelectedModel,
                onRefreshModels = viewModel::refreshModels,
                isFetchingModels = state.isFetchingModels,
                fetchModelsError = state.fetchModelsError,
                onDismiss = { showModelSheet = false },
            )
        }
    }
}

/**
 * v1.133: "新版本可用"Banner — 在 HomeScreen 顶部展示。
 *
 * 设计:
 *  - Surface 容器用 primaryContainer 色(暖色调高亮,与品牌一致)
 *  - 左侧:Close 图标(关闭 Banner,本次会话不再显示)
 *  - 中间:标题"<tagName> 已发布" + 副标题"点击查看详情或下载 APK"
 *  - 右侧:两个 TextButton("查看详情" / "下载 APK")
 *  - "下载 APK"在无 APK 资源时禁用
 *
 * @param release 最新版本信息
 * @param onClose 关闭 Banner 回调
 * @param onViewRelease 打开 release 页面(htmlUrl)
 * @param onDownloadApk 下载 APK(browser_download_url)
 */
@Composable
private fun UpdateAvailableBanner(
    release: UpdateChecker.ReleaseInfo,
    onClose: () -> Unit,
    onViewRelease: (String) -> Unit,
    onDownloadApk: (String) -> Unit,
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
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
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
            TextButton(
                onClick = { onViewRelease(release.htmlUrl) },
                content = { Text(viewDetailText) },
            )
            // 无 APK 资源时禁用下载按钮(空列表/无 url)
            val firstApk = release.apkAssets.firstOrNull()
            TextButton(
                onClick = { firstApk?.let { onDownloadApk(it.downloadUrl) } },
                enabled = firstApk != null,
                content = { Text(downloadApkText) },
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.semantics { contentDescription = closeCd },
            ) {
                Icon(
                    imageVector = TablerIcons.X,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
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
 *  - 长椭圆胶囊,与 iOS ChatGPT / MANUS 风格一致
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
) {
    Surface(
        modifier = modifier
            .padding(end = 8.dp, bottom = 16.dp)
            .navigationBarsPadding(),
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // v1.136 T8: 收起时仅显示 Plus 按钮;展开时显示全部 4 个按钮(长按 Plus 切换)
            AnimatedVisibility(
                visible = expanded,
                enter = expandHorizontally(animationSpec = tween(220)),
                exit = shrinkHorizontally(animationSpec = tween(220)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HomeCapsuleButton(
                        icon = TablerIcons.CalendarTime,
                        contentDescription = stringResource(R.string.chat_list_scheduled_tasks),
                        onClick = onOpenScheduledTasks,
                    )
                    HomeCapsuleDivider()
                    HomeCapsuleButton(
                        icon = TablerIcons.Edit,
                        contentDescription = stringResource(R.string.chat_list_quick_notes),
                        onClick = onOpenQuickNotes,
                    )
                    HomeCapsuleDivider()
                    HomeCapsuleButton(
                        icon = TablerIcons.Language,
                        contentDescription = stringResource(R.string.chat_list_quick_translate),
                        onClick = onOpenQuickTranslate,
                    )
                    HomeCapsuleDivider()
                }
            }
            // Plus 按钮始终显示;长按切换展开/收起
            HomeCapsuleButton(
                icon = TablerIcons.Plus,
                contentDescription = stringResource(R.string.chat_list_new_task),
                onClick = onCreateNewTask,
                onLongClick = onToggleExpanded,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
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
    Box(
        modifier = modifier
            .size(56.dp)
            .background(containerColor)
            // v1.0.16: 恢复全局 ripple 按压反馈
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(24.dp),
        )
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
