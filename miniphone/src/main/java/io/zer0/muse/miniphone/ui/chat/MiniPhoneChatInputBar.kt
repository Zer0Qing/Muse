package io.zer0.muse.miniphone.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.zer0.muse.miniphone.data.voice.MpVoiceCapture
import io.zer0.muse.miniphone.data.voice.MpVoiceRecorder
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * 底部输入栏:图片入口 + 麦克风 + 多行输入 + 发送。
 *
 * 输入框最多长到四行,再多就在框内滚动 —— 聊天页不能被一句长话顶掉半屏消息。
 * 空输入时发送键只有形不肯按:点击回调根本不挂,而不是按下去什么都不做。
 * 选图本身不在本组件里做:拉起系统选择器要 Activity 结果通道,交给有它的上层。
 *
 * 语音是手动端点的一次性流程:点麦克风开始录,再点「结束」停止并转写,
 * 出来的文字只补进输入框末尾,不自动发送 —— 说错一个字还能改,发出去就收不回来了。
 * 录音期间输入框换成一条录音提示:边录边打字,录完那段话该插到哪儿没人说得清。
 *
 * @param onTranscribeVoice 把整段 WAV 交给宿主转写;拿不到文字时返回 null
 * @param voiceInputEnabled 当前识别引擎能不能把录音转成文字;false 时麦克风置灰
 */
