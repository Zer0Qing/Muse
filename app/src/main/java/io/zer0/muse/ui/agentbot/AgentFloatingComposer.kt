package io.zer0.muse.ui.agentbot

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import coil.compose.AsyncImage
import io.zer0.muse.R
import io.zer0.muse.data.agentbot.AgentBotUiState
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill

/**
 * 底部悬浮药丸输入栏。
 *
 * 后台任务执行和前台流式回复期间均不锁定输入框，保证“做事时随时继续说话”的核心体验。
 */
@Composable
fun AgentFloatingComposer(
    composer: AgentBotUiState.Composer,
    busy: AgentBotUiState.Busy,
    isStreaming: Boolean,
    errorText: String?,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStopReply: () -> Unit,
    onPickImages: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onVoiceInput: () -> Unit,
    onOpenWorkbench: () -> Unit,
    onTriggerBriefing: () -> Unit,
    onStartTeach: () -> Unit,
    onOpenModel: (() -> Unit)?,
    onOpenProviderSettings: (() -> Unit)?,
    onOpenRuntimeInstall: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var plusMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val hasText = composer.text.isNotBlank()
    val plusCd = stringResource(R.string.agent_hub_plus_cd)
    val voiceCd = stringResource(R.string.agent_hub_voice_cd)
    val sendCd = stringResource(R.string.action_send)
    val stopCd = stringResource(R.string.agent_bot_stop_reply)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(
                start = MusePaddings.inputIslandHorizontal,
                end = MusePaddings.inputIslandHorizontal,
                top = MusePaddings.tightGap,
                bottom = MusePaddings.inputIslandBottomGap,
            ),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.inputStackGap),
    ) {
        if (!errorText.isNullOrBlank()) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = MusePaddings.contentGap),
            )
        }

        if (composer.pendingImages.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                modifier = Modifier.padding(horizontal = MusePaddings.tightGap),
            ) {
                items(composer.pendingImages, key = { it.hashCode() }) { image ->
                    Box {
                        AsyncImage(
                            model = "data:image/jpeg;base64,$image",
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(MusePaddings.previewThumb)
                                .clip(MuseShapes.medium),
                        )
                        Surface(
                            onClick = { onRemoveImage(image) },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            shadowElevation = MuseElevation.low,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(MusePaddings.removeDotPadding)
                                .size(MuseIconSizes.icon),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = MuseIcons.x,
                                    contentDescription = stringResource(R.string.agent_screen_remove_image),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                                )
                            }
                        }
                    }
                }
            }
        }

        Surface(
            shape = MuseShapes.pill,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = MuseElevation.medium,
            border = BorderStroke(
                width = MuseBorderWidth.hairline,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.subtleBorder),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = MusePaddings.contentGap,
                        vertical = MusePaddings.tightGap,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                // 左侧 [+] 快捷工具抽屉
                Box {
                    MuseTactileButton(
                        icon = MuseIcons.plus,
                        contentDescription = plusCd,
                        onClick = { plusMenuExpanded = true },
                    )
                    DropdownMenu(
                        expanded = plusMenuExpanded,
                        onDismissRequest = { plusMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.agent_screen_add_image)) },
                            leadingIcon = { Icon(MuseIcons.image, contentDescription = null) },
                            onClick = {
                                plusMenuExpanded = false
                                onPickImages()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.agent_hub_cue_trigger_briefing)) },
                            leadingIcon = { Icon(MuseIcons.sun, contentDescription = null) },
                            onClick = {
                                plusMenuExpanded = false
                                onTriggerBriefing()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.agent_hub_quick_teach)) },
                            leadingIcon = { Icon(MuseIcons.handFinger, contentDescription = null) },
                            onClick = {
                                plusMenuExpanded = false
                                onStartTeach()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.agent_hub_quick_workbench)) },
                            leadingIcon = { Icon(MuseIcons.computer, contentDescription = null) },
                            onClick = {
                                plusMenuExpanded = false
                                onOpenWorkbench()
                            },
                        )
                        if (onOpenModel != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_select_provider)) },
                                leadingIcon = { Icon(MuseIcons.brain, contentDescription = null) },
                                onClick = {
                                    plusMenuExpanded = false
                                    onOpenModel()
                                },
                            )
                        }
                        if (onOpenProviderSettings != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.agent_screen_provider_settings)) },
                                leadingIcon = { Icon(MuseIcons.sliders, contentDescription = null) },
                                onClick = {
                                    plusMenuExpanded = false
                                    onOpenProviderSettings()
                                },
                            )
                        }
                        if (onOpenRuntimeInstall != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.agent_screen_runtime_install)) },
                                leadingIcon = { Icon(MuseIcons.terminal, contentDescription = null) },
                                onClick = {
                                    plusMenuExpanded = false
                                    onOpenRuntimeInstall()
                                },
                            )
                        }
                    }
                }

                // 中间自适应输入框
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = MuseIconSizes.inputMinHeight)
                        .padding(vertical = MusePaddings.labelVerticalGap),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (composer.text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.agent_hub_input_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = composer.text,
                        onValueChange = onTextChange,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = { if (hasText) onSend() },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 右侧：有文字 → 发送；流式回复中且无输入 → 停止；否则 → 语音
                when {
                    hasText -> {
                        Surface(
                            onClick = onSend,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(MuseIconSizes.stopButton)
                                .semantics { contentDescription = sendCd },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = MuseIcons.send,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                )
                            }
                        }
                    }
                    busy.sending || isStreaming -> {
                        Surface(
                            onClick = onStopReply,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .size(MuseIconSizes.stopButton)
                                .semantics { contentDescription = stopCd },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = MuseIcons.stop,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                )
                            }
                        }
                    }
                    else -> {
                        MuseTactileButton(
                            icon = MuseIcons.microphone,
                            contentDescription = voiceCd,
                            onClick = onVoiceInput,
                        )
                    }
                }
            }
        }
    }
}
