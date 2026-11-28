package io.zer0.muse.ui.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.zer0.common.Logger
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 语音播放状态。
 *
 * [uri] 是"当前占用播放权"的那条语音；为空即空闲。[positionMs] 只在播放/暂停期间有效，
 * 复位后清 0。[playedUris] 跟踪本会话内点播过的语音条，用于隐藏未听红点。
 */
data class VoicePlaybackState(
    val uri: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val speed: Float = 1.0f,
    val earpieceMode: Boolean = false,
    val playedUris: Set<String> = emptySet(),
)

/** 播放引擎端口（测试注入假实现，避免触碰真实音频栈）。 */
interface VoiceAudioPlayer {
    fun load(file: File, onCompletion: () -> Unit, onError: (Throwable) -> Unit): Boolean
    fun start()
    fun pause()
    fun positionMs(): Long
    fun seekTo(positionMs: Long) {}
    fun setSpeed(speed: Float) {}
    fun setEarpieceMode(earpiece: Boolean) {}
    fun release()
}

/** 基于系统 [MediaPlayer] 的实现（支持听筒/扬声器切换与倍速）。 */
class MediaPlayerVoiceAudioPlayer : VoiceAudioPlayer {
    private var player: MediaPlayer? = null
    private var prepared = false
    private var pendingSeekMs: Long? = null
    private var pendingSpeed: Float = 1.0f
    private var earpiece: Boolean = false

    override fun setSpeed(speed: Float) {
        pendingSpeed = speed
        val mp = player ?: return
        if (!prepared || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            mp.playbackParams = mp.playbackParams.setSpeed(speed)
        }.onFailure { Logger.w(TAG, "语音倍速设置失败: ${it.message}") }
    }

    override fun setEarpieceMode(earpiece: Boolean) {
        this.earpiece = earpiece
    }

    override fun load(file: File, onCompletion: () -> Unit, onError: (Throwable) -> Unit): Boolean = runCatching {
        release()
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (earpiece) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        mp.setDataSource(file.absolutePath)
        mp.setOnPreparedListener { ready ->
            prepared = true
            if (pendingSpeed != 1.0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { ready.playbackParams = ready.playbackParams.setSpeed(pendingSpeed) }
                    .onFailure { Logger.w(TAG, "起播倍速应用失败: ${it.message}") }
            }
            runCatching { ready.start() }
                .onFailure { e ->
                    Logger.w(TAG, "语音起播失败: ${e.message}")
                    onError(e)
                }
            pendingSeekMs?.let { target ->
                pendingSeekMs = null
                runCatching { ready.seekTo(target.toInt()) }
                    .onFailure { Logger.w(TAG, "语音起播后跳转失败: ${it.message}") }
            }
        }
        mp.setOnCompletionListener { onCompletion() }
        mp.setOnErrorListener { _, what, extra ->
            onError(IllegalStateException("MediaPlayer error what=$what extra=$extra"))
            true
        }
        mp.prepareAsync()
        true
    }.getOrElse { e ->
        Logger.w(TAG, "语音装载失败: ${e.message}")
        runCatching { release() }
        onError(e)
        false
    }

    override fun start() {
        val mp = player ?: return
        if (!prepared) return
        runCatching { mp.start() }.onFailure { Logger.w(TAG, "语音续播失败: ${it.message}") }
    }

    override fun pause() {
        val mp = player ?: return
        if (!prepared) return
        runCatching { mp.pause() }.onFailure { Logger.w(TAG, "语音暂停失败: ${it.message}") }
    }

    override fun positionMs(): Long = runCatching {
        val mp = player ?: return 0L
        if (!prepared) 0L else mp.currentPosition.toLong()
    }.getOrDefault(0L)

    override fun seekTo(positionMs: Long) {
        val mp = player ?: return
        if (!prepared) {
            pendingSeekMs = positionMs
            return
        }
        runCatching { mp.seekTo(positionMs.toInt()) }
            .onFailure { Logger.w(TAG, "语音跳转失败: ${it.message}") }
    }

    override fun release() {
        val mp = player ?: return
        player = null
        prepared = false
        pendingSeekMs = null
        runCatching { mp.release() }.onFailure { Logger.w(TAG, "语音播放器释放失败: ${it.message}") }
    }

    private companion object {
        private const val TAG = "MediaPlayerVoiceAudioPlayer"
    }
}

/**
 * 语音消息共享播放控制器（单例）。
 *
 *  - 与 TTS 朗读互斥（起播前触发 [onBeforePlay]）并托管系统音频焦点；
 *  - 支持倍速循环（1.0x / 1.5x / 2.0x）、听筒/扬声器切换与自然播完连续回调。
 */
