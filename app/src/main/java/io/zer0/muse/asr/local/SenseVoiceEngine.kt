package io.zer0.muse.asr.local

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * SenseVoice ONNX 推理引擎。
 *
 * 职责单一：加载模型 → 一次整段推理 → 输出 token id 序列。
 * 录音、状态机、文件管理分别在 [SenseVoiceController] / [LocalModelStore]。
 *
 * session 是进程级单例：重复加载要付约 1-5 s 的初始化成本，而模型常驻约
 * 300-400 MB 内存。空闲超时由 [release] 的调用方（idle watcher）控制，
 * 本类只保证并发安全与正确关闭。
 */
internal object SenseVoiceEngine {

    private const val TAG = "SenseVoiceEngine"

    /** ORT 推理线程数。与 RAG 侧 ONNX 会话同一量级，避免挤占 UI 线程核。 */
    private const val INTRA_OP_THREADS = 2

    /** 从模型 metadata 读出的前处理参数，加载会话时一并缓存。 */
    class ModelParams(
        val lfrWindow: Int,
        val lfrShift: Int,
        val negMean: DoubleArray,
        val invStddev: DoubleArray,
        /** 语种 token id（如 lang_zh=3）。 */
        val languageId: Int,
        /** 逆文本正则化开关的 token id（如 with_itn=14 / without_itn=15）。 */
        val withItnId: Int,
        val withoutItnId: Int,
    )

    private data class LoadedSession(
        val session: OrtSession,
        val env: OrtEnvironment,
        val params: ModelParams,
        val inputNames: Set<String>,
    )

    private val loaded = AtomicReference<LoadedSession?>(null)
    private val loadMutex = Any()

    // ---- 空闲释放 ----
    // 两条识别路径（流式控制器、小手机整段转写）共用同一个进程级 session，
    // 计时必须也只有一处：谁调了 recognize 谁就续期。放在引擎里而不是各调用方，
    // 是因为「还有谁在用模型」只有引擎知道 —— 两处各管一个计时器就会互相释放对方的 session。
    private val idleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val idleGeneration = AtomicInteger(0)

    /** 空闲多久释放模型。默认 5 分钟：一次会话里连续说话不会反复重载。 */
    private const val DEFAULT_IDLE_MS = 5 * 60_000L

    /** 模型是否已加载（可用 [recognize]）。 */
    fun isLoaded(): Boolean = loaded.get() != null

    /** 每次推理后重置空闲计时；[idleMs] 后可释放。 */
    private fun touchIdleTimer() {
        val generation = idleGeneration.incrementAndGet()
        idleScope.launch {
            delay(DEFAULT_IDLE_MS)
            // 期间又有推理发生（generation 变了）就不释放 —— 只让最后一次续期生效
            if (idleGeneration.get() == generation && isLoaded()) {
                Logger.i(TAG, "本地语音模型空闲超时，释放")
                release()
            }
        }
    }

    /**
     * 懒加载模型并读取 metadata 参数。重复调用直接复用已加载 session。
     *
     * @throws IllegalStateException 模型文件缺失或 metadata 不完整（调用方转成用户可见错误）
     */
    fun ensureLoaded(modelDir: File, languageIdFallback: Int = 3): ModelParams {
        loaded.get()?.let { return it.params }
        synchronized(loadMutex) {
            loaded.get()?.let { return it.params }
            val modelFile = File(modelDir, LocalModelStore.MODEL_FILE)
            val tokensFile = File(modelDir, LocalModelStore.TOKENS_FILE)
            require(modelFile.isFile) { "模型文件不存在: ${modelFile.absolutePath}" }
            require(tokensFile.isFile) { "词表文件不存在: ${tokensFile.absolutePath}" }

            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(INTRA_OP_THREADS)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = env.createSession(modelFile.absolutePath, opts)
            val meta = session.getMetadata().customMetadata
            val params = parseMetadata(meta, languageIdFallback)
            loaded.set(LoadedSession(session, env, params, session.inputNames.toSet()))
            Logger.i(
                TAG,
                "SenseVoice 模型已加载: lfr=${params.lfrWindow}/${params.lfrShift}, " +
                    "cmvn=${params.negMean.size}维, lang=${params.languageId}",
            )
            return params
        }
    }

    /**
     * 释放 session（空闲超时或显式释放时调用）。幂等。
     */
    fun release() {
        val current = loaded.getAndSet(null) ?: return
        synchronized(loadMutex) {
            runCatching { current.session.close() }
                .onFailure { Logger.w(TAG, "释放 SenseVoice session 失败: ${it.message}") }
        }
        Logger.i(TAG, "SenseVoice session 已释放")
    }

