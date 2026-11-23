package io.zer0.muse.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.zer0.muse.R
import io.zer0.muse.ui.chat.ChatViewModel
import io.zer0.muse.ui.chat.QuickMessageScreen
import io.zer0.muse.ui.common.animation.LocalMuseAnimatedVisibilityScope
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.memory.MemoryScreen
import io.zer0.muse.ui.assistant.AssistantAdvancedPage
import io.zer0.muse.ui.assistant.AssistantDetailPage
import io.zer0.muse.ui.assistant.AssistantBasicPage
import io.zer0.muse.ui.assistant.AssistantPromptPage
import io.zer0.muse.ui.assistant.AssistantExtensionsPage
import io.zer0.muse.ui.assistant.AssistantMemoryPage
import io.zer0.muse.ui.assistant.AssistantScreen
import io.zer0.muse.ui.screen.FavoritesScreen
import io.zer0.muse.ui.screen.SkillScreen
import io.zer0.muse.ui.screen.LorebookScreen
import io.zer0.muse.ui.screen.PromptInjectionScreen

/**
 * 助手域 NavGraph — 包含助手管理、助手详情及 5 个子页、收藏夹、世界书、
 * 快捷消息、Prompt 注入、Skills、记忆共 13 个 composable。
 *
 * 从 MainActivity 抽取以解决后者过载问题(原 1804 行 → 目标 ≤ 800 行)。
 * 所有 composable 统一使用 [MuseTransitions.horizontalPushEnter] / [horizontalPushPopExit] 过渡。
 * ASSISTANTS 页在 Expanded 模式下使用双列布局(左列表 + 右详情)。
 */