class VoicePlaybackController(
    private val appContext: Context? = null,
    private val onBeforePlay: (() -> Unit)? = null,
    private val playerFactory: () -> VoiceAudioPlayer = { MediaPlayerVoiceAudioPlayer() },
) {
    var state: VoicePlaybackState by mutableStateOf(VoicePlaybackState())
        private set

    private var player: VoiceAudioPlayer? = null
    private var activeCompletion: (() -> Unit)? = null
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + GlobalCoroutineExceptionHandler) }
    private var ticker: Job? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    /** 点按：切换同一条语音的播放/暂停；换一条则停掉上一条再起播。 */
    fun toggle(uri: String, file: File, onCompleted: (() -> Unit)? = null) {
        val current = state
        if (current.uri == uri) {
            activeCompletion = onCompleted
            if (current.isPlaying) pause() else resume(file)
            return
        }
        stop()
        play(uri, file, onCompleted)
    }

    /** 拖动进度。不是当前条时先起播再跳；位置钳到 >=0。 */
    fun seek(uri: String, file: File, positionMs: Long, onCompleted: (() -> Unit)? = null) {
        val ms = positionMs.coerceAtLeast(0L)
        if (state.uri != uri) {
            stop()
            play(uri, file, onCompleted)
        } else {
            activeCompletion = onCompleted
        }
        player?.seekTo(ms)
        state = state.copy(positionMs = ms)
    }

    /** 循环切换倍速：1.0x → 1.5x → 2.0x → 1.0x。 */
    fun cycleSpeed() {
        val next = when {
            state.speed < 1.25f -> 1.5f
            state.speed < 1.75f -> 2.0f
            else -> 1.0f
        }
        state = state.copy(speed = next)
        player?.setSpeed(next)
    }

    /** 切换听筒/扬声器并在需要时保持当前进度续播。 */
    fun toggleEarpiece(file: File?) {
        val nextEarpiece = !state.earpieceMode
        val activeUri = state.uri
        val savedPos = state.positionMs
        val wasPlaying = state.isPlaying
        val completion = activeCompletion
        applyAudioRouting(nextEarpiece)
        state = state.copy(earpieceMode = nextEarpiece)
        if (activeUri != null && file != null && wasPlaying) {
            stop()
            play(activeUri, file, completion)
            if (savedPos > 0L) {
                player?.seekTo(savedPos)
                state = state.copy(positionMs = savedPos)
            }
        }
    }

    /** 停掉当前语音并释放播放器与音频焦点（切后台/离开页面/换条时调用）。 */
    fun stop() {
        ticker?.cancel()
        ticker = null
        activeCompletion = null
        val current = player
        player = null
        current?.let { runCatching { it.release() } }
        abandonAudioFocus()
        if (state.earpieceMode) {
            applyAudioRouting(earpiece = false)
        }
        if (state.uri != null || state.isPlaying || state.positionMs != 0L) {
            state = state.copy(uri = null, isPlaying = false, positionMs = 0L)
        }
    }

    /** 只有 [uri] 正是当前那条时才停。 */
    fun stopIfActive(uri: String) {
        if (state.uri == uri) stop()
    }

    private fun play(uri: String, file: File, onCompleted: (() -> Unit)? = null) {
        onBeforePlay?.invoke()
        requestAudioFocus()
        applyAudioRouting(state.earpieceMode)
        activeCompletion = onCompleted
        val engine = playerFactory()
        engine.setEarpieceMode(state.earpieceMode)
        engine.setSpeed(state.speed)
        val loaded = runCatching {
            engine.load(
                file = file,
                onCompletion = {
                    val cb = activeCompletion
                    stop()
                    cb?.invoke()
                },
                onError = { stop() },
            )
        }.getOrElse { e ->
            Logger.w(TAG, "语音播放器装载异常: ${e.message}")
            false
        }
        if (!loaded) {
            runCatching { engine.release() }
            stop()
            return
        }
        player = engine
        state = state.copy(
            uri = uri,
            isPlaying = true,
            positionMs = 0L,
            playedUris = state.playedUris + uri,
        )
        startTicker()
    }

    private fun pause() {
        ticker?.cancel()
        ticker = null
        player?.let { runCatching { it.pause() } }
        abandonAudioFocus()
        if (state.earpieceMode) {
            applyAudioRouting(earpiece = false)
        }
        state = state.copy(isPlaying = false)
    }

    private fun resume(file: File) {
        val engine = player
        val uri = state.uri
        if (engine == null || uri == null) {
            if (uri != null) play(uri, file, activeCompletion)
            return
        }
        onBeforePlay?.invoke()
        requestAudioFocus()
        val started = runCatching { engine.start() }.isSuccess
        if (!started) {
            stop()
            return
        }
        state = state.copy(isPlaying = true)
        startTicker()
    }

    private fun requestAudioFocus() {
        val ctx = appContext ?: return
        runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val listener = AudioManager.OnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    if (state.isPlaying) pause()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(if (state.earpieceMode) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(listener)
                    .build()
                audioFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                legacyFocusListener = listener
            }
        }
    }

    private fun abandonAudioFocus() {
        val ctx = appContext ?: return
        val req = audioFocusRequest
        val legacy = legacyFocusListener
        audioFocusRequest = null
        legacyFocusListener = null
        if (req == null && legacy == null) return
        runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && req != null) {
                am.abandonAudioFocusRequest(req)
            } else if (legacy != null) {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(legacy)
            }
        }
    }

    private fun applyAudioRouting(earpiece: Boolean) {
        val ctx = appContext ?: return
        runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.mode = if (earpiece) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = !earpiece
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                val engine = player ?: break
                val position = runCatching { engine.positionMs() }.getOrDefault(0L)
                if (state.isPlaying && position != state.positionMs) {
                    state = state.copy(positionMs = position)
                }
            }
        }
    }

    private companion object {
        private const val TAG = "VoicePlaybackController"
        private const val PROGRESS_INTERVAL_MS = 100L
    }
}
