package io.zer0.muse.miniphone.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.zer0.muse.miniphone.ui.shell.MiniPhoneShell
import io.zer0.muse.miniphone.ui.shell.MiniPhoneShellActions
import io.zer0.muse.miniphone.ui.shell.MiniPhoneShellViewModel
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import org.koin.androidx.compose.koinViewModel

/**
 * 小手机模块入口。
 *
 * 只做三件事:从 Koin 取外壳 ViewModel、收集它的一份状态、把状态交给主题里的外壳。
 * 状态与渲染的分界就在这里 —— 下面全是无状态组件,可以直接在测试里喂数据。
 *
 * 本模块还没接进宿主导航,这个入口目前只用于独立预览与 UI 测试。
 */
@Composable
fun MiniPhoneRoot(modifier: Modifier = Modifier) {
    val viewModel: MiniPhoneShellViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsState()
    MiniPhoneTheme {
        MiniPhoneShell(
            state = state,
            modifier = modifier,
            onOpenCharacterChat = viewModel::openOrCreateThread,
            actions = MiniPhoneShellActions(
                onToggleAutoMoment = viewModel::setAutoMomentEnabled,
                onToggleChatProactive = viewModel::setChatProactiveEnabled,
                onToggleThreadPin = viewModel::setThreadPinned,
                onMarkThreadRead = viewModel::markThreadRead,
                onDeleteThread = viewModel::deleteThread,
                onClearConversations = viewModel::clearConversations,
                onCreateGroup = viewModel::createGroup,
            ),
        )
    }
}
