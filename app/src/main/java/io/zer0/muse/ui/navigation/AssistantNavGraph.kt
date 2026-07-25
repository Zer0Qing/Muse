package io.zer0.muse.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.zer0.muse.R
import io.zer0.muse.ui.AssistantAdvancedPage
import io.zer0.muse.ui.AssistantBasicPage
import io.zer0.muse.ui.AssistantDetailPage
import io.zer0.muse.ui.AssistantExtensionsPage
import io.zer0.muse.ui.AssistantMemoryPage
import io.zer0.muse.ui.AssistantPromptPage
import io.zer0.muse.ui.AssistantScreen
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.FavoritesScreen
import io.zer0.muse.ui.LorebookScreen
import io.zer0.muse.ui.MemoryScreen
import io.zer0.muse.ui.MuseRoutes
import io.zer0.muse.ui.PromptInjectionScreen
import io.zer0.muse.ui.QuickMessageScreen
import io.zer0.muse.ui.SkillScreen
import io.zer0.muse.ui.common.EmptyState
import io.zer0.muse.ui.common.WindowWidthClass
import io.zer0.muse.ui.common.rememberWindowWidthClass

/**
 * 助手域 NavGraph — 包含助手管理、助手详情及 5 个子页、收藏夹、世界书、
 * 快捷消息、Prompt 注入、Skills、记忆共 13 个 composable。
 *
 * 从 MainActivity 抽取以解决后者过载问题(原 1804 行 → 目标 ≤ 800 行)。
 * 所有 composable 统一使用 [MuseTransitions.horizontalPushEnter] / [horizontalPushPopExit] 过渡。
 * ASSISTANTS 页在 Expanded 模式下使用双列布局(左列表 + 右详情)。
 */
