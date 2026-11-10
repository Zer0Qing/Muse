package io.zer0.muse.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
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
import io.zer0.muse.ui.common.form.MuseSelectionSheet
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSliderRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.speech.TtsEngineSwitch
import io.zer0.muse.ui.speech.TtsManager
import io.zer0.muse.ui.speech.VoiceInfo
import io.zer0.muse.ui.theme.MusePaddings
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
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { showDiscardConfirm = false },
            destructive = true,
        )
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_media_page_title), onBack = requestBack) {
        // ── 1. 语音播报(TTS) ──
        item { SectionLabel(stringResource(R.string.settings_media_tts_section)) }
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                // 本地草稿 + 400ms 防抖:此前每一步都直接写 DataStore,一次拖动十几次写,
                // 且两条滑块各持一份旧 config 互相覆盖。合并成一次写入两个字段。
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
                SettingsSliderRow(
                    icon = MuseIcons.microphone,
                    title = stringResource(R.string.settings_media_speech_rate),
                    subtitle = stringResource(R.string.settings_media_speech_rate_subtitle),
                    value = speechRateDraft,
                    valueRange = 0.5f..2.0f,
                    steps = 14,
                    valueText = "%.1fx".format(speechRateDraft),
                    onValueChange = { v -> speechRateDraft = v },
                )
                SettingsGroupDivider()
                SettingsSliderRow(
                    icon = MuseIcons.microphone,
                    title = stringResource(R.string.settings_media_pitch),
                    subtitle = stringResource(R.string.settings_media_pitch_subtitle),
                    value = pitchDraft,
                    valueRange = 0.5f..2.0f,
                    steps = 14,
                    valueText = "%.1fx".format(pitchDraft),
                    onValueChange = { v -> pitchDraft = v },
                )
                // TTS 声音选择
                SettingsGroupDivider()
                TtsVoiceSelector(
                    ttsManager = ttsManager,
                    currentVoice = config.ttsVoiceName,
                    onVoiceSelected = { voiceName ->
                        scope.launch { settings.saveMediaConfig(config.copy(ttsVoiceName = voiceName)) }
                    },
                )
            }
        }

        // ── 22③ / 2.6.24：语音回复频率（唯一设置处；约定页不再重复一份） ──
        globalVoiceFrequencySection()

        // ── 发言指南：只改注入给模型的写作约束，不做客户端后处理 ──
        speechGuideSection()

        // ── 2. 云端 TTS 引擎 ──
        item { SectionLabel(stringResource(R.string.settings_media_cloud_tts_section)) }
        item {
            CloudTtsConfigSection(
                config = config,
                settings = settings,
                ttsManager = ttsManager,
                onOpenProviderSettings = onOpenProviderSettings,
                reportDirty = reportDirty,
            )
        }

        // ── v1.99(4.8): 云端 TTS 高级参数(仅云端引擎显示)──
        if (config.ttsEngine != "system" && hasAdvancedParams(config.ttsEngine)) {
            item { SectionLabel(stringResource(R.string.settings_media_tts_advanced_section)) }
            item {
                AdvancedTtsParamsSection(
                    config = config,
                    settings = settings,
                    reportDirty = reportDirty,
                )
            }
        }

        // ── P2-9: 语音克隆入口(独立 SettingsGroup,从云端 TTS 引擎下方进入)──
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                SettingsItemRow(
                    icon = MuseIcons.microphone,
                    title = stringResource(R.string.voice_cloning_title),
                    subtitle = stringResource(R.string.voice_cloning_new_voice),
                    onClick = onOpenVoiceCloning,
                ) { ChevronRight() }
            }
        }

        // 输出设备跟随 Android 系统与当前音频焦点，不在 Muse 内重复抽象。
        // 08: 表情包库不再挂在语音/媒体页——它与语音播报无关，且专用入口
        // （设置→聊天→表情包库 / StickerSettingsRoute）一直都在，这里是重复残留。
    }
}

/**
 * TTS 声音选择器 — 下拉菜单列出系统可用的 TTS 声音。
 */