@Composable
internal fun ChatInputBar(
    onSend: (String) -> Unit,
    onPickImages: () -> Unit,
    modifier: Modifier = Modifier,
    onTranscribeVoice: suspend (ByteArray) -> String? = { null },
    voiceInputEnabled: Boolean = true,
) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    val canSend = text.isNotBlank()
    val submit = {
        if (canSend) {
            onSend(text)
            text = ""
        }
    }
    val recorder = remember(context) { MpVoiceRecorder(context.cacheDir) }
    val voice = rememberVoiceInput(
        recorder = recorder,
        onTranscribeVoice = onTranscribeVoice,
        voiceInputEnabled = voiceInputEnabled,
        onTranscript = { transcript -> text = appendTranscript(text, transcript) },
        onNotice = { notice -> context.toast(notice) },
    )
    val voiceState by voice.state.collectAsState()
    val askMic = rememberMicPermissionRequest(
        onGranted = voice::start,
        onDenied = { context.toast(MIC_PERMISSION_NOTICE) },
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(dimens.hairline)
                .background(colors.divider),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.pageMargin, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            PickImageButton(onClick = onPickImages)
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(InputShape)
                    .background(colors.background),
            ) {
                when (val current = voiceState) {
                    is MpVoiceInputState.Recording -> RecordingStrip(
                        elapsedSeconds = current.elapsedSeconds,
                        onCancel = voice::cancel,
                    )
                    MpVoiceInputState.Transcribing -> TranscribingStrip()
                    MpVoiceInputState.Idle -> BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = typography.body.copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.accent),
                        maxLines = INPUT_MAX_LINES,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag(MiniPhoneChatTags.INPUT_FIELD),
                        decorationBox = { innerTextField ->
                            if (text.isEmpty()) {
                                Text(
                                    text = INPUT_PLACEHOLDER,
                                    style = typography.body.copy(color = colors.textSecondary),
                                )
                            }
                            innerTextField()
                        },
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            MicButton(
                state = voiceState,
                enabled = voiceInputEnabled,
                onClick = {
                    when {
                        // 引擎不支持录音输入:键是灰的,点了只解释一句,不弹权限框也不开采集
                        !voiceInputEnabled -> context.toast(VOICE_UNSUPPORTED_NOTICE)
                        voiceState is MpVoiceInputState.Recording -> voice.stop()
                        voiceState == MpVoiceInputState.Transcribing -> Unit
                        else -> askMic()
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            SendButton(enabled = canSend && voiceState == MpVoiceInputState.Idle, onClick = submit)
        }
    }
}

/**
 * 把一次录音交给状态机,并把「页面没了」这件事接上:离页时停采并删掉临时文件。
 *
 * 三个回调都会在组合里被重新创建,所以取最新值再传给状态机 —— 否则状态机抓到的是
 * 第一次组合时的 `text`,识别结果会写到一行早就被别处改过的文字里。
 */
@Composable
private fun rememberVoiceInput(
    recorder: MpVoiceCapture,
    onTranscribeVoice: suspend (ByteArray) -> String?,
    voiceInputEnabled: Boolean,
    onTranscript: (String) -> Unit,
    onNotice: (String) -> Unit,
): MpVoiceInputController {
    val scope = rememberCoroutineScope()
    val currentTranscribe = rememberUpdatedState(onTranscribeVoice)
    val currentTranscript = rememberUpdatedState(onTranscript)
    val currentNotice = rememberUpdatedState(onNotice)
    val currentEnabled = rememberUpdatedState(voiceInputEnabled)
    val controller = remember(recorder) {
        MpVoiceInputController(
            recorder = recorder,
            transcribe = { wav -> currentTranscribe.value(wav) },
            onTranscript = { transcript -> currentTranscript.value(transcript) },
            onEmptyTranscript = { currentNotice.value(EMPTY_TRANSCRIPT_NOTICE) },
            onStartFailed = { currentNotice.value(START_FAILED_NOTICE) },
            canRecord = { currentEnabled.value },
            scope = scope,
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    return controller
}

/**
 * 麦克风权限:没授过就问一次,拒了之后只提示不再弹系统对话框。
 *
 * 反复弹同一个权限框既没用又烦人 —— 用户已经表过态,再问一遍只会挡住输入栏。
 * 用户后来在系统设置里放开,下一次点麦克风会直接开始录音。
 */
@Composable
private fun rememberMicPermissionRequest(onGranted: () -> Unit, onDenied: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var deniedOnce by rememberSaveable { mutableStateOf(false) }
    val currentGranted = rememberUpdatedState(onGranted)
    val currentDenied = rememberUpdatedState(onDenied)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            currentGranted.value()
        } else {
            deniedOnce = true
            currentDenied.value()
        }
    }
    return {
        when {
            context.hasRecordPermission() -> currentGranted.value()
            deniedOnce -> currentDenied.value()
            else -> launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/** 图片入口:自绘相册图标,和发送键同尺寸,底边对齐。 */
@Composable
private fun PickImageButton(onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    Box(
        modifier = Modifier
            .size(SendButtonSize)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag(MiniPhoneChatTags.PICK_IMAGE),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiniPhoneIcons.Image,
            contentDescription = PICK_IMAGE_DESCRIPTION,
            tint = colors.textSecondary,
            modifier = Modifier.size(SendIconSize),
        )
    }
}

/**
 * 麦克风键:空闲是图标,录音中变成「结束」,转写中置灰不动。
 *
 * 录音中的键换成胶囊而不是继续画话筒 —— 同一个位置上一枚话筒加一句「结束」,
 * 用户得先想「现在按下去会发生什么」才敢按。
 * 引擎不支持录音输入时画成灰的,但仍旧可点:点下去给一句解释。
 */
@Composable
private fun MicButton(state: MpVoiceInputState, enabled: Boolean, onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    when {
        state is MpVoiceInputState.Recording -> Box(
            modifier = Modifier
                .height(SendButtonSize)
                .clip(CircleShape)
                .background(colors.accent)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp)
                .testTag(MiniPhoneChatTags.MIC_BUTTON),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = STOP_LABEL, style = typography.caption.copy(color = colors.onAccent))
        }
        state == MpVoiceInputState.Transcribing -> Box(
            modifier = Modifier
                .size(SendButtonSize)
                .clip(CircleShape)
                .semantics { disabled() }
                .testTag(MiniPhoneChatTags.MIC_BUTTON),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MiniPhoneIcons.Mic,
                contentDescription = MIC_DESCRIPTION,
                tint = colors.divider,
                modifier = Modifier.size(SendIconSize),
            )
        }
        else -> Box(
            modifier = Modifier
                .size(SendButtonSize)
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .testTag(MiniPhoneChatTags.MIC_BUTTON),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MiniPhoneIcons.Mic,
                contentDescription = MIC_DESCRIPTION,
                tint = if (enabled) colors.textSecondary else colors.divider,
                modifier = Modifier.size(SendIconSize),
            )
        }
    }
}

