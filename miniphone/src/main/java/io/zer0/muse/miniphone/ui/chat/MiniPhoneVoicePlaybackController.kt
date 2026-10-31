package io.zer0.muse.miniphone.ui.chat

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 语音条的播放状态。
 *
 * [uri] 是当前占着播放权的那条语音,空表示空闲;[positionMs] 只在播放/暂停期间有意义,
 * 复位后清零。
 */
data class MpVoicePlaybackState(
    val uri: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
)

/**
 * 播放引擎端口。
 *
 * 语音条不直接碰 [MediaPlayer]:真机走 [MediaPlayerMpVoiceAudioPlayer],测试给假实现就能断言
 * 「装载了几次、暂停没有、播完有没有释放」,不必碰系统音频栈。
 */
interface MpVoiceAudioPlayer {
    /**
     * 装载并起播。
     *
     * @param onCompletion 自然播到结尾(主动 [release] 引起的停止不算)
     * @param onError 装载/解码失败
     * @return 装载是否成功;false 时调用方复位到空闲
     */
    fun load(file: File, onCompletion: () -> Unit, onError: (Throwable) -> Unit): Boolean

    /** 从暂停位置继续。 */
    fun start()

    /** 暂停,保留位置以便继续。 */
    fun pause()

    /** 当前播放位置(毫秒)。 */
    fun positionMs(): Long

    /** 释放底层资源。 */
    fun release()
}

/** 基于系统 [MediaPlayer] 的实现:小手机不引第三方播放器。 */
class MediaPlayerMpVoiceAudioPlayer : MpVoiceAudioPlayer {

    private var player: MediaPlayer? = null
    private var prepared: Boolean = false

    override fun load(file: File, onCompletion: () -> Unit, onError: (Throwable) -> Unit): Boolean = runCatching {
        release()
        val created = MediaPlayer()
        player = created
        created.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        created.setDataSource(file.absolutePath)
        created.setOnPreparedListener { ready ->
            prepared = true
            runCatching { ready.start() }.onFailure { error ->
                onError(error)
            }
        }
        created.setOnCompletionListener { onCompletion() }
        created.setOnErrorListener { _, what, extra ->
            onError(IllegalStateException("MediaPlayer error what=$what extra=$extra"))
            true
        }
        created.prepareAsync()
        true
    }.getOrElse { error ->
        runCatching { release() }
        onError(error)
        false
    }

    override fun start() {
        val playing = player ?: return
        if (!prepared) return
        runCatching { playing.start() }
    }

    override fun pause() {
        val playing = player ?: return
        if (!prepared) return
        runCatching { playing.pause() }
    }

    override fun positionMs(): Long = runCatching {
        val playing = player ?: return 0L
        if (prepared) playing.currentPosition.toLong() else 0L
    }.getOrDefault(0L)

    override fun release() {
        val playing = player ?: return
        player = null
        prepared = false
        runCatching { playing.release() }
    }
}

/**
 * 模块内唯一的语音播放控制器(Koin 单例)。
 *
 * 语音条本身不持有播放器,点按只是向这里要播放权 —— 「同一时刻只有一条在播」因此是结构保证,
 * 不靠各气泡互相协调:换一条会先把上一条停掉并释放。
 *
 * 状态用 Compose 快照状态承载,读取它的气泡自动重组;进度靠内部定时采样,气泡只读结果。
 *
 * @param playerFactory 播放器工厂;测试注入假实现,不碰真实音频栈
 */
class MiniPhoneVoicePlaybackController(
    private val playerFactory: () -> MpVoiceAudioPlayer = { MediaPlayerMpVoiceAudioPlayer() },
) {

    var state: MpVoicePlaybackState by mutableStateOf(MpVoicePlaybackState())
        private set

    private var player: MpVoiceAudioPlayer? = null

    /** 惰性创建:从不播放的进程(含多数单测)不会触碰 Main 调度器。 */
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    private var ticker: Job? = null

    /** 点按:同一条就切播放/暂停,换一条先停掉上一条再起播。 */
    fun toggle(uri: String, file: File) {
        val current = state
        if (current.uri == uri) {
            if (current.isPlaying) pause() else resume(uri, file)
            return
        }
        stop()
        play(uri, file)
    }

    /** 停掉当前语音并释放播放器(离开页面、切会话、换条时调用,可重复调用)。 */
    fun stop() {
        ticker?.cancel()
        ticker = null
        val current = player
        player = null
        current?.let { runCatching { it.release() } }
        if (state != MpVoicePlaybackState()) state = MpVoicePlaybackState()
    }

    /** 只有 [uri] 正是当前那条时才停:页面退到后台时用,不误停别人。 */
    fun stopIfActive(uri: String) {
        if (state.uri == uri) stop()
    }

    private fun play(uri: String, file: File) {
        val engine = playerFactory()
        val loaded = runCatching {
            engine.load(
                file = file,
                onCompletion = { stop() },
                onError = { stop() },
            )
        }.getOrElse { false }
        if (!loaded) {
            runCatching { engine.release() }
            stop()
            return
        }
        player = engine
        state = MpVoicePlaybackState(uri = uri, isPlaying = true, positionMs = 0L)
        startTicker()
    }

    private fun pause() {
        ticker?.cancel()
        ticker = null
        player?.let { runCatching { it.pause() } }
        state = state.copy(isPlaying = false)
    }

    private fun resume(uri: String, file: File) {
        val engine = player
        if (engine == null) {
            // 播放器已被释放(中途被别的语音抢走):按新起播处理
            play(uri, file)
            return
        }
        val started = runCatching { engine.start() }.isSuccess
        if (!started) {
            stop()
            return
        }
        state = state.copy(isPlaying = true)
        startTicker()
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
        /** 进度采样间隔:语音条不需要逐帧,10 fps 足够顺,也省电。 */
        private const val PROGRESS_INTERVAL_MS = 100L
    }
}
