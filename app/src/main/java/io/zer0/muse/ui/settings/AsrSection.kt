package io.zer0.muse.ui.settings

import io.zer0.muse.ui.common.MuseToast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.asr.AsrConfig
import io.zer0.muse.asr.AsrProviderType
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsGroupDivider
import io.zer0.muse.ui.common.SettingsItemRow
import io.zer0.muse.ui.common.SwitchRow
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch

/**
 * 阶段 7: 语音识别(ASR)section — iOS 风格分组列表。
 *
 * Provider 选择(系统 / DashScope / Step / 文件 / OpenAI Whisper / OpenAI Realtime / Agnes)
 * 用 FlowRow 胶囊分段控件放在分组外(数量多,FlowRow 自动换行),
 * API Key / baseUrl / 模型 / 采样率 / 语言 / 热词 / VAD 用 [SettingsGroup] 包裹(SYSTEM 模式隐藏)。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AsrSection(
    asrConfig: AsrConfig,
    settings: SettingsRepository,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    SectionLabel(stringResource(R.string.section_asr))
    Text(
        text = stringResource(R.string.settings_asr_provider_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 4.dp),
    )

    // Provider 选择(FlowRow 自动换行,7 个胶囊)
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemeModeOption(stringResource(R.string.settings_asr_provider_system), asrConfig.provider == AsrProviderType.SYSTEM) {
            scope.launch {
                settings.saveAsrConfig(asrConfig.copy(provider = AsrProviderType.SYSTEM))
            }
        }
        ThemeModeOption("DashScope", asrConfig.provider == AsrProviderType.DASHSCOPE) {
            scope.launch {
                settings.saveAsrConfig(
                    asrConfig.copy(
                        provider = AsrProviderType.DASHSCOPE,
                        model = asrConfig.model.ifBlank { asrConfig.defaultModel() },
                    )
                )
            }
        }
        ThemeModeOption("Step", asrConfig.provider == AsrProviderType.STEP) {
            scope.launch {
                settings.saveAsrConfig(
                    asrConfig.copy(
                        provider = AsrProviderType.STEP,
                        model = asrConfig.model.ifBlank { asrConfig.defaultModel() },
                    )
                )
            }
        }
        ThemeModeOption(stringResource(R.string.settings_asr_provider_file), asrConfig.provider == AsrProviderType.DASHSCOPE_FILE) {
            scope.launch {
                settings.saveAsrConfig(
                    asrConfig.copy(
                        provider = AsrProviderType.DASHSCOPE_FILE,
                        model = asrConfig.model.ifBlank { asrConfig.defaultModel() },
                    )
                )
            }
        }
        ThemeModeOption(stringResource(R.string.settings_asr_provider_openai_whisper), asrConfig.provider == AsrProviderType.OPENAI_WHISPER) {
            scope.launch {
                settings.saveAsrConfig(
                    asrConfig.copy(
                        provider = AsrProviderType.OPENAI_WHISPER,
                        model = asrConfig.model.ifBlank { asrConfig.defaultModel() },
                    )
                )
            }
        }
        ThemeModeOption(stringResource(R.string.settings_asr_provider_openai_realtime), asrConfig.provider == AsrProviderType.OPENAI_REALTIME) {
            scope.launch {
                settings.saveAsrConfig(
                    asrConfig.copy(
                        provider = AsrProviderType.OPENAI_REALTIME,
                        model = asrConfig.model.ifBlank { asrConfig.defaultModel() },
                    )
                )
            }
        }
        ThemeModeOption(stringResource(R.string.settings_asr_provider_agnes), asrConfig.provider == AsrProviderType.AGNES) {
            scope.launch {
                settings.saveAsrConfig(
                    asrConfig.copy(
                        provider = AsrProviderType.AGNES,
                        model = asrConfig.model.ifBlank { asrConfig.defaultModel() },
                    )
                )
            }
        }
    }

    // API Key / baseUrl / 模型 / 采样率 / 语言 / 热词 / VAD(SYSTEM 模式隐藏)
    if (asrConfig.provider != AsrProviderType.SYSTEM) {
        SettingsGroup(
            modifier = Modifier.padding(top = 8.dp),
        ) {
            // API Key
            var asrApiKey by remember(asrConfig.provider) { mutableStateOf(asrConfig.apiKey) }
            var asrApiKeyVisible by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
            ) {
                if (asrConfig.apiKey.isBlank()) {
                    InlineError(stringResource(R.string.asr_missing_api_key_error))
                }
                OutlinedTextField(
                    value = asrApiKey,
                    onValueChange = { asrApiKey = it },
                    label = { Text(stringResource(R.string.settings_asr_api_key)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (asrApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { asrApiKeyVisible = !asrApiKeyVisible }) {
                            Text(if (asrApiKeyVisible) stringResource(R.string.settings_asr_hide) else stringResource(R.string.settings_asr_show))
                        }
                    },
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            settings.saveAsrConfig(asrConfig.copy(apiKey = asrApiKey.trim()))
                            MuseToast.show(context.getString(R.string.settings_asr_saved_api_key))
                        }
                    },
                ) { Text(stringResource(R.string.settings_asr_save_api_key)) }
            }

            // baseUrl(仅 OPENAI_WHISPER / OPENAI_REALTIME / AGNES 显示,可自定义中转站)
            if (asrConfig.provider == AsrProviderType.OPENAI_WHISPER ||
                asrConfig.provider == AsrProviderType.OPENAI_REALTIME ||
                asrConfig.provider == AsrProviderType.AGNES
            ) {
                SettingsGroupDivider()
                var asrBaseUrl by remember(asrConfig.provider) { mutableStateOf(asrConfig.baseUrl) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                ) {
                    OutlinedTextField(
                        value = asrBaseUrl,
                        onValueChange = { asrBaseUrl = it },
                        label = { Text(stringResource(R.string.settings_asr_base_url)) },
                        placeholder = { Text(asrConfig.defaultBaseUrl().ifBlank { "https://api.openai.com/v1" }) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                settings.saveAsrConfig(asrConfig.copy(baseUrl = asrBaseUrl.trim()))
                                MuseToast.show(context.getString(R.string.settings_asr_saved_base_url))
                            }
                        },
                    ) { Text(stringResource(R.string.settings_asr_save_base_url)) }
                }
            }

            SettingsGroupDivider()

            // 模型名(可编辑,留空用默认)
            var asrModel by remember(asrConfig.provider) { mutableStateOf(asrConfig.model) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
            ) {
                OutlinedTextField(
                    value = asrModel,
                    onValueChange = { asrModel = it },
                    label = { Text(stringResource(R.string.settings_asr_model)) },
                    placeholder = { Text(asrConfig.defaultModel()) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            settings.saveAsrConfig(asrConfig.copy(model = asrModel.trim()))
                            MuseToast.show(context.getString(R.string.settings_asr_saved_model))
                        }
                    },
                ) { Text(stringResource(R.string.settings_asr_save_model)) }
            }

            SettingsGroupDivider()

            // 采样率(只读)
            SettingsItemRow(
                icon = Icons.Outlined.GraphicEq,
                title = stringResource(R.string.settings_asr_sample_rate),
                subtitle = stringResource(R.string.settings_asr_sample_rate_subtitle, asrConfig.sampleRate),
            )

            SettingsGroupDivider()

            // 语言
            var asrLang by remember(asrConfig.provider) {
                mutableStateOf(asrConfig.language ?: "zh")
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
            ) {
                OutlinedTextField(
                    value = asrLang,
                    onValueChange = { asrLang = it },
                    label = { Text(stringResource(R.string.settings_asr_language_code)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            settings.saveAsrConfig(asrConfig.copy(language = asrLang.trim().ifBlank { null }))
                            MuseToast.show(context.getString(R.string.settings_asr_saved_language))
                        }
                    },
                ) { Text(stringResource(R.string.settings_asr_save_language)) }
            }

            // 热词(除 SYSTEM 外均显示,用逗号分隔的输入框)
            SettingsGroupDivider()
            var asrHotwords by remember(asrConfig.provider) {
                mutableStateOf(asrConfig.hotwords.joinToString(", "))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
            ) {
                OutlinedTextField(
                    value = asrHotwords,
                    onValueChange = { asrHotwords = it },
                    label = { Text(stringResource(R.string.settings_asr_hotwords)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 1,
                    maxLines = 3,
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            val list = asrHotwords.split(",")
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                            settings.saveAsrConfig(asrConfig.copy(hotwords = list))
                            MuseToast.show(context.getString(R.string.settings_asr_saved_hotwords))
                        }
                    },
                ) { Text(stringResource(R.string.settings_asr_save_hotwords)) }
            }

            // VAD 配置(除 SYSTEM / OPENAI_REALTIME 外显示;OPENAI_REALTIME 走服务端 VAD)
            if (asrConfig.provider != AsrProviderType.OPENAI_REALTIME) {
                SettingsGroupDivider()
                SwitchRow(
                    label = stringResource(R.string.settings_asr_vad),
                    description = stringResource(R.string.settings_asr_vad_desc),
                    checked = asrConfig.vadEnabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveAsrConfig(asrConfig.copy(vadEnabled = v)) }
                    },
                )
                if (asrConfig.vadEnabled) {
                    SettingsGroupDivider()
                    var vadThreshold by remember(asrConfig.vadEnabled) {
                        mutableStateOf(asrConfig.vadThreshold.toString())
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                    ) {
                        OutlinedTextField(
                            value = vadThreshold,
                            onValueChange = { vadThreshold = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text(stringResource(R.string.settings_asr_vad_threshold)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val v = vadThreshold.trim().toFloatOrNull() ?: 0.05f
                                    settings.saveAsrConfig(asrConfig.copy(vadThreshold = v))
                                    MuseToast.show(context.getString(R.string.settings_asr_saved_vad))
                                }
                            },
                        ) { Text(stringResource(R.string.settings_asr_save_vad)) }
                    }

                    SettingsGroupDivider()
                    var vadSilence by remember(asrConfig.vadEnabled) {
                        mutableStateOf(asrConfig.vadSilenceDurationMs.toString())
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                    ) {
                        OutlinedTextField(
                            value = vadSilence,
                            onValueChange = { vadSilence = it.filter { c -> c.isDigit() } },
                            label = { Text(stringResource(R.string.settings_asr_vad_silence)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val ms = vadSilence.trim().toLongOrNull() ?: 1_500L
                                    settings.saveAsrConfig(asrConfig.copy(vadSilenceDurationMs = ms))
                                    MuseToast.show(context.getString(R.string.settings_asr_saved_vad))
                                }
                            },
                        ) { Text(stringResource(R.string.settings_asr_save_vad)) }
                    }
                }
            }

            // 阶段 F: DashScope 高级字段(仅 DASHSCOPE / DASHSCOPE_FILE 显示)
            if (asrConfig.provider == AsrProviderType.DASHSCOPE ||
                asrConfig.provider == AsrProviderType.DASHSCOPE_FILE
            ) {
                SettingsGroupDivider()
                SwitchRow(
                    label = stringResource(R.string.settings_asr_punctuation),
                    description = stringResource(R.string.settings_asr_punctuation_desc),
                    checked = asrConfig.enablePunctuation,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveAsrConfig(asrConfig.copy(enablePunctuation = v)) }
                    },
                )
                SettingsGroupDivider()
                SwitchRow(
                    label = stringResource(R.string.settings_asr_itn),
                    description = stringResource(R.string.settings_asr_itn_desc),
                    checked = asrConfig.enableInverseTextNormalization,
                    onCheckedChange = { v ->
                        scope.launch {
                            settings.saveAsrConfig(asrConfig.copy(enableInverseTextNormalization = v))
                        }
                    },
                )
            }

            // 阶段 F: DASHSCOPE_FILE 异步文件转录字段(仅 DASHSCOPE_FILE 显示)
            if (asrConfig.provider == AsrProviderType.DASHSCOPE_FILE) {
                SettingsGroupDivider()
                var fileUrl by remember(asrConfig.provider) {
                    mutableStateOf(asrConfig.fileAudioUrl)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                ) {
                    OutlinedTextField(
                        value = fileUrl,
                        onValueChange = { fileUrl = it },
                        label = { Text(stringResource(R.string.settings_asr_audio_url)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                settings.saveAsrConfig(asrConfig.copy(fileAudioUrl = fileUrl.trim()))
                                MuseToast.show(context.getString(R.string.settings_asr_saved_audio_url))
                            }
                        },
                    ) { Text(stringResource(R.string.settings_asr_save_audio_url)) }
                }

                SettingsGroupDivider()
                var pollInterval by remember(asrConfig.provider) {
                    mutableStateOf(asrConfig.pollIntervalMs.toString())
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                ) {
                    OutlinedTextField(
                        value = pollInterval,
                        onValueChange = { pollInterval = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.settings_asr_poll_interval)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                val ms = pollInterval.trim().toLongOrNull() ?: 3000L
                                settings.saveAsrConfig(asrConfig.copy(pollIntervalMs = ms))
                                MuseToast.show(context.getString(R.string.settings_asr_saved_poll_interval))
                            }
                        },
                    ) { Text(stringResource(R.string.settings_asr_save_poll_interval)) }
                }

                SettingsGroupDivider()
                var pollTimeout by remember(asrConfig.provider) {
                    mutableStateOf(asrConfig.pollTimeoutMs.toString())
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                ) {
                    OutlinedTextField(
                        value = pollTimeout,
                        onValueChange = { pollTimeout = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.settings_asr_poll_timeout)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                val ms = pollTimeout.trim().toLongOrNull() ?: 300_000L
                                settings.saveAsrConfig(asrConfig.copy(pollTimeoutMs = ms))
                                MuseToast.show(context.getString(R.string.settings_asr_saved_poll_timeout))
                            }
                        },
                    ) { Text(stringResource(R.string.settings_asr_save_poll_timeout)) }
                }
            }
        }
    }
}

/**
 * 表单内联错误提示条。
 */
@Composable
private fun InlineError(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}
