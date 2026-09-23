package io.zer0.muse.ui.settings

import io.zer0.muse.ui.common.feedback.MuseToast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.asr.AsrConfig
import io.zer0.muse.asr.AsrProviderType
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.launch

/**
 * v2.0 重设计: 语音识别(ASR)设置。
 *
 * 旧版把 7 个 Provider 铺成胶囊墙 + 每个字段一个"保存"按钮,信息密度低、主次不分。
 * 新版结构:
 *  1. 引擎卡片 — 一眼看到当前识别引擎与用途,点"更换"弹出底部选择面板;
 *  2. 连接 — API Key / baseUrl / 模型,一个"保存连接设置"统一提交;
 *  3. 识别 — 采样率 / 语言 / 热词 / VAD / 标点等,一个"保存识别设置"统一提交;
 *  4. 文件转录 — 仅 DashScope 文件模式显示,一个"保存转录设置"。
 */
@Composable
internal fun AsrSection(
    asrConfig: AsrConfig,
    settings: SettingsRepository,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var enginePickerOpen by remember { mutableStateOf(false) }

    // 切换 Provider 时,若仍保留上一个 Provider 的默认模型,请求会带错模型名;
    // 只有用户明确改过自定义模型时才保留,否则切到目标 Provider 的默认模型。
    fun configForProvider(provider: AsrProviderType): AsrConfig {
        val next = asrConfig.copy(provider = provider)
        val knownDefaults = AsrProviderType.values()
            .map { candidate -> AsrConfig(provider = candidate).defaultModel() }
            .filter { it.isNotBlank() }
            .toSet()
        val model = asrConfig.model
            .takeIf { it.isNotBlank() && it !in knownDefaults }
            ?: next.defaultModel()
        return next.copy(model = model)
    }

    fun switchProvider(provider: AsrProviderType) {
        scope.launch { settings.saveAsrConfig(configForProvider(provider)) }
    }

    SectionLabel(stringResource(R.string.section_asr))
    Text(
        text = stringResource(R.string.settings_asr_provider_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 4.dp),
    )

    // ---- 引擎卡片 ----
    Spacer(Modifier.size(MusePaddings.itemGap))
    Surface(
        onClick = { enginePickerOpen = true },
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            EngineIconTile(asrConfig.provider, size = 44.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_asr_engine_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = asrProviderName(asrConfig.provider),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = asrProviderDesc(asrConfig.provider),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MuseCapsuleButton(
                text = stringResource(R.string.settings_asr_engine_change),
                onClick = { enginePickerOpen = true },
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
            )
        }
    }

    if (asrConfig.provider != AsrProviderType.SYSTEM) {
        // ---- 连接 ----
        SectionLabel(stringResource(R.string.settings_asr_group_connection))
        SettingsGroup(modifier = Modifier.padding(top = 4.dp)) {
            var asrApiKey by remember(asrConfig.provider) { mutableStateOf(asrConfig.apiKey) }
            var asrApiKeyVisible by remember { mutableStateOf(false) }
            var asrBaseUrl by remember(asrConfig.provider) { mutableStateOf(asrConfig.baseUrl) }
            var asrModel by remember(asrConfig.provider) { mutableStateOf(asrConfig.model) }
            val needsBaseUrl = asrConfig.provider == AsrProviderType.OPENAI_WHISPER ||
                asrConfig.provider == AsrProviderType.OPENAI_REALTIME ||
                asrConfig.provider == AsrProviderType.AGNES

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                if (asrConfig.apiKey.isBlank()) {
                    InlineError(stringResource(R.string.asr_missing_api_key_error))
                }
                MuseTextField(
                    value = asrApiKey,
                    onValueChange = { asrApiKey = it },
                    label = { Text(stringResource(R.string.settings_asr_api_key)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (asrApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        MuseCapsuleButton(
                            text = if (asrApiKeyVisible) stringResource(R.string.settings_asr_hide) else stringResource(R.string.settings_asr_show),
                            onClick = { asrApiKeyVisible = !asrApiKeyVisible },
                            variant = IosCapsuleButtonVariant.Text,
                            fillWidth = false,
                        )
                    },
                )
                if (needsBaseUrl) {
                    MuseTextField(
                        value = asrBaseUrl,
                        onValueChange = { asrBaseUrl = it },
                        label = { Text(stringResource(R.string.settings_asr_base_url)) },
                        placeholder = { Text(asrConfig.defaultBaseUrl().ifBlank { "https://api.openai.com/v1" }) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                MuseTextField(
                    value = asrModel,
                    onValueChange = { asrModel = it },
                    label = { Text(stringResource(R.string.settings_asr_model)) },
                    placeholder = { Text(asrConfig.defaultModel()) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                SaveRow(stringResource(R.string.settings_asr_save_connection)) {
                    scope.launch {
                        settings.saveAsrConfig(
                            asrConfig.copy(
                                apiKey = asrApiKey.trim(),
                                baseUrl = asrBaseUrl.trim(),
                                model = asrModel.trim(),
                            ),
                        )
                        MuseToast.show(context.getString(R.string.settings_asr_saved))
                    }
                }
            }
        }

        // ---- 识别 ----
        SectionLabel(stringResource(R.string.settings_asr_group_recognition))
        SettingsGroup(modifier = Modifier.padding(top = 4.dp)) {
            SettingsItemRow(
                icon = TablerIcons.Adjustments,
                title = stringResource(R.string.settings_asr_sample_rate),
                subtitle = stringResource(R.string.settings_asr_sample_rate_subtitle, asrConfig.sampleRate),
            )
            SettingsGroupDivider()

            var asrLang by remember(asrConfig.provider) { mutableStateOf(asrConfig.language ?: "zh") }
            var asrHotwords by remember(asrConfig.provider) {
                mutableStateOf(asrConfig.hotwords.joinToString(", "))
            }
            var vadThreshold by remember(asrConfig.vadEnabled) {
                mutableStateOf(asrConfig.vadThreshold.toString())
            }
            var vadSilence by remember(asrConfig.vadEnabled) {
                mutableStateOf(asrConfig.vadSilenceDurationMs.toString())
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                MuseTextField(
                    value = asrLang,
                    onValueChange = { asrLang = it },
                    label = { Text(stringResource(R.string.settings_asr_language_code)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                MuseTextField(
                    value = asrHotwords,
                    onValueChange = { asrHotwords = it },
                    label = { Text(stringResource(R.string.settings_asr_hotwords)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 1,
                    maxLines = 3,
                )
                SaveRow(stringResource(R.string.settings_asr_save_recognition)) {
                    scope.launch {
                        val list = asrHotwords.split(",")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                        settings.saveAsrConfig(
                            asrConfig.copy(
                                language = asrLang.trim().ifBlank { null },
                                hotwords = list,
                                vadThreshold = vadThreshold.trim().toFloatOrNull() ?: asrConfig.vadThreshold,
                                vadSilenceDurationMs = vadSilence.trim().toLongOrNull()
                                    ?: asrConfig.vadSilenceDurationMs,
                            ),
                        )
                        MuseToast.show(context.getString(R.string.settings_asr_saved))
                    }
                }
            }

            // VAD(除 SYSTEM / OPENAI_REALTIME 外显示;OPENAI_REALTIME 走服务端 VAD)
            if (asrConfig.provider != AsrProviderType.OPENAI_REALTIME) {
                SettingsGroupDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_asr_vad),
                    subtitle = stringResource(R.string.settings_asr_vad_desc),
                    checked = asrConfig.vadEnabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveAsrConfig(asrConfig.copy(vadEnabled = v)) }
                    },
                )
                if (asrConfig.vadEnabled) {
                    SettingsGroupDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                    ) {
                        MuseTextField(
                            value = vadThreshold,
                            onValueChange = { v ->
                                vadThreshold = v.filter { c -> c.isDigit() || c == '.' }
                            },
                            label = { Text(stringResource(R.string.settings_asr_vad_threshold)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        MuseTextField(
                            value = vadSilence,
                            onValueChange = { v -> vadSilence = v.filter { c -> c.isDigit() } },
                            label = { Text(stringResource(R.string.settings_asr_vad_silence)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        SaveRow(stringResource(R.string.settings_asr_save_vad)) {
                            scope.launch {
                                settings.saveAsrConfig(
                                    asrConfig.copy(
                                        vadThreshold = vadThreshold.trim().toFloatOrNull() ?: 0.05f,
                                        vadSilenceDurationMs = vadSilence.trim().toLongOrNull() ?: 1_500L,
                                    ),
                                )
                                MuseToast.show(context.getString(R.string.settings_asr_saved_vad))
                            }
                        }
                    }
                }
            }

            // DashScope 高级字段(仅 DASHSCOPE / DASHSCOPE_FILE 显示)
            if (asrConfig.provider == AsrProviderType.DASHSCOPE ||
                asrConfig.provider == AsrProviderType.DASHSCOPE_FILE
            ) {
                SettingsGroupDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_asr_punctuation),
                    subtitle = stringResource(R.string.settings_asr_punctuation_desc),
                    checked = asrConfig.enablePunctuation,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveAsrConfig(asrConfig.copy(enablePunctuation = v)) }
                    },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_asr_itn),
                    subtitle = stringResource(R.string.settings_asr_itn_desc),
                    checked = asrConfig.enableInverseTextNormalization,
                    onCheckedChange = { v ->
                        scope.launch {
                            settings.saveAsrConfig(asrConfig.copy(enableInverseTextNormalization = v))
                        }
                    },
                )
            }
        }

        // ---- 文件转录(仅 DASHSCOPE_FILE) ----
        if (asrConfig.provider == AsrProviderType.DASHSCOPE_FILE) {
            SectionLabel(stringResource(R.string.settings_asr_group_transcription))
            SettingsGroup(modifier = Modifier.padding(top = 4.dp)) {
                var fileUrl by remember(asrConfig.provider) { mutableStateOf(asrConfig.fileAudioUrl) }
                var pollInterval by remember(asrConfig.provider) {
                    mutableStateOf(asrConfig.pollIntervalMs.toString())
                }
                var pollTimeout by remember(asrConfig.provider) {
                    mutableStateOf(asrConfig.pollTimeoutMs.toString())
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                ) {
                    MuseTextField(
                        value = fileUrl,
                        onValueChange = { fileUrl = it },
                        label = { Text(stringResource(R.string.settings_asr_audio_url)) },
                        placeholder = { Text("https://example.com/audio.mp3") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    MuseTextField(
                        value = pollInterval,
                        onValueChange = { v -> pollInterval = v.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.settings_asr_poll_interval)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    MuseTextField(
                        value = pollTimeout,
                        onValueChange = { v -> pollTimeout = v.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.settings_asr_poll_timeout)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    SaveRow(stringResource(R.string.settings_asr_save_transcription)) {
                        scope.launch {
                            settings.saveAsrConfig(
                                asrConfig.copy(
                                    fileAudioUrl = fileUrl.trim(),
                                    pollIntervalMs = pollInterval.trim().toLongOrNull()
                                        ?.coerceIn(500L, 60_000L)
                                        ?: 3_000L,
                                    pollTimeoutMs = pollTimeout.trim().toLongOrNull()
                                        ?.coerceIn(10_000L, 900_000L)
                                        ?: 300_000L,
                                ),
                            )
                            MuseToast.show(context.getString(R.string.settings_asr_saved))
                        }
                    }
                }
            }
        }
    }

    // ---- 引擎选择面板 ----
    if (enginePickerOpen) {
        MuseBottomSheet(onDismissRequest = { enginePickerOpen = false }) {
            Text(
                text = stringResource(R.string.settings_asr_pick_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = MusePaddings.itemGap),
            )
            AsrProviderType.values().forEach { provider ->
                EnginePickerRow(
                    provider = provider,
                    selected = asrConfig.provider == provider,
                    onClick = {
                        enginePickerOpen = false
                        if (asrConfig.provider != provider) switchProvider(provider)
                    },
                )
            }
        }
    }
}

/** 引擎选择面板中的单个 Provider 行:图标砖 + 名称 + 描述 + 选中勾。 */
@Composable
private fun EnginePickerRow(
    provider: AsrProviderType,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MuseShapes.large)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        EngineIconTile(provider, size = 40.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = asrProviderName(provider),
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = asrProviderDesc(provider),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                imageVector = TablerIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Provider 图标砖 — 主色浅底圆角方块 + 线性图标。 */
@Composable
private fun EngineIconTile(provider: AsrProviderType, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = asrProviderIcon(provider),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** 保存按钮行 — 右对齐,避免每个字段一个保存按钮占满纵向空间。 */
@Composable
private fun SaveRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        MuseCapsuleButton(
            text = text,
            onClick = onClick,
            variant = IosCapsuleButtonVariant.Primary,
            fillWidth = false,
        )
    }
}

@Composable
private fun asrProviderName(provider: AsrProviderType): String = when (provider) {
    AsrProviderType.SYSTEM -> stringResource(R.string.settings_asr_provider_system)
    AsrProviderType.DASHSCOPE -> "DashScope"
    AsrProviderType.STEP -> "Step"
    AsrProviderType.DASHSCOPE_FILE -> stringResource(R.string.settings_asr_provider_file)
    AsrProviderType.OPENAI_WHISPER -> stringResource(R.string.settings_asr_provider_openai_whisper)
    AsrProviderType.OPENAI_REALTIME -> stringResource(R.string.settings_asr_provider_openai_realtime)
    AsrProviderType.AGNES -> stringResource(R.string.settings_asr_provider_agnes)
}

@Composable
private fun asrProviderDesc(provider: AsrProviderType): String = when (provider) {
    AsrProviderType.SYSTEM -> stringResource(R.string.settings_asr_desc_system)
    AsrProviderType.DASHSCOPE -> stringResource(R.string.settings_asr_desc_dashscope)
    AsrProviderType.STEP -> stringResource(R.string.settings_asr_desc_step)
    AsrProviderType.DASHSCOPE_FILE -> stringResource(R.string.settings_asr_desc_file)
    AsrProviderType.OPENAI_WHISPER -> stringResource(R.string.settings_asr_desc_whisper)
    AsrProviderType.OPENAI_REALTIME -> stringResource(R.string.settings_asr_desc_realtime)
    AsrProviderType.AGNES -> stringResource(R.string.settings_asr_desc_agnes)
}

private fun asrProviderIcon(provider: AsrProviderType): ImageVector = when (provider) {
    AsrProviderType.SYSTEM -> TablerIcons.DeviceMobile
    AsrProviderType.DASHSCOPE -> TablerIcons.Cloud
    AsrProviderType.STEP -> TablerIcons.Bolt
    AsrProviderType.DASHSCOPE_FILE -> TablerIcons.FileMusic
    AsrProviderType.OPENAI_WHISPER -> TablerIcons.Microphone
    AsrProviderType.OPENAI_REALTIME -> TablerIcons.WaveSine
    AsrProviderType.AGNES -> TablerIcons.Wind
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
    )
}
