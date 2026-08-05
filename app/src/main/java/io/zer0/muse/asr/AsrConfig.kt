package io.zer0.muse.asr

import io.zer0.muse.data.SecureKeyStore
import kotlinx.serialization.Serializable

/**
 * Phase 9.3 (M2): ASR Provider 类型枚举。
 *
 * - [SYSTEM]: 系统 ACTION_RECOGNIZE_SPEECH Intent(依赖 Google/厂商服务,国产 ROM 可能缺失)
 * - [DASHSCOPE]: 阿里云 DashScope Paraformer 实时语音识别(WebSocket 流式)
 * - [STEP]: 阶跃星辰 Step-Audio(OpenAI 兼容 API,audio 输入)
 * - [DASHSCOPE_FILE]: Phase 11.1.5 DashScope 异步文件转录(submit → query 轮询),
 *   适合长音频文件(需先上传到 OSS 拿 URL,或直接传公网可访问 URL)
 * - [OPENAI_WHISPER]: 通用 OpenAI Whisper 兼容端点(POST {baseUrl}/audio/transcriptions),
 *   分段批量非真流式,适配任何 OpenAI 兼容中转站(含 agnes)
 * - [OPENAI_REALTIME]: OpenAI Realtime WebSocket 流式(wss://api.openai.com/v1/realtime?intent=transcription),
 *   服务端 VAD + 增量回调,真流式
 * - [AGNES]: Agnes 中转站(OpenAI 兼容),复用 [OPENAI_WHISPER] 适配器,baseUrl 指向 agnes 端点
 */
enum class AsrProviderType {
    /** 系统 Intent 语音识别(默认,无网络依赖但依赖厂商服务)。 */
    SYSTEM,
    /** 阿里云 DashScope Paraformer(wss://dashscope.aliyuncs.com/api-ws/v1/inference)。 */
    DASHSCOPE,
    /** 阶跃星辰 Step-Audio(OpenAI 兼容 API,audio base64 输入)。 */
    STEP,
    /** Phase 11.1.5: DashScope 异步文件转录(POST submit → GET query 轮询)。 */
    DASHSCOPE_FILE,
    /** 通用 OpenAI Whisper 兼容端点(multipart POST /audio/transcriptions)。 */
    OPENAI_WHISPER,
    /** OpenAI Realtime WebSocket 流式(服务端 VAD + 增量 transcription)。 */
    OPENAI_REALTIME,
    /** Agnes 中转站(OpenAI 兼容,内部走 [OPENAI_WHISPER] 适配器,baseUrl 不同)。 */
    AGNES,
}

/**
 * Phase 9.3 (M2): ASR 配置。
 *
 * @param provider ASR Provider 类型
 * @param apiKey DashScope/Step/OpenAI API Key(SYSTEM 模式忽略)
 * @param model 模型名(DashScope 默认 paraformer-real-time-v2,Step 默认 step-audio-r1.1,
 *   OPENAI_WHISPER/OPENAI_REALTIME 默认 whisper-1,DASHSCOPE_FILE 默认 paraformer-v2)
 * @param sampleRate 采样率(DashScope Paraformer v2 支持任意,默认 16000)
 * @param language 语种提示(DashScope: zh/en/ja/yue/ko/de/fr/ru,null=自动识别)
 * @param enablePunctuation 是否启用标点预测(DashScope v2,默认 true)
 * @param enableInverseTextNormalization 是否启用逆文本正则化(中文数字→阿拉伯数字,默认 true)
 * @param fileAudioUrl Phase 11.1.5: 异步文件转录的音频 URL(公网可访问,如 OSS URL)
 * @param pollIntervalMs Phase 11.1.5: 轮询间隔(默认 3000ms)
 * @param pollTimeoutMs Phase 11.1.5: 轮询总超时(默认 300000ms = 5 分钟)
 * @param asrEndpoint L-ASR3: DashScope WebSocket 端点(默认空,空时用代码内置默认值)
 * @param baseUrl 自定义 API 基础 URL(OPENAI_WHISPER/OPENAI_REALTIME/AGNES 用,
 *   默认空时 OPENAI_WHISPER/AGNES 用 https://api.openai.com/v1,OPENAI_REALTIME 用 wss 内置端点)
 * @param hotwords 热词列表,提升专有名词识别率(支持的 Provider 才会传入:
 *   DashScope/SenseVoice/Whisper 兼容端点的 prompt 参数)
 * @param vadEnabled 是否启用本地 VAD 静音检测(默认 false,
 *   OPENAI_REALTIME 走服务端 VAD 不需要本地;DashScope/Whisper/Step 可选启用)
 * @param vadThreshold VAD 能量阈值(归一化 RMS 0-1f,默认 0.05f,低于此值视为静音)
 * @param vadSilenceDurationMs VAD 静音自动停止时长(毫秒,默认 1500ms)
 */
