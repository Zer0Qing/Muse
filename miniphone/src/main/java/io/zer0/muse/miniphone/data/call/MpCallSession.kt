package io.zer0.muse.miniphone.data.call

import io.zer0.muse.miniphone.data.db.MpActorType
import java.util.UUID

/**
 * 一次通话的阶段。
 *
 * [FAILED] 只用于「根本没拨出去」；对方没接是 [ENDED] 加 [MpCallSessionUi.noAnswer]，
 * 两者在记录里都写时长 0，但界面说的话不一样：一个是「没接通」，一个是「打不出去」。
 */
internal enum class MpCallPhase {
    DIALING,
    RINGING,
    CONNECTED,
    ENDED,
    FAILED,
}

/** 通话界面要区分、但不能硬编码文案的几种情况。界面按当前语言把记号换成话。 */
internal object MpCallSentinel {
    /** 视频通话拿不到相机权限，已退回语音。 */
    const val CAMERA_DENIED: String = "[mp:call_camera_denied]"

    /** 没拨出去（宿主生成链路不可用等）。 */
    const val DIAL_FAILED: String = "[mp:call_dial_failed]"

    fun of(reason: MpCallRejectReason): String? = when (reason) {
        MpCallRejectReason.CAMERA_DENIED -> CAMERA_DENIED
        MpCallRejectReason.DIAL_FAILED -> DIAL_FAILED
        MpCallRejectReason.NONE -> null
    }
}

/** 通话被降级的原因。 */
internal enum class MpCallRejectReason {
    NONE,
    CAMERA_DENIED,
    DIAL_FAILED,
}

/** 通话里的一句话。[voiceUri] 非空表示这一句有可播的音频。 */
internal data class MpCallTurnUi(
    val speaker: MpActorType,
    val text: String,
    val voiceUri: String? = null,
    val atMs: Long,
)

/**
 * 通话会话的可观测状态。
 *
 * [durationMs] 只在 [MpCallPhase.CONNECTED] 之后计时，未接通恒为 0 ——
 * 记录里 0 就是「没接通」，界面不能把它显示成「通话 0 秒」。
 */
internal data class MpCallSessionUi(
    val callId: String,
    val threadId: String,
    val characterId: String,
    val kind: MpCallKind,
    val phase: MpCallPhase = MpCallPhase.DIALING,
    val startedAtMs: Long,
    val connectedAtMs: Long = 0L,
    val turns: List<MpCallTurnUi> = emptyList(),
    val noAnswer: Boolean = false,
    val rejectReason: MpCallRejectReason = MpCallRejectReason.NONE,
) {
    /** 通话已进行的毫秒数；未接通为 0。 */
    fun durationMsAt(nowMs: Long): Long = if (phase == MpCallPhase.CONNECTED) (nowMs - connectedAtMs).coerceAtLeast(0L) else 0L
}

/**
 * 通话会话状态机：拨号 → 振铃 → 接通 → 结束。
 *
 * 内容不是这里编的：每一轮都由调用方把 `MiniPhoneHost.respond` 产出的文本与语音事件喂进来
 * （那条流本来就会同时给文本段与合成好的语音段）。这里只管阶段、计时与「谁说了什么」，
 * 所以不依赖任何实时传输实现，也不假装已经有了。
 *
 * 时钟与 id 都可注入 —— 未接通、超时、时长这些边界只有钉死时间才测得准。
 */
internal class MpCallSession(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun start(threadId: String, characterId: String, kind: MpCallKind): MpCallSessionUi = MpCallSessionUi(
        callId = newId(),
        threadId = threadId,
        characterId = characterId,
        kind = kind,
        phase = MpCallPhase.DIALING,
        startedAtMs = now(),
    )

    /** 开始振铃；铃声由界面负责，这里只改阶段。 */
    fun ringing(session: MpCallSessionUi): MpCallSessionUi =
        if (session.phase == MpCallPhase.DIALING) session.copy(phase = MpCallPhase.RINGING) else session

    /** 接通：记录接通时刻，之后的时长才算通话时长。 */
    fun connected(session: MpCallSessionUi): MpCallSessionUi =
        if (session.phase == MpCallPhase.DIALING || session.phase == MpCallPhase.RINGING) {
            session.copy(phase = MpCallPhase.CONNECTED, connectedAtMs = now())
        } else {
            session
        }

    /** 追加一轮发言；已结束或失败的会话不再收。 */
    fun appendTurn(session: MpCallSessionUi, speaker: MpActorType, text: String, voiceUri: String?): MpCallSessionUi {
        val content = text.trim()
        if (session.phase == MpCallPhase.ENDED || session.phase == MpCallPhase.FAILED) return session
        if (content.isEmpty() && voiceUri == null) return session
        return session.copy(
            turns = session.turns + MpCallTurnUi(
                speaker = speaker,
                text = content,
                voiceUri = voiceUri,
                atMs = now(),
            ),
        )
    }

    /**
     * 挂断。仍在振铃就记为「没接通」，接通过就是正常结束。
     *
     * 已结束的会话重复挂断不会改状态：界面上一连点两下不应该把「没接通」洗成「已接通」。
     */
    fun hangUp(session: MpCallSessionUi): MpCallSessionUi = when (session.phase) {
        MpCallPhase.DIALING, MpCallPhase.RINGING -> session.copy(phase = MpCallPhase.ENDED, noAnswer = true)
        MpCallPhase.CONNECTED -> session.copy(phase = MpCallPhase.ENDED)
        MpCallPhase.ENDED, MpCallPhase.FAILED -> session
    }

    /** 拨不出去：失败而不是没接通，界面要能说清是哪种。 */
    fun fail(session: MpCallSessionUi, reason: MpCallRejectReason): MpCallSessionUi =
        if (session.phase == MpCallPhase.ENDED) session else session.copy(phase = MpCallPhase.FAILED, rejectReason = reason)

    /**
     * 视频通话拿不到相机权限时退回语音。
     *
     * 直接拒绝整通电话会让用户以为「这功能坏了」；退成语音并说明原因才是可用的降级。
     * 只在还没接通时降级 —— 接通后再改种类会让时长与记录对不上。
     */
    fun degradeToVoice(session: MpCallSessionUi): MpCallSessionUi =
        if (session.kind == MpCallKind.VIDEO && session.phase == MpCallPhase.DIALING) {
            session.copy(kind = MpCallKind.VOICE, rejectReason = MpCallRejectReason.CAMERA_DENIED)
        } else {
            session
        }

    /** 结束时该落库的那一行；失败（没拨出去）不落库，避免记录里出现从没发生的通话。 */
    fun recordOf(session: MpCallSessionUi): MpCallSessionUi? = if (session.phase == MpCallPhase.ENDED) session else null
}
