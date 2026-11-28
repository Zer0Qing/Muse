package io.zer0.muse.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.MediaConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.SpeechProviderResolver
import io.zer0.muse.data.assistant.SpeechGuideStore
import io.zer0.muse.data.assistant.SpeechGuideStyle
import io.zer0.muse.data.assistant.VoiceReplyFrequency
import io.zer0.muse.data.assistant.VoiceReplyPolicyStore
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSecretTextField
import io.zer0.muse.ui.common.form.MuseSelectionSheet
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SettingsSliderRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.common.surface.actionItem
import io.zer0.muse.ui.common.surface.navigationItem
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.speech.TtsEngineSwitch
import io.zer0.muse.ui.speech.TtsManager
import io.zer0.muse.ui.speech.VoiceInfo
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v0.32: 媒体设置页。
 *
 * 控制语音录制和 TTS 语音播报的参数:
 *  - 录制采样率/比特率
 *  - TTS 开关/语速/音高/语言
 *  - 系统默认音频路由
 */
@Composable
fun MediaSettingsPage(
    onBack: () -> Unit,
    /** P2-9: 打开语音克隆页(从「语音播报(TTS)」分组入口进入)。 */
    onOpenVoiceCloning: () -> Unit = {},
    /** 模型候选为空时的出口：供应商统一管模型，得回到那页去加。 */
    onOpenProviderSettings: () -> Unit = {},
) {
    val settings: SettingsRepository = koinInject()
    val ttsManager: TtsManager = koinInject()
    val config by settings.mediaConfigFlow.collectAsStateWithLifecycle(initialValue = MediaConfig())
    val scope = rememberCoroutineScope()

    // 页内草稿分散在几组字段里(Key/接口地址/音色/高级参数),填完直接返回等于白填。
    // 与供应商编辑页同一保护:任一组有未保存草稿,离开前先确认。各组把脏标记上报到这里。
    var dirtyGroups by remember { mutableStateOf(emptySet<String>()) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val reportDirty: (String, Boolean) -> Unit = { group, isDirty ->
        dirtyGroups = if (isDirty) dirtyGroups + group else dirtyGroups - group
    }
    val requestBack: () -> Unit = {
        if (dirtyGroups.isNotEmpty()) showDiscardConfirm = true else onBack()
    }
    BackHandler(enabled = dirtyGroups.isNotEmpty()) { showDiscardConfirm = true }
    if (showDiscardConfirm) {
        MuseDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = stringResource(R.string.settings_unsaved_changes_title),
            content = { Text(stringResource(R.string.settings_unsaved_changes_content)) },
            confirmText = stringResource(R.string.settings_unsaved_discard),
            onConfirm = {
                showDiscardConfirm = false
                onBack()
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showDiscardConfirm = false },
            destructive = true,
        )
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_media_page_title), onBack = requestBack) {
        // ── 1. 语音播报(TTS) ──
        item(key = "media_tts_core") {
            var speechRateDraft by remember { mutableStateOf(config.ttsSpeechRate) }
            var pitchDraft by remember { mutableStateOf(config.ttsPitch) }
            LaunchedEffect(config.ttsSpeechRate) {
                if (config.ttsSpeechRate != speechRateDraft) speechRateDraft = config.ttsSpeechRate
            }
            LaunchedEffect(config.ttsPitch) {
                if (config.ttsPitch != pitchDraft) pitchDraft = config.ttsPitch
            }
            LaunchedEffect(speechRateDraft, pitchDraft) {
                if (speechRateDraft != config.ttsSpeechRate || pitchDraft != config.ttsPitch) {
                    delay(400)
                    settings.saveMediaConfig(
                        config.copy(ttsSpeechRate = speechRateDraft, ttsPitch = pitchDraft),
                    )
                }
            }
            val voices = remember { ttsManager.getAvailableVoices() }
            var voiceExpanded by remember { mutableStateOf(false) }
            val defaultVoiceLabel = stringResource(R.string.settings_media_tts_voice_default)
            val voiceDisplay = if (voices.isEmpty()) {
                stringResource(R.string.settings_media_tts_voice_empty)
            } else if (config.ttsVoiceName.isNotBlank()) {
                config.ttsVoiceName
            } else {
                defaultVoiceLabel
            }

            CardGroup(title = { Text(stringResource(R.string.settings_media_tts_section)) }) {
                content(key = "tts_rate") {
                    SettingsSliderRow(
                        icon = MuseIcons.microphone,
                        title = stringResource(R.string.settings_media_speech_rate),
                        value = speechRateDraft,
                        valueRange = 0.5f..2.0f,
                        steps = 14,
                        valueText = "%.1fx".format(speechRateDraft),
                        onValueChange = { speechRateDraft = it },
                    )
                }
                content(key = "tts_pitch") {
                    SettingsSliderRow(
                        icon = MuseIcons.microphone,
                        title = stringResource(R.string.settings_media_pitch),
                        value = pitchDraft,
                        valueRange = 0.5f..2.0f,
                        steps = 14,
                        valueText = "%.1fx".format(pitchDraft),
                        onValueChange = { pitchDraft = it },
                    )
                }
                navigationItem(
                    key = "tts_system_voice",
                    title = stringResource(R.string.settings_media_tts_voice_selector),
                    subtitle = voiceDisplay,
                    leadingContent = {
                        Icon(
                            MuseIcons.microphone,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                    onClick = { if (voices.isNotEmpty()) voiceExpanded = true },
                )
            }
            if (voiceExpanded) {
                MuseSelectionSheet(
                    title = stringResource(R.string.settings_media_tts_voice_selector),
                    onDismissRequest = { voiceExpanded = false },
                ) {
                    MuseListItem(
                        headlineContent = { Text(defaultVoiceLabel) },
                        onClick = {
                            scope.launch { settings.saveMediaConfig(config.copy(ttsVoiceName = "")) }
                            voiceExpanded = false
                        },
                    )
                    voices.forEach { voice ->
                        MuseListItem(
                            headlineContent = { Text(voice.name) },
                            onClick = {
                                scope.launch { settings.saveMediaConfig(config.copy(ttsVoiceName = voice.name)) }
                                voiceExpanded = false
                            },
                        )
                    }
                }
            }
        }

        globalVoiceFrequencySection()

        speechGuideSection()

        // ── 2. 云端 TTS 引擎 ──
        item(key = "media_cloud_tts") {
            CloudTtsConfigSection(
                config = config,
                settings = settings,
                ttsManager = ttsManager,
                onOpenProviderSettings = onOpenProviderSettings,
                reportDirty = reportDirty,
            )
        }

        // ── 云端 TTS 高级参数（仅云端引擎显示）──
        if (config.ttsEngine != "system" && hasAdvancedParams(config.ttsEngine)) {
            item(key = "media_cloud_tts_advanced") {
                AdvancedTtsParamsSection(
                    config = config,
                    settings = settings,
                    reportDirty = reportDirty,
                )
            }
        }

        // ── 语音克隆入口 ──
        item(key = "media_voice_cloning") {
            CardGroup {
                navigationItem(
                    key = "voice_cloning_entry",
                    title = stringResource(R.string.voice_cloning_title),
                    leadingContent = {
                        Icon(
                            MuseIcons.microphone,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                    onClick = onOpenVoiceCloning,
                )
            }
        }
    }
}

/**
 * v1.97: 云端 TTS 引擎配置区。
 *
 * 引擎选择(system + 11 家云端 Provider)→ API Key / Voice / Model / Endpoint 表单。
 * system 模式仅显示引擎选择,隐藏表单。
 * 表单交互按 [AsrSection] 的分组提交:一组字段一个"保存",保存后 Toast 反馈。
 *
 * v1.99(4.4): 在音色输入下方增加「拉取音色列表」按钮,调用 [TtsManager.listCloudVoices]
 * 动态拉取并展示可选音色,选中后写回 [MediaConfig.ttsVoice]。
 */
@Composable
@Suppress("LongMethod") // 引擎/跟进/凭据/音色/模型/接口地址同属一块，拆开反而要反复跳转
private fun CloudTtsConfigSection(
    config: MediaConfig,
    settings: SettingsRepository,
    ttsManager: TtsManager,
    onOpenProviderSettings: () -> Unit,
    reportDirty: (String, Boolean) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    val systemLabel = stringResource(R.string.settings_media_tts_engine_system)
    val savedToast = stringResource(R.string.settings_media_tts_saved)
    val fetchFailedLabel = stringResource(R.string.settings_media_tts_voice_fetch_failed)
    val voiceResetLabel = stringResource(R.string.settings_media_tts_voice_reset)
    val pickLabel = stringResource(R.string.settings_media_tts_voice_pick)

    var engineExpanded by remember { mutableStateOf(false) }
    val matchedResId = TtsManager.CLOUD_TTS_ENGINES.firstOrNull { it.first == config.ttsEngine }?.second
    val currentLabel = if (config.ttsEngine == "system" || matchedResId == null) {
        systemLabel
    } else {
        stringResource(matchedResId)
    }
    val ttsProviders by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val unsetLabel = stringResource(R.string.settings_speech_follow_unset)
    var providerExpanded by remember { mutableStateOf(false) }

    var apiKey by remember(config.ttsEngine) { mutableStateOf(config.ttsApiKey) }
    var voice by remember(config.ttsEngine) { mutableStateOf(config.ttsVoice) }
    var endpoint by remember(config.ttsEngine) { mutableStateOf(config.ttsEndpoint) }
    var fetchedVoices by remember(config.ttsEngine) { mutableStateOf<List<VoiceInfo>>(emptyList()) }
    var isFetching by remember(config.ttsEngine) { mutableStateOf(false) }
    var fetchError by remember(config.ttsEngine) { mutableStateOf(false) }
    var voicePickerExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(apiKey, endpoint, voice, config) {
        val isCloud = config.ttsEngine != "system"
        reportDirty(
            "connection",
            isCloud && (apiKey.trim() != config.ttsApiKey || endpoint.trim() != config.ttsEndpoint),
        )
        reportDirty("voice", isCloud && voice.trim() != config.ttsVoice)
    }

    CardGroup(title = { Text(stringResource(R.string.settings_media_cloud_tts_section)) }) {
        content(key = "cloud_tts_engine") {
            Box {
                MuseListItem(
                    onClick = { engineExpanded = true },
                    leadingContent = {
                        Icon(
                            MuseIcons.cloud,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.settings_media_tts_engine)) },
                    supportingContent = { Text(currentLabel) },
                    trailingContent = { io.zer0.muse.ui.common.settings.ChevronRight() },
                )
                MuseAnchoredMenu(
                    expanded = engineExpanded,
                    onDismissRequest = { engineExpanded = false },
                ) {
                    val switchEngine: (String) -> Unit = { engineId ->
                        val switched = TtsEngineSwitch.apply(config, engineId)
                        scope.launch { settings.saveMediaConfig(switched.config) }
                        engineExpanded = false
                        if (switched.voiceReset) MuseToast.show(voiceResetLabel, 2500)
                    }
                    MuseListItem(
                        onClick = { switchEngine("system") },
                        headlineContent = { Text(systemLabel) },
                    )
                    TtsManager.CLOUD_TTS_ENGINES.forEach { (engineId, labelRes) ->
                        MuseListItem(
                            onClick = { switchEngine(engineId) },
                            headlineContent = { Text(stringResource(labelRes)) },
                        )
                    }
                }
            }
        }

        if (config.ttsEngine != "system") {
            switchItem(
                key = "tts_follow_provider",
                title = stringResource(R.string.settings_media_tts_follow_provider),
                checked = config.ttsFollowProvider,
                onCheckedChange = { follow ->
                    scope.launch { settings.saveMediaConfig(config.copy(ttsFollowProvider = follow)) }
                },
                leadingContent = {
                    Icon(
                        MuseIcons.cloud,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                },
            )
            if (config.ttsFollowProvider) {
                val selectedProvider = ttsProviders.firstOrNull { it.id == config.ttsProviderId }
                content(key = "tts_follow_provider_picker") {
                    Box {
                        MuseListItem(
                            onClick = { providerExpanded = true },
                            leadingContent = {
                                Icon(
                                    MuseIcons.server,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.common_provider)) },
                            supportingContent = { Text(selectedProvider?.displayName ?: unsetLabel) },
                            trailingContent = { io.zer0.muse.ui.common.settings.ChevronRight() },
                        )
                        MuseAnchoredMenu(
                            expanded = providerExpanded,
                            onDismissRequest = { providerExpanded = false },
                        ) {
                            ttsProviders.forEach { provider ->
                                MuseListItem(
                                    headlineContent = { Text(provider.displayName) },
                                    onClick = {
                                        scope.launch {
                                            settings.saveMediaConfig(
                                                config.copy(ttsProviderId = provider.id, ttsModelId = ""),
                                            )
                                        }
                                        providerExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                val resolved = SpeechProviderResolver.resolveMedia(config, ttsProviders)
                content(key = "tts_follow_hints") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_media_tts_follow_voice_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (resolved.isProviderFallback) {
                            Text(
                                text = stringResource(
                                    R.string.settings_speech_follow_fallback_notice,
                                    stringResource(speechFollowFallbackReasonRes(resolved.fallbackReason)),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            content(key = "cloud_tts_form") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                ) {
                    MuseSecretTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = stringResource(R.string.settings_media_tts_api_key),
                        enabled = !config.ttsFollowProvider,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MuseTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = { Text(stringResource(R.string.settings_media_tts_endpoint)) },
                        placeholder = { Text(stringResource(R.string.settings_media_tts_endpoint_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !config.ttsFollowProvider,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        MuseTextField(
                            value = voice,
                            onValueChange = { voice = it },
                            label = { Text(stringResource(R.string.settings_media_tts_voice)) },
                            placeholder = { Text(stringResource(R.string.settings_media_tts_voice_placeholder)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        MuseTactileButton(
                            icon = MuseIcons.refresh,
                            onClick = {
                                scope.launch {
                                    isFetching = true
                                    fetchError = false
                                    val result = ttsManager.listCloudVoices()
                                    if (result.isEmpty()) {
                                        fetchError = true
                                    } else {
                                        fetchedVoices = result
                                    }
                                    isFetching = false
                                }
                            },
                            contentDescription = pickLabel,
                            enabled = !isFetching,
                            compact = true,
                        )
                    }
                    if (fetchError) {
                        Text(
                            text = fetchFailedLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (fetchedVoices.isNotEmpty()) {
                        MuseCapsuleButton(
                            text = pickLabel,
                            onClick = { voicePickerExpanded = true },
                            variant = IosCapsuleButtonVariant.Text,
                            fillWidth = false,
                        )
                    }
                }
            }

            content(key = "cloud_tts_model") {
                val modelProvider = remember(ttsProviders, config.ttsProviderId) {
                    SpeechModelPicker.candidateProvider(ttsProviders, config.ttsProviderId)
                }
                val currentModelId = if (config.ttsFollowProvider) config.ttsModelId else config.ttsModel
                SpeechModelSection(
                    title = stringResource(R.string.settings_media_tts_model),
                    currentModelId = currentModelId,
                    provider = modelProvider,
                    emptyLabel = stringResource(R.string.settings_media_tts_model_placeholder),
                    onPick = { modelId ->
                        scope.launch { settings.saveMediaConfig(SpeechModelPicker.applyToTts(config, modelId)) }
                    },
                    onOpenProviders = onOpenProviderSettings,
                )
            }

            actionItem(
                key = "cloud_tts_save_all",
                title = stringResource(R.string.settings_media_tts_save_connection),
                onClick = {
                    scope.launch {
                        settings.saveMediaConfig(
                            config.copy(
                                ttsApiKey = if (config.ttsFollowProvider) config.ttsApiKey else apiKey.trim(),
                                ttsEndpoint = if (config.ttsFollowProvider) config.ttsEndpoint else endpoint.trim(),
                                ttsVoice = voice.trim(),
                            ),
                        )
                        MuseToast.show(savedToast)
                    }
                },
            )
        }
    }

    if (voicePickerExpanded) {
        MuseSelectionSheet(
            title = pickLabel,
            onDismissRequest = { voicePickerExpanded = false },
        ) {
            fetchedVoices.forEach { v ->
                MuseListItem(
                    headlineContent = { Text(v.name) },
                    supportingContent = v.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        {
                            Text(
                                desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        voice = v.id
                        scope.launch {
                            settings.saveMediaConfig(config.copy(ttsVoice = v.id))
                            MuseToast.show(savedToast)
                        }
                        voicePickerExpanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ResponseFormatRow(current: String, onPicked: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        MuseListItem(
            onClick = { expanded = true },
            leadingContent = {
                Icon(
                    MuseIcons.template,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
            headlineContent = { Text(stringResource(R.string.settings_media_tts_response_format)) },
            supportingContent = { Text(current) },
            trailingContent = { io.zer0.muse.ui.common.settings.ChevronRight() },
        )
        MuseAnchoredMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            listOf("mp3", "opus", "aac", "flac", "wav").forEach { fmt ->
                MuseListItem(
                    onClick = {
                        onPicked(fmt)
                        expanded = false
                    },
                    headlineContent = { Text(fmt) },
                )
            }
        }
    }
}

/** 高级参数草稿是否偏离已保存值:构造"草稿态 config"再整体比对,与供应商编辑页同一口径。 */
internal fun advancedTtsDraftDirty(
    config: MediaConfig,
    stability: Float,
    similarity: Float,
    emotion: String,
    speed: Float,
    responseFormat: String,
): Boolean = config.copy(
    ttsStability = stability,
    ttsSimilarityBoost = similarity,
    ttsEmotion = emotion.trim(),
    ttsCloudSpeed = speed,
    ttsResponseFormat = responseFormat,
) != config

/** 回退原因的文案资源(未知原因按「供应商未找到」处理,不在 UI 上暴露内部字符串)。 */
internal fun speechFollowFallbackReasonRes(reason: String?): Int = when (reason) {
    SpeechProviderResolver.REASON_PROVIDER_DISABLED -> R.string.settings_speech_follow_reason_provider_disabled
    SpeechProviderResolver.REASON_MODEL_MISSING -> R.string.settings_speech_follow_reason_model_missing
    else -> R.string.settings_speech_follow_reason_provider_missing
}

@Composable
private fun AdvancedTtsParamsSection(
    config: MediaConfig,
    settings: SettingsRepository,
    reportDirty: (String, Boolean) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    val savedToast = stringResource(R.string.settings_media_tts_saved)
    val engine = config.ttsEngine

    var stability by remember(engine) { mutableStateOf(config.ttsStability) }
    var similarity by remember(engine) { mutableStateOf(config.ttsSimilarityBoost) }
    var emotion by remember(engine) { mutableStateOf(config.ttsEmotion) }
    var speed by remember(engine) { mutableStateOf(config.ttsCloudSpeed) }
    var responseFormat by remember(engine) { mutableStateOf(config.ttsResponseFormat.ifBlank { "mp3" }) }
    LaunchedEffect(stability, similarity, emotion, speed, responseFormat, config) {
        reportDirty("advanced", advancedTtsDraftDirty(config, stability, similarity, emotion, speed, responseFormat))
    }

    CardGroup(title = { Text(stringResource(R.string.settings_media_tts_advanced_section)) }) {
        if (engine == "elevenlabs") {
            content(key = "tts_stability") {
                SettingsSliderRow(
                    icon = MuseIcons.shield,
                    title = stringResource(R.string.settings_media_tts_stability),
                    value = stability,
                    valueRange = 0f..1f,
                    steps = 9,
                    valueText = "%.2f".format(stability),
                    onValueChange = { stability = it },
                )
            }
            content(key = "tts_similarity") {
                SettingsSliderRow(
                    icon = MuseIcons.waveSine,
                    title = stringResource(R.string.settings_media_tts_similarity),
                    value = similarity,
                    valueRange = 0f..1f,
                    steps = 9,
                    valueText = "%.2f".format(similarity),
                    onValueChange = { similarity = it },
                )
            }
        }

        if (engine == "minimax") {
            content(key = "tts_emotion") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                ) {
                    MuseTextField(
                        value = emotion,
                        onValueChange = { emotion = it },
                        label = { Text(stringResource(R.string.settings_media_tts_emotion)) },
                        placeholder = { Text(stringResource(R.string.settings_media_tts_emotion_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
        }

        if (supportsSpeed(engine)) {
            content(key = "tts_cloud_speed") {
                SettingsSliderRow(
                    icon = MuseIcons.play,
                    title = stringResource(R.string.settings_media_tts_cloud_speed),
                    value = speed,
                    valueRange = 0.25f..4.0f,
                    steps = 14,
                    valueText = "%.2fx".format(speed),
                    onValueChange = { speed = it },
                )
            }
        }

        if (supportsResponseFormat(engine)) {
            content(key = "tts_response_format") {
                ResponseFormatRow(current = responseFormat, onPicked = { responseFormat = it })
            }
        }

        actionItem(
            key = "tts_save_advanced",
            title = stringResource(R.string.settings_media_tts_save_advanced),
            onClick = {
                scope.launch {
                    settings.saveMediaConfig(
                        config.copy(
                            ttsStability = stability,
                            ttsSimilarityBoost = similarity,
                            ttsEmotion = emotion.trim(),
                            ttsCloudSpeed = speed,
                            ttsResponseFormat = responseFormat,
                        ),
                    )
                    MuseToast.show(savedToast)
                }
            },
        )
    }
}

private fun supportsSpeed(engine: String): Boolean = engine in listOf("openai", "minimax", "dashscope", "groq", "step", "edge")

private fun supportsResponseFormat(engine: String): Boolean = engine in listOf("openai", "dashscope", "groq", "step", "edge")

private fun hasAdvancedParams(engine: String): Boolean = engine == "elevenlabs" || engine == "minimax" ||
    supportsSpeed(engine) || supportsResponseFormat(engine)

private fun LazyListScope.speechGuideSection() {
    item(key = "media_speech_guide") {
        val context = LocalContext.current
        val store = remember { SpeechGuideStore.of(context) }
        val current by store.snapshot.collectAsStateWithLifecycle(initialValue = SpeechGuideStyle.OFF)
        val scope = rememberCoroutineScope()
        CardGroup(title = { Text(stringResource(R.string.speech_guide_section)) }) {
            content(key = "speech_guide_body") {
                Column(Modifier.padding(MusePaddings.cardInner)) {
                    Text(
                        text = stringResource(R.string.speech_guide_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MuseSpacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        SpeechGuideStyle.entries.forEach { style ->
                            MuseChip(
                                label = stringResource(
                                    when (style) {
                                        SpeechGuideStyle.OFF -> R.string.speech_guide_off
                                        SpeechGuideStyle.CONVERSATIONAL -> R.string.speech_guide_conversational
                                    },
                                ),
                                selected = style == current,
                                onClick = { if (style != current) scope.launch { store.set(style) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.globalVoiceFrequencySection() {
    item(key = "media_voice_frequency") {
        val context = LocalContext.current
        val voiceStore = remember { VoiceReplyPolicyStore.of(context) }
        val snapshot by voiceStore.snapshot.collectAsStateWithLifecycle(initialValue = emptyMap())
        val autoBarSnapshot by voiceStore.autoVoiceBarSnapshot.collectAsStateWithLifecycle(initialValue = emptyMap())
        val scope = rememberCoroutineScope()
        GlobalVoiceFrequencySection(
            current = VoiceReplyFrequency.fromName(snapshot[VoiceReplyPolicyStore.GLOBAL_KEY]),
            autoVoiceBar = autoBarSnapshot[VoiceReplyPolicyStore.GLOBAL_KEY] == true,
            onSelect = { level -> scope.launch { voiceStore.setGlobal(level) } },
            onToggleAutoVoiceBar = { enabled -> scope.launch { voiceStore.setGlobalAutoVoiceBar(enabled) } },
        )
    }
}

@Composable
private fun GlobalVoiceFrequencySection(
    current: VoiceReplyFrequency,
    autoVoiceBar: Boolean,
    onSelect: (VoiceReplyFrequency) -> Unit,
    onToggleAutoVoiceBar: (Boolean) -> Unit,
) {
    val labels = mapOf(
        VoiceReplyFrequency.NEVER to stringResource(R.string.voice_reply_never),
        VoiceReplyFrequency.RARELY to stringResource(R.string.voice_reply_rarely),
        VoiceReplyFrequency.OFTEN to stringResource(R.string.voice_reply_often),
        VoiceReplyFrequency.ALWAYS to stringResource(R.string.voice_reply_always),
    )
    CardGroup(title = { Text(stringResource(R.string.voice_reply_global_section)) }) {
        content(key = "voice_freq_body") {
            Column(Modifier.padding(MusePaddings.cardInner)) {
                Text(
                    text = stringResource(R.string.voice_reply_global_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MuseSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    VoiceReplyFrequency.entries.forEach { level ->
                        MuseChip(
                            label = labels.getValue(level),
                            selected = level == current,
                            onClick = { if (level != current) onSelect(level) },
                        )
                    }
                }
            }
        }
        content(key = "voice_auto_bar_toggle") {
            SettingsSwitchRow(
                icon = MuseIcons.microphone,
                title = stringResource(R.string.voice_reply_auto_bar_title),
                subtitle = stringResource(R.string.voice_reply_auto_bar_desc),
                checked = autoVoiceBar,
                onCheckedChange = onToggleAutoVoiceBar,
            )
        }
    }
}