/** 录音中:一个红点报「在录」,已录秒数报「录了多久」,右边是取消。 */
@Composable
private fun RecordingStrip(elapsedSeconds: Int, onCancel: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(RecordingDotSize)
                .clip(CircleShape)
                .background(colors.unreadDot),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = voiceElapsedLabel(elapsedSeconds),
            style = typography.body.copy(color = colors.textPrimary),
            modifier = Modifier.testTag(MiniPhoneChatTags.RECORDING_SECONDS),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = CANCEL_LABEL,
            style = typography.caption.copy(color = colors.textSecondary),
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onCancel)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag(MiniPhoneChatTags.CANCEL_RECORDING),
        )
    }
}

/** 转写中:说明这段录音去哪儿了,别让输入框凭空空着。 */
@Composable
private fun TranscribingStrip() {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = TRANSCRIBING_LABEL,
            style = typography.body.copy(color = colors.textSecondary),
            modifier = Modifier.testTag(MiniPhoneChatTags.VOICE_STATUS),
        )
    }
}

/** 发送键:一直占着位置,只换底色和可点状态,不让输入栏随文字抖动。 */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val press = if (enabled) Modifier.clickable(onClick = onClick) else Modifier.semantics { disabled() }
    Box(
        modifier = Modifier
            .size(SendButtonSize)
            .clip(CircleShape)
            .background(if (enabled) colors.accent else colors.divider)
            .then(press)
            .testTag(MiniPhoneChatTags.SEND_BUTTON),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiniPhoneIcons.Send,
            contentDescription = SEND_DESCRIPTION,
            tint = if (enabled) colors.onAccent else colors.textSecondary,
            modifier = Modifier.size(SendIconSize),
        )
    }
}

private fun Context.hasRecordPermission(): Boolean =
    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/** 把识别结果补到输入框末尾;框里已有内容就隔一个空格,不覆盖用户打了一半的字。 */
internal fun appendTranscript(current: String, addition: String): String {
    val extra = addition.trim()
    if (extra.isEmpty()) return current
    return if (current.isBlank()) extra else current.trimEnd() + " " + extra
}

private val InputShape = RoundedCornerShape(8.dp)
private val SendButtonSize = 38.dp
private val SendIconSize = 18.dp
private val RecordingDotSize = 8.dp

/** 输入框最多长到四行,再长就在框内滚动。 */
private const val INPUT_MAX_LINES: Int = 4

private const val INPUT_PLACEHOLDER: String = "说点什么…"
private const val SEND_DESCRIPTION: String = "发送"
private const val PICK_IMAGE_DESCRIPTION: String = "选择图片"
private const val MIC_DESCRIPTION: String = "发语音"
private const val STOP_LABEL: String = "结束"
private const val CANCEL_LABEL: String = "取消"
private const val TRANSCRIBING_LABEL: String = "正在转写…"
private const val EMPTY_TRANSCRIPT_NOTICE: String = "没听清，再说一次"
private const val START_FAILED_NOTICE: String = "录音启动失败，请检查麦克风是否被其他应用占用"
private const val MIC_PERMISSION_NOTICE: String = "需要麦克风权限才能发语音"
private const val VOICE_UNSUPPORTED_NOTICE: String = "当前语音识别引擎不支持录音输入，请到设置里选择支持转写的引擎"
