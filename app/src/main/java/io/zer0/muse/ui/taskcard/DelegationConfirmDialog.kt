package io.zer0.muse.ui.taskcard

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.zer0.muse.tools.DelegationPauseManager
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge
import io.zer0.muse.ui.theme.semiLarge

/** 弹窗内容区内边距(与 MuseDialog 对齐)。 */
private val DialogContentPadding = 22.dp
/** 弹窗元素之间间距(与 MuseDialog 对齐)。 */
private val DialogSpacing = 10.dp
/** 弹窗最大宽度(与 MuseDialog 对齐)。 */
private val DialogMaxWidth = 340.dp
/** 弹窗内容区最大高度(超出滚动)。 */
private val DialogContentMaxHeight = 460.dp
/** 中间结果预览字数上限(超出折叠 + 展开按钮)。 */
private const val INTERMEDIATE_PREVIEW_CHARS = 500

/**
 * v1.201: 委派暂停确认弹窗。
 *
 * 当 [DelegationPauseManager.activePauses] 中有活跃请求时,由 ChatScreen 末尾渲染本组件。
 * 用户可:
 *  - 批准继续(APPROVE)
 *  - 拒绝(REJECT)
 *  - 取消整个委派(CANCEL)
 *  - 修改后继续(MODIFY)— 切换到编辑模式,输入修改后的指令后再次确认
 *
 * 右上角 X 视为 CANCEL。
 *
 * @param pauseRequest 当前活跃的暂停请求;null 时不渲染任何内容
 * @param onSubmit 用户决策回调,携带 [DelegationPauseManager.PauseResponse]
 */
@Composable
fun DelegationConfirmDialog(
    pauseRequest: DelegationPauseManager.PauseRequest?,
    onSubmit: (DelegationPauseManager.PauseResponse) -> Unit,
) {
    if (pauseRequest == null) return

    Dialog(
        onDismissRequest = {
            // 点外部 / 返回键 视为取消
            onSubmit(DelegationPauseManager.PauseResponse(DelegationPauseManager.PauseDecision.CANCEL))
        },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            shape = MuseShapes.huge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = MuseElevation.none,
            shadowElevation = MuseElevation.none,
            modifier = Modifier.widthIn(max = DialogMaxWidth),
        ) {
            DelegationConfirmDialogContent(
                pauseRequest = pauseRequest,
                onSubmit = onSubmit,
                onCancel = {
                    onSubmit(DelegationPauseManager.PauseResponse(DelegationPauseManager.PauseDecision.CANCEL))
                },
            )
        }
    }
}

@Composable
private fun DelegationConfirmDialogContent(
    pauseRequest: DelegationPauseManager.PauseRequest,
    onSubmit: (DelegationPauseManager.PauseResponse) -> Unit,
    onCancel: () -> Unit,
) {
    // MODIFY 编辑模式状态
    var modifyMode by remember(pauseRequest.requestId) { mutableStateOf(false) }
    var modifiedInput by remember(pauseRequest.requestId) { mutableStateOf("") }
    // 中间结果展开状态
    var intermediateExpanded by remember(pauseRequest.requestId) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = DialogContentPadding, vertical = DialogContentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 标题栏:标题居中 + 右侧 X 按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // 占位左侧,使标题视觉居中
            Spacer(Modifier.size(MuseIconSizes.iconMedium))
            Text(
                text = stringResource(R.string.delegation_confirm_title),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(MuseIconSizes.touchTarget),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.delegation_option_cancel),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            }
        }
        Spacer(Modifier.height(DialogSpacing))

        // 内容区(可滚动,防长内容溢出)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = DialogContentMaxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DialogSpacing),
            ) {
                // 任务标题
                Text(
                    text = pauseRequest.taskTitle.ifBlank { pauseRequest.taskId },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 任务描述
                if (pauseRequest.taskDescription.isNotBlank()) {
                    InfoLabelBlock(
                        label = stringResource(R.string.delegation_confirm_task),
                        value = pauseRequest.taskDescription,
                    )
                }

                // 目标
                InfoLabelBlock(
                    label = stringResource(R.string.delegation_confirm_target),
                    value = "[${pauseRequest.targetType}] ${pauseRequest.targetName}",
                )

                // 暂停原因
                InfoLabelBlock(
                    label = stringResource(R.string.delegation_confirm_reason),
                    value = pauseRequest.reason,
                )

                // 中间结果(可折叠)
                pauseRequest.intermediateResult?.let { intermediate ->
                    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                        Text(
                            text = stringResource(R.string.delegation_confirm_intermediate),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        val displayText = if (intermediate.length > INTERMEDIATE_PREVIEW_CHARS && !intermediateExpanded) {
                            intermediate.take(INTERMEDIATE_PREVIEW_CHARS) + "..."
                        } else {
                            intermediate
                        }
                        Surface(
                            shape = MuseShapes.semiLarge,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = displayText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(MusePaddings.inputPadding),
                            )
                        }
                        if (intermediate.length > INTERMEDIATE_PREVIEW_CHARS) {
                            Text(
                                text = stringResource(
                                    if (intermediateExpanded) R.string.taskcard_collapse else R.string.taskcard_expand,
                                ),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { intermediateExpanded = !intermediateExpanded },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(DialogContentPadding))

        // 选项按钮区
        if (modifyMode) {
            // MODIFY 编辑模式:多行输入框 + 确认按钮
            Column(verticalArrangement = Arrangement.spacedBy(DialogSpacing)) {
                OutlinedTextField(
                    value = modifiedInput,
                    onValueChange = { modifiedInput = it },
                    label = { Text(stringResource(R.string.delegation_confirm_modify_hint)) },
                    minLines = 3,
                    maxLines = 6,
                    shape = MuseShapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                DelegationDialogButton(
                    text = stringResource(R.string.delegation_option_approve),
                    backgroundColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    onClick = {
                        val input = modifiedInput.trim()
                        if (input.isNotEmpty()) {
                            onSubmit(
                                DelegationPauseManager.PauseResponse(
                                    decision = DelegationPauseManager.PauseDecision.MODIFY,
                                    modifiedInput = input,
                                ),
                            )
                        }
                    },
                )
                DelegationDialogButton(
                    text = stringResource(R.string.common_cancel),
                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { modifyMode = false },
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(DialogSpacing)) {
                pauseRequest.options.forEach { option ->
                    val isDestructive = option.isDestructive
                    val bgColor = when {
                        isDestructive -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                    val contentColor = when {
                        isDestructive -> MaterialTheme.colorScheme.onError
                        else -> MaterialTheme.colorScheme.onPrimary
                    }
                    DelegationDialogButton(
                        text = option.label,
                        backgroundColor = bgColor,
                        contentColor = contentColor,
                        onClick = {
                            if (option.decision == DelegationPauseManager.PauseDecision.MODIFY) {
                                // 切换到编辑模式,不立即提交
                                modifyMode = true
                            } else {
                                onSubmit(
                                    DelegationPauseManager.PauseResponse(option.decision),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 信息标签块:label(灰小字) + value(onSurface 正文),左对齐。
 */
@Composable
private fun InfoLabelBlock(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 全宽胶囊按钮(复用 MuseDialog 按钮视觉风格:48dp 高 + 24dp 圆角 + 按压透明度变化)。
 */
@Composable
private fun DelegationDialogButton(
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
        label = "delegationBtnScale",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(MuseIconSizes.touchTarget)
            .background(
                color = backgroundColor,
                shape = MuseShapes.huge,
            )
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
