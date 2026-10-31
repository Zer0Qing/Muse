package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.data.voice.MpVoiceCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 语音输入的三个状态。
 *
 * 「停止录音」与「开始转写」是同一个动作的两面:用户点结束的那一刻就该看见转写提示,
 * 而不是等采集真的收完线才变 —— 所以 [Transcribing] 在停采之前就落位。
 */
internal sealed interface MpVoiceInputState {

    /** 没在录音,也没有转写在进行。 */
    data object Idle : MpVoiceInputState

    /** 正在录;[elapsedSeconds] 从 0 开始每秒加一。 */
    data class Recording(val elapsedSeconds: Int) : MpVoiceInputState

    /** 采完了,等宿主把录音转成文字。 */
    data object Transcribing : MpVoiceInputState
}

/**
 * 聊天页输入栏的语音输入状态机。
 *
 * ```
 * Idle → Recording(elapsedSeconds) → Transcribing → Idle
 *              ↓ 取消
 *            Idle
 * ```
 *
 * 三条规矩:
 *  - 转写没结束不接受新的录音([start] 直接忽略),否则前一段的临时文件会没人认领;
 *  - 取消只丢音频,不调转写 —— 用户按取消就是「这句话不要了」;
 *  - 录到 [MAX_RECORDING_SECONDS] 秒自动走一遍「停止并转写」,不靠用户记得端点。
 *
 * 识别出的文字交给 [onTranscript];识别不出来(空串、null、没采到音频)交给
 * [onEmptyTranscript],由界面决定怎么说「没听清」—— 本类不碰文案,也不碰 toast。
 * 麦克风起不来时交给 [onStartFailed]:用户点了一下却什么都没发生,比一句报错更难受。
 *
 * @param recorder 录音器;停止时由它交出整段 WAV
 * @param transcribe 把 WAV 转成文字的一次性入口,失败返回 null
 * @param canRecord 当前设置能不能录音;不能时 [start] 直接不动录音器
 * @param scope 计时与转写作业挂在这里,随页面一起结束
 */
internal class MpVoiceInputController(
    private val recorder: MpVoiceCapture,
    private val transcribe: suspend (ByteArray) -> String?,
    private val onTranscript: (String) -> Unit,
    private val onEmptyTranscript: () -> Unit,
    private val onStartFailed: () -> Unit,
    private val canRecord: () -> Boolean,
    private val scope: CoroutineScope,
) {

    private val mutableState = MutableStateFlow<MpVoiceInputState>(MpVoiceInputState.Idle)

    val state: StateFlow<MpVoiceInputState> = mutableState.asStateFlow()

    /** 正在跑的秒表;离开录音状态就取消。 */
    private var ticker: Job? = null

    /**
     * 开始录音。只在 [MpVoiceInputState.Idle] 且当前设置支持录音时生效。
     *
     * 拿不到麦克风时状态不变并发一次 [onStartFailed] —— 置灰与不是置灰两种情形分开处理:
     * 设置不支持是「功能没开」,采集失败是「这次没成」,用户要看的不是同一句话。
     */
    fun start() {
        if (mutableState.value != MpVoiceInputState.Idle) return
        if (!canRecord()) return
        if (!recorder.start()) {
            onStartFailed()
            return
        }
        mutableState.value = MpVoiceInputState.Recording(elapsedSeconds = 0)
        ticker = scope.launch { tick() }
    }

    /** 停止录音并转写。只在 [MpVoiceInputState.Recording] 生效。 */
    fun stop() {
        if (mutableState.value !is MpVoiceInputState.Recording) return
        ticker?.cancel()
        ticker = null
        mutableState.value = MpVoiceInputState.Transcribing
        scope.launch { finishTranscription() }
    }

    /** 取消这一次录音:音频丢掉,不转写。 */
    fun cancel() {
        if (mutableState.value !is MpVoiceInputState.Recording) return
        ticker?.cancel()
        ticker = null
        recorder.cancel()
        mutableState.value = MpVoiceInputState.Idle
    }

    /** 页面离开时收尾:不管在录音还是转写,都回到空闲并确保临时文件被删。 */
    fun release() {
        ticker?.cancel()
        ticker = null
        recorder.cancel()
        mutableState.value = MpVoiceInputState.Idle
    }

    /** 停采 → 转写 → 交文字。空结果也走这条路,只是交给 [onEmptyTranscript]。 */
    private suspend fun finishTranscription() {
        val wav = recorder.stop()
        val text = if (wav == null) null else transcribe(wav)
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) onEmptyTranscript() else onTranscript(trimmed)
        mutableState.value = MpVoiceInputState.Idle
    }

    /** 秒表:一秒一跳,跳到上限就自己按「结束」。 */
    private suspend fun tick() {
        while (true) {
            delay(TICK_INTERVAL_MS)
            val recording = mutableState.value as? MpVoiceInputState.Recording ?: return
            val next = recording.elapsedSeconds + 1
            mutableState.value = MpVoiceInputState.Recording(next)
            if (next >= MAX_RECORDING_SECONDS) {
                stop()
                return
            }
        }
    }

    companion object {
        /** 单次录音上限:与录音器自己的写入上限一致。 */
        const val MAX_RECORDING_SECONDS: Int = 60
    }
}

private const val TICK_INTERVAL_MS: Long = 1_000L

/** 输入栏上的录音时长:`0:07`。 */
internal fun voiceElapsedLabel(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "${safe / 60}:${(safe % 60).toString().padStart(2, '0')}"
}
