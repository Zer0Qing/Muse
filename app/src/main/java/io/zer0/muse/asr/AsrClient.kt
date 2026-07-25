package io.zer0.muse.asr

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Phase 9.3 (M2): ASR 识别结果。
 *
 * @param text 识别出的文本
 * @param isFinal 是否是最终结果(流式 ASR 的中间结果 isFinal=false)
 * @param durationMs 音频时长(毫秒,部分 Provider 返回)
 */
data class AsrResult(
    val text: String,
    val isFinal: Boolean = true,
    val durationMs: Long = 0,
)

/**
 * Phase 9.3 (M2): ASR 客户端接口。
 *
 * 抽象不同 ASR Provider(DashScope Paraformer / Step-Audio 等),
 * 输入是 PCM 音频字节,输出是识别文本。
 *
 * 实现要点:
 *  - DashScope: 用 OkHttp WebSocket 流式发送音频块,接收 result-generated 事件
 *  - Step: 用 OpenAI 兼容 API,base64 audio 作为 input(暂为骨架,Step-Audio API 规范待完善)
 *  - SYSTEM: 不实现此接口,直接用 Intent(见 SpeechInput)
 *
 * 用法:
 * ```
 * val client = AsrClientFactory.create(config)
 * val result = client.recognize(pcmAudioBytes, sampleRate = 16000)
 * println(result.text)
 * ```
 */
interface AsrClient {
    /**
     * 识别音频。
     *
     * @param audioData PCM 音频字节(16-bit little-endian,单声道)
     * @param sampleRate 采样率(DashScope Paraformer v2 支持任意,默认 16000)
     * @return 识别结果,失败返回 null
     */
    suspend fun recognize(audioData: ByteArray, sampleRate: Int = 16000): AsrResult?

    /** 释放资源(WebSocket 连接等)。 */
    fun close()
}

/**
 * Phase 9.3 (M2): ASR 客户端工厂。
 *
 * 根据 [AsrConfig.provider] 创建对应的 [AsrClient] 实例。
 * SYSTEM 模式返回 null(用 Intent,不走此接口)。
 */
object AsrClientFactory {
    // M-ASR4: 工厂单例持有共享 OkHttpClient,避免每次 create 都新建独立 client
    // (DashScopeAsrClient 原先每次 new 一个含独立线程池/连接池的 OkHttpClient,造成资源浪费)。
    // 共享 client 生命周期与工厂(应用进程)一致,DashScopeAsrClient.close() 不会 shutdown 它。
    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(AsrConstants.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(AsrConstants.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    fun create(config: AsrConfig): AsrClient? = when (config.provider) {
        // ASR-3: DashScopeAsrClient 已重构为 DashScopeAsrController(流式,实现 ASRController 接口),
        // 不再走旧的 AsrClient 一次性 recognize 路径。此处返回 null,后续 ASR-4 任务会集成新 Controller。
        // sharedClient 保留,供 ASR-4 创建 DashScopeAsrController 时注入。
        AsrProviderType.DASHSCOPE -> null
        // Step 已重构为流式 StepAsrController(实现 ASRController,不再实现 AsrClient),
        // 旧版 AsrClient 接口不再支持,此处临时返回 null,由上层改用 StepAsrController。
        AsrProviderType.STEP -> null
        AsrProviderType.DASHSCOPE_FILE -> DashScopeFileAsrClient(config)
        AsrProviderType.SYSTEM -> null
        // 新增的流式 Provider 均实现 ASRController 接口,不走旧 AsrClient 路径,
        // 由 ChatAudioCoordinator.getOrCreateAsrController 创建 Controller。
        AsrProviderType.OPENAI_WHISPER -> null
        AsrProviderType.OPENAI_REALTIME -> null
        AsrProviderType.AGNES -> null
    }
}
