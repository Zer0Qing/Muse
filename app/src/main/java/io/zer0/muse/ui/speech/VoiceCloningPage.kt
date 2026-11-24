package io.zer0.muse.ui.speech

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.R
import io.zer0.muse.data.MediaConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSecretTextField
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.actionItem
import io.zer0.muse.ui.common.surface.navigationItem
import io.zer0.muse.ui.settings.SettingsSubPageScaffold
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.util.launchSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * 语音克隆页。
 *
 * 布局按系统设置卡片语法统一收拢：
 *  1. 服务商与 API Key 卡片；
 *  2. 克隆新语音表单卡片（名称 + 样本音频 + 提交动作）；
 *  3. 已克隆语音列表卡片（支持试听与二次确认删除）。
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
fun VoiceCloningPage(
    onBack: () -> Unit,
    voiceCloningService: VoiceCloningService = koinInject(),
    elevenLabsProvider: ElevenLabsVoiceCloningProvider = koinInject(),
    fishProvider: FishAudioVoiceCloningProvider = koinInject(),
    settings: SettingsRepository = koinInject(),
    ttsManager: TtsManager = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 表单状态：apiKey 用 remember 避免进入 savedState Bundle 落盘
    var selectedProvider by rememberSaveable { mutableStateOf("elevenlabs") }
    var apiKey by remember { mutableStateOf("") }
    var voiceName by rememberSaveable { mutableStateOf("") }
    var selectedAudioUri by remember { mutableStateOf<Uri?>(null) }
    var selectedAudioLabel by rememberSaveable { mutableStateOf("") }

    // 操作状态
    var isCloning by rememberSaveable { mutableStateOf(false) }
    var isLoadingList by rememberSaveable { mutableStateOf(false) }
    var isDeleting by rememberSaveable { mutableStateOf(false) }
    val voices = remember { mutableStateListOf<ClonedVoice>() }
    var voiceToDelete by remember { mutableStateOf<ClonedVoice?>(null) }

    val pickAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            selectedAudioUri = uri
            selectedAudioLabel = uri.lastPathSegment?.substringAfterLast('/')
                ?: uri.toString()
        }
    }

    val strSuccess = stringResource(R.string.voice_cloning_success)
    val strKeySaved = stringResource(R.string.voice_cloning_key_saved)
    val strKeyFromProvider = stringResource(R.string.voice_cloning_key_from_provider)
    val strAudioRequired = stringResource(R.string.voice_cloning_audio_required)
    val strEmpty = stringResource(R.string.voice_cloning_list_empty)
    val strDeleteConfirm = stringResource(R.string.voice_cloning_delete_confirm)

    // apiKey 输入 500ms 防抖后再拉取已克隆语音列表，避免逐字触发网络请求
    LaunchedEffect(selectedProvider) {
        snapshotFlow { apiKey.trim() }
            .debounce(500)
            .collect { key ->
                if (key.isBlank()) {
                    voices.clear()
                    return@collect
                }
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

    SettingsSubPageScaffold(
        title = stringResource(R.string.voice_cloning_title),
        onBack = onBack,
    ) {
        // ── 服务商与凭据 ──
        item(key = "voice_cloning_connection") {
            CardGroup(title = { Text(stringResource(R.string.common_provider)) }) {
                content(key = "vc_provider_chips") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
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
                content(key = "vc_api_key") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                    ) {
                        MuseSecretTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = stringResource(R.string.voice_cloning_api_key),
                            placeholder = "xi-api-key",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // ── 克隆新语音表单 ──
        item(key = "voice_cloning_create") {
            CardGroup(title = { Text(stringResource(R.string.voice_cloning_new_voice)) }) {
                content(key = "vc_name_field") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                    ) {
                        MuseTextField(
                            value = voiceName,
                            onValueChange = { voiceName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.voice_cloning_voice_name)) },
                            singleLine = true,
                            enabled = !isCloning,
                            leadingIcon = {
                                Icon(
                                    imageVector = MuseIcons.user,
                                    contentDescription = null,
                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                )
                            },
                        )
                    }
                }
                navigationItem(
                    key = "vc_select_audio",
                    title = stringResource(R.string.voice_cloning_select_audio),
                    subtitle = selectedAudioLabel.ifBlank { null },
                    enabled = !isCloning,
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.waveSine,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                    onClick = { pickAudioLauncher.launchSafely(arrayOf("audio/*"), context) },
                )
                actionItem(
                    key = "vc_submit",
                    title = stringResource(R.string.voice_cloning_submit),
                    loading = isCloning,
                    enabled = !isCloning,
                    onClick = {
                        if (apiKey.isBlank()) {
                            MuseToast.show(
                                context.getString(R.string.voice_cloning_api_key_required),
                                2500,
                            )
                            return@actionItem
                        }
                        if (voiceName.isBlank()) {
                            MuseToast.show(
                                context.getString(R.string.voice_cloning_voice_name),
                                2500,
                            )
                            return@actionItem
                        }
                        val uri = selectedAudioUri
                        if (uri == null) {
                            MuseToast.show(strAudioRequired, 2500)
                            return@actionItem
                        }
                        scope.launch {
                            isCloning = true
                            val base64Result = withContext(Dispatchers.IO) {
                                runCatching {
                                    context.contentResolver.openInputStream(uri)?.use {
                                        Base64.encodeToString(
                                            it.readBytes(),
                                            Base64.NO_WRAP,
                                        )
                                    } ?: throw java.io.IOException("Cannot open audio stream")
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
                                voiceName = ""
                                selectedAudioUri = null
                                selectedAudioLabel = ""
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
                )
            }
        }

        // ── 已克隆语音列表 ──
        item(key = "voice_cloning_list") {
            if (isLoadingList && voices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MusePaddings.sectionGap),
                    contentAlignment = Alignment.Center,
                ) {
                    MuseSpinner(
                        size = MuseIconSizes.iconMedium,
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
                    MuseEmptyState(title = strEmpty)
                }
            } else {
                CardGroup(title = { Text(stringResource(R.string.voice_cloning_list_title)) }) {
                    voices.forEach { voice ->
                        item(
                            key = "voice_${voice.voiceId}",
                            leadingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(MuseSpacing.xxl)
                                        .background(
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            shape = CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = MuseIcons.user,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                    )
                                }
                            },
                            headlineContent = {
                                Text(
                                    text = voice.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = voice.voiceId,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs)) {
                                    MuseTactileButton(
                                        icon = MuseIcons.volume,
                                        contentDescription = stringResource(R.string.tts_preview_action),
                                        enabled = !isDeleting,
                                        onClick = {
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
                                    MuseTactileButton(
                                        icon = MuseIcons.trash,
                                        contentDescription = stringResource(R.string.common_delete),
                                        tint = MaterialTheme.colorScheme.error,
                                        enabled = !isDeleting,
                                        onClick = { voiceToDelete = voice },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // ── 删除确认弹窗 ──
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
            confirmText = stringResource(R.string.common_delete),
            destructive = true,
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
        )
    }
}

/**
 * 克隆成功后要写回的 TTS 配置：引擎、音色，手动模式下连同输入框里的 API Key。
 */
internal fun MediaConfig.afterVoiceClone(engine: String, voiceId: String, apiKey: String): MediaConfig = copy(
    ttsEngine = engine,
    ttsVoice = voiceId,
    ttsApiKey = if (ttsFollowProvider) ttsApiKey else apiKey.trim(),
)
