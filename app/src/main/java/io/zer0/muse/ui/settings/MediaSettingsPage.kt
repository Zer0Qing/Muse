package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.MediaConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSegmentedRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.speech.TtsManager
import io.zer0.muse.ui.speech.VoiceInfo
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v0.32: 媒体设置页。
 *
 * 控制语音录制和 TTS 语音播报的参数:
 *  - 录制采样率/比特率
 *  - TTS 开关/语速/音高/语言
 *  - 音频输出方式(扬声器/听筒/蓝牙)
 */
@Composable
fun MediaSettingsPage(
    onBack: () -> Unit,
    /** P2-9: 打开语音克隆页(从「语音播报(TTS)」分组入口进入)。 */
    onOpenVoiceCloning: () -> Unit = {},
) {
    val settings: SettingsRepository = koinInject()
    val ttsManager: TtsManager = koinInject()
    val config by settings.mediaConfigFlow.collectAsStateWithLifecycle(initialValue = MediaConfig())
    val scope = rememberCoroutineScope()

    SettingsSubPageScaffold(title = stringResource(R.string.settings_media_page_title), onBack = onBack) {
        // ── 1. 语音录制 ──
        item { SectionLabel(stringResource(R.string.settings_media_recording_section)) }
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                SliderRow(
                    icon = TablerIcons.Adjustments,
                    title = stringResource(R.string.settings_media_sample_rate),
                    subtitle = stringResource(R.string.settings_media_sample_rate_subtitle),
                    value = config.recordingSampleRate.toFloat(),
                    range = 8000f..48000f,
                    steps = 4,
                    valueText = "${config.recordingSampleRate / 1000} kHz",
                    onValueChange = { v ->
                        scope.launch { settings.saveMediaConfig(config.copy(recordingSampleRate = v.toInt())) }
                    },
                )
                SettingsGroupDivider()
                SliderRow(
                    icon = TablerIcons.Adjustments,
                    title = stringResource(R.string.settings_media_bit_rate),
                    subtitle = stringResource(R.string.settings_media_bit_rate_subtitle),
                    value = config.recordingBitRate.toFloat(),
                    range = 64000f..320000f,
                    steps = 7,
                    valueText = "${config.recordingBitRate / 1000} kbps",
                    onValueChange = { v ->
                        scope.launch { settings.saveMediaConfig(config.copy(recordingBitRate = v.toInt())) }
                    },
                )
            }
        }

        // ── 2. 语音播报(TTS) ──
        item { SectionLabel(stringResource(R.string.settings_media_tts_section)) }
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                SettingsSwitchRow(
                    icon = TablerIcons.Microphone,
                    title = stringResource(R.string.settings_media_tts_enable),
                    subtitle = stringResource(R.string.settings_media_tts_enable_subtitle),
                    checked = config.ttsEnabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveMediaConfig(config.copy(ttsEnabled = v)) }
                    },
                )
                SettingsGroupDivider()
                SliderRow(
                    icon = TablerIcons.Microphone,
                    title = stringResource(R.string.settings_media_speech_rate),
                    subtitle = stringResource(R.string.settings_media_speech_rate_subtitle),
                    value = config.ttsSpeechRate,
                    range = 0.5f..2.0f,
                    steps = 14,
                    valueText = "%.1fx".format(config.ttsSpeechRate),
                    onValueChange = { v ->
                        scope.launch { settings.saveMediaConfig(config.copy(ttsSpeechRate = v)) }
                    },
                )
                SettingsGroupDivider()
                SliderRow(
                    icon = TablerIcons.Microphone,
                    title = stringResource(R.string.settings_media_pitch),
                    subtitle = stringResource(R.string.settings_media_pitch_subtitle),
                    value = config.ttsPitch,
                    range = 0.5f..2.0f,
                    steps = 14,
                    valueText = "%.1fx".format(config.ttsPitch),
                    onValueChange = { v ->
                        scope.launch { settings.saveMediaConfig(config.copy(ttsPitch = v)) }
                    },
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

        // ── 3. 云端 TTS 引擎 ──
        item { SectionLabel(stringResource(R.string.settings_media_cloud_tts_section)) }
        item {
            CloudTtsConfigSection(
                config = config,
                settings = settings,
                ttsManager = ttsManager,
            )
        }

        // ── v1.99(4.8): 云端 TTS 高级参数(仅云端引擎显示)──
        if (config.ttsEngine != "system" && hasAdvancedParams(config.ttsEngine)) {
            item { SectionLabel(stringResource(R.string.settings_media_tts_advanced_section)) }
            item {
                AdvancedTtsParamsSection(
                    config = config,
                    settings = settings,
                )
            }
        }

        // ── P2-9: 语音克隆入口(独立 SettingsGroup,从云端 TTS 引擎下方进入)──
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                SettingsItemRow(
                    icon = TablerIcons.Microphone,
                    title = stringResource(R.string.voice_cloning_title),
                    subtitle = stringResource(R.string.voice_cloning_new_voice),
                    onClick = onOpenVoiceCloning,
                ) {
                    ChevronRight()
                }
            }
        }

        // ── 4. 音频输出 ──
        item { SectionLabel(stringResource(R.string.settings_media_output_section)) }
        item {
            val outputOptions = listOf(
                stringResource(R.string.settings_media_output_speaker),
                stringResource(R.string.settings_media_output_earpiece),
                stringResource(R.string.settings_media_output_bluetooth),
            )
            val outputValues = listOf("speaker", "earpiece", "bluetooth")
            val selectedOutputIndex = outputValues.indexOf(config.audioOutput).coerceAtLeast(0)
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                SettingsSegmentedRow(
                    icon = TablerIcons.Volume,
                    title = stringResource(R.string.settings_media_output_method),
                    subtitle = stringResource(R.string.settings_media_output_method_subtitle),
                    options = outputOptions,
                    selectedIndex = selectedOutputIndex,
                    onSelectedChange = { idx ->
                        scope.launch { settings.saveMediaConfig(config.copy(audioOutput = outputValues[idx])) }
                    },
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: String,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            MuseSlider(
                value = value,
                onValueChange = { v -> onValueChange(v) },
                valueRange = range,
                steps = steps,
                modifier = Modifier.padding(top = 4.dp),
                showValueLabel = false,
            )
        }
    }
}