@Composable
private fun TtsVoiceSelector(ttsManager: TtsManager, currentVoice: String, onVoiceSelected: (String) -> Unit) {
    val voices = remember { ttsManager.getAvailableVoices() }

    var expanded by remember { mutableStateOf(false) }
    val defaultLabel = stringResource(R.string.settings_media_tts_voice_default)
    val displayName = if (currentVoice.isNotBlank()) currentVoice else defaultLabel

    Box {
        SettingsItemRow(
            icon = MuseIcons.microphone,
            title = stringResource(R.string.settings_media_tts_voice_selector),
            // MEM-08: 空列表不再消失 — 副标题给说明
            subtitle = if (voices.isEmpty()) {
                stringResource(R.string.settings_media_tts_voice_empty)
            } else {
                displayName
            },
            onClick = { if (voices.isNotEmpty()) expanded = true },
        ) {
            ChevronRight()
        }
        // MEM-08: 浏览型列表改 MuseSelectionSheet(DropdownMenu 不适合长列表)
        if (expanded) {
            MuseSelectionSheet(
                title = stringResource(R.string.settings_media_tts_voice_selector),
                onDismissRequest = { expanded = false },
            ) {
                MuseListItem(
                    headlineContent = { Text(defaultLabel) },
                    onClick = {
                        onVoiceSelected("")
                        expanded = false
                    },
                )
                voices.forEach { voice ->
                    MuseListItem(
                        headlineContent = { Text(voice.name) },
                        onClick = {
                            onVoiceSelected(voice.name)
                            expanded = false
                        },
                    )
                }
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

    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        // 引擎选择(system + 11 家云端)
        var engineExpanded by remember { mutableStateOf(false) }
        // stringResource 必须在 @Composable 作用域直接调用,不能在 let/lambda 内嵌套
        val matchedResId = TtsManager.CLOUD_TTS_ENGINES
            .firstOrNull { it.first == config.ttsEngine }?.second
        val currentLabel = if (config.ttsEngine == "system" || matchedResId == null) {
            systemLabel
        } else {
            stringResource(matchedResId)
        }

        Box {
            SettingsItemRow(
                icon = MuseIcons.cloud,
                title = stringResource(R.string.settings_media_tts_engine),
                subtitle = currentLabel,
                onClick = { engineExpanded = true },
            ) {
                ChevronRight()
            }
            MuseAnchoredMenu(
                expanded = engineExpanded,
                onDismissRequest = { engineExpanded = false },
            ) {
                // 切引擎同时校验音色 id：旧引擎的音色在新引擎下只会换来 4xx，不如清空重选
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

        // system 模式不显示表单
        if (config.ttsEngine == "system") return@SettingsGroup

        // 跟随供应商配置(开启后 endpoint / API Key 取自供应商,模型在这里手选)
        TtsFollowProviderSection(config = config, settings = settings)

        // API Key
        SettingsGroupDivider()
        var apiKey by remember(config.ttsEngine) { mutableStateOf(config.ttsApiKey) }
        var apiKeyVisible by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
        ) {
            MuseTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(stringResource(R.string.settings_media_tts_api_key)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                // 跟随供应商时凭据由供应商提供,手填值保留但不可编辑
                enabled = !config.ttsFollowProvider,
                visualTransformation = if (apiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    MuseCapsuleButton(
                        text = stringResource(
                            if (apiKeyVisible) {
                                R.string.settings_media_tts_show
                            } else {
                                R.string.settings_media_tts_hide
                            },
                        ),
                        onClick = { apiKeyVisible = !apiKeyVisible },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                },
            )
        }

        // 音色 / Voice ID(手动输入 + 动态拉取)
        SettingsGroupDivider()
        var voice by remember(config.ttsEngine) { mutableStateOf(config.ttsVoice) }
        var fetchedVoices by remember(config.ttsEngine) {
            mutableStateOf<List<VoiceInfo>>(emptyList())
        }
        var isFetching by remember(config.ttsEngine) { mutableStateOf(false) }
        var fetchError by remember(config.ttsEngine) { mutableStateOf(false) }
        var voicePickerExpanded by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
        ) {
            MuseTextField(
                value = voice,
                onValueChange = { voice = it },
                label = { Text(stringResource(R.string.settings_media_tts_voice)) },
                placeholder = { Text(stringResource(R.string.settings_media_tts_voice_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MuseCapsuleButton(
                    text = stringResource(R.string.settings_media_tts_save_voice),
                    onClick = {
                        scope.launch {
                            settings.saveMediaConfig(config.copy(ttsVoice = voice.trim()))
                            MuseToast.show(savedToast)
                        }
                    },
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
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
                    contentDescription = null,
                    enabled = !isFetching,
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
                // MEM-08: 浏览型列表改 MuseSelectionSheet(音色带说明,长列表可滚动)
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
        }

        // 模型：候选来自供应商，这里只负责选一个
        SettingsGroupDivider()
        val ttsProviders by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
        val modelProvider = remember(ttsProviders, config.ttsProviderId) {
            SpeechModelPicker.candidateProvider(ttsProviders, config.ttsProviderId)
        }
        // 跟随供应商时模型存在 ttsModelId，手动模式存在 ttsModel
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

        // 自定义 Endpoint
        SettingsGroupDivider()
        var endpoint by remember(config.ttsEngine) { mutableStateOf(config.ttsEndpoint) }
        LaunchedEffect(apiKey, endpoint, voice, config) {
            reportDirty(
                "connection",
                apiKey.trim() != config.ttsApiKey || endpoint.trim() != config.ttsEndpoint,
            )
            reportDirty("voice", voice.trim() != config.ttsVoice)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
        ) {
            MuseTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text(stringResource(R.string.settings_media_tts_endpoint)) },
                placeholder = { Text(stringResource(R.string.settings_media_tts_endpoint_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                // 跟随供应商时接口地址由供应商提供,手填值保留但不可编辑
                enabled = !config.ttsFollowProvider,
            )
            // 连接参数一次提交:此前 Key 与接口地址各挂一个"保存",同一页两种心智。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                MuseCapsuleButton(
                    text = stringResource(R.string.settings_media_tts_save_connection),
                    onClick = {
                        scope.launch {
                            settings.saveMediaConfig(
                                config.copy(
                                    ttsApiKey = apiKey.trim(),
                                    ttsEndpoint = endpoint.trim(),
                                ),
                            )
                            MuseToast.show(savedToast)
                        }
                    },
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                    enabled = !config.ttsFollowProvider,
                )
            }
        }
    }
}

/** 响应格式选择行 — 只在引擎支持时出现;展开态归自己管,页面无需替它记。 */
@Composable
private fun ResponseFormatRow(current: String, onPicked: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsItemRow(
            icon = MuseIcons.template,
            title = stringResource(R.string.settings_media_tts_response_format),
            subtitle = current,
            onClick = { expanded = true },
        ) {
            ChevronRight()
        }
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

/**
 * 云端 TTS「跟随供应商配置」区块。
 *
 * 开启后 endpoint / API Key 取自所选供应商;模型在下方「模型」行选（跟随模式写 ttsModelId）,
 * 手填的值原样保留(只是变灰不可编辑),关掉开关立即恢复手动配置。
 * 供应商被删除/停用或未选模型时,合成回退手动配置,这里同步给出提示。
 */
@Composable
private fun TtsFollowProviderSection(config: MediaConfig, settings: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val unsetLabel = stringResource(R.string.settings_speech_follow_unset)

    SettingsGroupDivider()
    SettingsSwitchRow(
        icon = MuseIcons.cloud,
        title = stringResource(R.string.settings_media_tts_follow_provider),
        subtitle = stringResource(R.string.settings_media_tts_follow_provider_subtitle),
        checked = config.ttsFollowProvider,
        onCheckedChange = { follow ->
            scope.launch { settings.saveMediaConfig(config.copy(ttsFollowProvider = follow)) }
        },
    )

    if (config.ttsFollowProvider) {
        val selectedProvider = providers.firstOrNull { it.id == config.ttsProviderId }
        var providerExpanded by remember { mutableStateOf(false) }

        SettingsGroupDivider()
        Box {
            SettingsItemRow(
                icon = MuseIcons.server,
                title = stringResource(R.string.settings_media_tts_follow_provider_picker),
                subtitle = selectedProvider?.displayName ?: unsetLabel,
                onClick = { providerExpanded = true },
            ) {
                ChevronRight()
            }
            MuseAnchoredMenu(
                expanded = providerExpanded,
                onDismissRequest = { providerExpanded = false },
            ) {
                providers.forEach { provider ->
                    MuseListItem(
                        headlineContent = { Text(provider.displayName) },
                        onClick = {
                            // 换供应商时清空模型选择,避免带着上一个供应商的模型发请求
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

        // 模型选择不在这里重复：下方「模型」行按跟随开关自动写到 ttsModelId / ttsModel

        // 音色不跟随供应商:提醒用户自行确认兼容性,避免误以为整份凭据都已接管
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
        ) {
            Text(
                text = stringResource(R.string.settings_media_tts_follow_voice_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val resolved = SpeechProviderResolver.resolveMedia(config, providers)
        if (resolved.isProviderFallback) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
            ) {
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

/** 回退原因的文案资源(未知原因按「供应商未找到」处理,不在 UI 上暴露内部字符串)。 */
internal fun speechFollowFallbackReasonRes(reason: String?): Int = when (reason) {
    SpeechProviderResolver.REASON_PROVIDER_DISABLED -> R.string.settings_speech_follow_reason_provider_disabled
    SpeechProviderResolver.REASON_MODEL_MISSING -> R.string.settings_speech_follow_reason_model_missing
    else -> R.string.settings_speech_follow_reason_provider_missing
}

/**
 * v1.99(4.8): 云端 TTS 高级参数配置区。
 *
 * 按引擎能力条件渲染:
 *  - ElevenLabs: stability / similarity_boost 滑块
 *  - MiniMax: 情感文本输入 + 语速滑块
 *  - OpenAI 兼容(openai/dashscope/groq/step/edge): 语速滑块 + 音频格式
 *  - 其他: 仅语速滑块(若支持)
 */
@Composable
private fun AdvancedTtsParamsSection(
    config: MediaConfig,
    settings: SettingsRepository,
    reportDirty: (String, Boolean) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    val savedToast = stringResource(R.string.settings_media_tts_saved)
    val engine = config.ttsEngine

    // 本地编辑态(随引擎切换重置)
    var stability by remember(engine) { mutableStateOf(config.ttsStability) }
    var similarity by remember(engine) { mutableStateOf(config.ttsSimilarityBoost) }
    var emotion by remember(engine) { mutableStateOf(config.ttsEmotion) }
    var speed by remember(engine) { mutableStateOf(config.ttsCloudSpeed) }
    var responseFormat by remember(engine) { mutableStateOf(config.ttsResponseFormat.ifBlank { "mp3" }) }
    LaunchedEffect(stability, similarity, emotion, speed, responseFormat, config) {
        reportDirty("advanced", advancedTtsDraftDirty(config, stability, similarity, emotion, speed, responseFormat))
    }

    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        if (engine == "elevenlabs") {
            SettingsSliderRow(
                icon = MuseIcons.shield,
                title = stringResource(R.string.settings_media_tts_stability),
                value = stability,
                valueRange = 0f..1f,
                steps = 9,
                valueText = "%.2f".format(stability),
                onValueChange = { stability = it },
            )
            SettingsGroupDivider()
            SettingsSliderRow(
                icon = MuseIcons.waveSine,
                title = stringResource(R.string.settings_media_tts_similarity),
                value = similarity,
                valueRange = 0f..1f,
                steps = 9,
                valueText = "%.2f".format(similarity),
                onValueChange = { similarity = it },
            )
            SettingsGroupDivider()
        }

        if (engine == "minimax") {
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
            SettingsGroupDivider()
        }

        if (supportsSpeed(engine)) {
            SettingsSliderRow(
                icon = MuseIcons.play,
                title = stringResource(R.string.settings_media_tts_cloud_speed),
                value = speed,
                valueRange = 0.25f..4.0f,
                steps = 14,
                valueText = "%.2fx".format(speed),
                onValueChange = { speed = it },
            )
            SettingsGroupDivider()
        }

        if (supportsResponseFormat(engine)) {
            ResponseFormatRow(current = responseFormat, onPicked = { responseFormat = it })
            SettingsGroupDivider()
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
        ) {
            MuseCapsuleButton(
                text = stringResource(R.string.settings_media_tts_save_advanced),
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
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
            )
        }
    }
}

/**
 * 引擎是否支持语速参数(OpenAI 兼容 + MiniMax + Edge)。
 */
private fun supportsSpeed(engine: String): Boolean = engine in listOf("openai", "minimax", "dashscope", "groq", "step", "edge")

/**
 * 引擎是否支持音频格式选择(OpenAI 兼容 + Edge)。
 */
private fun supportsResponseFormat(engine: String): Boolean = engine in listOf("openai", "dashscope", "groq", "step", "edge")

/**
 * 引擎是否有可配置的高级参数(决定是否显示「高级参数」section)。
 */
private fun hasAdvancedParams(engine: String): Boolean = engine == "elevenlabs" || engine == "minimax" ||
    supportsSpeed(engine) || supportsResponseFormat(engine)

/**
 * 22③：语音回复频率的「全局默认档」。
 *
 * 抽成 LazyListScope 扩展而不是内联在 [MediaSettingsPage]，是为了不把已经很长的页面再撑过
 * 单函数长度门禁（127→回落到阈值内）；档位只改注入给模型的约定文案，不做客户端硬拦截（见 VoiceReplyPolicy）。
 */
/**
 * 发言指南：让回复正文像聊天里说话。
 *
 * 与语音频率同一套做法 —— 只改注入给模型的约定文案，不做客户端硬改：
 * 客户端分不清「正文」与「内省/工具记录」，硬改会把不该动的部分一起改坏。
 */
private fun LazyListScope.speechGuideSection() {
    item { SectionLabel(stringResource(R.string.speech_guide_section)) }
    item {
        val context = LocalContext.current
        val store = remember { SpeechGuideStore.of(context) }
        val current by store.snapshot.collectAsStateWithLifecycle(initialValue = SpeechGuideStyle.OFF)
        val scope = rememberCoroutineScope()
        SettingsGroup(modifier = Modifier.padding(top = 8.dp)) {
            Column(Modifier.padding(MusePaddings.cardInner)) {
                Text(
                    text = stringResource(R.string.speech_guide_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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

private fun LazyListScope.globalVoiceFrequencySection() {
    item { SectionLabel(stringResource(R.string.voice_reply_global_section)) }
    item {
        val context = LocalContext.current
        val voiceStore = remember { VoiceReplyPolicyStore.of(context) }
        val snapshot by voiceStore.snapshot.collectAsStateWithLifecycle(initialValue = emptyMap())
        val scope = rememberCoroutineScope()
        GlobalVoiceFrequencySection(
            current = VoiceReplyFrequency.fromName(snapshot[VoiceReplyPolicyStore.GLOBAL_KEY]),
            onSelect = { level -> scope.launch { voiceStore.setGlobal(level) } },
        )
    }
}

@Composable
private fun GlobalVoiceFrequencySection(current: VoiceReplyFrequency, onSelect: (VoiceReplyFrequency) -> Unit) {
    val labels = mapOf(
        VoiceReplyFrequency.NEVER to stringResource(R.string.voice_reply_never),
        VoiceReplyFrequency.RARELY to stringResource(R.string.voice_reply_rarely),
        VoiceReplyFrequency.OFTEN to stringResource(R.string.voice_reply_often),
        VoiceReplyFrequency.ALWAYS to stringResource(R.string.voice_reply_always),
    )
    SettingsGroup(modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.padding(MusePaddings.cardInner)) {
            Text(
                text = stringResource(R.string.voice_reply_global_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 四档 chip 窄屏一行放不下会压成竖排，用 FlowRow。
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
}
