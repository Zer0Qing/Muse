package io.zer0.muse.ui.agentbot

import android.net.Uri
import android.view.KeyEvent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.zer0.ai.core.ReasoningLevel
import io.zer0.muse.R
import io.zer0.muse.asr.ASRStatus
import io.zer0.muse.data.agentbot.AgentBotUiState
import io.zer0.muse.ui.assistant.reasoningLevelLabelRes
import io.zer0.muse.ui.chat.LoadingDots
import io.zer0.muse.ui.chat.MentionHighlightTransformation
import io.zer0.muse.ui.chat.MuseToolSheet
import io.zer0.muse.ui.chat.RecordingWaveform
import io.zer0.muse.ui.chat.ToolEntry
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AttachmentRemoveButton
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseIsland
import io.zer0.muse.ui.theme.GlassIsland
import io.zer0.muse.ui.theme.LocalLiquidGlass
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.delay

/**
 * 底部独立悬浮药丸输入岛。
 *
 * 外层不铺背景底条，直接叠浮在全屏对话流之上；
 * 与常规对话输入岛统一材质、按钮节奏与加号工具抽屉，后台任务执行期间仍不锁定输入。
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
    onVoiceInput: () -> Unit = {},
    onOpenWorkbench: () -> Unit,
    onTriggerBriefing: () -> Unit,
    onStartTeach: () -> Unit,
    onOpenModel: (() -> Unit)?,
    onOpenProviderSettings: (() -> Unit)?,
    onOpenRuntimeInstall: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enterToSend: Boolean = false,
    showExpandButton: Boolean = false,
    isWebSearchEnabled: Boolean = false,
    isDeepThinkingEnabled: Boolean = false,
    deepThinkingLevel: ReasoningLevel = ReasoningLevel.HIGH,
    isRecording: Boolean = false,
    asrStatus: ASRStatus = ASRStatus.Idle,
    asrErrorMessage: String? = null,
    recordingAmplitudes: List<Float> = emptyList(),
    showMic: Boolean = true,
    onStartRecording: (() -> Boolean)? = null,
    onStopRecording: (() -> Unit)? = null,
    onCancelRecording: (() -> Unit)? = null,
    onClearQuote: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onToggleWebSearch: () -> Unit = {},
    onToggleDeepThinking: () -> Unit = {},
    onCycleDeepThinkingLevel: () -> Unit = {},
    onOpenKnowledgePicker: () -> Unit = {},
    onPreviewPendingImage: (Int) -> Unit = {},
) {
    var showToolSheet by rememberSaveable { mutableStateOf(false) }
    var showFullscreenEditor by rememberSaveable { mutableStateOf(false) }
    var showCancelledHint by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val glassConfig = LocalLiquidGlass.current
    val reducedMotion = MuseMotion.isReducedMotion()
    val canSend = composer.text.isNotBlank() || composer.pendingImages.isNotEmpty()
    val plusCd = stringResource(R.string.agent_hub_plus_cd)
    val voiceCd = stringResource(R.string.agent_hub_voice_cd)
    val sendCd = stringResource(R.string.action_send)
    val stopCd = stringResource(R.string.agent_bot_stop_reply)
    LaunchedEffect(showCancelledHint) {
        if (showCancelledHint) {
            delay(1_500L)
            showCancelledHint = false
        }
    }
    val mentionColor = MaterialTheme.colorScheme.primary
    val mentionTransform = remember(mentionColor) { MentionHighlightTransformation(mentionColor) }
    val islandBaseColor = lerp(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurface,
        0.06f,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
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

        val editingActive = !composer.editingMessageId.isNullOrBlank()
        if (editingActive) {
            MuseIsland(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onCancelEdit,
                    ),
                backgroundAlpha = 1f,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.bubbleInner),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.common_edit),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = MuseIcons.x,
                        contentDescription = stringResource(R.string.common_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }
            }
        } else {
            composer.quotedMessage?.let { quoted ->
                MuseIsland(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClearQuote,
                        ),
                    backgroundAlpha = 1f,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.bubbleInner),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(
                                    if (quoted.role == AgentBotUiState.Role.USER) {
                                        R.string.quote_label_user
                                    } else {
                                        R.string.quote_label_assistant
                                    },
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = io.zer0.muse.transformer.InternalMarkupSanitizer.displayContent(quoted.text),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        MuseTactileButton(
                            icon = MuseIcons.x,
                            contentDescription = stringResource(R.string.quote_clear),
                            onClick = onClearQuote,
                        )
                    }
                }
            }
        }

        if (composer.pendingImages.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                modifier = Modifier.padding(horizontal = MusePaddings.tightGap),
            ) {
                itemsIndexed(
                    items = composer.pendingImages,
                    key = { idx, image -> "$idx:${image.hashCode()}" },
                ) { index, image ->
                    Box {
                        AsyncImage(
                            model = "data:image/jpeg;base64,$image",
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(MusePaddings.previewThumb)
                                .clip(MuseShapes.medium)
                                .clickable { onPreviewPendingImage(index) },
                        )
                        AttachmentRemoveButton(
                            contentDescription = stringResource(R.string.agent_screen_remove_image),
                            onClick = { onRemoveImage(image) },
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                }
            }
        }

        GlassIsland(
            config = glassConfig,
            shape = MuseShapes.pill,
            solidColor = islandBaseColor,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val islandContentColor = LocalContentColor.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = MusePaddings.inputIslandInnerHorizontal,
                        vertical = MusePaddings.inputIslandRowVertical,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                MuseTactileButton(
                    icon = MuseIcons.plus,
                    contentDescription = plusCd,
                    tint = islandContentColor.copy(alpha = MuseAlpha.disabledContent),
                    onClick = {
                        MuseHaptics.light(hapticFeedback)
                        showToolSheet = true
                    },
                )

                MuseTextField(
                    value = composer.text,
                    onValueChange = onTextChange,
                    containerColor = Color.Transparent,
                    textColor = if (glassConfig.enabled) islandContentColor else null,
                    visualTransformation = mentionTransform,
                    maxLines = 5,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.chat_placeholder_simple),
                            color = islandContentColor.copy(alpha = MuseAlpha.mutedSurface),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (canSend) {
                                MuseHaptics.medium(hapticFeedback)
                                onSend()
                            }
                        },
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = MuseIconSizes.inputMinHeight, max = MusePaddings.maxMessageFieldHeight)
                        .onKeyEvent { event ->
                            if (enterToSend &&
                                event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                (event.nativeKeyEvent.metaState and KeyEvent.META_SHIFT_ON) == 0
                            ) {
                                if (canSend) onSend()
                                true
                            } else {
                                false
                            }
                        },
                )

                if (showExpandButton) {
                    MuseTactileButton(
                        icon = MuseIcons.maximize,
                        contentDescription = stringResource(R.string.chat_input_action_fullscreen),
                        tint = islandContentColor.copy(alpha = MuseAlpha.disabledContent),
                        onClick = { showFullscreenEditor = true },
                    )
                }

                // 右侧按钮与 Chat InputBar 同优先级：生成中无输入 → 48dp 停止；有文字/图片 → 发送；否则 → 语音
                when {
                    (busy.sending || isStreaming) && !canSend -> {
                        Box(
                            modifier = Modifier
                                .size(MuseIconSizes.touchTarget)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        MuseHaptics.medium(hapticFeedback)
                                        onStopReply()
                                    },
                                )
                                .semantics { contentDescription = stopCd },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(MuseIconSizes.stopButton)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = MuseIcons.stop,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                )
                            }
                        }
                    }
                    canSend -> {
                        Box(
                            modifier = Modifier
                                .size(MuseIconSizes.touchTarget)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        MuseHaptics.medium(hapticFeedback)
                                        onSend()
                                    },
                                )
                                .semantics { contentDescription = sendCd },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(MuseIconSizes.stopButton)
                                    .clip(CircleShape)
                                    .background(MuseActionColors.container),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = MuseIcons.send,
                                    contentDescription = null,
                                    tint = MuseActionColors.content,
                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                )
                            }
                        }
                    }
                    showMic && onStartRecording != null && onStopRecording != null && onCancelRecording != null -> {
                        val pulseScale by animateFloatAsState(
                            targetValue = if (isRecording && !reducedMotion) 1.25f else 1f,
                            animationSpec = if (isRecording && !reducedMotion) {
                                infiniteRepeatable(
                                    animation = MuseMotion.tween(MuseAnimation.LOOP_NORMAL_MS, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse,
                                )
                            } else {
                                MuseMotion.tween(MuseAnimation.TACTILE_MS)
                            },
                            label = "agentMicPulse",
                        )
                        val currentOnStart by rememberUpdatedState(onStartRecording)
                        val currentOnStop by rememberUpdatedState(onStopRecording)
                        val currentOnCancel by rememberUpdatedState(onCancelRecording)
                        val recognizingCd = stringResource(R.string.chat_recognizing_cd)
                        val recordingCd = stringResource(R.string.chat_recording_cd)
                        val holdToRecordCd = stringResource(R.string.chat_hold_to_record_cd)
                        Box(
                            modifier = Modifier
                                .size(MuseIconSizes.touchTarget)
                                .pointerInput(Unit) {
                                    val slideThresholdPx = MuseIconSizes.touchTarget.toPx()
                                    detectTapGestures(
                                        onPress = { offset ->
                                            val started = currentOnStart()
                                            if (!started) return@detectTapGestures
                                            MuseHaptics.medium(hapticFeedback)
                                            var cancelled = false
                                            awaitPointerEventScope {
                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    val change = event.changes.firstOrNull() ?: continue
                                                    if (offset.y - change.position.y > slideThresholdPx) {
                                                        cancelled = true
                                                        break
                                                    }
                                                    if (change.changedToUp()) break
                                                }
                                            }
                                            if (cancelled) {
                                                currentOnCancel()
                                                showCancelledHint = true
                                            } else {
                                                currentOnStop()
                                            }
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                asrStatus == ASRStatus.Stopping || asrStatus == ASRStatus.Reconnecting -> MuseSpinner(
                                    modifier = Modifier.semantics { contentDescription = recognizingCd },
                                    size = MuseIconSizes.iconMedium,
                                )
                                isRecording -> Icon(
                                    imageVector = MuseIcons.microphone,
                                    contentDescription = recordingCd,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .size(MuseIconSizes.iconMedium)
                                        .scale(pulseScale),
                                )
                                else -> Box(
                                    modifier = Modifier
                                        .size(MuseIconSizes.stopButton)
                                        .clip(CircleShape)
                                        .background(MuseActionColors.neutralContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = MuseIcons.microphone,
                                        contentDescription = holdToRecordCd,
                                        tint = MuseActionColors.mutedContent,
                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                    )
                                }
                            }
                        }
                    }
                    showMic -> {
                        MuseTactileButton(
                            icon = MuseIcons.microphone,
                            contentDescription = voiceCd,
                            tint = islandContentColor.copy(alpha = MuseAlpha.disabledContent),
                            onClick = {
                                MuseHaptics.light(hapticFeedback)
                                onVoiceInput()
                            },
                        )
                    }
                    else -> {
                        Box(
                            modifier = Modifier.size(MuseIconSizes.touchTarget),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(MuseIconSizes.stopButton)
                                    .clip(CircleShape)
                                    .background(MuseActionColors.neutralContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = MuseIcons.send,
                                    contentDescription = sendCd,
                                    tint = MuseActionColors.mutedContent,
                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 录音/识别/取消状态提示条（与普通对话 InputBar 一致）
        if (isRecording || asrStatus == ASRStatus.Stopping ||
            (asrStatus == ASRStatus.Error && !asrErrorMessage.isNullOrBlank()) || showCancelledHint
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MusePaddings.tightGap),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    asrStatus == ASRStatus.Stopping -> LoadingDots(text = stringResource(R.string.voice_recognizing))
                    asrStatus == ASRStatus.Reconnecting -> LoadingDots(text = stringResource(R.string.voice_reconnecting))
                    asrStatus == ASRStatus.Connecting -> LoadingDots(text = stringResource(R.string.voice_connecting))
                    asrStatus == ASRStatus.Error -> Text(
                        text = asrErrorMessage ?: stringResource(R.string.chat_recording_start_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    isRecording -> {
                        RecordingWaveform(amplitudes = recordingAmplitudes)
                        Spacer(modifier = Modifier.width(MusePaddings.contentGap))
                        Text(
                            text = stringResource(R.string.voice_release_to_recognize),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    showCancelledHint -> Text(
                        text = stringResource(R.string.chat_cancelled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showFullscreenEditor) {
        MuseDialog(
            onDismissRequest = { showFullscreenEditor = false },
            title = stringResource(R.string.chat_input_action_fullscreen),
            content = {
                MuseTextField(
                    value = composer.text,
                    onValueChange = onTextChange,
                    placeholder = { Text(stringResource(R.string.chat_placeholder_simple)) },
                    minLines = 6,
                    maxLines = 14,
                    visualTransformation = mentionTransform,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmText = if (canSend) stringResource(R.string.action_send) else stringResource(R.string.common_done),
            onConfirm = {
                showFullscreenEditor = false
                if (canSend) onSend()
            },
            dismissText = stringResource(R.string.common_close),
            onDismiss = { showFullscreenEditor = false },
        )
    }

    if (showToolSheet) {
        val workbenchTitle = stringResource(R.string.agent_hub_quick_workbench)
        val briefingTitle = stringResource(R.string.agent_hub_cue_trigger_briefing)
        val teachTitle = stringResource(R.string.agent_hub_quick_teach)
        val webSearchTitle = stringResource(R.string.chat_web_search_cd)
        val deepThinkingLabel = stringResource(R.string.chat_deep_thinking_cd)
        val deepThinkingTitle = if (isDeepThinkingEnabled) {
            "$deepThinkingLabel · ${stringResource(reasoningLevelLabelRes(deepThinkingLevel))}"
        } else {
            deepThinkingLabel
        }
        val knowledgeTitle = stringResource(R.string.chat_tool_knowledge)
        val knowledgeSubtitle = stringResource(R.string.chat_tool_knowledge_subtitle)
        val fullscreenTitle = stringResource(R.string.chat_input_action_fullscreen)
        val fullscreenSubtitle = stringResource(R.string.chat_input_action_fullscreen_subtitle)
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
                    icon = MuseIcons.globe,
                    title = webSearchTitle,
                    titleRes = R.string.chat_web_search_cd,
                    isActive = isWebSearchEnabled,
                    showArrow = false,
                    onClick = {
                        MuseHaptics.light(hapticFeedback)
                        onToggleWebSearch()
                    },
                ),
            )
            add(
                ToolEntry(
                    icon = MuseIcons.brain,
                    title = deepThinkingTitle,
                    titleRes = R.string.chat_deep_thinking_cd,
                    isActive = isDeepThinkingEnabled,
                    showArrow = false,
                    onClick = {
                        MuseHaptics.light(hapticFeedback)
                        onToggleDeepThinking()
                    },
                    onLongClick = {
                        MuseHaptics.medium(hapticFeedback)
                        onCycleDeepThinkingLevel()
                    },
                ),
            )
            add(
                ToolEntry(
                    icon = MuseIcons.book,
                    title = knowledgeTitle,
                    titleRes = R.string.chat_tool_knowledge,
                    subtitle = knowledgeSubtitle,
                    onClick = {
                        MuseHaptics.light(hapticFeedback)
                        showToolSheet = false
                        onOpenKnowledgePicker()
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
            add(
                ToolEntry(
                    icon = MuseIcons.maximize,
                    title = fullscreenTitle,
                    subtitle = fullscreenSubtitle,
                    onClick = {
                        MuseHaptics.light(hapticFeedback)
                        showToolSheet = false
                        showFullscreenEditor = true
                    },
                ),
            )
            if (onOpenModel != null) {
                add(
                    ToolEntry(
                        icon = MuseIcons.memoryChip,
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
                R.string.chat_web_search_cd,
            ),
        )
    }
}
