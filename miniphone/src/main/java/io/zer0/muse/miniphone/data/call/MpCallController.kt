package io.zer0.muse.miniphone.data.call

import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.db.MpActorType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 一通电话的控制器：推进阶段、跑每一轮内容、计时、结束时落库。
 *
 * 说清楚这里有什么、没有什么：
 * - **有**：真实的阶段状态机、真实的每轮内容（来自 [MiniPhoneHost.respond]，那条流本来就会同时
 *   给出文本段与合成好的语音段）、真实的计时与真实的通话记录。
 * - **没有**：实时音视频传输。小手机与角色之间不存在信令通道，所以「对方何时接听」是本机
 *   模拟的（[acceptDelayMs]），远端画面也不存在。这一段属于真机才能继续的工作，
 *   计划 §七 已标注，这里不假装它已经具备。
 *
 * 因此界面对用户的说法始终是「这次通话说了什么、多久」，不会声称连上了对方线路。
 */
internal class MpCallController(
    private val host: MiniPhoneHost,
    private val store: MpCallStore,
    private val scope: CoroutineScope,
    private val session: MpCallSession = MpCallSession(),
    private val now: () -> Long = { System.currentTimeMillis() },
    private val ringDelayMs: Long = RING_DELAY_MS,
    private val tickMs: Long = TICK_MS,
) {
    private val _state = MutableStateFlow<MpCallSessionUi?>(null)
    private val _elapsedMs = MutableStateFlow(0L)
    private var dialJob: Job? = null
    private var roundJob: Job? = null
    private var timerJob: Job? = null

    val state: StateFlow<MpCallSessionUi?> = _state.asStateFlow()
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    /**
     * 拨号。同一时刻只允许一通：已有未结束的会话时忽略这次调用，
     * 否则用户快速点两下会攒出两条记录、并让计时互相覆盖。
     */
    fun dial(threadId: String, characterId: String, kind: MpCallKind, cameraAllowed: Boolean) {
        _state.value?.takeIf { it.phase != MpCallPhase.ENDED && it.phase != MpCallPhase.FAILED }?.let { return }
        var current = session.start(threadId = threadId, characterId = characterId, kind = kind)
        if (kind == MpCallKind.VIDEO && !cameraAllowed) {
            current = session.degradeToVoice(current)
        }
        _state.value = current
        dialJob = scope.launch {
            delay(ringDelayMs)
            val ringing = session.ringing(_state.value ?: return@launch)
            _state.value = ringing
            if (ringing.phase != MpCallPhase.RINGING) return@launch
            delay(ringDelayMs)
            val connected = session.connected(_state.value ?: return@launch)
            if (connected.phase != MpCallPhase.CONNECTED) return@launch
            _state.value = connected
            startTimer()
        }
    }

    /**
     * 跑一轮：把最近的对话交给宿主，收回文本与语音段落进会话。
     *
     * 只接仍然处于 CONNECTED 的会话；挂断后到达的残余事件不写进去，
     * 否则记录里会出现「通话结束之后又多了半句」。
     */
    fun runRound(history: List<MpChatTurn>) {
        val current = _state.value ?: return
        if (current.phase != MpCallPhase.CONNECTED) return
        roundJob?.cancel()
        roundJob = scope.launch {
            host.respond(current.characterId, history.takeLast(HISTORY_TURNS)).collect { event ->
                val latest = _state.value ?: return@collect
                if (latest.phase != MpCallPhase.CONNECTED) return@collect
                _state.value = when (event) {
                    is MpReplyEvent.Text -> session.appendTurn(latest, MpActorType.CHARACTER, event.content, null)
                    // 语音段只有音频文件与时长，没有文本 —— 正文留空，界面靠音频那一行说话。
                    is MpReplyEvent.Voice -> session.appendTurn(latest, MpActorType.CHARACTER, "", event.uri)
                    is MpReplyEvent.Activity -> latest
                    MpReplyEvent.Failed -> session.fail(latest, MpCallRejectReason.DIAL_FAILED)
                }
            }
        }
    }

    /** 用户开口说的那一句也进记录，否则回看时只有角色在自言自语。 */
    fun speak(text: String) {
        val current = _state.value ?: return
        if (current.phase != MpCallPhase.CONNECTED) return
        _state.value = session.appendTurn(current, MpActorType.USER, text, null)
    }

    /**
     * 挂断：结束会话、停计时、把这一条落库。
     *
     * 返回落库用的时长，调用方（界面）可以直接显示，不用再等一次状态流。
     */
    fun hangUp(): Long {
        val current = _state.value ?: return 0L
        dialJob?.cancel()
        roundJob?.cancel()
        stopTimer()
        // 时长必须在切到 ENDED 之前取：ENDED 之后 durationMsAt 恒为 0，
        // 真聊了九分钟的电话会被记成「没接通」。
        val durationMs = current.durationMsAt(now())
        val ended = session.hangUp(current)
        _state.value = ended
        session.recordOf(ended)?.let { record ->
            scope.launch {
                store.record(
                    id = record.callId,
                    threadId = record.threadId,
                    characterId = record.characterId,
                    kind = record.kind,
                    startedAt = record.startedAtMs,
                    durationMs = durationMs,
                )
            }
        }
        return durationMs
    }

    /** 离开页面时收尾：还在进行的通话必须挂断，否则会留一条永远不结束的会话。 */
    fun dispose() {
        _state.value?.takeIf { it.phase == MpCallPhase.DIALING || it.phase == MpCallPhase.RINGING || it.phase == MpCallPhase.CONNECTED }
            ?.let { hangUp() }
        dialJob = null
        roundJob = null
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (_state.value?.phase == MpCallPhase.CONNECTED) {
                _state.value?.let { _elapsedMs.value = it.durationMsAt(now()) }
                delay(tickMs)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private companion object {
        const val RING_DELAY_MS = 1_500L
        const val TICK_MS = 1_000L
        const val HISTORY_TURNS = 8
    }
}
