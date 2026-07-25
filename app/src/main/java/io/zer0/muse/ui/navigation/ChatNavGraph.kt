package io.zer0.muse.ui.navigation

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.zer0.muse.ui.ChatListScreen
import io.zer0.muse.ui.ChatScreen
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.HomeScreen
import io.zer0.muse.ui.HtmlPreviewScreen
import io.zer0.muse.ui.MuseRoutes
import io.zer0.muse.ui.RecentlyDeletedScreen
import io.zer0.muse.ui.SearchScreen
import io.zer0.muse.ui.common.WindowWidthClass
import io.zer0.muse.ui.common.rememberWindowWidthClass
import io.zer0.muse.ui.quicknotes.QuickNotesScreen
import io.zer0.muse.ui.quicknotes.QuickNotesViewModel
import org.koin.androidx.compose.koinViewModel

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
    composable(
        route = MuseRoutes.HOME,
        enterTransition = { MuseTransitions.verticalPushEnter() },
        exitTransition = { MuseTransitions.verticalPushExit() },
        popEnterTransition = { MuseTransitions.verticalPushPopEnter() },
        popExitTransition = { MuseTransitions.verticalPushPopExit() },
    ) {
        HomeScreen(
            onOpenSettings = { navController.navigate(MuseRoutes.SETTINGS) },
            onOpenAssistants = { navController.navigate(MuseRoutes.ASSISTANTS) },
            onOpenScheduledTasks = { navController.navigate(MuseRoutes.SCHEDULED_TASKS) },
            onOpenQuickNotes = { navController.navigate(MuseRoutes.QUICK_NOTES) },
            onOpenQuickTranslate = { navController.navigate(MuseRoutes.TRANSLATE) },
            onOpenKnowledgeBase = { navController.navigate(MuseRoutes.KNOWLEDGE) },
            // v0.27: 点击任务项 / 新建任务 → push 到独立聊天详情页(右滑入场,对标 iOS push)
            onOpenChat = { navController.navigate(MuseRoutes.CHAT_DETAIL) },
            // v0.45: 右上角搜索 → 独立全局搜索页
            onOpenSearch = { navController.navigate(MuseRoutes.SEARCH) },
            // v1.30: 群聊卡片点击 → 群聊详情页(右滑入场)
            onOpenGroupChat = { chatId ->
                navController.navigate(MuseRoutes.groupChatDetailRoute(chatId))
            },
            onOpenRecentlyDeleted = { navController.navigate(MuseRoutes.RECENTLY_DELETED) },
            // HTML/SVG 代码块全屏预览:URL 编码后跳转 HtmlPreviewScreen
            onHtmlPreview = { html ->
                val encoded = java.net.URLEncoder.encode(html, "UTF-8")
                navController.navigate(MuseRoutes.htmlPreviewRoute(encoded))
            },
        )
    }
    // v0.45: 独立全局搜索页(从首页右上角搜索按钮进入,右滑入场)
    composable(
        route = MuseRoutes.SEARCH,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SearchScreen(
            onBack = { navController.popBackStack() },
            onOpenSession = { sessionId ->
                // 切换会话后回到首页(任务列表会高亮该会话)
                sharedViewModel.switchSession(sessionId)
                navController.popBackStack(MuseRoutes.HOME, inclusive = false)
            },
            // v2.x: Tab=消息内容 点击消息项跳转 — 切换会话 + 设置目标消息,
            // 回到 HOME 后 ChatScreen 监听 targetMessageId 滚动定位 + 短暂高亮
            onOpenMessage = { sessionId, messageId, query ->
                sharedViewModel.switchSession(sessionId)
                sharedViewModel.setTargetMessage(messageId, query)
                navController.popBackStack(MuseRoutes.HOME, inclusive = false)
            },
            onOpenSettings = { route -> navController.navigate(route) },
        )
    }
    // v0.27: 聊天详情页 — 从首页 push 进入,右滑入场 + 左滑返回(对标 iOS push)
    // P1-4 平板适配:Expanded 模式下双列布局(左 ChatListScreen 40% + 右 ChatScreen 60%),
    //               Compact/Medium 保持单列 push/pop
    composable(
        route = MuseRoutes.CHAT_DETAIL,
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
                        onOpenScheduledTasks = { navController.navigate(MuseRoutes.SCHEDULED_TASKS) },
                        onOpenQuickNotes = { navController.navigate(MuseRoutes.QUICK_NOTES) },
                        onOpenQuickTranslate = { navController.navigate(MuseRoutes.TRANSLATE) },
                        onOpenKnowledgeBase = { navController.navigate(MuseRoutes.KNOWLEDGE) },
                        onOpenRecentlyDeleted = { navController.navigate(MuseRoutes.RECENTLY_DELETED) },
                        onOpenAssistants = { navController.navigate(MuseRoutes.ASSISTANTS) },
                        isSessionsLoading = state.isSessionsLoading,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // 右列:聊天页(onBack=null,列表常驻无需返回按钮)
                Box(modifier = Modifier.weight(0.6f).fillMaxSize()) {
                    ChatScreen(
                        onOpenAssistants = { navController.navigate(MuseRoutes.ASSISTANTS) },
                        onBack = null,
                        viewModel = sharedViewModel,
                        onHtmlPreview = { html ->
                            val encoded = java.net.URLEncoder.encode(html, "UTF-8")
                            navController.navigate(MuseRoutes.htmlPreviewRoute(encoded))
                        },
                    )
                }
            }
        } else {
            ChatScreen(
                onOpenAssistants = { navController.navigate(MuseRoutes.ASSISTANTS) },
                onBack = { navController.popBackStack() },
                // HTML/SVG 代码块全屏预览:URL 编码后跳转 HtmlPreviewScreen
                onHtmlPreview = { html ->
                    val encoded = java.net.URLEncoder.encode(html, "UTF-8")
                    navController.navigate(MuseRoutes.htmlPreviewRoute(encoded))
                },
            )
        }
    }
    // v1.30: 群聊详情页 — 从群聊列表 push 进入,右滑入场 + 左滑返回
    composable(
        route = MuseRoutes.GROUP_CHAT_DETAIL + "/{chatId}",
        arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val chatId = backStackEntry.arguments?.getString("chatId").orEmpty()
        io.zer0.muse.ui.groupchat.GroupChatDetailScreen(
            chatId = chatId,
            onBack = { navController.popBackStack() },
            // HTML/SVG 代码块全屏预览:URL 编码后跳转 HtmlPreviewScreen
            onHtmlPreview = { html ->
                val encoded = java.net.URLEncoder.encode(html, "UTF-8")
                navController.navigate(MuseRoutes.htmlPreviewRoute(encoded))
            },
        )
    }
    // 定时任务(首页大方块入口)
    composable(
        route = MuseRoutes.SCHEDULED_TASKS,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.schedule.ScheduledTasksScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.136: 快速记录(首页大方块入口替代原知识库)
    // v1.0.17: 改用 QuickNotesViewModel(Room 持久化 + 回收站),替代 QuickNoteStore
    composable(
        route = MuseRoutes.QUICK_NOTES,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        val quickNotesViewModel: QuickNotesViewModel = koinViewModel()
        QuickNotesScreen(
            onBack = { navController.popBackStack() },
            viewModel = quickNotesViewModel,
            onSendToNewChat = { text ->
                sharedViewModel.sendToNewChat(text)
                navController.navigate(MuseRoutes.CHAT_DETAIL)
            },
        )
    }
    // v1.126: Agent 私信收件箱(从 Agent 设置页进入)
    composable(
        route = MuseRoutes.AGENT_DM,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.agentdm.AgentDmScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.127: 里程碑管理页
    composable(
        route = MuseRoutes.MILESTONES,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.MilestoneScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.127: 表情包管理页
    composable(
        route = MuseRoutes.STICKERS,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.StickerManagerScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.97 gap8: 独立翻译页(设置 → 工具 → AI 翻译)
    composable(
        route = MuseRoutes.TRANSLATE,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.translate.TranslateScreen(
            onBack = { navController.popBackStack() },
            onSendToNewChat = { text ->
                // 创建新会话并发送文本,然后跳转到聊天详情页
                sharedViewModel.sendToNewChat(text)
                navController.navigate(MuseRoutes.CHAT_DETAIL) {
                    popUpTo(MuseRoutes.TRANSLATE) { inclusive = true }
                }
            },
        )
    }
    // v2.0: 最近删除页(从 ChatListScreen 进入)
    composable(
        route = MuseRoutes.RECENTLY_DELETED,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        RecentlyDeletedScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // HTML 全屏预览页 — 从消息气泡内 HTML/SVG 代码块入口进入
    // html 参数由调用方 URLEncoder.encode 编码,此处 NavType.StringType 接收原字符串
    composable(
        route = "${MuseRoutes.HTML_PREVIEW}/{html}",
        arguments = listOf(navArgument("html") { type = NavType.StringType }),
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val encodedHtml = backStackEntry.arguments?.getString("html").orEmpty()
        // URL 解码还原 HTML 源码(调用方用 URLEncoder.encode 编码后注入路由,
        // NavHost 以 NavType.StringType 原样接收,这里解码还原)
        val html = runCatching {
            java.net.URLDecoder.decode(encodedHtml, "UTF-8")
        }.getOrDefault(encodedHtml)
        HtmlPreviewScreen(
            html = html,
            onBack = { navController.popBackStack() },
        )
    }
}