fun NavGraphBuilder.assistantNavGraph(navController: NavHostController, sharedViewModel: ChatViewModel) {
    composable<AssistantsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        CompositionLocalProvider(LocalMuseAnimatedVisibilityScope provides this) {
            // P1-4 平板适配:Expanded 模式下双列布局(左 AssistantScreen 40% + 右 AssistantDetailPage 60%)
            val widthClass = rememberWindowWidthClass()
            if (widthClass == WindowWidthClass.Expanded) {
                // 跟踪当前选中的助手 ID(rememberSaveable 保证旋转/配置变更后保留)
                var selectedAssistantId by rememberSaveable { mutableStateOf<String?>(null) }
                Row(modifier = Modifier.fillMaxSize()) {
                    // 左列:助手列表(onBack 仍可返回上级;onOpenDetail 切换右栏)
                    Box(modifier = Modifier.weight(0.4f).fillMaxSize()) {
                        AssistantScreen(
                            viewModel = sharedViewModel,
                            onBack = { navController.popBackStackSafe() },
                            onOpenDetail = { id -> selectedAssistantId = id },
                        )
                    }
                    // 右列:助手详情页(无选中时显示空状态)
                    Box(modifier = Modifier.weight(0.6f).fillMaxSize()) {
                        val currentId = selectedAssistantId
                        if (currentId != null) {
                            AssistantDetailPage(
                                assistantId = currentId,
                                onBack = null,
                                onOpenBasic = { navController.navigateSingleTop(AssistantBasicRoute(currentId)) },
                                onOpenPrompt = { navController.navigateSingleTop(AssistantPromptRoute(currentId)) },
                                onOpenExtensions = { navController.navigateSingleTop(AssistantExtensionsRoute(currentId)) },
                                onOpenMemory = { navController.navigateSingleTop(AssistantMemoryRoute(currentId)) },
                                onOpenAdvanced = { navController.navigateSingleTop(AssistantAdvancedRoute(currentId)) },
                            )
                        } else {
                            // P1-4: 未选中助手时的占位空状态(右侧详情区空提示)
                            MuseEmptyState(
                                icon = MuseIcons.user,
                                title = stringResource(R.string.assistant_detail_title_default),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            } else {
                AssistantScreen(
                    viewModel = sharedViewModel,
                    onBack = { navController.popBackStackSafe() },
                    onOpenDetail = { id -> navController.navigateSingleTop(AssistantDetailRoute(id)) },
                )
            }
        } // 关闭共享元素作用域注入
    }
    // v0.37: 助手详情聚合页(头部 + 5 个子页入口)
    composable<AssistantDetailRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val assistantId = backStackEntry.arguments?.getString("assistantId").orEmpty()
        CompositionLocalProvider(LocalMuseAnimatedVisibilityScope provides this) {
            AssistantDetailPage(
                assistantId = assistantId,
                onBack = { navController.popBackStackSafe() },
                onOpenBasic = { navController.navigateSingleTop(AssistantBasicRoute(assistantId)) },
                onOpenPrompt = { navController.navigateSingleTop(AssistantPromptRoute(assistantId)) },
                onOpenExtensions = { navController.navigateSingleTop(AssistantExtensionsRoute(assistantId)) },
                onOpenMemory = { navController.navigateSingleTop(AssistantMemoryRoute(assistantId)) },
                onOpenAdvanced = { navController.navigateSingleTop(AssistantAdvancedRoute(assistantId)) },
            )
        } // 关闭共享元素作用域注入
    }
    // v0.37: 助手基础子页
    composable<AssistantBasicRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val assistantId = backStackEntry.arguments?.getString("assistantId").orEmpty()
        AssistantBasicPage(
            assistantId = assistantId,
            onBack = { navController.popBackStackSafe() },
        )
    }
    // v0.37: 助手提示词子页
    composable<AssistantPromptRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val assistantId = backStackEntry.arguments?.getString("assistantId").orEmpty()
        AssistantPromptPage(
            assistantId = assistantId,
            onBack = { navController.popBackStackSafe() },
        )
    }
    // v0.37: 助手扩展子页
    composable<AssistantExtensionsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val assistantId = backStackEntry.arguments?.getString("assistantId").orEmpty()
        AssistantExtensionsPage(
            assistantId = assistantId,
            onBack = { navController.popBackStackSafe() },
        )
    }
    // v0.37: 助手记忆子页
    composable<AssistantMemoryRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val assistantId = backStackEntry.arguments?.getString("assistantId").orEmpty()
        AssistantMemoryPage(
            assistantId = assistantId,
            onBack = { navController.popBackStackSafe() },
        )
    }
    // v0.37: 助手高级子页
    composable<AssistantAdvancedRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val assistantId = backStackEntry.arguments?.getString("assistantId").orEmpty()
        AssistantAdvancedPage(
            assistantId = assistantId,
            onBack = { navController.popBackStackSafe() },
        )
    }
    composable<MemoryRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        MemoryScreen(
            onBack = { navController.popBackStackSafe() },
            onOpenSettings = { navController.navigateSingleTop(SettingsMemoryConfigRoute) },
            onOpenSession = { sessionId ->
                sharedViewModel.switchSession(sessionId)
                navController.navigate(ChatDetailRoute) { launchSingleTop = true }
            },
        )
    }
    composable<FavoritesRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        FavoritesScreen(
            viewModel = sharedViewModel,
            onBack = { navController.popBackStackSafe() },
        )
    }
    composable<LorebooksRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        LorebookScreen(
            viewModel = sharedViewModel,
            onBack = { navController.popBackStackSafe() },
        )
    }
    // P1-2: Worldbook 动态世界书(独立 ViewModel,不依赖 ChatViewModel)
    composable<WorldbookRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.worldbook.WorldBookScreen(
            onBack = { navController.popBackStackSafe() },
        )
    }
    composable<QuickMessagesRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        QuickMessageScreen(
            viewModel = sharedViewModel,
            onBack = { navController.popBackStackSafe() },
        )
    }
    composable<PromptInjectionsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        PromptInjectionScreen(
            viewModel = sharedViewModel,
            onBack = { navController.popBackStackSafe() },
        )
    }
    composable<SkillsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SkillScreen(
            onBack = { navController.popBackStackSafe() },
        )
    }
}
