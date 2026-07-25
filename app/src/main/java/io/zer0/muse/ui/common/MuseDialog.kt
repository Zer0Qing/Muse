package io.zer0.muse.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import io.zer0.muse.ui.theme.MuseAnimation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge

/**
 * L-DLG3: 弹窗专属尺寸令牌,避免裸 22/10/340/420dp 散落多处难以统一调整。
 */
/** 弹窗内容区内边距。 */
private val DialogContentPadding = 22.dp
/** 弹窗元素之间间距(标题与内容、内容与按钮、按钮之间)。 */
private val DialogSpacing = 10.dp
/** 弹窗最大宽度(限制在大屏上的居中宽度)。 */
private val DialogMaxWidth = 340.dp
/** 弹窗内容区最大高度(超出滚动)。 */
private val DialogContentMaxHeight = 420.dp

/**
 * v0.28: 自定义风格弹窗 — 居中卡片式弹窗。
 *
 * 设计要点(告别原生 AlertDialog 的扁平文字按钮感):
 *  - 容器:RoundedCornerShape(24dp) + surface 色 + 居中 + 无 tonalElevation
 *  - 标题:titleLarge + SemiBold + 居中对齐
 *  - 内容:bodyMedium + 暖灰 onSurfaceVariant + 居中
 *  - 按钮:垂直排列 + 全宽胶囊形(高 48dp + RoundedCornerShape(24dp))
 *    - 主按钮:品牌绿(primary)背景 + 白字 + SemiBold
 *      (destructive = true 时改用 error 红色)
 *    - 次按钮:surfaceVariant 浅灰背景 + onSurface 文字
 *  - 按下不透明度变化(替代 ripple 默认效果,更精致)
 *
 * 与原生 AlertDialog 的视觉差异:
 *  - 按钮是胶囊形彩色块(iOS 风格),而非扁平文字
 *  - 标题/内容居中对齐,而非左对齐
 *  - 无分隔线,间距和色块承担分区职责
 *  - 主按钮高对比度(品牌绿/红),操作意图一目了然
 *
 * 用法:
 * ```kotlin
 * MuseDialog(
 *     onDismissRequest = { showDialog = false },
 *     title = "确认删除",
 *     content = { Text("此操作不可撤销,确认删除该会话?") },
 *     confirmText = "删除",
 *     onConfirm = { /* 删除 */ },
 *     destructive = true,
 * )
 * ```
 */
@Composable
fun MuseDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    content: @Composable () -> Unit,
    confirmText: String = stringResource(R.string.common_confirm),
    onConfirm: (() -> Unit)? = null,
    dismissText: String? = stringResource(R.string.common_cancel),
    onDismiss: (() -> Unit)? = null,
    /** 主按钮是否为危险操作(删除/清除) — true 时背景改用 error 红色。 */
    destructive: Boolean = false,
    properties: DialogProperties = DialogProperties(
        // v0.28: 关闭弹窗时整体淡出,提升精致感
        dismissOnClickOutside = true,
        dismissOnBackPress = true,
        // edge-to-edge: 关闭 decor 系统窗镶嵌,使内部 navigationBarsPadding 正确偏移系统导航栏,
        // 避免底部胶囊按钮被手势条/导航栏遮挡。
        decorFitsSystemWindows = false,
    ),
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        Surface(
            shape = MuseShapes.huge,
            color = MaterialTheme.colorScheme.surface,
            // v0.28: 无 tonalElevation(扁平 + 暖色 surface),阴影由 Dialog 系统层提供
            tonalElevation = MuseElevation.none,
            shadowElevation = MuseElevation.none,
            // L-DLG3: 340.dp → DialogMaxWidth 令牌。
            modifier = Modifier.widthIn(max = DialogMaxWidth),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding() // v1.48: 键盘遮挡修复
                    .navigationBarsPadding()
                    // L-DLG3: 22.dp → DialogContentPadding 令牌。
                    .padding(horizontal = DialogContentPadding, vertical = DialogContentPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 标题(居中加粗)
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(DialogSpacing))
                }
                // 内容区(居中 + 可滚动,防长内容溢出)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // L-DLG3: 420.dp → DialogContentMaxHeight 令牌。
                        .heightIn(max = DialogContentMaxHeight)
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        content()
                    }
                }
                Spacer(Modifier.height(DialogContentPadding))
                // 按钮区(垂直排列 + 全宽胶囊)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(DialogSpacing),
                ) {
                    // 主按钮(全宽胶囊,品牌绿/红色背景)
                    if (onConfirm != null) {
                        val confirmBg = if (destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                        MuseDialogButton(
                            text = confirmText,
                            backgroundColor = confirmBg,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            onClick = {
                                onConfirm()
                            },
                        )
                    }
                    // 次按钮(全宽胶囊,浅灰背景)
                    if (dismissText != null) {
                        MuseDialogButton(
                            text = dismissText,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = {
                                onDismiss?.invoke() ?: onDismissRequest()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 弹窗内部胶囊按钮 — 全宽 + 48dp 高 + 24dp 圆角 + 按压透明度变化。
 * 替代原生 TextButton,提升"应用感"和视觉层次。
 */
@Composable
private fun MuseDialogButton(
    text: String,
    backgroundColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(MuseAnimation.FAST_MS),
        label = "dialogBtnScale",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // L-DLG3: 48.dp → MuseIconSizes.touchTarget 令牌(满足 MD3 触摸目标红线)。
            .height(MuseIconSizes.touchTarget)
            .background(
                color = backgroundColor,
                shape = MuseShapes.huge,
            )
            // M-DLG1: 加 role = Role.Button, TalkBack 朗读为"按钮"并注册正确的点击语义动作,
            // 旧实现裸 clickable 会被朗读为通用可点击区域,操作意图不明确。
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .graphicsLayer { scaleX = scale; scaleY = scale },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = contentColor,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 简化版确认弹窗 — 仅标题 + 消息 + 单按钮(用于提示性弹窗)。
 */
@Composable
fun MuseAlertDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    message: String,
    confirmText: String = stringResource(R.string.common_got_it),
    // L-DLG4: 默认 onConfirm 改为空 lambda,避免旧默认值 = onDismissRequest 与下方
    // onConfirm 回调里的 onDismissRequest() 重复调用导致关闭逻辑执行两次。
    onConfirm: () -> Unit = {},
) {
    MuseDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        content = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        },
        confirmText = confirmText,
        onConfirm = {
            onConfirm()
            onDismissRequest()
        },
        dismissText = null,
    )
}