@Serializable
data class AsrConfig(
    val provider: AsrProviderType = AsrProviderType.SYSTEM,
    val apiKey: String = "",
    val model: String = "paraformer-realtime-v2",
    val sampleRate: Int = 16000,
    val language: String? = "zh",
    val enablePunctuation: Boolean = true,
    val enableInverseTextNormalization: Boolean = true,
    val fileAudioUrl: String = "",
    val pollIntervalMs: Long = 3000L,
    val pollTimeoutMs: Long = 300_000L,
    val asrEndpoint: String = "",
    // 新增:OpenAI 兼容端点 / Realtime / Agnes 共用的自定义 baseUrl
    val baseUrl: String = "",
    // 新增:热词列表(逗号分隔在 UI 层处理,这里用 List<String>)
    val hotwords: List<String> = emptyList(),
    // 新增:本地 VAD 配置
    val vadEnabled: Boolean = false,
    val vadThreshold: Float = 0.05f,
    val vadSilenceDurationMs: Long = 1_500L,
) {
    /** 是否已配置可用(SYSTEM 总是可用,API 模式需要 apiKey)。 */
    val isConfigured: Boolean
        get() = provider == AsrProviderType.SYSTEM || apiKey.isNotBlank()

    /** 根据 provider 返回默认模型名。 */
    fun defaultModel(): String = when (provider) {
        AsrProviderType.DASHSCOPE -> "paraformer-realtime-v2"
        AsrProviderType.STEP -> "step-audio-r1.1"
        AsrProviderType.DASHSCOPE_FILE -> "paraformer-v2"
        AsrProviderType.OPENAI_WHISPER -> "whisper-1"
        AsrProviderType.OPENAI_REALTIME -> "gpt-4o-realtime-preview"
        AsrProviderType.AGNES -> "whisper-1"
        AsrProviderType.SYSTEM -> ""
    }

    /**
     * 根据 provider 返回默认 baseUrl(空串表示由各 Controller 内部决定内置端点)。
     * - OPENAI_WHISPER / AGNES: 默认 https://api.openai.com/v1(agnes 用户需自定义)
     * - OPENAI_REALTIME: WebSocket 端点由 Controller 内部决定
     * - 其他: 空串(用各 Controller 内置端点)
     */
    fun defaultBaseUrl(): String = when (provider) {
        AsrProviderType.OPENAI_WHISPER -> "https://api.openai.com/v1"
        AsrProviderType.AGNES -> "https://api.openai.com/v1"
        else -> ""
    }

    /**
     * M-03: 返回 apiKey 已加密(走 [SecureKeyStore.encrypt])的副本,供持久化前调用。
     * 空值原样保留(不加密空值)。与 WebServerConfig.encrypted 行为一致。
     */
    suspend fun encrypted(): AsrConfig = copy(
        apiKey = SecureKeyStore.encrypt(apiKey),
    )

    /**
     * M-03: 返回 apiKey 已解密(走 [SecureKeyStore.decrypt])的副本,供从持久化层读出后调用。
     * 旧版明文由 decrypt 透传(兼容)。与 WebServerConfig.decrypted 行为一致。
     */
    suspend fun decrypted(): AsrConfig = copy(
        apiKey = SecureKeyStore.decrypt(apiKey),
    )
}
