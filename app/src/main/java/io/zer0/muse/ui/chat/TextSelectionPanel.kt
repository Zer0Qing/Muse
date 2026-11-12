@file:Suppress("FunctionNaming")

package io.zer0.muse.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBarIconButton
import io.zer0.muse.ui.theme.MusePaddings

/**
 * 全屏「选择文本」面板。
 *
 * ## 为什么要单独做一屏
 *
 * 旧实现是「点更多 → 选择文本」后**只把气泡切到可选状态并弹一句提示**，
 * 用户还得再长按一次文字、还要在气泡那个狭窄区域里拖手柄，缩略气泡里长文本根本选不准。
 *
 * 参考成熟做法：点「选择文本」**直接进入一整屏纯文本视图**，正文铺满、可以长按划选，
 * 系统上下文菜单（复制 / 全选 / 搜索）自然可用。这里不自己造菜单 ——
 * 交给 [SelectionContainer] 与平台文本选择，避免和系统手势打架。
 *
 * ## 为什么不用 BottomSheet
 *
 * 选择文本需要**尽量大的可拖拽面积**。BottomSheet 有最大高度比例限制（默认 0.78）
 * 且带拖拽关闭手势，长按拖动选区时容易误触关闭。整屏 Dialog 更稳。
 */
@Composable
fun TextSelectionPanel(
    text: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶栏：关闭 + 居中标题（与参考一致，标题不抢占正文空间）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(
                            horizontal = MusePaddings.screen,
                            vertical = MusePaddings.contentGap,
                        ),
                ) {
                    MuseTopBarIconButton(
                        icon = MuseIcons.x,
                        contentDescription = stringResource(R.string.action_close),
                        onClick = onDismiss,
                        solid = false,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    Text(
                        text = stringResource(R.string.action_select_text),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(horizontal = MusePaddings.emptyStateGap),
                    )
                }

                // 正文：整屏可选，长按即出系统菜单
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = MusePaddings.screen)
                            .padding(bottom = MusePaddings.sectionGap)
                            .navigationBarsPadding(),
                    )
                }
            }
        }
    }
}
