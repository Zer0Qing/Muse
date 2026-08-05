package io.zer0.muse.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.DataManagementScreen
import io.zer0.muse.ui.MuseRoutes
import io.zer0.muse.ui.NotificationListenerScreen
import io.zer0.muse.ui.ToolsScreen
import io.zer0.muse.ui.VideoGenerationPage
import io.zer0.muse.ui.knowledge.KnowledgeScreen
import io.zer0.muse.ui.speech.VoiceCloningPage
import io.zer0.muse.ui.stats.StatsScreen

/**
 * 工具域 NavGraph — 包含 AI 工具管理、浏览器自动化、视频生成、语音克隆、
 * 统计、通知监听、数据管理、知识库共 8 个 composable。
 *
 * 从 MainActivity 抽取以解决后者过载问题(原 1804 行 → 目标 ≤ 800 行)。
 * 所有 composable 统一使用 [MuseTransitions.horizontalPushEnter] / [horizontalPushPopExit] 过渡。
 * STATS 页需要 [ChatViewModel] 以支持点击会话跳转回首页。
 */
fun NavGraphBuilder.toolsNavGraph(
    navController: NavHostController,
    sharedViewModel: ChatViewModel,
) {
    // v1.0.4: AI 工具管理页(展示 ToolRegistry 全部工具 + 详情 + 风险等级)
    composable<ToolsScreenRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        ToolsScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // P2-6: 浏览器自动化演示页 — 全屏 WebView + 顶部地址栏 + 底部操作栏
    // P2-8: 视频生成页 — 从设置 → 工具 → 视频生成 进入
    composable<VideoGenerationRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        VideoGenerationPage(
            onBack = { navController.popBackStack() },
        )
    }
    // P2-9: 语音克隆页 — 从设置 → 媒体 → 语音克隆 进入
    composable<VoiceCloningRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        VoiceCloningPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v0.46: 统计页(热力图 + 使用概览,右滑入场)
    composable<StatsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        StatsScreen(
            onBack = { navController.popBackStack() },
            onOpenSession = { sessionId ->
                // v1.66: 统计页点击会话跳转 — 切换会话后回到首页
                sharedViewModel.switchSession(sessionId)
                navController.popBackStack(HomeRoute, inclusive = false)
            },
        )
    }
    // v1.0.4: 通知监听页(授权引导 + 最近通知列表)
    composable<NotificationListenerRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        NotificationListenerScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // v2.0: 数据管理页(从设置进入)
    composable<DataManagementRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        DataManagementScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // 知识库(首页大方块入口)
    composable<KnowledgeRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        KnowledgeScreen(
            onBack = { navController.popBackStack() },
            onOpenCoverManager = { navController.navigate(CoverManagerRoute) },
        )
    }

    // v1.0.53: 封面库管理页(知识库文档详情 → 封面库 / 生成封面)
    composable<CoverManagerRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.CoverManagerScreen(
            onBack = { navController.popBackStack() },
        )
    }
}