    /**
     * 整段推理：PCM 16-bit → token id 序列（调用方再用词表 decode）。
     *
     * 阻塞推理（30 s 音频约数百毫秒到数秒），必须在 Dispatchers.Default 上调用。
     *
     * @param withItn true=数字转阿拉伯数字（with_itn），false=保留中文数字
     */
    fun recognize(pcm: ShortArray, sampleRate: Int, withItn: Boolean): IntArray {
        val active = loaded.get() ?: throw IllegalStateException("模型未加载，先调用 ensureLoaded")
        // 先续期再推理：即使本次推理抛异常，模型也享有完整空闲窗口，不会被立刻回收
        touchIdleTimer()
        val p = active.params
        val features = SenseVoiceFrontend.compute(pcm, sampleRate, p.lfrWindow, p.lfrShift, p.negMean, p.invStddev)
        if (features.isEmpty()) return IntArray(0)
        val t = features.size
        val dim = features[0].size
        // ONNX 输入要求 [1, T, D] 的连续 float32 数组
        val flat = FloatArray(t * dim)
        for (i in 0 until t) features[i].copyInto(flat, i * dim)
        val xShape = longArrayOf(1, t.toLong(), dim.toLong())

        val inputs = HashMap<String, OnnxTensor>(4)
        var xTensor: OnnxTensor? = null
        var lenTensor: OnnxTensor? = null
        var langTensor: OnnxTensor? = null
        var itnTensor: OnnxTensor? = null
        try {
            xTensor = OnnxTensor.createTensor(active.env, java.nio.FloatBuffer.wrap(flat), xShape)
            inputs["x"] = xTensor
            lenTensor = OnnxTensor.createTensor(active.env, LongBuffer.wrap(longArrayOf(t.toLong())))
            if ("x_length" in active.inputNames) inputs["x_length"] = lenTensor
            langTensor = OnnxTensor.createTensor(active.env, LongBuffer.wrap(longArrayOf(p.languageId.toLong())))
            if ("language" in active.inputNames) inputs["language"] = langTensor
            itnTensor = OnnxTensor.createTensor(
                active.env,
                LongBuffer.wrap(longArrayOf((if (withItn) p.withItnId else p.withoutItnId).toLong())),
            )
            if ("text_norm" in active.inputNames) inputs["text_norm"] = itnTensor

            active.session.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST")
                val logits = result[0].value as? Array<Array<FloatArray>>
                    ?: throw IllegalStateException("SenseVoice 输出形状异常: ${result[0].info}")
                val frames = logits[0]
                val frameIds = IntArray(frames.size) { f ->
                    var best = 0
                    var bestVal = frames[f][0]
                    for (k in 1 until frames[f].size) {
                        if (frames[f][k] > bestVal) {
                            bestVal = frames[f][k]
                            best = k
                        }
                    }
                    best
                }
                return SenseVoiceTokens.ctcGreedy(frameIds).toIntArray()
            }
        } finally {
            // OnnxTensor 由我方创建，必须显式关闭释放 native 内存
            runCatching { xTensor?.close() }
            runCatching { lenTensor?.close() }
            runCatching { langTensor?.close() }
            runCatching { itnTensor?.close() }
        }
    }

    /** 解析模型 metadata → [ModelParams]。缺关键键时抛异常而不是静默用默认值。 */
    private fun parseMetadata(meta: Map<String, String>, languageIdFallback: Int): ModelParams {
        val lfrWindow = meta["lfr_window_size"]?.toIntOrNull()
            ?: throw IllegalStateException("模型 metadata 缺少 lfr_window_size")
        val lfrShift = meta["lfr_window_shift"]?.toIntOrNull()
            ?: throw IllegalStateException("模型 metadata 缺少 lfr_window_shift")
        val negMean = parseFloatList(meta["neg_mean"])
        val invStddev = parseFloatList(meta["inv_stddev"])
        val dim = lfrWindow * SENSE_VOICE_FRONTEND_DIM
        require(negMean.size == dim && invStddev.size == dim) {
            "CMVN 维度(${negMean.size}/${invStddev.size})与 LFR×mel($dim) 不一致"
        }
        val langZh = meta["lang_zh"]?.toIntOrNull() ?: languageIdFallback
        val withItn = meta["with_itn"]?.toIntOrNull()
            ?: throw IllegalStateException("模型 metadata 缺少 with_itn")
        val withoutItn = meta["without_itn"]?.toIntOrNull()
            ?: throw IllegalStateException("模型 metadata 缺少 without_itn")
        return ModelParams(lfrWindow, lfrShift, negMean, invStddev, langZh, withItn, withoutItn)
    }

    private fun parseFloatList(raw: String?): DoubleArray {
        require(!raw.isNullOrBlank()) { "模型 metadata 缺少 CMVN 数据" }
        return raw.split(",").map { it.trim().toDouble() }.toDoubleArray()
    }

    /** 80（mel 维度），独立常量避免与 frontend 互相暴露内部细节。 */
    private const val SENSE_VOICE_FRONTEND_DIM = 80
}
