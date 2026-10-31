package io.zer0.muse.ui.speech

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.zer0.common.Logger
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
 * 复位后清 0。
 */
data class VoicePlaybackState(
    val uri: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
)

/**
 * 播放引擎端口。
 *
 * 抽出接口是为了让语音条能在预览/Robolectric 里构造：真机走 [MediaPlayerVoiceAudioPlayer]，
 * 测试给假实现即可断言"被调了几次、有没有被停掉"，不必碰真实音频栈。
 */
interface VoiceAudioPlayer {
    /**
     * 装载并开始播放。
     *
     * @param onCompletion 自然播放到结尾（不含 [release] 引起的停止）
     * @param onError 解码/装载失败
     * @return 装载是否成功；false 时调用方会复位到空闲态
     */
    fun load(file: File, onCompletion: () -> Unit, onError: (Throwable) -> Unit): Boolean

    /** 从暂停位置继续播放。 */
    fun start()

    /** 暂停，保留播放位置以便继续。 */
    fun pause()

    /** 当前播放位置（毫秒）。 */
    fun positionMs(): Long

    /** 释放底层资源。 */
    fun release()
}

/** 基于系统 [MediaPlayer] 的实现（不引入第三方播放器依赖）。 */
class MediaPlayerVoiceAudioPlayer : VoiceAudioPlayer {
    private var player: MediaPlayer? = null
    private var prepared = false

    override fun load(file: File, onCompletion: () -> Unit, onError: (Throwable) -> Unit): Boolean =
        runCatching {
            release()
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { ready ->
                prepared = true
                runCatching { ready.start() }
                    .onFailure { e ->
                        Logger.w(TAG, "语音起播失败: ${e.message}")
                        onError(e)
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

    override fun release() {
        val mp = player ?: return
        player = null
        prepared = false
        runCatching { mp.release() }.onFailure { Logger.w(TAG, "语音播放器释放失败: ${it.message}") }
    }

    private companion object {
        private const val TAG = "MediaPlayerVoiceAudioPlayer"
    }
}

/**
 * 语音消息共享播放控制器。
 *
 * 全应用只有一个实例（Koin 单例）：语音条本身不持有播放器，点按只向这里要播放权，
 * 因此"同时只有一条语音在播"是结构保证，而不是各气泡之间互相协调的结果。
 *
 * - [toggle] 新语音会先停掉上一条再起播；
 * - 自然播完 / 出错 / 主动 [stop] 都会释放播放器并复位到空闲；
 * - 进度靠内部定时采样，气泡只读状态。
 *
 * 状态用 Compose 快照状态承载，读取方（气泡）自动重组，无需额外订阅。
 */
class VoicePlaybackController(
    private val playerFactory: () -> VoiceAudioPlayer = { MediaPlayerVoiceAudioPlayer() },
) {
    var state: VoicePlaybackState by mutableStateOf(VoicePlaybackState())
        private set

    private var player: VoiceAudioPlayer? = null

    /** 惰性创建：从不播放的进程（含多数单测）不会触碰 Main 调度器。 */
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var ticker: Job? = null

    /** 点按：切换同一条语音的播放/暂停；换一条则停掉上一条再起播。 */
    fun toggle(uri: String, file: File) {
        val current = state
        if (current.uri == uri) {
            if (current.isPlaying) pause() else resume(file)
            return
        }
        stop()
        play(uri, file)
    }

    /** 停掉当前语音并释放播放器（切后台/离开页面/换条时调用，可重复调用）。 */
    fun stop() {
        ticker?.cancel()
        ticker = null
        val current = player
        player = null
        current?.let { runCatching { it.release() } }
        if (state != VoicePlaybackState()) state = VoicePlaybackState()
    }

    /** 只有 [uri] 正是当前那条时才停（气泡所在页面退到后台时用，避免误停别人的）。 */
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
        state = VoicePlaybackState(uri = uri, isPlaying = true, positionMs = 0L)
        startTicker()
    }

    private fun pause() {
        ticker?.cancel()
        ticker = null
        player?.let { runCatching { it.pause() } }
        state = state.copy(isPlaying = false)
    }

    private fun resume(file: File) {
        val engine = player
        val uri = state.uri
        if (engine == null || uri == null) {
            // 播放器已被释放（例如中途被别的语音抢走）——按新起播处理
            if (uri != null) play(uri, file)
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
        private const val TAG = "VoicePlaybackController"

        /** 进度采样间隔：语音条填充不需要逐帧，10 fps 足够顺滑且省电。 */
        private const val PROGRESS_INTERVAL_MS = 100L
    }
}