fun NavGraphBuilder.assistantNavGraph(
    navController: NavHostController,
    sharedViewModel: ChatViewModel,
) {
    composable(
        route = MuseRoutes.ASSISTANTS,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
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
                        onBack = { navController.popBackStack() },
                        onOpenDetail = { id -> selectedAssistantId = id },
                        onOpenMemory = { navController.navigate(MuseRoutes.MEMORY) },
                    )
                }
                // 右列:助手详情页(无选中时显示空状态)
                Box(modifier = Modifier.weight(0.6f).fillMaxSize()) {
                    val currentId = selectedAssistantId
                    if (currentId != null) {
                        AssistantDetailPage(
                            assistantId = currentId,
                            onBack = null,
                            onOpenBasic = { navController.navigate("${MuseRoutes.ASSISTANT_BASIC}/$currentId") },
                            onOpenPrompt = { navController.navigate("${MuseRoutes.ASSISTANT_PROMPT}/$currentId") },
                            onOpenExtensions = { navController.navigate("${MuseRoutes.ASSISTANT_EXTENSIONS}/$currentId") },
                            onOpenMemory = { navController.navigate("${MuseRoutes.ASSISTANT_MEMORY}/$currentId") },
                            onOpenAdvanced = { navController.navigate("${MuseRoutes.ASSISTANT_ADVANCED}/$currentId") },
                        )
                    } else {
                        // P1-4: 未选中助手时的占位空状态(右侧详情区空提示)
                        EmptyState(
                            icon = Icons.Outlined.AccountCircle,
                            title = stringResource(R.string.assistant_detail_title_default),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        } else {
            AssistantScreen(
                viewModel = sharedViewModel,
                onBack = { navController.popBackStack() },
                onOpenDetail = { id -> navController.navigate("${MuseRoutes.ASSISTANT_DETAIL}/$id") },
                onOpenMemory = { navController.navigate(MuseRoutes.MEMORY) },
            )
        }
    }
    // v0.37: 助手详情聚合页(头部 + 5 个子页入口)
    composable(
        route = "${MuseRoutes.ASSISTANT_DETAIL}/{assistantId}",
        arguments = listOf(navArgument("assistantId") { type = NavType.StringType }),
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val assistantId = backStackEntry.arguments?.getString("assistantId").orEmpty()
        AssistantDetailPage(
            assistantId = assistantId,
            onBack = { navController.popBackStack() },
            onOpenBasic = { navController.navigate("${MuseRoutes.ASSISTANT_BASIC}/$assistantId") },
            onOpenPrompt = { navController.navigate("${MuseRoutes.ASSISTANT_PROMPT}/$assistantId") },
            onOpenExtensions = { navController.navigate("${MuseRoutes.ASSISTANT_EXTENSIONS}/$assistantId") },
            onOpenMemory = { navController.navigate("${MuseRoutes.ASSISTANT_MEMORY}/$assistantId") },
            onOpenAdvanced = { navController.navigate("${MuseRoutes.ASSISTANT_ADVANCED}/$assistantId") },
        )
    }
    // v0.37: 助手基础子页
    composable(
        route = "${MuseRoutes.ASSISTANT_BASIC}/{assistantId}",
        arguments = listOf(navArgument("assistantId") { type = NavType.StringType }),
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val assistantId = backStackEntry.arguments?.getString("assistantId").orEmpty()
        AssistantBasicPage(
            assistantId = assistantId,
            onBack = { navController.popBackStack() },
        )
    }
    // v0.37: 助手提示词子页
    composable(
        route = "${MuseRoutes.ASSISTANT_PROMPT}/{assistantId}",
        arguments = listOf(navArgument("assistantId") { type = NavType.StringType }),
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val assistantId = backStackEntry.arguments?.getString("assistantId").orEmpty()
        AssistantPromptPage(
            assistantId = assistantId,
            onBack = { navController.popBackStack() },
        )
    }
    // v0.37: 助手扩展子页
    composable(
        route = "${MuseRoutes.ASSISTANT_EXTENSIONS}/{assistantId}",
        arguments = listOf(navArgument("assistantId") { type = NavType.StringType }),
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val assistantId = backStackEntry.arguments?.getString("assistantId").orEmpty()
        AssistantExtensionsPage(
            assistantId = assistantId,
            onBack = { navController.popBackStack() },
        )
    }
    // v0.37: 助手记忆子页
    composable(
        route = "${MuseRoutes.ASSISTANT_MEMORY}/{assistantId}",
        arguments = listOf(navArgument("assistantId") { type = NavType.StringType }),
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val assistantId = backStackEntry.arguments?.getString("assistantId").orEmpty()
        AssistantMemoryPage(
            assistantId = assistantId,
            onBack = { navController.popBackStack() },
        )
    }
    // v0.37: 助手高级子页
    composable(
        route = "${MuseRoutes.ASSISTANT_ADVANCED}/{assistantId}",
        arguments = listOf(navArgument("assistantId") { type = NavType.StringType }),
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val assistantId = backStackEntry.arguments?.getString("assistantId").orEmpty()
        AssistantAdvancedPage(
            assistantId = assistantId,
            onBack = { navController.popBackStack() },
        )
    }
    composable(
        route = MuseRoutes.MEMORY,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        MemoryScreen(
            onBack = { navController.popBackStack() },
        )
    }
    composable(
        route = MuseRoutes.FAVORITES,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        FavoritesScreen(
            viewModel = sharedViewModel,
            onBack = { navController.popBackStack() },
        )
    }
    composable(
        route = MuseRoutes.LOREBOOKS,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        LorebookScreen(
            viewModel = sharedViewModel,
            onBack = { navController.popBackStack() },
        )
    }
    composable(
        route = MuseRoutes.QUICK_MESSAGES,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        QuickMessageScreen(
            viewModel = sharedViewModel,
            onBack = { navController.popBackStack() },
        )
    }
    composable(
        route = MuseRoutes.PROMPT_INJECTIONS,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        PromptInjectionScreen(
            viewModel = sharedViewModel,
            onBack = { navController.popBackStack() },
        )
    }
    composable(
        route = MuseRoutes.SKILLS,
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SkillScreen(
            onBack = { navController.popBackStack() },
        )
    }
}
