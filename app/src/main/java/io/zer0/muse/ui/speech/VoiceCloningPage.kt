package io.zer0.muse.ui.speech

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.R
import io.zer0.muse.data.MediaConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * P2-9: 语音克隆页 — 全屏工具页。
 *
 * 布局:
 *  - MuseTopBar:返回 + 标题「语音克隆」
 *  - Provider 选择(FilterChip,当前仅 elevenlabs,后续可扩展)
 *  - API Key 输入框(OutlinedTextField + PasswordVisualTransformation)
 *  - 「克隆新语音」表单:语音名称输入 + 选择样本音频按钮 + 提交按钮(Surface + clickable)
 *  - 已克隆语音列表(LazyColumn):每行显示 name + voiceId + 删除按钮
 *
 * 设计令牌:[MuseShapes] / [MusePaddings],不使用 Material3 默认 Button / AlertDialog
 * (提交按钮用 Surface+clickable,删除确认弹窗用 [MuseDialog])。
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
fun VoiceCloningPage(
    onBack: () -> Unit,
    voiceCloningService: VoiceCloningService = koinInject(),
    elevenLabsProvider: ElevenLabsVoiceCloningProvider = koinInject(),
    fishProvider: FishAudioVoiceCloningProvider = koinInject(),
    settings: SettingsRepository = koinInject(),
    /** v2.6.7 fix (AUDIT F21): 试听用。 */
    ttsManager: TtsManager = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 表单状态(rememberSaveable 保证旋转/配置变更后保留;Uri 用 remember 避免序列化兼容问题)──
    var selectedProvider by rememberSaveable { mutableStateOf("elevenlabs") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }
    var voiceName by rememberSaveable { mutableStateOf("") }
    var selectedAudioUri by remember { mutableStateOf<Uri?>(null) }
    var selectedAudioLabel by rememberSaveable { mutableStateOf("") }

    // ── 操作状态 ──
    var isCloning by rememberSaveable { mutableStateOf(false) }
    var isLoadingList by rememberSaveable { mutableStateOf(false) }
    var isDeleting by rememberSaveable { mutableStateOf(false) }
    val voices = remember { mutableStateListOf<ClonedVoice>() }

    // 删除确认弹窗状态(不使用 Material3 AlertDialog,使用 MuseDialog)
    var voiceToDelete by remember { mutableStateOf<ClonedVoice?>(null) }

    // ── 音频文件选择器(SAF OpenDocument,选 audio/* 类型)──
    val pickAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            selectedAudioUri = uri
            // 显示文件名(从 Uri 取 lastPathSegment 作为可读标识)
            selectedAudioLabel = uri.lastPathSegment?.substringAfterLast('/')
                ?: uri.toString()
        }
    }

    // ── 预提取 stringResource(非 Composable lambda 内不能直接调用)──
    val strSuccess = stringResource(R.string.voice_cloning_success)
    val strKeySaved = stringResource(R.string.voice_cloning_key_saved)
    val strKeyFromProvider = stringResource(R.string.voice_cloning_key_from_provider)
    val strAudioRequired = stringResource(R.string.voice_cloning_audio_required)
    val strEmpty = stringResource(R.string.voice_cloning_list_empty)
    val strDeleteConfirm = stringResource(R.string.voice_cloning_delete_confirm)

    // v1.0.74 fix (前端审计 1.5): apiKey 每字符触发网络请求 → 500ms debounce。
    // 原实现 LaunchedEffect(selectedProvider, apiKey) 里 apiKey 是实时输入态,
    // 用户每敲一个字符都 listClonedVoices,输入卡顿且烧 API 配额。
    LaunchedEffect(selectedProvider) {
        snapshotFlow { apiKey.trim() }
            .debounce(500)
            .collect { key ->
                if (key.isBlank()) {
                    voices.clear()
                    return@collect
                }
                // 透传 apiKey 到 ElevenLabs provider(后续 provider 可扩展类似 setter)
                if (selectedProvider == "elevenlabs") {
                    elevenLabsProvider.apiKey = key
                }
                if (selectedProvider == "fish") {
                    fishProvider.apiKey = key
                    fishProvider.endpoint = settings.mediaConfigFlow.first().ttsEndpoint
                }
                isLoadingList = true
                voiceCloningService.listClonedVoices(selectedProvider)
                    .onSuccess { list ->
                        voices.clear()
                        voices.addAll(list)
                    }
                    .onError { msg, _ ->
                        MuseToast.show(msg, 3000)
                    }
                isLoadingList = false
            }
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.voice_cloning_title),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        // v1.132: 整页统一可滚动(表单 + 已克隆语音列表一起滚动),
        // 不再让语音列表固定在底部钻毛一块区域
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
        ) {
            // ── 服务商选择(FilterChip 行)──
            FormSection(label = stringResource(R.string.voice_cloning_provider)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    voiceCloningService.availableProviders().forEach { pid ->
                        MuseChip(
                            selected = pid == selectedProvider,
                            onClick = { selectedProvider = pid },
                            label = pid,
                        )
                    }
                }
            }

            // ── API Key 输入 ──
            FormSection(label = stringResource(R.string.voice_cloning_api_key)) {
                MuseTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("xi-api-key") },
                    singleLine = true,
                    visualTransformation = if (apiKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        // v1.132: 替换原 emoji(🙈/👁)为 Material Icons,与 ProviderSection 风格一致
                        MuseTactileButton(
                            icon = if (apiKeyVisible) {
                                MuseIcons.eyeOff
                            } else {
                                MuseIcons.eye
                            },
                            onClick = { apiKeyVisible = !apiKeyVisible },
                            contentDescription = if (apiKeyVisible) {
                                stringResource(R.string.settings_common_hide)
                            } else {
                                stringResource(R.string.settings_common_show)
                            },
                        )
                    },
                )
            }

            // ── 克隆新语音表单 ──
            FormSection(label = stringResource(R.string.voice_cloning_new_voice)) {
                // 语音名称输入
                MuseTextField(
                    value = voiceName,
                    onValueChange = { voiceName = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.voice_cloning_voice_name)) },
                    singleLine = true,
                    enabled = !isCloning,
                    leadingIcon = {
                        Icon(
                            imageVector = MuseIcons.user,
                            contentDescription = null,
                            modifier = Modifier.size(MusePaddings.iconPadding * 2),
                        )
                    },
                )

                Spacer(Modifier.height(MusePaddings.contentGap))

                // 选择样本音频按钮(Surface + clickable,不用 Material3 Button)
                Surface(
                    shape = MuseShapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isCloning) {
                            pickAudioLauncher.launch(arrayOf("audio/*"))
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        Icon(
                            imageVector = MuseIcons.waveSine,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(MusePaddings.iconPadding * 2),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.voice_cloning_select_audio),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (selectedAudioLabel.isNotBlank()) {
                                Text(
                                    text = selectedAudioLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(MusePaddings.contentGap))

                // 提交按钮(Surface + clickable,不用 Material3 Button)
                Surface(
                    shape = MuseShapes.medium,
                    color = if (isCloning) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isCloning) {
                            if (apiKey.isBlank()) {
                                MuseToast.show(strAudioRequired, 2500)
                                return@clickable
                            }
                            if (voiceName.isBlank()) {
                                MuseToast.show(
                                    context.getString(R.string.voice_cloning_voice_name),
                                    2500,
                                )
                                return@clickable
                            }
                            val uri = selectedAudioUri
                            if (uri == null) {
                                MuseToast.show(strAudioRequired, 2500)
                                return@clickable
                            }
                            scope.launch {
                                isCloning = true
                                // 读音频文件 + base64 编码(必须在 IO 线程)
                                val base64Result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        context.contentResolver.openInputStream(uri)?.use {
                                            Base64.encodeToString(
                                                it.readBytes(),
                                                Base64.NO_WRAP,
                                            )
                                        } ?: throw java.io.IOException(
                                            "Cannot open audio stream",
                                        )
                                    }
                                }
                                val base64 = base64Result.getOrElse { e ->
                                    isCloning = false
                                    MuseToast.show(
                                        context.getString(
                                            R.string.voice_cloning_failed,
                                            e.message ?: "read audio failed",
                                        ),
                                        3000,
                                    )
                                    return@launch
                                }

                                // 透传 apiKey 到 provider(防止用户刚改完未触发 LaunchedEffect)
                                if (selectedProvider == "elevenlabs") {
                                    elevenLabsProvider.apiKey = apiKey.trim()
                                }
                                if (selectedProvider == "fish") {
                                    fishProvider.apiKey = apiKey.trim()
                                    fishProvider.endpoint = settings.mediaConfigFlow.first().ttsEndpoint
                                }

                                voiceCloningService.cloneVoice(
                                    providerId = selectedProvider,
                                    name = voiceName.trim(),
                                    sampleAudioBase64 = base64,
                                ).onSuccess { voiceId ->
                                    MuseToast.show(strSuccess, 2000)
                                    // B6-04: 克隆成功自动设为当前 TTS 音色；手动模式下把输入框里的
                                    // API Key 一并落库 —— 只写引擎与音色的结果是：音色看起来换上了，
                                    // 云端合成却因 key 为空直接失败，静默回落系统 TTS。
                                    var followProvider = false
                                    runCatching {
                                        val media = settings.mediaConfigFlow.first()
                                        followProvider = media.ttsFollowProvider
                                        settings.saveMediaConfig(
                                            media.afterVoiceClone(
                                                engine = selectedProvider,
                                                voiceId = voiceId,
                                                apiKey = apiKey,
                                            ),
                                        )
                                    }.onSuccess {
                                        MuseToast.show(
                                            if (followProvider) strKeyFromProvider else strKeySaved,
                                            2500,
                                        )
                                    }
                                    // 重置表单
                                    voiceName = ""
                                    selectedAudioUri = null
                                    selectedAudioLabel = ""
                                    // 刷新列表
                                    voiceCloningService.listClonedVoices(selectedProvider)
                                        .onSuccess { list ->
                                            voices.clear()
                                            voices.addAll(list)
                                        }
                                }.onError { msg, _ ->
                                    MuseToast.show(
                                        context.getString(R.string.voice_cloning_failed, msg),
                                        3000,
                                    )
                                }
                                isCloning = false
                            }
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MusePaddings.cardInner.calculateTopPadding()),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isCloning) {
                            MuseSpinner(
                                size = MusePaddings.iconPadding * 2,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.size(MusePaddings.iconPadding))
                        } else {
                            Icon(
                                imageVector = MuseIcons.plus,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(MusePaddings.iconPadding * 2),
                            )
                            Spacer(Modifier.size(MusePaddings.iconPadding))
                        }
                        Text(
                            text = stringResource(R.string.voice_cloning_submit),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isCloning) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(MusePaddings.sectionGap))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(MusePaddings.contentGap))

            // ── 已克隆语音列表(跟随上下滚动,不再固定底部)──
            // v1.132: 改用 forEach 替代 LazyColumn(verticalScroll 内不能嵌套 LazyColumn),
            // 让克隆语音列表与表单一起随整页滚动
            if (isLoadingList && voices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MusePaddings.sectionGap),
                    contentAlignment = Alignment.Center,
                ) {
                    MuseSpinner(
                        size = MusePaddings.iconPadding * 3,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else if (voices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MusePaddings.sectionGap),
                    contentAlignment = Alignment.Center,
                ) {
                    // ST-03: 空态统一 MuseEmptyState
                    MuseEmptyState(title = strEmpty)
                }
            } else {
                // 列表标题
                Text(
                    text = stringResource(R.string.voice_cloning_list_title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                voices.forEach { voice ->
                    ClonedVoiceRow(
                        voice = voice,
                        isDeleting = isDeleting,
                        onDelete = { voiceToDelete = voice },
                        // v2.6.7 fix (AUDIT F21): 直接调 TtsManager.speakWithClonedVoice
                        // —— 该方法早已实现但零调用。
                        onPreview = {
                            val sample = context.getString(R.string.tts_preview_sample_text)
                            runCatching {
                                ttsManager.speakWithClonedVoice(
                                    text = sample,
                                    utteranceId = "preview_${voice.voiceId}",
                                    voiceId = voice.voiceId,
                                    providerId = selectedProvider,
                                )
                            }.onFailure {
                                scope.launch {
                                    MuseToast.show(
                                        context.getString(R.string.tts_preview_failed, it.message),
                                        3000,
                                    )
                                }
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(MusePaddings.screen))
        }
    }

    // ── 删除确认弹窗(MuseDialog,不用 Material3 AlertDialog)──
    voiceToDelete?.let { target ->
        MuseDialog(
            onDismissRequest = { voiceToDelete = null },
            title = stringResource(R.string.voice_cloning_title),
            content = {
                Text(
                    text = strDeleteConfirm,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.voice_cloning_submit),
            onConfirm = {
                val target0 = target
                voiceToDelete = null
                scope.launch {
                    isDeleting = true
                    if (selectedProvider == "elevenlabs") {
                        elevenLabsProvider.apiKey = apiKey.trim()
                    }
                    if (selectedProvider == "fish") {
                        fishProvider.apiKey = apiKey.trim()
                        fishProvider.endpoint = settings.mediaConfigFlow.first().ttsEndpoint
                    }
                    voiceCloningService.deleteVoice(
                        providerId = selectedProvider,
                        voiceId = target0.voiceId,
                    ).onSuccess {
                        voices.removeIf { it.voiceId == target0.voiceId }
                    }.onError { msg, _ ->
                        MuseToast.show(msg, 3000)
                    }
                    isDeleting = false
                }
            },
            destructive = true,
        )
    }
}

/**
 * 表单分组(标题 + 内容),与 VideoGenerationPage 风格一致。
 */
@Composable
private fun FormSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        content()
    }
}

/**
 * 已克隆语音行 — 名称 + voiceId + 删除按钮。
 */
@Composable
@Suppress("LongMethod") // 单行卡片：图标/文本/两个按钮同处更易读
private fun ClonedVoiceRow(
    voice: ClonedVoice,
    isDeleting: Boolean,
    onDelete: () -> Unit,
    /** v2.6.7 fix (AUDIT F21): 试听 —— 旧实现克隆完只能手动去媒体设置改 ttsVoice。 */
    onPreview: () -> Unit = {},
) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            // 左侧:Person 图标
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                modifier = Modifier.size(MusePaddings.iconPadding * 4),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MuseIcons.user,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MusePaddings.iconPadding * 2),
                    )
                }
            }
            // 中间:名称 + voiceId
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = voice.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = voice.voiceId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 右侧:试听 + 删除（v2.6.7 fix AUDIT F21: 补试听入口）
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier
                    .size(MusePaddings.touchTarget)
                    .clickable(enabled = !isDeleting) { onPreview() },
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MuseIcons.volume,
                        contentDescription = stringResource(R.string.tts_preview_action),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MusePaddings.iconPadding * 2),
                    )
                }
            }
            // 右侧:删除按钮(圆形 Surface + clickable,不用 IconButton + 默认 Material3 风格)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                modifier = Modifier
                    .size(MusePaddings.touchTarget)
                    .clickable(enabled = !isDeleting) { onDelete() },
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MuseIcons.trash,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(MusePaddings.iconPadding * 2),
                    )
                }
            }
        }
    }
}

/**
 * 克隆成功后要写回的 TTS 配置：引擎、音色，手动模式下连同输入框里的 API Key。
 *
 * 三件东西是一体的 —— 只写引擎与音色、不写 key，用户看到「克隆成功 + 已设为当前音色」，
 * 而云端合成其实因为 key 为空一直在回落系统 TTS。
 *
 * 「跟随供应商配置」开启时 key 由供应商提供，这里不动 [MediaConfig.ttsApiKey]：
 * 把手填值写进去只会留一份切换回手动模式后才会用到、且未必有效的凭据。
 *
 * @param engine 克隆所用供应商（同时就是 TTS 引擎 id，避免把 voiceId 挂到别的引擎下）
 * @param voiceId 克隆返回的音色 id
 * @param apiKey 页面上输入的 API Key（手动模式下写入，前后空白去掉）
 */
internal fun MediaConfig.afterVoiceClone(engine: String, voiceId: String, apiKey: String): MediaConfig = copy(
    ttsEngine = engine,
    ttsVoice = voiceId,
    ttsApiKey = if (ttsFollowProvider) ttsApiKey else apiKey.trim(),
)
