package io.zer0.muse.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import io.zer0.muse.ui.theme.MuseAnimation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * P2-13: 桌面端右键菜单项。
 *
 * @param label 显示文本(已本地化)
 * @param icon 可选图标(对齐桌面应用习惯,左侧显示)
 * @param onClick 点击回调(组件会在调用后自动 dismiss)
 * @param destructive 是否为危险操作(删除等),true 时图标与文字改用 error 红色
 */
data class ContextMenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
)

/**
 * P2-13: 桌面端右键上下文菜单。
 *
 * 设计要点(对齐桌面应用语境,而非移动端 ActionSheet):
 *  - 容器:Surface + [MuseShapes.medium] 圆角 + surface 色 + 无 tonalElevation
 *  - 宽度:固定 220dp(对齐 macOS / VS Code 右键菜单宽度)
 *  - 项:左侧图标 + 文字 + 全宽点击区,高度满足 [MuseIconSizes.touchTarget] 红线
 *  - 危险项([ContextMenuItem.destructive] = true)用 error 红色高亮
 *  - 按压时透明度变化(替代 ripple,与 [MuseDialog] 按钮风格一致)
 *  - 不使用 Material3 AlertDialog / Button(遵守项目设计令牌约束)
 *
 * 显示位置:Compose `Dialog` 默认居中,在桌面语境下足够使用;若后续需要跟随光标,
 * 可在外层用 `Popup` + offset 替代,但不在本任务范围内。
 *
 * @param items 菜单项列表(顺序即显示顺序)
 * @param onDismiss 用户点击外部 / Esc / 选择菜单项后的关闭回调
 */
@Composable
fun DesktopContextMenu(
    items: List<ContextMenuItem>,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // 桌面语境:点击外部关闭 + Esc 关闭(对齐桌面应用习惯)
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            shape = MuseShapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = MuseElevation.low,
            shadowElevation = MuseElevation.modal,
            modifier = Modifier.widthIn(min = 200.dp, max = 240.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = MusePaddings.tightGap),
            ) {
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(MusePaddings.tightGap))
                    }
                    ContextMenuRow(item = item, onDismiss = onDismiss)
                }
            }
        }
    }
}

/**
 * 单个菜单项行 — 全宽点击区 + 左侧图标 + 文字 + 按压透明度反馈。
 */
@Composable
private fun ContextMenuRow(
    item: ContextMenuItem,
    onDismiss: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(MuseAnimation.FAST_MS),
        label = "contextMenuRowScale",
    )
    val tint = if (item.destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MuseIconSizes.touchTarget)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = {
                    item.onClick()
                    onDismiss()
                },
            )
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .padding(horizontal = MusePaddings.screen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
    ) {
        if (item.icon != null) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        } else {
            // 无图标时占位,保持文字对齐
            Spacer(modifier = Modifier.width(MuseIconSizes.iconMedium))
        }
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = tint,
        )
    }
}
