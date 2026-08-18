package io.zer0.muse.ui.navigation

import android.content.Context
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.zer0.muse.ui.ChatListScreen
import io.zer0.muse.ui.ArchivedChatsScreen
import io.zer0.muse.ui.ChatScreen
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.HomeScreen
import io.zer0.muse.ui.HtmlPreviewScreen
import io.zer0.muse.ui.MuseRoutes
import io.zer0.muse.ui.RecentlyDeletedScreen
import io.zer0.muse.ui.SearchScreen
import io.zer0.muse.ui.settings.PromptTemplateManagerPage
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.quicknotes.QuickNotesScreen
import io.zer0.muse.ui.quicknotes.QuickNotesViewModel
import org.koin.androidx.compose.koinViewModel
import androidx.navigation.toRoute
import kotlinx.coroutines.launch

/**
 * 聊天域 NavGraph — 包含首页、搜索、聊天详情、群聊详情、最近删除、快速记录、
 * 定时任务、Agent 私信、里程碑、表情包、翻译、HTML 预览共 12 个 composable。
 *
 * 从 MainActivity 抽取以解决后者过载问题(原 1804 行 → 目标 ≤ 800 行)。
 * HOME 使用垂直过渡,其余页面统一使用 [MuseTransitions.horizontalPushEnter] / [horizontalPushPopExit]。
 */
