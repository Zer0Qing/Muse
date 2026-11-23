package io.zer0.muse.ui.agentbot

import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import coil.compose.AsyncImage
import io.zer0.muse.R
import io.zer0.muse.data.agentbot.AgentBotUiState
import io.zer0.muse.ui.chat.MuseToolSheet
import io.zer0.muse.ui.chat.ToolEntry
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill

/**
 * 底部独立悬浮药丸输入岛。
 *
 * 外层不铺背景底条，直接叠浮在全屏对话流之上；
 * 后台任务执行和前台流式回复期间均不锁定输入框，加号菜单复用普通对话的 [MuseToolSheet]。
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
    onPickGalleryImage: (Uri) -> Unit,
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
    var showToolSheet by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
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
            shadowElevation = MuseElevation.high,
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
                // 左侧 [+] 按钮：沿用普通对话的 MuseToolSheet 底部面板
                MuseTactileButton(
                    icon = MuseIcons.plus,
                    contentDescription = plusCd,
                    onClick = {
                        MuseHaptics.light(hapticFeedback)
                        showToolSheet = true
                    },
                )

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

    if (showToolSheet) {
        val workbenchTitle = stringResource(R.string.agent_hub_quick_workbench)
        val briefingTitle = stringResource(R.string.agent_hub_cue_trigger_briefing)
        val teachTitle = stringResource(R.string.agent_hub_quick_teach)
        val modelTitle = stringResource(R.string.chat_select_provider)
        val providerTitle = stringResource(R.string.agent_screen_provider_settings)
        val runtimeTitle = stringResource(R.string.agent_screen_runtime_install)
        val toolEntries = buildList {
            add(
                ToolEntry(
                    icon = MuseIcons.computer,
                    title = workbenchTitle,
                    titleRes = R.string.agent_hub_quick_workbench,
                    onClick = {
                        MuseHaptics.light(hapticFeedback)
                        showToolSheet = false
                        onOpenWorkbench()
                    },
                ),
            )
            add(
                ToolEntry(
                    icon = MuseIcons.sun,
                    title = briefingTitle,
                    titleRes = R.string.agent_hub_cue_trigger_briefing,
                    onClick = {
                        MuseHaptics.light(hapticFeedback)
                        showToolSheet = false
                        onTriggerBriefing()
                    },
                ),
            )
            add(
                ToolEntry(
                    icon = MuseIcons.handFinger,
                    title = teachTitle,
                    titleRes = R.string.agent_hub_quick_teach,
                    onClick = {
                        MuseHaptics.light(hapticFeedback)
                        showToolSheet = false
                        onStartTeach()
                    },
                ),
            )
            if (onOpenModel != null) {
                add(
                    ToolEntry(
                        icon = MuseIcons.brain,
                        title = modelTitle,
                        titleRes = R.string.chat_select_provider,
                        onClick = {
                            MuseHaptics.light(hapticFeedback)
                            showToolSheet = false
                            onOpenModel()
                        },
                    ),
                )
            }
            if (onOpenProviderSettings != null) {
                add(
                    ToolEntry(
                        icon = MuseIcons.sliders,
                        title = providerTitle,
                        titleRes = R.string.agent_screen_provider_settings,
                        onClick = {
                            MuseHaptics.light(hapticFeedback)
                            showToolSheet = false
                            onOpenProviderSettings()
                        },
                    ),
                )
            }
            if (onOpenRuntimeInstall != null) {
                add(
                    ToolEntry(
                        icon = MuseIcons.terminal,
                        title = runtimeTitle,
                        titleRes = R.string.agent_screen_runtime_install,
                        onClick = {
                            MuseHaptics.light(hapticFeedback)
                            showToolSheet = false
                            onOpenRuntimeInstall()
                        },
                    ),
                )
            }
        }
        MuseToolSheet(
            context = context,
            hapticFeedback = hapticFeedback,
            onPickImage = {
                showToolSheet = false
                onPickImages()
            },
            onPickGalleryImage = { uri ->
                showToolSheet = false
                onPickGalleryImage(uri)
            },
            entries = toolEntries,
            onDismiss = { showToolSheet = false },
            featuredTitleRes = listOf(
                R.string.agent_hub_quick_workbench,
                R.string.agent_hub_cue_trigger_briefing,
            ),
        )
    }
}
