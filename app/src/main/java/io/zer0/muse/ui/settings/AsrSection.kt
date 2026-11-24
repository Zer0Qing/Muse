package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.asr.AsrConfig
import io.zer0.muse.asr.AsrProviderType
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.SpeechProviderResolver
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseSecretTextField
import io.zer0.muse.ui.common.form.MuseSelectionSheet
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.common.surface.actionItem
import io.zer0.muse.ui.common.surface.navigationItem
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.launch

/**
 * 语音识别（ASR）设置区块。
 *
 * 结构：
 *  1. 引擎与离线模型卡片 — 一眼看到当前引擎、点击底部面板切换，并附离线模型管理入口；
 *  2. 连接 — 跟随供应商、API Key / Base URL、统一保存动作、模型选择；
 *  3. 识别 — 采样率、语言/热词、VAD、标点/逆文本规范化；
 *  4. 文件转录 — 仅 DashScope 文件模式显示。
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod") // 单设置分区：字段按顺序组织更易读
internal fun AsrSection(
    asrConfig: AsrConfig,
    settings: SettingsRepository,
    onOpenProviderSettings: () -> Unit = {},
    onOpenLocalModel: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var enginePickerOpen by remember { mutableStateOf(false) }
    var providerPickerOpen by remember { mutableStateOf(false) }
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // 切换 Provider 时，若仍保留上一个 Provider 的默认模型，请求会带错模型名；
    // 只有用户明确改过自定义模型时才保留，否则切到目标 Provider 的默认模型。
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

    Text(
        text = stringResource(R.string.settings_asr_provider_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = MuseSpacing.xs),
    )

    // ---- 引擎卡片 ----
    CardGroup(modifier = Modifier.padding(top = MusePaddings.itemGap)) {
        item(
            key = "asr_engine",
            onClick = { enginePickerOpen = true },
            leadingContent = { EngineIconTile(asrConfig.provider, size = 44.dp) },
            headlineContent = { Text(asrProviderName(asrConfig.provider)) },
            supportingContent = { Text(asrProviderDesc(asrConfig.provider)) },
            trailingContent = {
                Text(
                    text = stringResource(R.string.settings_asr_engine_change),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )
    }

    // ---- 本地模型入口 ----
    // 不挂在引擎选中状态上：用户先看见「模型下载」才可能去下模型。
    LocalModelEntryRow(onClick = onOpenLocalModel)

    if (asrConfig.provider != AsrProviderType.SYSTEM && asrConfig.provider != AsrProviderType.LOCAL_SENSEVOICE) {
        // ---- 连接 ----
        var asrApiKey by remember(asrConfig.provider) { mutableStateOf(asrConfig.apiKey) }
        var asrBaseUrl by remember(asrConfig.provider) { mutableStateOf(asrConfig.baseUrl) }
        val unsetLabel = stringResource(R.string.settings_speech_follow_unset)
        val followProvider = providers.firstOrNull { it.id == asrConfig.providerId }
        val needsBaseUrl = asrConfig.provider == AsrProviderType.OPENAI_WHISPER ||
            asrConfig.provider == AsrProviderType.OPENAI_REALTIME ||
            asrConfig.provider == AsrProviderType.AGNES
        // DashScope 实时识别走内置 wss 端点，供应商地址不参与：该引擎下不提供跟随开关
        val followAvailable = asrConfig.provider != AsrProviderType.DASHSCOPE
        val followActive = followAvailable && asrConfig.followProvider
        val resolvedFollow = remember(asrConfig, providers) {
            SpeechProviderResolver.resolveAsr(asrConfig, providers)
        }
        val modelProvider = remember(providers, asrConfig.providerId) {
            SpeechModelPicker.candidateProvider(providers, asrConfig.providerId)
        }
        val currentModelId = if (SpeechModelPicker.asrFollowActive(asrConfig)) {
            asrConfig.modelId
        } else {
            asrConfig.model
        }

        CardGroup(
            title = { Text(stringResource(R.string.settings_asr_group_connection)) },
            modifier = Modifier.padding(top = MusePaddings.cardGap),
        ) {
            if (followAvailable) {
                switchItem(
                    key = "asr_follow_provider",
                    title = stringResource(R.string.settings_asr_follow_provider),
                    checked = asrConfig.followProvider,
                    onCheckedChange = { follow ->
                        scope.launch { settings.saveAsrConfig(asrConfig.copy(followProvider = follow)) }
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
            }

            if (followActive) {
                navigationItem(
                    key = "asr_follow_provider_picker",
                    title = stringResource(R.string.common_provider),
                    subtitle = followProvider?.displayName ?: unsetLabel,
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.server,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                    onClick = { providerPickerOpen = true },
                )
                if (resolvedFollow.isProviderFallback) {
                    content(key = "asr_follow_fallback") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MusePaddings.cardInner),
                        ) {
                            InlineError(
                                stringResource(
                                    R.string.settings_speech_follow_fallback_notice,
                                    stringResource(speechFollowFallbackReasonRes(resolvedFollow.fallbackReason)),
                                ),
                            )
                        }
                    }
                }
            }

            content(key = "asr_credentials") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                ) {
                    if (!followActive && asrConfig.apiKey.isBlank()) {
                        InlineError(stringResource(R.string.asr_missing_api_key_error))
                    }
                    MuseSecretTextField(
                        value = asrApiKey,
                        onValueChange = { asrApiKey = it },
                        label = stringResource(R.string.settings_asr_api_key),
                        enabled = !followActive,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (needsBaseUrl) {
                        MuseTextField(
                            value = asrBaseUrl,
                            onValueChange = { asrBaseUrl = it },
                            label = { Text(stringResource(R.string.settings_asr_base_url)) },
                            placeholder = { Text(asrConfig.defaultBaseUrl().ifBlank { "https://api.openai.com/v1" }) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !followActive,
                        )
                    }
                }
            }

            if (!followActive) {
                actionItem(
                    key = "asr_save_connection",
                    title = stringResource(R.string.settings_asr_save_connection),
                    onClick = {
                        scope.launch {
                            settings.saveAsrConfig(
                                asrConfig.copy(
                                    apiKey = asrApiKey.trim(),
                                    baseUrl = asrBaseUrl.trim(),
                                ),
                            )
                            MuseToast.show(context.getString(R.string.settings_asr_saved))
                        }
                    },
                )
            }

            content(key = "asr_model") {
                SpeechModelSection(
                    title = stringResource(R.string.settings_asr_model),
                    currentModelId = currentModelId,
                    provider = modelProvider,
                    emptyLabel = asrConfig.defaultModel(),
                    onPick = { modelId ->
                        scope.launch { settings.saveAsrConfig(SpeechModelPicker.applyToAsr(asrConfig, modelId)) }
                    },
                    onOpenProviders = onOpenProviderSettings,
                )
            }
        }

        // ---- 识别 ----
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

        CardGroup(
            title = { Text(stringResource(R.string.settings_asr_group_recognition)) },
            modifier = Modifier.padding(top = MusePaddings.cardGap),
        ) {
            item(
                key = "asr_sample_rate",
                leadingContent = {
                    Icon(
                        imageVector = MuseIcons.waveSine,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                },
                headlineContent = { Text(stringResource(R.string.settings_asr_sample_rate)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_asr_sample_rate_subtitle, asrConfig.sampleRate))
                },
            )

            content(key = "asr_recognition_fields") {
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
                }
            }

            actionItem(
                key = "asr_save_recognition",
                title = stringResource(R.string.settings_asr_save_recognition),
                onClick = {
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
                },
            )

            if (asrConfig.provider != AsrProviderType.OPENAI_REALTIME) {
                switchItem(
                    key = "asr_vad_toggle",
                    title = stringResource(R.string.settings_asr_vad),
                    checked = asrConfig.vadEnabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveAsrConfig(asrConfig.copy(vadEnabled = v)) }
                    },
                )
                if (asrConfig.vadEnabled) {
                    content(key = "asr_vad_fields") {
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
                        }
                    }
                    actionItem(
                        key = "asr_save_vad",
                        title = stringResource(R.string.settings_asr_save_vad),
                        onClick = {
                            scope.launch {
                                settings.saveAsrConfig(
                                    asrConfig.copy(
                                        vadThreshold = vadThreshold.trim().toFloatOrNull() ?: 0.05f,
                                        vadSilenceDurationMs = vadSilence.trim().toLongOrNull() ?: 1_500L,
                                    ),
                                )
                                MuseToast.show(context.getString(R.string.settings_asr_saved_vad))
                            }
                        },
                    )
                }
            } else {
                content(key = "asr_vad_realtime_note") {
                    Text(
                        text = stringResource(R.string.settings_asr_vad_realtime_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                    )
                }
            }

            if (asrConfig.provider == AsrProviderType.DASHSCOPE ||
                asrConfig.provider == AsrProviderType.DASHSCOPE_FILE
            ) {
                switchItem(
                    key = "asr_punctuation",
                    title = stringResource(R.string.settings_asr_punctuation),
                    checked = asrConfig.enablePunctuation,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveAsrConfig(asrConfig.copy(enablePunctuation = v)) }
                    },
                )
                switchItem(
                    key = "asr_itn",
                    title = stringResource(R.string.settings_asr_itn),
                    checked = asrConfig.enableInverseTextNormalization,
                    onCheckedChange = { v ->
                        scope.launch {
                            settings.saveAsrConfig(asrConfig.copy(enableInverseTextNormalization = v))
                        }
                    },
                )
            }
        }

        // ---- 文件转录（仅 DASHSCOPE_FILE）----
        if (asrConfig.provider == AsrProviderType.DASHSCOPE_FILE) {
            var fileUrl by remember(asrConfig.provider) { mutableStateOf(asrConfig.fileAudioUrl) }
            var pollInterval by remember(asrConfig.provider) {
                mutableStateOf(asrConfig.pollIntervalMs.toString())
            }
            var pollTimeout by remember(asrConfig.provider) {
                mutableStateOf(asrConfig.pollTimeoutMs.toString())
            }
            CardGroup(
                title = { Text(stringResource(R.string.settings_asr_group_transcription)) },
                modifier = Modifier.padding(top = MusePaddings.cardGap),
            ) {
                content(key = "asr_transcription_fields") {
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
                    }
                }
                actionItem(
                    key = "asr_save_transcription",
                    title = stringResource(R.string.settings_asr_save_transcription),
                    onClick = {
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
                    },
                )
            }
        }
    }

    // ---- 跟随供应商选择面板 ----
    if (providerPickerOpen) {
        MuseSelectionSheet(
            title = stringResource(R.string.common_provider),
            onDismissRequest = { providerPickerOpen = false },
        ) {
            providers.forEach { provider ->
                MuseListItem(
                    onClick = {
                        // 换供应商时清空模型选择，避免带着上一个供应商的模型发请求
                        scope.launch {
                            settings.saveAsrConfig(
                                asrConfig.copy(providerId = provider.id, modelId = ""),
                            )
                        }
                        providerPickerOpen = false
                    },
                    headlineContent = { Text(provider.displayName) },
                    trailingContent = if (provider.id == asrConfig.providerId) {
                        {
                            Icon(
                                imageVector = MuseIcons.check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(MuseIconSizes.iconSmall),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }

    // ---- 引擎选择面板 ----
    if (enginePickerOpen) {
        MuseBottomSheet(onDismissRequest = { enginePickerOpen = false }) {
            Text(
                text = stringResource(R.string.settings_asr_pick_title),
                style = MaterialTheme.typography.titleLarge,
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

/** 引擎选择面板中的单个 Provider 行：图标砖 + 名称 + 描述 + 选中勾。 */
@Composable
private fun EnginePickerRow(provider: AsrProviderType, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MuseShapes.large)
            .clickable(onClick = onClick)
            .padding(vertical = MuseSpacing.sm, horizontal = MuseSpacing.xs),
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
                imageVector = MuseIcons.check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
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
            .clip(RoundedCornerShape(MuseCornerRadius.SEMI_LARGE.dp))
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

