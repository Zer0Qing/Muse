package io.zer0.muse.ui.speech

import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.MediaConfig
import io.zer0.muse.ui.common.feedback.MuseToast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 8.7: 文本转语音管理器(Android 系统 TextToSpeech)。
 *
 * 设计选择: 用 Android 框架自带的 TextToSpeech 引擎,0 APK 体积,
 * 与现有 [SpeechInput](系统 Intent ASR)对称 — 系统服务负责输入/输出。
 *
 * v1.4 改造(参考 rikkahub SystemTTSProvider + TtsController):
 *  - 不再用 TextToSpeech.speak() 实时播放(无暂停/进度),
 *    改用 synthesizeToFile 写临时 wav 文件 → MediaPlayer 播放
 *  - 用 [TextChunker] 按标点分片,串行合成 + 播放,支持暂停/恢复/快进/速度
 *  - 暴露 [playbackState] StateFlow 给 UI(双圆环进度:片进度 + 片内音频进度)
 *  - 保留单例 TextToSpeech(muse 已有设计,优于 rikkahub 每次新建)
 *  - 保留 [speakStream] 流式朗读 + [stripMarkdown] + [applyConfig] 媒体配置
 *
 * 调用关系:
 *  - ChatViewModel.toggleTts → [speak] (utteranceId 跟踪消息高亮)
 *  - TtsControllerWidget → [playbackState] / [pause] / [resume] / [stop] / [seekBy] / [setSpeed]
 *
 * @param context 应用 Context(用于创建 TextToSpeech 实例 + 访问 cacheDir)
 */