fun NavGraphBuilder.chatNavGraph(
    navController: NavHostController,
    sharedViewModel: ChatViewModel,
    context: Context,
) {
    // v0.22: 首页 — 顶部 Tab 导航(垂直过渡,HOME 专属)
    composable<HomeRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        exitTransition = { MuseTransitions.horizontalPushExit() },
        popEnterTransition = { MuseTransitions.horizontalPopEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        HomeScreen(
            onOpenSettings = { navController.navigate(SettingsRoute) },
            onOpenAssistants = { navController.navigate(AssistantsRoute) },
            onOpenScheduledTasks = { navController.navigate(ScheduledTasksRoute) },
            onOpenQuickNotes = { navController.navigate(QuickNotesRoute) },
            onOpenQuickTranslate = { navController.navigate(TranslateRoute) },
            onOpenKnowledgeBase = { navController.navigate(KnowledgeRoute) },
            // v0.27: 点击任务项 / 新建任务 → push 到独立聊天详情页(右滑入场,对标 iOS push)
            onOpenChat = { navController.navigate(ChatDetailRoute) },
            // v0.45: 右上角搜索 → 独立全局搜索页
            onOpenSearch = { navController.navigate(SearchRoute) },
            // v1.30: 群聊卡片点击 → 群聊详情页(右滑入场)
            onOpenGroupChat = { chatId ->
                navController.navigate(GroupChatDetailRoute(chatId))
            },
            onOpenRecentlyDeleted = { navController.navigate(RecentlyDeletedRoute) },
            onOpenArchivedChats = { navController.navigate(ArchivedChatsRoute) },
            // v1.0.72: 小手机 + AI 朋友圈入口
            onOpenMiniPhone = { navController.navigate(MiniPhoneRoute) },
            // HTML/SVG 代码块全屏预览:URL 编码后跳转 HtmlPreviewScreen
            onHtmlPreview = { html ->
                navController.navigate(HtmlPreviewRoute(html))
            },
            onOpenSkills = { navController.navigate(SkillsRoute) },
            onOpenPromptTemplateManager = { navController.navigate(PromptTemplateManagerRoute) },
        )
    }
    // v0.45: 独立全局搜索页(从首页右上角搜索按钮进入,右滑入场)
    composable<SearchRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SearchScreen(
            onBack = { navController.popBackStack() },
            onOpenSession = { sessionId ->
                // 切换会话后回到首页(任务列表会高亮该会话)
                sharedViewModel.switchSession(sessionId)
                navController.popBackStack(HomeRoute, inclusive = false)
            },
            // v2.x: Tab=消息内容 点击消息项跳转 — 切换会话 + 设置目标消息,
            // 回到 HOME 后 ChatScreen 监听 targetMessageId 滚动定位 + 短暂高亮
            onOpenMessage = { sessionId, messageId, query ->
                // v2.x: 先切会话再定位(避免竞态丢定位),然后进入对话页
                sharedViewModel.openMessageFromSearch(sessionId, messageId, query)
                navController.popBackStack(HomeRoute, inclusive = false)
                navController.navigate(ChatDetailRoute)
            },
        )
    }
    // v1.0.72: 小手机 + AI 朋友圈沉浸页
    composable<MiniPhoneRoute>(
        enterTransition = { fadeIn() },
        popExitTransition = { fadeOut() },
    ) {
        val momentViewModel: io.zer0.muse.ui.moment.MomentViewModel = org.koin.androidx.compose.koinViewModel()
        val momentState by momentViewModel.state.collectAsStateWithLifecycle()
        val settings: io.zer0.muse.data.SettingsRepository = org.koin.compose.koinInject()
        val hiddenMiniPhoneApps by settings.miniPhoneHiddenAppsFlow.collectAsStateWithLifecycle(
            initialValue = emptySet(),
        )
        val miniPhoneAppOrder by settings.miniPhoneAppOrderFlow.collectAsStateWithLifecycle(
            initialValue = emptyList(),
        )
        var showMoments by remember { mutableStateOf(false) }
        // v1.0.74 fix: 消息图标直达消息页(此前红点清了却进 feed)
        var initialPage by remember { mutableStateOf("feed") }
        val context = LocalContext.current

        if (!showMoments) {
            io.zer0.muse.ui.moment.MiniPhoneScreen(
                momentsCount = momentState.moments.size,
                unreadMoments = momentState.unreadMomentsCount,
                unreadMessages = momentState.unreadMessagesCount,
                wallpaper = momentState.wallpaper,
                // v1.0.74: 小手机主人 = 用户
                userName = momentState.userName,
                userAvatarUri = momentState.userAvatarUri,
                hiddenApps = hiddenMiniPhoneApps,
                appOrder = miniPhoneAppOrder,
                onOpenMoments = {
                    momentViewModel.markMomentsRead()
                    initialPage = "feed"
                    showMoments = true
                },
                onOpenMessages = {
                    momentViewModel.markMessagesRead()
                    initialPage = "messages"
                    showMoments = true
                },
                // v1.0.74: 备忘录复用快速记录,相册/天气/日记本独立页
                onOpenQuickNotes = { navController.navigate(QuickNotesRoute) },
                onOpenAlbum = { navController.navigate(MiniAlbumRoute) },
                onOpenWeather = { navController.navigate(MiniWeatherRoute) },
                onOpenDiary = { navController.navigate(MiniDiaryRoute) },
                onOpenSettings = { navController.navigate(SettingsMiniPhoneRoute) },
                onSetWallpaper = momentViewModel::setWallpaper,
                onPrepareImage = { uri -> momentViewModel.prepareImageDataUri(uri, context) },
                onBack = { navController.popBackStack() },
            )
        } else {
            io.zer0.muse.ui.moment.MomentsScreen(
                moments = momentState.moments,
                commentsByMoment = momentState.comments,
                favoriteMomentIds = momentState.favoriteMomentIds,
                messages = momentState.messages,
                unreadMessagesCount = momentState.unreadMessagesCount,
                isLoading = momentState.isLoading,
                userAvatarUri = momentState.userAvatarUri,
                userName = momentState.userName,
                coverImage = momentState.coverImage,
                assistants = momentState.assistants,
                banner = momentState.banner,
                onToggleLike = momentViewModel::toggleLike,
                onToggleFavorite = momentViewModel::toggleFavorite,
                onAddComment = momentViewModel::addComment,
                onDeleteMoment = momentViewModel::deleteMoment,
                onPublish = momentViewModel::publish,
                onSetCover = momentViewModel::setCoverImage,
                onPrepareImage = { uri -> momentViewModel.prepareImageDataUri(uri, context) },
                onMarkMessagesRead = momentViewModel::markMessagesRead,
                onConsumeBanner = momentViewModel::consumeBanner,
                onRefresh = momentViewModel::load,
                onBack = { showMoments = false },
                initialPage = initialPage,
            )
        }
    }
    // v1.0.74: 小手机子页 — AI 相册(聚合 messages 里的 AI 生成图)
    composable<MiniAlbumRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        val settings: io.zer0.muse.data.SettingsRepository = org.koin.compose.koinInject()
        val scope = rememberCoroutineScope()
        val hiddenAlbumImageIds by settings.miniAlbumHiddenImageIdsFlow.collectAsStateWithLifecycle(
            initialValue = emptySet(),
        )
        val favoriteAlbumImageIds by settings.miniAlbumFavoriteImageIdsFlow.collectAsStateWithLifecycle(
            initialValue = emptySet(),
        )
        var albumImages by remember {
            mutableStateOf<List<io.zer0.muse.data.`import`.MiniAlbumImage>>(emptyList())
        }
        var albumReloadKey by remember { mutableIntStateOf(0) }
        var albumLoading by remember { mutableStateOf(false) }
        LaunchedEffect(albumReloadKey) {
            albumLoading = true
            albumImages = io.zer0.muse.data.`import`.MiniDataLoader.loadAiGeneratedImages(context)
            albumLoading = false
        }
        io.zer0.muse.ui.moment.MiniAlbumScreen(
            images = albumImages,
            onBack = { navController.popBackStack() },
            isLoading = albumLoading,
            onRefresh = { albumReloadKey++ },
            hiddenImageIds = hiddenAlbumImageIds,
            favoriteImageIds = favoriteAlbumImageIds,
            onToggleFavorite = { imageId ->
                scope.launch { settings.toggleMiniAlbumFavoriteImage(imageId) }
            },
            onHideImage = { imageId ->
                scope.launch { settings.hideMiniAlbumImage(imageId) }
            },
            onUnhideImage = { imageId ->
                scope.launch { settings.unhideMiniAlbumImage(imageId) }
            },
        )
    }
    // v1.0.74: 小手机子页 — 天气(Open-Meteo)
    composable<MiniWeatherRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.moment.MiniWeatherScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.0.74: 小手机子页 — 模型日记本(月视图 + LLM 生成)
    composable<MiniDiaryRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.moment.MiniDiaryScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // 归档聊天列表 — 从首页归档入口进入
    composable<ArchivedChatsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        val state by sharedViewModel.state.collectAsStateWithLifecycle()
        ArchivedChatsScreen(
            sessions = state.archivedSessions,
            onBack = { navController.popBackStack() },
            onUnarchive = { id -> sharedViewModel.setSessionArchived(id, false) },
            onOpenSession = { id ->
                sharedViewModel.switchSession(id)
                navController.popBackStack(HomeRoute, inclusive = false)
            },
        )
    }

    // v0.27: 聊天详情页 — 从首页 push 进入,右滑入场 + 左滑返回(对标 iOS push)
    // P1-4 平板适配:Expanded 模式下双列布局(左 ChatListScreen 40% + 右 ChatScreen 60%),
    //               Compact/Medium 保持单列 push/pop
    composable<ChatDetailRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        val widthClass = rememberWindowWidthClass()
        if (widthClass == WindowWidthClass.Expanded) {
            // P1-4: Expanded 双列布局 — 左列任务列表(40%) + 右列聊天(60%)
            val state by sharedViewModel.state.collectAsStateWithLifecycle()
            Row(modifier = Modifier.fillMaxSize()) {
                // 左列:任务列表(与 HomeScreen Tab 0 同源,共享 sharedViewModel.state)
                Box(modifier = Modifier.weight(0.4f).fillMaxSize()) {
                    ChatListScreen(
                        sessions = state.sessions,
                        folders = state.folders,
                        currentSessionId = state.currentSessionId,
                        onSelect = { id -> sharedViewModel.switchSession(id) },
                        onCreate = { sharedViewModel.createNewSession() },
                        onDelete = sharedViewModel::deleteSession,
                        onRename = { session ->
                            sharedViewModel.renameSession(session.id, session.title)
                        },
                        onRenameTo = { session, newName ->
                            sharedViewModel.renameSession(session.id, newName)
                        },
                        onTogglePinned = sharedViewModel::togglePinned,
                        onMoveSessionToFolder = sharedViewModel::moveSessionToFolder,
                        onCreateFolder = sharedViewModel::createFolder,
                        onRenameFolder = sharedViewModel::renameFolder,
                        onDeleteFolder = sharedViewModel::deleteFolder,
                        onToggleFolderExpanded = sharedViewModel::toggleFolderExpanded,
                        assistants = state.assistants,
                        currentAssistant = state.currentAssistant,
                        archivedSessions = state.archivedSessions,
                        onArchive = { id -> sharedViewModel.setSessionArchived(id, true) },
                        onUnarchive = { id -> sharedViewModel.setSessionArchived(id, false) },
                        onOpenScheduledTasks = { navController.navigate(ScheduledTasksRoute) },
                        onOpenQuickNotes = { navController.navigate(QuickNotesRoute) },
                        onOpenQuickTranslate = { navController.navigate(TranslateRoute) },
                        onOpenKnowledgeBase = { navController.navigate(KnowledgeRoute) },
                        onOpenRecentlyDeleted = { navController.navigate(RecentlyDeletedRoute) },
                        onOpenAssistants = { navController.navigate(AssistantsRoute) },
                        isSessionsLoading = state.isSessionsLoading,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // 右列:聊天页(onBack=null,列表常驻无需返回按钮)
                Box(modifier = Modifier.weight(0.6f).fillMaxSize()) {
                    ChatScreen(
                        onOpenAssistants = { navController.navigate(AssistantsRoute) },
                        onBack = null,
                        viewModel = sharedViewModel,
                        onHtmlPreview = { html ->
                            navController.navigate(HtmlPreviewRoute(html))
                        },
                        onOpenSkills = { navController.navigate(SkillsRoute) },
                        onOpenPromptTemplateManager = { navController.navigate(PromptTemplateManagerRoute) },
                    )
                }
            }
        } else {
            ChatScreen(
                onOpenAssistants = { navController.navigate(AssistantsRoute) },
                onBack = {
                    // 退出对话时触发 AI 摘要命名(仅当标题仍为默认值且有至少一轮完整对话)
                    val currentSessionId = sharedViewModel.state.value.currentSessionId
                    if (currentSessionId != null) {
                        sharedViewModel.autoTitleOnExit(currentSessionId)
                    }
                    navController.popBackStack()
                },
                // HTML/SVG 代码块全屏预览:URL 编码后跳转 HtmlPreviewScreen
                onHtmlPreview = { html ->
                    navController.navigate(HtmlPreviewRoute(html))
                },
                onOpenSkills = { navController.navigate(SkillsRoute) },
                onOpenPromptTemplateManager = { navController.navigate(PromptTemplateManagerRoute) },
            )
        }
    }
    // v1.30: 群聊详情页 — 从群聊列表 push 进入,右滑入场 + 左滑返回
    // B0-07: 提示词模板管理页(从聊天/群聊模板弹窗进入)
    composable<PromptTemplateManagerRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        PromptTemplateManagerPage(
            onBack = { navController.popBackStack() },
        )
    }
    composable<GroupChatDetailRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val chatId = backStackEntry.arguments?.getString("chatId").orEmpty()
        io.zer0.muse.ui.groupchat.GroupChatDetailScreen(
            chatId = chatId,
            onBack = { navController.popBackStack() },
            // HTML/SVG 代码块全屏预览:URL 编码后跳转 HtmlPreviewScreen
                onHtmlPreview = { html ->
                    navController.navigate(HtmlPreviewRoute(html))
                },
                onOpenPromptTemplateManager = { navController.navigate(PromptTemplateManagerRoute) },
                // v1.0.72: 编辑助手供应商 → 模型与服务设置页
                onEditAssistantProvider = { navController.navigate(io.zer0.muse.ui.navigation.SettingsModelRoute) },
        )
    }
    // 定时任务(首页大方块入口)
    composable<ScheduledTasksRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.schedule.ScheduledTasksScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.136: 快速记录(首页大方块入口替代原知识库)
    // v1.0.17: 改用 QuickNotesViewModel(Room 持久化 + 回收站),替代 QuickNoteStore
    composable<QuickNotesRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        val quickNotesViewModel: QuickNotesViewModel = koinViewModel()
        QuickNotesScreen(
            onBack = { navController.popBackStack() },
            viewModel = quickNotesViewModel,
            onSendToNewChat = { text ->
                sharedViewModel.sendToNewChat(text)
                navController.navigate(ChatDetailRoute)
            },
        )
    }
    // v1.126: Agent 私信收件箱(从 Agent 设置页进入)
    composable<AgentDmRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.agentdm.AgentDmScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.127: 里程碑管理页
    composable<MilestonesRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.MilestoneScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.127: 表情包管理页
    // v1.97 gap8: 独立翻译页(设置 → 工具 → AI 翻译)
    composable<TranslateRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.translate.TranslateScreen(
            onBack = { navController.popBackStack() },
            onSendToNewChat = { text ->
                // 创建新会话并发送文本,然后跳转到聊天详情页
                sharedViewModel.sendToNewChat(text)
                navController.navigate(ChatDetailRoute) {
                    popUpTo(TranslateRoute) { inclusive = true }
                }
            },
        )
    }
    // v2.0: 最近删除页(从 ChatListScreen 进入)
    composable<RecentlyDeletedRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        RecentlyDeletedScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // HTML 全屏预览页 — 从消息气泡内 HTML/SVG 代码块入口进入
    // 类型安全路由直接传递原始 HTML，无需 URL 编码
    composable<HtmlPreviewRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val html = backStackEntry.toRoute<HtmlPreviewRoute>().html
        HtmlPreviewScreen(
            html = html,
            onBack = { navController.popBackStack() },
        )
    }
}