@Composable
private fun asrProviderName(provider: AsrProviderType): String = when (provider) {
    AsrProviderType.SYSTEM -> stringResource(R.string.settings_asr_provider_system)
    AsrProviderType.DASHSCOPE -> "DashScope"
    AsrProviderType.STEP -> "Step"
    AsrProviderType.DASHSCOPE_FILE -> stringResource(R.string.settings_asr_provider_file)
    AsrProviderType.OPENAI_WHISPER -> stringResource(R.string.settings_asr_provider_openai_whisper)
    AsrProviderType.OPENAI_REALTIME -> stringResource(R.string.settings_asr_provider_openai_realtime)
    AsrProviderType.AGNES -> stringResource(R.string.settings_asr_provider_agnes)
    AsrProviderType.LOCAL_SENSEVOICE -> stringResource(R.string.settings_asr_provider_local_sensevoice)
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
    AsrProviderType.LOCAL_SENSEVOICE -> stringResource(R.string.settings_asr_desc_local_sensevoice)
}

private fun asrProviderIcon(provider: AsrProviderType): ImageVector = when (provider) {
    AsrProviderType.SYSTEM -> MuseIcons.deviceMobile
    AsrProviderType.DASHSCOPE -> MuseIcons.cloud
    AsrProviderType.STEP -> MuseIcons.bolt
    AsrProviderType.DASHSCOPE_FILE -> MuseIcons.file
    AsrProviderType.OPENAI_WHISPER -> MuseIcons.microphone
    AsrProviderType.OPENAI_REALTIME -> MuseIcons.waveSine
    AsrProviderType.AGNES -> MuseIcons.wind
    AsrProviderType.LOCAL_SENSEVOICE -> MuseIcons.deviceMobile
}

/** 表单内联错误提示条。 */
@Composable
private fun InlineError(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}