class TtsManager(
    context: Context,
    /** v1.97: 云端 TTS 服务(当 ttsEngine != "system" 时使用)。 */
    private val cloudTtsService: CloudTtsService,
) {

    companion object {
        // M-SP2 修复: 将 speakStream / stripMarkdown 中每次调用都新建的 Regex 提升为 companion object 常量,
        // 避免 Regex 模式编译开销(13 个 Regex × 每条消息 = 显著 GC 压力)
        private val SENTENCE_END_REGEX = Regex("[。！？.!?]")
        private val HEADING_PREFIX_REGEX = Regex("^#+\\s*")
        private val UNORDERED_LIST_REGEX = Regex("^[-*+]\\s+")
        private val ORDERED_LIST_REGEX = Regex("^\\d+\\.\\s+")
        private val QUOTE_PREFIX_REGEX = Regex("^>\\s*")
        private val IMAGE_REGEX = Regex("!\\[[^\\]]*\\]\\([^)]*\\)")
        private val LINK_REGEX = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")
        private val INLINE_CODE_REGEX = Regex("`([^`]*)`")
        private val BOLD_ASTERISK_REGEX = Regex("\\*\\*([^*]*)\\*\\*")
        private val BOLD_UNDERSCORE_REGEX = Regex("__([^_]*)__")
        private val ITALIC_ASTERISK_REGEX = Regex("\\*([^*]*)\\*")
        private val ITALIC_UNDERSCORE_REGEX = Regex("_([^_]*)_")
        private val HORIZONTAL_RULE_REGEX = Regex("---+")

        /**
         * v1.97: 支持的云端 TTS 引擎列表(engineId → 显示名字符串资源)。
         *
         * "system" 不在此列表中(由 SettingsRepository 默认值表示,UI 单独处理)。
         * 顺序决定设置页的展示顺序。
         */
        val CLOUD_TTS_ENGINES: List<Pair<String, Int>> = listOf(
            "openai" to R.string.settings_media_tts_engine_openai,
            "minimax" to R.string.settings_media_tts_engine_minimax,
            "edge" to R.string.settings_media_tts_engine_edge,
            "gemini" to R.string.settings_media_tts_engine_gemini,
            "dashscope" to R.string.settings_media_tts_engine_dashscope,
            "fish" to R.string.settings_media_tts_engine_fish,
            "elevenlabs" to R.string.settings_media_tts_engine_elevenlabs,
            "groq" to R.string.settings_media_tts_engine_groq,
            "qwen" to R.string.settings_media_tts_engine_qwen,
            "step" to R.string.settings_media_tts_engine_step,
            "xai" to R.string.settings_media_tts_engine_xai,
        )
    }

    /** 合成到文件用的 utteranceId 前缀(区分 speakStream 的真实 id)。 */
    private val SYNTH_PREFIX = "syn_"

    private val appContext = context.applicationContext
    private val cacheDir: File = appContext.cacheDir
    private val ready = AtomicBoolean(false)
    /**
     * v1.0.4 (P2): TTS 初始化就绪状态(暴露给 UI)。
     *
     * 系统 TTS 引擎异步初始化,首次点击朗读时可能尚未就绪。
     * UI 可读取此 Flow 判断是否需要提示"TTS 正在初始化"。
     * (云端 TTS 引擎不需要此判断,直接调 speak 即可)
     */
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    private var currentUtteranceId: String? = null

    /** v0.52: 流式朗读的句子缓冲(等完整句子再交给 TTS,避免 token 级断句拗口)。 */
    private val sentenceBuffer = StringBuilder()

    /** 朗读状态回调: (utteranceId, isSpeaking) — UI 据此切换图标。 */
    var onStateChange: ((utteranceId: String?, isSpeaking: Boolean) -> Unit)? = null

    /** v0.33: 当前生效的媒体配置(由 MuseApp 订阅 mediaConfigFlow 后调 [applyConfig] 更新)。 */
    @Volatile
    private var mediaConfig: MediaConfig = MediaConfig()

    /** 查询系统可用 TTS 声音列表。 */
    fun getAvailableVoices(): List<android.speech.tts.Voice> {
        return if (ready.get()) {
            @Suppress("DEPRECATION")
            tts.voices?.toList() ?: emptyList()
        } else emptyList()
    }

    /**
     * v1.99(4.4): 动态拉取云端 TTS 引擎的音色列表。
     *
     * 供 TTS 设置页音色选择器使用,失败返回空列表。
     */
    suspend fun listCloudVoices(): List<VoiceInfo> {
        val engine = mediaConfig.ttsEngine
        val provider = CloudTtsProvider.fromEngine(engine) ?: return emptyList()
        return cloudTtsService.listVoices(provider, mediaConfig.ttsApiKey, mediaConfig.ttsEndpoint)
    }

    /**
     * v1.99(4.8): 由当前 [mediaConfig] 构造 [CloudTtsConfig]。
     *
     * 各引擎按需取用对应字段;留空/默认值由 [CloudTtsService] 各实现兜底。
     */
    private fun buildCloudConfig(): CloudTtsConfig = CloudTtsConfig(
        stability = mediaConfig.ttsStability,
        similarityBoost = mediaConfig.ttsSimilarityBoost,
        emotion = mediaConfig.ttsEmotion,
        speed = mediaConfig.ttsCloudSpeed,
        responseFormat = mediaConfig.ttsResponseFormat.ifBlank { "mp3" },
    )

    /** 当前播放速度(由 [setSpeed] 设置,影响 MediaPlayer 播放速率,不影响 TTS 合成)。 */
    @Volatile
    private var playbackSpeed: Float = 1.0f

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            // 默认中文(系统 TTS 不支持时回退默认语言)
            applyLanguage(Locale.CHINESE)
            ready.set(true)
            // v1.0.4 (P2): 同步暴露给 UI
            _isReady.value = true
        } else {
            // v1.0.47: TTS init 失败多为设备无 TTS 引擎,属常见情况,从 Logger.e 降为 Logger.d 减少启动日志噪音
            Logger.d("TtsManager", "TTS init failed: status=$status")
            // v1.98: 移除 Toast 提示,静默处理(朗读功能不可用时用户自然知晓)
        }
    }

    /** v1.4: synthesizeToFile 完成回调句柄(串行,一次只合成一片)。 */
    @Volatile
    private var synthesizeDone: CompletableDeferred<Unit>? = null

    /** v1.4: 当前 MediaPlayer 实例(pause/resume/seekBy 操作目标)。 */
    @Volatile
    private var mediaPlayer: MediaPlayer? = null

    /** v1.4: 串行播放协程(合成 + 播放 + 进度更新)。 */
    private var playbackJob: Job? = null

    /** v1.4: 位置轮询协程(每 100ms 更新 positionMs)。 */
    private var positionUpdateJob: Job? = null

    // ── v1.99: 云端 TTS 流式管线(4.3 流式朗读支持云端 TTS) ──
    // 双 Channel 串联:句子队列 → 合成协程 → 文件队列 → 播放协程
    // 合成协程跑在前,播放协程消费文件,实现"边合成边播放"的流水线,
    // 第一句合成完成立即播放,同时合成第二句,显著降低首句延迟。
    private var cloudSynthChannel: Channel<String> = Channel(Channel.UNLIMITED)
    private var cloudPlayChannel: Channel<File> = Channel(Channel.UNLIMITED)
    private var cloudSynthJob: Job? = null
    private var cloudPlayJob: Job? = null
    @Volatile
    private var cloudStreamUtteranceId: String? = null

    /** v1.4: 播放协程 scope(Main 线程,MediaPlayer 必须主线程操作)。 */
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** v1.4: 当前分片列表(用于状态显示 totalChunks)。 */
    @Volatile
    private var currentChunks: List<String> = emptyList()

    /** v1.4: 播放状态 — 暴露给 UI 驱动悬浮控制器。 */
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // speakStream 路径才走 onStateChange;合成路径 synthesize 不触发 UI
                if (utteranceId == null || utteranceId.startsWith(SYNTH_PREFIX)) return
                currentUtteranceId = utteranceId
                onStateChange?.invoke(utteranceId, true)
            }

            override fun onDone(utteranceId: String?) {
                val id = utteranceId ?: return
                if (id.startsWith(SYNTH_PREFIX)) {
                    // 合成完成,唤醒等待中的 synthesizeToFile
                    synthesizeDone?.complete(Unit)
                } else {
                    // speakStream 单句播完
                    currentUtteranceId = null
                    onStateChange?.invoke(id, false)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handleSynthOrSpeakError(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleSynthOrSpeakError(utteranceId)
            }
        })
    }

    /** 统一处理合成/朗读错误回调。 */
    private fun handleSynthOrSpeakError(utteranceId: String?) {
        val id = utteranceId ?: return
        if (id.startsWith(SYNTH_PREFIX)) {
            synthesizeDone?.completeExceptionally(RuntimeException("TTS synthesize error"))
        } else {
            currentUtteranceId = null
            onStateChange?.invoke(id, false)
        }
    }

    /**
     * v0.33: 应用媒体配置(语速 / 音高 / 语言)。
     *
     * 由 MuseApp 订阅 [io.zer0.muse.data.SettingsRepository.mediaConfigFlow] 后调用,
     * 用户在「设置 → 媒体 → 语音播报」调整后立即生效(下次朗读即应用新参数)。
     *
     * @param config 新的媒体配置
     */
    fun applyConfig(config: MediaConfig) {
        mediaConfig = config
        if (ready.get()) {
            // 语速(0.5-2.0,1.0 为正常)— 影响 TTS 合成速率
            tts.setSpeechRate(config.ttsSpeechRate.coerceIn(0.5f, 2.0f))
            // 音高(0.5-2.0,1.0 为正常)
            tts.setPitch(config.ttsPitch.coerceIn(0.5f, 2.0f))
            // 语言(null 跟随系统,否则尝试切换)
            applyLanguage(config.ttsLanguage?.let { parseLocale(it) } ?: Locale.getDefault())
            // 系统 TTS 声音(仅引擎为 system 时生效)
            if (config.ttsVoiceName.isNotBlank() && config.ttsEngine == "system") {
                val voice = tts.voices?.firstOrNull { it.name == config.ttsVoiceName }
                if (voice != null) {
                    tts.setVoice(voice)
                }
            }
            Logger.d("TtsManager", "applyConfig: rate=${config.ttsSpeechRate}, pitch=${config.ttsPitch}, lang=${config.ttsLanguage}, voice=${config.ttsVoiceName}")
        }
    }

    /** 把 BCP-47 / ISO-639 字符串解析为 Locale。 */
    private fun parseLocale(tag: String): Locale {
        // 简单解析:支持 "zh"/"zh-CN"/"en-US" 等
        return runCatching {
            val parts = tag.replace("_", "-").split("-")
            if (parts.size == 1) Locale(parts[0])
            else Locale(parts[0], parts[1])
        }.getOrDefault(Locale.getDefault())
    }

    /** 应用语言(不支持时回退默认)。 */
    private fun applyLanguage(locale: Locale) {
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Logger.w("TtsManager", "Language $locale not available, fallback to default")
            tts.setLanguage(Locale.getDefault())
        }
    }

    /**
     * v1.4: 合成单片文本到临时 wav 文件。
     *
     * 用 [TextToSpeech.synthesizeToFile] 异步写入,[UtteranceProgressListener.onDone]
     * 回调唤醒等待的协程。30 秒超时兜底(避免某些 ROM 卡死回调)。
     *
     * @param text 待合成的纯文本(单片,已分片)
     * @param file 目标 wav 文件路径(调用方负责创建/清理)
     * @return true 合成成功;false 失败或超时
     */
    private suspend fun synthesizeToFile(text: String, file: File): Boolean {
        // v1.97: 云端 TTS 引擎走 CloudTtsService 路径
        if (mediaConfig.ttsEngine != "system") {
            val cloudOk = cloudTtsService.synthesizeToFile(
                text = text,
                engine = mediaConfig.ttsEngine,
                apiKey = mediaConfig.ttsApiKey,
                model = mediaConfig.ttsModel,
                voice = mediaConfig.ttsVoice,
                endpoint = mediaConfig.ttsEndpoint,
                outputFile = file,
                cloudConfig = buildCloudConfig(),
            )
            // v1.104: 云端 TTS 失败时回退到系统 TTS,避免网络断/额度用尽时朗读静默失败
            if (cloudOk) return true
            Logger.w("TtsManager", "云端 TTS 失败,回退到系统 TTS")
            if (!ready.get()) {
                Logger.w("TtsManager", "系统 TTS 也不可用,放弃合成")
                return false
            }
            return synthesizeWithSystemTts(text, file)
        }

        return synthesizeWithSystemTts(text, file)
    }

    /**
     * v1.104: 系统 TTS 合成(从 synthesizeToFile 抽出,供云端失败回退复用)。
     */
    private suspend fun synthesizeWithSystemTts(text: String, file: File): Boolean {
        if (!ready.get()) {
            Logger.w("TtsManager", "synthesizeToFile: TTS not ready")
            return false
        }
        // 确保合成参数已应用(避免首次朗读用默认值)
        if (mediaConfig.ttsSpeechRate != 1.0f || mediaConfig.ttsPitch != 1.0f || mediaConfig.ttsLanguage != null) {
            tts.setSpeechRate(mediaConfig.ttsSpeechRate.coerceIn(0.5f, 2.0f))
            tts.setPitch(mediaConfig.ttsPitch.coerceIn(0.5f, 2.0f))
        }
        val deferred = CompletableDeferred<Unit>()
        synthesizeDone = deferred
        val utteranceId = SYNTH_PREFIX + System.nanoTime()
        // synthesizeToFile 重载:(CharSequence, Bundle, File, String) — 传 File 而非路径
        val result = tts.synthesizeToFile(text, Bundle(), file, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            synthesizeDone = null
            Logger.w("TtsManager", "synthesizeToFile failed: result=$result")
            return false
        }
        return try {
            // 30 秒超时兜底(单片 150 字符通常数秒内完成)
            val ok = withTimeoutOrNull(30_000) { deferred.await() } != null
            if (!ok) Logger.w("TtsManager", "synthesizeToFile timeout: ${text.take(20)}...")
            ok
        } catch (e: Exception) {
            Logger.e("TtsManager", "synthesizeToFile error: ${e.message}")
            false
        } finally {
            synthesizeDone = null
        }
    }

    /**
     * 朗读文本(v1.4 改造:分片 + 合成到文件 + MediaPlayer 串行播放)。
     *
     * 兼容旧调用方:保留 utteranceId 参数(用于 UI 高亮当前播放消息)。
     * 内部用 [TextChunker] 按标点分片,串行合成 + 播放,支持暂停/恢复/快进/速度。
     *
     * @param text 待朗读的纯文本(含 Markdown 会被 [stripMarkdown] 清理)
     * @param utteranceId 跟踪 id(通常传消息 id,用于 UI 切换图标)
     * @param flush true 打断旧请求(默认);false 排队追加(暂未使用,保留接口)
     * @return true 已开始播放;false TTS 未就绪或文本为空
     */
    fun speak(text: String, utteranceId: String, flush: Boolean = true): Boolean {
        // v1.97: 云端 TTS 不需要等待系统 TTS 就绪
        if (mediaConfig.ttsEngine == "system" && !ready.get()) {
            Logger.w("TtsManager", "TTS not ready yet")
            return false
        }
        // v0.52: 完整朗读时清空流式缓冲,避免残留文本串入
        sentenceBuffer.clear()
        val clean = stripMarkdown(text).trim()
        if (clean.isEmpty()) return false
        // 新请求打断旧请求(flush 默认 true)
        if (flush) stopInternal(resetState = false)
        currentUtteranceId = utteranceId
        // 用 TextChunker 按标点分片
        val chunks = TextChunker.chunk(clean)
        if (chunks.isEmpty()) return false
        currentChunks = chunks
        // 标记朗读开始(UI 高亮)
        onStateChange?.invoke(utteranceId, true)
        // 启动串行播放协程
        startPlayback(chunks, utteranceId)
        return true
    }

    /**
     * v1.4: 启动串行播放协程(合成一片 → 播放一片 → 下一片)。
     *
     * 协程在 [playbackScope] (Main 线程)执行,MediaPlayer 操作必须主线程。
     * 协程被 cancel 时(stop / 新 speak),MediaPlayer 释放、临时文件清理。
     */
    private fun startPlayback(chunks: List<String>, utteranceId: String) {
        playbackJob?.cancel()
        startPositionUpdates()
        playbackJob = playbackScope.launch {
            updateState {
                it.copy(
                    status = PlaybackStatus.Playing,
                    currentChunkIndex = 0,
                    totalChunks = chunks.size,
                    positionMs = 0L,
                    durationMs = 0L,
                )
            }
            for ((index, chunk) in chunks.withIndex()) {
                if (!isActive) return@launch
                updateState { it.copy(currentChunkIndex = index, positionMs = 0L, durationMs = 0L) }
                // 合成到临时 wav 文件
                val file = File(cacheDir, "tts_chunk_${utteranceId}_$index.wav")
                // L-SP3 修复: 用 try-finally 确保临时 wav 文件在任何路径下都被清理,
                // 含: 合成失败(continue)、协程取消(CancellationException 传播时 finally 执行)、
                // 正常播放完成。原先仅在各分支末尾手动 file.delete(),取消路径会漏删。
                try {
                    val ok = synthesizeToFile(chunk, file)
                    if (!ok) continue
                    if (!isActive) return@launch
                    // 用 MediaPlayer 播放(暂停时协程挂起在 await completion)
                    playWithMediaPlayer(file)
                } finally {
                    file.delete()
                }
            }
            // 全部播完
            updateState { it.copy(status = PlaybackStatus.Ended, positionMs = it.durationMs) }
            currentUtteranceId = null
            onStateChange?.invoke(utteranceId, false)
        }
    }

    /**
     * v1.4: 用 MediaPlayer 播放临时 wav 文件(挂起到播放完成)。
     *
     * - prepareAsync 异步准备 → onPrepared 回调里 start
     * - 应用当前 [playbackSpeed]
     * - onCompletion 回调 resume 协程,进入下一片
     * - 协程 cancel 时(onCancellation)释放 MediaPlayer
     *
     * @param file 待播放的 wav 文件
     */
    private suspend fun playWithMediaPlayer(file: File) {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer()
            mediaPlayer = mp
            try {
                mp.setDataSource(file.absolutePath)
                mp.setOnPreparedListener { player ->
                    // 应用播放速度(API 23+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        runCatching {
                            player.playbackParams = player.playbackParams.setSpeed(playbackSpeed)
                        }.onFailure { Logger.w("TtsManager", "apply playback speed failed: ${it.message}", it) }
                    }
                    player.start()
                    updateState { it.copy(durationMs = player.duration.toLong()) }
                }
                mp.setOnCompletionListener { player ->
                    player.release()
                    if (mediaPlayer === player) mediaPlayer = null
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                }
                mp.setOnErrorListener { player, what, extra ->
                    Logger.e("TtsManager", "MediaPlayer error: what=$what extra=$extra")
                    // v1.68: 播放出错时告知用户,否则朗读突然停止无任何提示
                    MuseToast.show(appContext.getString(R.string.speech_tts_play_error), 2500)
                    runCatching { player.release() }
                    if (mediaPlayer === player) mediaPlayer = null
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    true
                }
                mp.prepareAsync()
            } catch (e: Exception) {
                Logger.e("TtsManager", "playWithMediaPlayer setup failed: ${e.message}")
                runCatching { mp.release() }
                if (mediaPlayer === mp) mediaPlayer = null
                if (cont.isActive) cont.resumeWith(Result.success(Unit))
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                runCatching { mp.release() }
                if (mediaPlayer === mp) mediaPlayer = null
            }
        }
    }

    /** v1.4: 启动位置轮询协程(每 100ms 更新 positionMs,供进度条刷新)。 */
    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = playbackScope.launch {
            while (isActive) {
                mediaPlayer?.let { mp ->
                    runCatching {
                        val pos = mp.currentPosition.toLong()
                        updateState { it.copy(positionMs = pos) }
                    }.onFailure { Logger.w("TtsManager", "position update failed: ${it.message}", it) }
                }
                delay(100)
            }
        }
    }

    /** v1.4: 暂停播放(MediaPlayer.pause 暂停音频,协程挂起在 await completion)。 */
    fun pause() {
        mediaPlayer?.let { mp ->
            runCatching {
                if (mp.isPlaying) {
                    mp.pause()
                    updateState {
                        it.copy(status = PlaybackStatus.Paused, positionMs = mp.currentPosition.toLong())
                    }
                }
            }.onFailure { Logger.w("TtsManager", "pause failed: ${it.message}", it) }
        }
    }

    /** v1.4: 恢复播放(MediaPlayer.start 继续,协程自动恢复串行)。 */
    fun resume() {
        mediaPlayer?.let { mp ->
            runCatching {
                if (!mp.isPlaying) {
                    mp.start()
                    updateState { it.copy(status = PlaybackStatus.Playing) }
                }
            }.onFailure { Logger.w("TtsManager", "resume failed: ${it.message}", it) }
        }
    }

    /** v1.4: 快进/快退(相对当前位置 seek,[ms] 正数快进、负数快退)。 */
    fun seekBy(ms: Int) {
        mediaPlayer?.let { mp ->
            runCatching {
                val target = (mp.currentPosition + ms).coerceIn(0, mp.duration.coerceAtLeast(0))
                mp.seekTo(target)
                updateState { it.copy(positionMs = target.toLong()) }
            }.onFailure { Logger.w("TtsManager", "seekBy failed: ${it.message}", it) }
        }
    }

    /** v1.4: 绝对位置 seek(毫秒)。 */
    fun seekTo(ms: Long) {
        mediaPlayer?.let { mp ->
            runCatching {
                val target = ms.coerceIn(0, mp.duration.coerceAtLeast(0).toLong())
                mp.seekTo(target.toInt())
                updateState { it.copy(positionMs = target) }
            }.onFailure { Logger.w("TtsManager", "seekTo failed: ${it.message}", it) }
        }
    }

    /**
     * 跳到下一片(如果存在)。
     * 用于 TTS 控制器的长文本分段导航。
     */
    fun nextChunk() {
        val state = _playbackState.value
        val chunks = currentChunks
        if (chunks.isEmpty() || state.currentChunkIndex >= chunks.size - 1) return
        // 取消当前片播放,启动下一片
        stopPlayback()
        startPlaybackFromIndex(chunks, state.currentChunkIndex + 1)
    }

    /**
     * 跳到上一片(如果存在)。
     */
    fun previousChunk() {
        val state = _playbackState.value
        val chunks = currentChunks
        if (chunks.isEmpty() || state.currentChunkIndex <= 0) return
        stopPlayback()
        startPlaybackFromIndex(chunks, state.currentChunkIndex - 1)
    }

    /** 从指定分片索引开始串行播放(供 nextChunk/previousChunk 复用)。 */
    private fun startPlaybackFromIndex(chunks: List<String>, startIndex: Int) {
        val utteranceId = currentUtteranceId ?: return
        startPositionUpdates()
        playbackJob = playbackScope.launch {
            updateState {
                it.copy(
                    status = PlaybackStatus.Playing,
                    currentChunkIndex = startIndex,
                    totalChunks = chunks.size,
                    positionMs = 0L,
                    durationMs = 0L,
                )
            }
            for (index in startIndex until chunks.size) {
                if (!isActive) return@launch
                updateState { it.copy(currentChunkIndex = index, positionMs = 0L, durationMs = 0L) }
                val file = File(cacheDir, "tts_chunk_${utteranceId}_$index.wav")
                try {
                    val ok = synthesizeToFile(chunks[index], file)
                    if (!ok) continue
                    if (!isActive) return@launch
                    playWithMediaPlayer(file)
                } finally {
                    file.delete()
                }
            }
            updateState { it.copy(status = PlaybackStatus.Ended, positionMs = it.durationMs) }
            currentUtteranceId = null
            onStateChange?.invoke(utteranceId, false)
        }
    }

    /**
     * v1.4: 设置播放速度(影响 MediaPlayer 播放速率,不影响 TTS 合成语速)。
     *
     * @param speed 0.8 / 1.0 / 1.2 / 1.5 等(API 23+ 支持)
     */
    fun setSpeed(speed: Float) {
        playbackSpeed = speed
        mediaPlayer?.let { mp ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching {
                    mp.playbackParams = mp.playbackParams.setSpeed(speed)
                }.onFailure { Logger.w("TtsManager", "setSpeed failed: ${it.message}", it) }
            }
        }
    }

    /**
     * v0.52: 流式追加文本并按句朗读。
     *
     * 把增量 chunk 追加到缓冲区,遇到完整句子(以 。！？.!? 结尾)就切出交给 TTS,
     * 用 QUEUE_ADD 顺序排队,避免 token 级碎片化朗读(听起来更自然)。
     *
     * v1.99(4.3): 云端 TTS 引擎走双 Channel 流水线(合成协程 → 播放协程),
     * 第一句合成完成立即播放,同时合成第二句,显著降低首句延迟。系统 TTS 仍走
     * TextToSpeech.speak() 队列路径。
     *
     * 注意:流式朗读与 [speak] 的新实现互斥 — 调用 [speak] 会 stop 流式缓冲,
     * 调用 [speakStream] 会 stop 新播放。
     *
     * @param chunk 流式增量文本(可能含 Markdown,按句切出后会剥离)
     * @param utteranceId 跟踪 id(用于 UI 切换图标)
     */
    fun speakStream(chunk: String, utteranceId: String) {
        if (chunk.isEmpty()) return
        // 云端 TTS 路径:双 Channel 流水线
        if (mediaConfig.ttsEngine != "system") {
            // 新消息打断旧流(不同 utteranceId 视为新一段流式朗读)
            if (cloudStreamUtteranceId != null && cloudStreamUtteranceId != utteranceId) {
                stopCloudStream()
            }
            // 切到流式朗读前先停掉分片播放(MediaPlayer)
            stopPlayback()
            ensureCloudPipeline(utteranceId)
            sentenceBuffer.append(chunk)
            // 按句切分,送入合成 Channel
            while (true) {
                val match = SENTENCE_END_REGEX.find(sentenceBuffer) ?: break
                val end = match.range.last + 1
                val sentence = sentenceBuffer.substring(0, end)
                sentenceBuffer.delete(0, end)
                val clean = stripMarkdown(sentence).trim()
                if (clean.isNotEmpty() && !cloudSynthChannel.isClosedForSend) {
                    cloudSynthChannel.trySend(clean)
                }
            }
            return
        }
        // 系统 TTS 路径
        if (!ready.get()) {
            Logger.w("TtsManager", "TTS not ready yet (stream)")
            return
        }
        // 切到流式朗读前先停掉分片播放(MediaPlayer)
        stopPlayback()
        sentenceBuffer.append(chunk)
        // M-SP2: 句子结尾正则已提升为 companion object 常量 SENTENCE_END_REGEX
        while (true) {
            val match = SENTENCE_END_REGEX.find(sentenceBuffer) ?: break
            val end = match.range.last + 1
            val sentence = sentenceBuffer.substring(0, end)
            sentenceBuffer.delete(0, end)
            val clean = stripMarkdown(sentence).trim()
            if (clean.isNotEmpty()) {
                // 首句打断旧请求,后续用 QUEUE_ADD 顺序排队
                val mode = if (currentUtteranceId == null) TextToSpeech.QUEUE_FLUSH
                           else TextToSpeech.QUEUE_ADD
                currentUtteranceId = utteranceId
                tts.speak(clean, mode, null, utteranceId)
            }
        }
    }

    /**
     * v1.99(4.3): 确保云端流式管线运行。
     *
     * 双 Channel 串联:
     *  - 合成协程:从 [cloudSynthChannel] 取句子 → [synthesizeToFile] → 文件送入 [cloudPlayChannel]
     *  - 播放协程:从 [cloudPlayChannel] 取文件 → [playWithMediaPlayer] → 删除文件
     *
     * 合成协程跑在前(UNLIMITED 容量 Channel 背压不阻塞),播放协程消费,
     * 自然实现"边合成边播放"流水线。flushStream 关闭 [cloudSynthChannel]
     * 触发合成协程退出 → 关闭 [cloudPlayChannel] → 播放协程退出 → 触发 onStateChange(false)。
     */
    private fun ensureCloudPipeline(utteranceId: String) {
        if (cloudSynthJob?.isActive == true && !cloudSynthChannel.isClosedForSend) {
            // 管线运行中,仅更新 utterance id(追加模式)
            cloudStreamUtteranceId = utteranceId
            return
        }
        // 取消残留的旧协程(可能处于 close 后的收尾阶段)
        cloudSynthJob?.cancel()
        cloudPlayJob?.cancel()
        // 重建 Channel(旧的已 close)
        cloudSynthChannel = Channel(Channel.UNLIMITED)
        cloudPlayChannel = Channel(Channel.UNLIMITED)
        cloudStreamUtteranceId = utteranceId
        currentUtteranceId = utteranceId
        onStateChange?.invoke(utteranceId, true)
        startPositionUpdates()
        val uid = utteranceId
        cloudSynthJob = playbackScope.launch {
            try {
                for (sentence in cloudSynthChannel) {
                    if (!isActive) break
                    val file = File(cacheDir, "tts_stream_${uid}_${System.nanoTime()}.mp3")
                    val ok = try {
                        synthesizeToFile(sentence, file)
                    } catch (e: Exception) {
                        Logger.w("TtsManager", "cloud stream synth failed: ${e.message}")
                        false
                    }
                    if (ok) {
                        cloudPlayChannel.send(file)
                    } else {
                        file.delete()
                        // v1.140: 合成失败(云端 + 单片系统 TTS 兜底均已失败)→
                        // 收集剩余未播放文本,降级到系统 TTS 直播,避免半句后静默中断。
                        // synthesizeToFile 已对单片做过 cloud→system 兜底,这里走 tts.speak()
                        // 直播路径(不经文件 / MediaPlayer),即便文件合成回调卡住仍可能成功。
                        val remaining = collectRemainingStreamText(sentence)
                        if (remaining.isNotEmpty()) {
                            streamFallbackToSystemTts(remaining, uid)
                        }
                        break
                    }
                }
            } finally {
                // 合成协程结束(Channel close 或取消)→ 关闭播放 Channel,触发播放协程收尾
                cloudPlayChannel.close()
            }
        }
        cloudPlayJob = playbackScope.launch {
            for (file in cloudPlayChannel) {
                if (!isActive) break
                try {
                    playWithMediaPlayer(file)
                } catch (e: Exception) {
                    Logger.w("TtsManager", "cloud stream play failed: ${e.message}")
                } finally {
                    file.delete()
                }
            }
            // 全部播完,触发 UI 收起高亮
            // v1.140: 若已切换到系统 TTS 兜底, currentUtteranceId 仍为 uid,
            //         由 UtteranceProgressListener.onDone 负责清理;此处跳过避免提前清状态。
            if (cloudStreamUtteranceId == uid && currentUtteranceId == uid) {
                currentUtteranceId = null
                cloudStreamUtteranceId = null
                onStateChange?.invoke(uid, false)
            }
        }
    }

    /**
     * v1.140: 收集流式朗读中尚未播放的剩余文本。
     *
     * 调用时机:[ensureCloudPipeline] 合成协程某片失败后,需要把:
     *  - 当前失败的句子 [failedSentence]
     *  - 仍在 [cloudSynthChannel] 中排队未合成的句子
     *  - 缓冲区 [sentenceBuffer] 中尚未切句的残余文本
     * 全部收集起来,交给系统 TTS 一次性播报,避免半句中断。
     */
    private fun collectRemainingStreamText(failedSentence: String): String {
        val collected = StringBuilder(failedSentence)
        // 1. 抽干 synth Channel 中排队的句子
        while (true) {
            val next = cloudSynthChannel.tryReceive().getOrNull() ?: break
            collected.append(next)
        }
        // 2. 把缓冲区残余文本也带上(可能含未到句号的尾部)
        if (sentenceBuffer.isNotEmpty()) {
            collected.append(sentenceBuffer)
            sentenceBuffer.clear()
        }
        return collected.toString().trim()
    }

    /**
     * v1.140: 流式合成失败后,降级到系统 TextToSpeech 直播剩余文本。
     *
     * 与 [synthesizeWithSystemTts] 的区别:
     *  - synthesizeWithSystemTts 走 tts.synthesizeToFile → 写 wav → MediaPlayer 播放,
     *    某些 ROM 上合成回调可能卡住导致 30s 超时。
     *  - 本方法直接调 tts.speak(),由系统 TTS 引擎自行播报,绕过文件路径,
     *    在 synthesizeToFile 失败时仍可能成功。
     *
     * 时序处理:
     *  - 若系统 TTS 未就绪,日志告警并放弃(无法降级)。
     *  - 若已就绪,先停掉云端管线与 MediaPlayer,再用 tts.speak 直播。
     *  - UtteranceProgressListener.onDone 会触发 onStateChange(uid, false),无需在此处清理。
     *
     * @param remainingText 剩余待播报的纯文本(已剥离 Markdown)
     * @param utteranceId 跟踪 id,用于 UI 高亮与 onDone 回调
     */
    private fun streamFallbackToSystemTts(remainingText: String, utteranceId: String) {
        if (remainingText.isBlank()) return
        if (!ready.get()) {
            Logger.w("TtsManager", "stream fallback: 系统 TTS 未就绪,放弃降级")
            // 仍清掉云端管线状态,避免悬挂
            stopCloudStream()
            stopPlayback()
            currentUtteranceId = null
            cloudStreamUtteranceId = null
            onStateChange?.invoke(utteranceId, false)
            return
        }
        Logger.i("TtsManager", "stream fallback to system TTS: ${remainingText.length} chars")
        // 1. 停掉云端管线(关闭 Channel + 取消协程)
        stopCloudStream()
        // 2. 停掉 MediaPlayer 播放,避免与系统 TTS 直播重叠
        stopPlayback()
        // 3. 应用语言(系统 TTS 默认中文,fallback 也走中文)
        applyLanguage(Locale.CHINESE)
        // 4. 标记朗读状态(复用 utteranceId,UI 高亮保持不变)
        currentUtteranceId = utteranceId
        cloudStreamUtteranceId = utteranceId
        onStateChange?.invoke(utteranceId, true)
        // 5. 直播剩余文本(QUEUE_FLUSH 打断残留队列)
        val result = tts.speak(remainingText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Logger.w("TtsManager", "stream fallback tts.speak failed: result=$result")
            currentUtteranceId = null
            cloudStreamUtteranceId = null
            onStateChange?.invoke(utteranceId, false)
            return
        }
        // 6. UI 提示用户已切换系统语音
        MuseToast.show(appContext.getString(R.string.speech_tts_fallback_to_system), 2500)
        // 注: onDone / onError 由 UtteranceProgressListener 触发,
        //     走 handleSynthOrSpeakError 或 speakStream 单句播完路径清理 currentUtteranceId。
    }

    /** v1.99(4.3): 停止云端流式管线(取消协程 + 关闭 Channel)。 */
    private fun stopCloudStream() {
        cloudSynthJob?.cancel()
        cloudPlayJob?.cancel()
        cloudSynthJob = null
        cloudPlayJob = null
        runCatching { cloudSynthChannel.close() }
        runCatching { cloudPlayChannel.close() }
        cloudStreamUtteranceId = null
    }

    /**
     * v0.52: 冲刷流式缓冲区剩余文本(流式朗读结束后调用)。
     *
     * 把缓冲区里未到句号的残余文本交给 TTS 读完。
     */
    fun flushStream() {
        // 云端 TTS:把残余文本送入管线后关闭合成 Channel,触发自然收尾
        if (mediaConfig.ttsEngine != "system") {
            if (sentenceBuffer.isNotEmpty()) {
                val clean = stripMarkdown(sentenceBuffer.toString()).trim()
                sentenceBuffer.clear()
                if (clean.isNotEmpty()) {
                    if (cloudSynthJob?.isActive != true || cloudSynthChannel.isClosedForSend) {
                        // 管线未启动(仅 flushStream 被调用),临时启动一个
                        val uid = currentUtteranceId ?: "flush"
                        ensureCloudPipeline(uid)
                    }
                    if (!cloudSynthChannel.isClosedForSend) {
                        cloudSynthChannel.trySend(clean)
                    }
                }
            }
            // 关闭合成 Channel,让管线在消费完最后一句后自然结束
            runCatching { cloudSynthChannel.close() }
            return
        }
        // 系统 TTS 路径
        if (sentenceBuffer.isEmpty()) return
        val clean = stripMarkdown(sentenceBuffer.toString()).trim()
        sentenceBuffer.clear()
        if (clean.isNotEmpty() && ready.get()) {
            val mode = if (currentUtteranceId == null) TextToSpeech.QUEUE_FLUSH
                       else TextToSpeech.QUEUE_ADD
            tts.speak(clean, mode, null, currentUtteranceId ?: "flush")
        }
    }

    /**
     * v1.99(4.7): 朗读 SSML 标记文本。
     *
     * 解析 SSML 标签(<break>、<prosody rate="slow">、<emphasis> 等):
     *  - <break time="Xs"/> → 转为对应标点停顿(≥1s 句号,否则逗号)
     *  - <prosody rate="slow|fast|1.5"> → 提取倍率,临时覆盖 [playbackSpeed](系统 TTS
     *    还会同步 setSpeechRate)与音高
     *  - <emphasis> → 提升音高(系统 TTS)
     *  - 其他标签(<speak>、<s>、<phoneme> 等)剥离为纯文本
     *
     * 不支持 SSML 的云端引擎降级为纯文本播放。播放结束后自动恢复原速率/音高。
     *
     * @param ssml SSML 文本
     * @param utteranceId 跟踪 id
     * @param flush true 打断旧请求(默认)
     * @return true 已开始播放;false 文本为空
     */
    fun speakSsml(ssml: String, utteranceId: String, flush: Boolean = true): Boolean {
        val parsed = parseSsml(ssml)
        if (parsed.plainText.isEmpty()) return false
        val rateOverride = parsed.rate
        val prevSpeed = playbackSpeed
        // 速率:云端 TTS 影响 MediaPlayer 播放速率;系统 TTS 影响 setSpeechRate
        if (rateOverride != null) {
            playbackSpeed = (prevSpeed * rateOverride).coerceIn(0.5f, 2f)
        }
        if (mediaConfig.ttsEngine == "system" && ready.get()) {
            val effRate = (mediaConfig.ttsSpeechRate * (rateOverride ?: 1f)).coerceIn(0.5f, 2f)
            val effPitch = (mediaConfig.ttsPitch * (parsed.pitch ?: 1f)).coerceIn(0.5f, 2f)
            runCatching {
                tts.setSpeechRate(effRate)
                tts.setPitch(effPitch)
            }.onFailure { Logger.w("TtsManager", "set speech rate/pitch failed: ${it.message}", it) }
        }
        val ok = speak(parsed.plainText, utteranceId, flush)
        // 播放结束后恢复原速率/音高
        if (rateOverride != null || parsed.pitch != null) {
            playbackScope.launch {
                runCatching { playbackJob?.join() }
                playbackSpeed = prevSpeed
                if (mediaConfig.ttsEngine == "system" && ready.get()) {
                    runCatching {
                        tts.setSpeechRate(mediaConfig.ttsSpeechRate.coerceIn(0.5f, 2f))
                        tts.setPitch(mediaConfig.ttsPitch.coerceIn(0.5f, 2f))
                    }.onFailure { Logger.w("TtsManager", "restore speech rate/pitch failed: ${it.message}", it) }
                }
            }
        }
        return ok
    }

    /**
     * v1.99(4.7): SSML 解析结果。
     *
     * @property plainText 剥离标签后的纯文本(break 已转为标点)
     * @property rate prosody rate 倍率(null 表示未指定)
     * @property pitch prosody pitch 倍率(null 表示未指定)
     */
    private data class SsmlParsed(
        val plainText: String,
        val rate: Float? = null,
        val pitch: Float? = null,
    )

    /**
     * v1.99(4.7): 简易 SSML 解析器。
     *
     * 仅处理常见标签(break / prosody rate|pitch / emphasis),其余标签直接剥离。
     * 不依赖 XML 解析器,正则实现,容错性强(对不规范 SSML 也能降级为纯文本)。
     */
    private fun parseSsml(ssml: String): SsmlParsed {
        var text = ssml
            .replace(Regex("<\\?xml[^>]*\\?>"), "")
            .replace(Regex("</?speak[^>]*>"), "")
        var rate: Float? = null
        var pitch: Float? = null
        // prosody rate="..."
        val prosodyRateRegex = Regex(
            "<prosody[^>]*rate=[\"']([^\"']+)[\"'][^>]*>(.*?)</prosody>",
            RegexOption.DOT_MATCHES_ALL,
        )
        text = prosodyRateRegex.replace(text) { m ->
            rate = parseProsodyValue(m.groupValues[1])
            m.groupValues[2]
        }
        // prosody pitch="..."
        val prosodyPitchRegex = Regex(
            "<prosody[^>]*pitch=[\"']([^\"']+)[\"'][^>]*>(.*?)</prosody>",
            RegexOption.DOT_MATCHES_ALL,
        )
        text = prosodyPitchRegex.replace(text) { m ->
            pitch = parseProsodyValue(m.groupValues[1])
            m.groupValues[2]
        }
        // 剩余 prosody(无 rate/pitch)直接保留内容
        text = text.replace(Regex("<prosody[^>]*>(.*?)</prosody>", RegexOption.DOT_MATCHES_ALL), "$1")
        // emphasis → 保留内容(系统 TTS 通过音高提升体现,这里不重复施加)
        text = text.replace(Regex("<emphasis[^>]*>(.*?)</emphasis>", RegexOption.DOT_MATCHES_ALL), "$1")
        // break → 转为标点停顿(≥1s 用句号,否则逗号;无 time 用逗号)
        text = text.replace(Regex("<break[^>]*/?>")) { m ->
            val timeMatch = Regex("time=[\"']([\\d.]+)\\s*(s|ms)[\"']").find(m.value)
            if (timeMatch != null) {
                val amt = timeMatch.groupValues[1].toFloatOrNull() ?: 0f
                val unit = timeMatch.groupValues[2]
                val secs = if (unit == "ms") amt / 1000f else amt
                if (secs >= 1f) "。" else "，"
            } else "，"
        }
        // 剥离其余未知标签
        text = text.replace(Regex("<[^>]+>"), "")
        // 解码基本 XML 实体
        text = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
        return SsmlParsed(text.trim(), rate, pitch)
    }

    /** 解析 prosody rate/pitch 值(slow/medium/fast/百分比/小数)。 */
    private fun parseProsodyValue(raw: String): Float? = when {
        raw.equals("slow", true) -> 0.7f
        raw.equals("medium", true) -> 1.0f
        raw.equals("fast", true) -> 1.5f
        raw.equals("low", true) -> 0.8f
        raw.equals("high", true) -> 1.3f
        raw.endsWith("%") -> (raw.dropLast(1).toFloatOrNull() ?: 100f) / 100f
        else -> raw.toFloatOrNull()
    }

    /**
     * v1.99(4.6): 用克隆音色朗读文本。
     *
     * 临时把 [mediaConfig] 切到对应 provider + 克隆 voiceId,调用 [speak] 后
     * 在播放结束时恢复原配置。当前克隆 provider 仅有 elevenlabs
     * (见 [ElevenLabsVoiceCloningProvider]),其 voiceId 可直接作为 TTS voice。
     *
     * @param text 待朗读文本
     * @param utteranceId 跟踪 id
     * @param voiceId 克隆音色 id(VoiceCloningService.cloneVoice 返回值)
     * @param providerId 克隆服务商(默认 elevenlabs)
     */
    fun speakWithClonedVoice(
        text: String,
        utteranceId: String,
        voiceId: String,
        providerId: String = "elevenlabs",
    ): Boolean {
        if (voiceId.isBlank()) return false
        val original = mediaConfig
        // 仅当当前引擎不是该 provider,或当前 voice 不是该克隆音色时才覆盖
        val needOverride = original.ttsEngine != providerId || original.ttsVoice != voiceId
        if (needOverride) {
            applyConfig(original.copy(ttsEngine = providerId, ttsVoice = voiceId))
        }
        val ok = speak(text, utteranceId, flush = true)
        if (needOverride) {
            playbackScope.launch {
                runCatching { playbackJob?.join() }
                applyConfig(original)
            }
        }
        return ok
    }

    /** 停止当前朗读(同时停分片播放 + 流式朗读)。 */
    fun stop() {
        stopInternal(resetState = true)
    }

    /** v1.4: 内部停止 — resetState=true 时把 playbackState 重置为 Idle(对外停止)。 */
    private fun stopInternal(resetState: Boolean) {
        stopPlayback()
        stopCloudStream()
        // 停流式朗读
        if (currentUtteranceId != null) {
            tts.stop()
            onStateChange?.invoke(null, false)
        }
        currentUtteranceId = null
        sentenceBuffer.clear()
        if (resetState) {
            updateState { PlaybackState() }
        }
    }

    /** v1.4: 停止分片播放(取消协程 + 释放 MediaPlayer)。 */
    private fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        positionUpdateJob?.cancel()
        positionUpdateJob = null
        mediaPlayer?.let { mp ->
            runCatching { mp.release() }
        }
        mediaPlayer = null
    }

    /** 当前是否正在朗读。 */
    fun isSpeaking(): Boolean = currentUtteranceId != null

    /** 当前朗读的 utterance id(用于 UI 高亮正在播放的消息)。 */
    fun currentUtterance(): String? = currentUtteranceId

    /** v1.4: 更新播放状态(线程安全 update)。 */
    private fun updateState(block: (PlaybackState) -> PlaybackState) {
        _playbackState.update(block)
    }

    /**
     * 剥离 Markdown 标记,生成适合朗读的纯文本。
     * - 去掉围栏代码块 ```...```(代码不朗读)
     * - 去掉行内代码 `...` 反引号
     * - 去掉粗体 **...** / 斜体 *...* 标记符
     * - 去掉标题 # 前缀
     * - 去掉列表 - / 1. 前缀
     * - 去掉链接 [text](url) 只保留 text
     * - 去掉引用 > 前缀
     * - 去掉图片 ![]() / 公式 $$...$$
     */
    private fun stripMarkdown(text: String): String {
        val sb = StringBuilder()
        val lines = text.split("\n")
        var inCodeBlock = false
        var inFormula = false
        for (line in lines) {
            val trimmed = line.trim()
            // 围栏代码块开关
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock
                continue
            }
            if (inCodeBlock) continue // 代码块内容跳过
            // 公式块 $$ ... $$(单行或多行)
            if (trimmed.startsWith("$$")) {
                if (trimmed.endsWith("$$") && trimmed.length > 2) {
                    // 单行公式,跳过
                    continue
                }
                inFormula = !inFormula
                continue
            }
            if (inFormula) continue
            // 引用 > 前缀
            // M-SP2: 以下 Regex 均已提升为 companion object 常量,避免每次调用重新编译模式
            val cleaned = trimmed
                .replace(HEADING_PREFIX_REGEX, "")           // 标题
                .replace(UNORDERED_LIST_REGEX, "")           // 无序列表
                .replace(ORDERED_LIST_REGEX, "")             // 有序列表
                .replace(QUOTE_PREFIX_REGEX, "")             // 引用
                .replace(IMAGE_REGEX, "")                    // 图片
                .replace(LINK_REGEX, "$1")                   // 链接保留 text
                .replace(INLINE_CODE_REGEX, "$1")            // 行内代码
                .replace(BOLD_ASTERISK_REGEX, "$1")          // 粗体 **
                .replace(BOLD_UNDERSCORE_REGEX, "$1")        // 粗体 _
                .replace(ITALIC_ASTERISK_REGEX, "$1")        // 斜体 *
                .replace(ITALIC_UNDERSCORE_REGEX, "$1")      // 斜体 _
                .replace(HORIZONTAL_RULE_REGEX, "")          // 水平线
            if (cleaned.isNotBlank()) {
                sb.appendLine(cleaned)
            }
        }
        return sb.toString().trim()
    }

    /** 释放资源(应用退出时调用)。 */
    fun shutdown() {
        stopInternal(resetState = true)
        // H-SP1 修复: 取消 playbackScope 自身(SupervisorJob),而非仅取消其子 Job。
        // 原先只 stopInternal 取消 playbackJob/positionUpdateJob,playbackScope 的 SupervisorJob
        // 仍存活,造成协程 scope 泄漏(其 Dispatchers.Main.immediate 上下文不会被回收)。
        playbackScope.cancel()
        tts.stop()
        tts.shutdown()
        currentUtteranceId = null
        sentenceBuffer.clear() // v0.52: 清空流式缓冲
        ready.set(false)
        // v1.0.4 (P2): 同步暴露给 UI
        _isReady.value = false
    }
}

/**
 * v1.4: 播放状态(暴露给 UI 驱动悬浮控制器)。
 *
 * @property status Idle/Playing/Paused/Ended
 * @property currentChunkIndex 当前分片索引(0-based)
 * @property totalChunks 总分片数
 * @property positionMs 当前片内音频位置(ms)
 * @property durationMs 当前片音频时长(ms)
 */
data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val currentChunkIndex: Int = 0,
    val totalChunks: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/** v1.4: 播放状态枚举。 */
enum class PlaybackStatus { Idle, Playing, Paused, Ended }