/**
 * TTS 声音选择器 — 下拉菜单列出系统可用的 TTS 声音。
 */
@Composable
private fun TtsVoiceSelector(
    ttsManager: TtsManager,
    currentVoice: String,
    onVoiceSelected: (String) -> Unit,
) {
    val voices = remember { ttsManager.getAvailableVoices() }
    if (voices.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val defaultLabel = stringResource(R.string.settings_media_tts_voice_default)
    val displayName = if (currentVoice.isNotBlank()) currentVoice else defaultLabel

    Box {
        SettingsItemRow(
            icon = TablerIcons.Microphone,
            title = stringResource(R.string.settings_media_tts_voice_selector),
            subtitle = displayName,
            onClick = { expanded = true },
        ) {
            ChevronRight()
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(defaultLabel) },
                onClick = {
                    onVoiceSelected("")
                    expanded = false
                },
            )
            voices.forEach { voice ->
                DropdownMenuItem(
                    text = { Text(voice.name) },
                    onClick = {
                        onVoiceSelected(voice.name)
                        expanded = false
                    },
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
 * 表单交互按 [AsrSection]:OutlinedTextField + 保存按钮 + Toast 反馈。
 *
 * v1.99(4.4): 在音色输入下方增加「拉取音色列表」按钮,调用 [TtsManager.listCloudVoices]
 * 动态拉取并展示可选音色,选中后写回 [MediaConfig.ttsVoice]。
 */
@Composable
private fun CloudTtsConfigSection(
    config: MediaConfig,
    settings: SettingsRepository,
    ttsManager: TtsManager,
) {
    val scope = rememberCoroutineScope()
    val systemLabel = stringResource(R.string.settings_media_tts_engine_system)
    val savedToast = stringResource(R.string.settings_media_tts_saved)
    val fetchingLabel = stringResource(R.string.settings_media_tts_voice_fetching)
    val fetchLabel = stringResource(R.string.settings_media_tts_voice_fetch)
    val fetchFailedLabel = stringResource(R.string.settings_media_tts_voice_fetch_failed)
    val pickLabel = stringResource(R.string.settings_media_tts_voice_pick)

    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        // 引擎选择(system + 11 家云端)
        var engineExpanded by remember { mutableStateOf(false) }
        // stringResource 必须在 @Composable 作用域直接调用,不能在 let/lambda 内嵌套
        val matchedResId = TtsManager.CLOUD_TTS_ENGINES
            .firstOrNull { it.first == config.ttsEngine }?.second
        val currentLabel = if (config.ttsEngine == "system" || matchedResId == null) systemLabel
        else stringResource(matchedResId)

        Box {
            SettingsItemRow(
                icon = TablerIcons.Cloud,
                title = stringResource(R.string.settings_media_tts_engine),
                subtitle = currentLabel,
                onClick = { engineExpanded = true },
            ) {
                ChevronRight()
            }
            DropdownMenu(
                expanded = engineExpanded,
                onDismissRequest = { engineExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(systemLabel) },
                    onClick = {
                        scope.launch { settings.saveMediaConfig(config.copy(ttsEngine = "system")) }
                        engineExpanded = false
                    },
                )
                TtsManager.CLOUD_TTS_ENGINES.forEach { (engineId, labelRes) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(labelRes)) },
                        onClick = {
                            scope.launch { settings.saveMediaConfig(config.copy(ttsEngine = engineId)) }
                            engineExpanded = false
                        },
                    )
                }
            }
        }

        // system 模式不显示表单
        if (config.ttsEngine == "system") return@SettingsGroup

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
                visualTransformation = if (apiKeyVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Text(stringResource(if (apiKeyVisible) R.string.settings_media_tts_show
                        else R.string.settings_media_tts_hide))
                    }
                },
            )
            TextButton(
                onClick = {
                    scope.launch {
                        settings.saveMediaConfig(config.copy(ttsApiKey = apiKey.trim()))
                        MuseToast.show(savedToast)
                    }
                },
            ) { Text(stringResource(R.string.settings_media_tts_save_api_key)) }
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
                TextButton(
                    onClick = {
                        scope.launch {
                            settings.saveMediaConfig(config.copy(ttsVoice = voice.trim()))
                            MuseToast.show(savedToast)
                        }
                    },
                ) { Text(stringResource(R.string.settings_media_tts_save_voice)) }
                TextButton(
                    enabled = !isFetching,
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
                ) {
                    Icon(
                        imageVector = TablerIcons.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (isFetching) fetchingLabel else fetchLabel,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            if (fetchError) {
                Text(
                    text = fetchFailedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (fetchedVoices.isNotEmpty()) {
                Box {
                    TextButton(onClick = { voicePickerExpanded = true }) {
                        Text(pickLabel)
                    }
                    DropdownMenu(
                        expanded = voicePickerExpanded,
                        onDismissRequest = { voicePickerExpanded = false },
                    ) {
                        fetchedVoices.forEach { v ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(v.name)
                                        v.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                            Text(
                                                text = desc,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.outline,
                                            )
                                        }
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

        // 模型
        SettingsGroupDivider()
        var model by remember(config.ttsEngine) { mutableStateOf(config.ttsModel) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
        ) {
            MuseTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text(stringResource(R.string.settings_media_tts_model)) },
                placeholder = { Text(stringResource(R.string.settings_media_tts_model_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            TextButton(
                onClick = {
                    scope.launch {
                        settings.saveMediaConfig(config.copy(ttsModel = model.trim()))
                        MuseToast.show(savedToast)
                    }
                },
            ) { Text(stringResource(R.string.settings_media_tts_save_model)) }
        }

        // 自定义 Endpoint
        SettingsGroupDivider()
        var endpoint by remember(config.ttsEngine) { mutableStateOf(config.ttsEndpoint) }
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
            )
            TextButton(
                onClick = {
                    scope.launch {
                        settings.saveMediaConfig(config.copy(ttsEndpoint = endpoint.trim()))
                        MuseToast.show(savedToast)
                    }
                },
            ) { Text(stringResource(R.string.settings_media_tts_save_endpoint)) }
        }
    }
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
    var formatExpanded by remember { mutableStateOf(false) }

    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        if (engine == "elevenlabs") {
            SliderRow(
                icon = TablerIcons.Adjustments,
                title = stringResource(R.string.settings_media_tts_stability),
                subtitle = stringResource(R.string.settings_media_tts_stability),
                value = stability,
                range = 0f..1f,
                steps = 9,
                valueText = "%.2f".format(stability),
                onValueChange = { stability = it },
            )
            SettingsGroupDivider()
            SliderRow(
                icon = TablerIcons.Adjustments,
                title = stringResource(R.string.settings_media_tts_similarity),
                subtitle = stringResource(R.string.settings_media_tts_similarity),
                value = similarity,
                range = 0f..1f,
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
            SliderRow(
                icon = TablerIcons.Adjustments,
                title = stringResource(R.string.settings_media_tts_cloud_speed),
                subtitle = stringResource(R.string.settings_media_tts_cloud_speed),
                value = speed,
                range = 0.25f..4.0f,
                steps = 14,
                valueText = "%.2fx".format(speed),
                onValueChange = { speed = it },
            )
            SettingsGroupDivider()
        }

        if (supportsResponseFormat(engine)) {
            Box {
                SettingsItemRow(
                    icon = TablerIcons.Adjustments,
                    title = stringResource(R.string.settings_media_tts_response_format),
                    subtitle = responseFormat,
                    onClick = { formatExpanded = true },
                ) {
                    ChevronRight()
                }
                DropdownMenu(
                    expanded = formatExpanded,
                    onDismissRequest = { formatExpanded = false },
                ) {
                    listOf("mp3", "opus", "aac", "flac", "wav").forEach { fmt ->
                        DropdownMenuItem(
                            text = { Text(fmt) },
                            onClick = {
                                responseFormat = fmt
                                formatExpanded = false
                            },
                        )
                    }
                }
            }
            SettingsGroupDivider()
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
        ) {
            TextButton(
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
            ) { Text(stringResource(R.string.settings_media_tts_save_advanced)) }
        }
    }
}

/**
 * 引擎是否支持语速参数(OpenAI 兼容 + MiniMax + Edge)。
 */
private fun supportsSpeed(engine: String): Boolean =
    engine in listOf("openai", "minimax", "dashscope", "groq", "step", "edge")

/**
 * 引擎是否支持音频格式选择(OpenAI 兼容 + Edge)。
 */
private fun supportsResponseFormat(engine: String): Boolean =
    engine in listOf("openai", "dashscope", "groq", "step", "edge")

/**
 * 引擎是否有可配置的高级参数(决定是否显示「高级参数」section)。
 */
private fun hasAdvancedParams(engine: String): Boolean =
    engine == "elevenlabs" || engine == "minimax" ||
        supportsSpeed(engine) || supportsResponseFormat(engine)
