package io.zer0.muse.ui.speech

import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.common.Result
import kotlinx.coroutines.withContext

/**
 * P2-9: 语音克隆抽象 — 多 Provider 统一接口。
 *
 * 与既有 [CloudTtsService] 解耦:CloudTtsService 负责"文本→音频"合成,
 * 本接口负责"样本音频→可复用 voiceId"的克隆管理(增/列/删)。
 *
 * 设计参考 rikkahub 的 Provider 抽象,但简化为单文件多 provider 分发;
 * 当前实现 [ElevenLabsVoiceCloningProvider],后续可扩展 OpenVoice / Fish Audio 等。
 *
 * 用法:
 * ```
 * val service: VoiceCloningService = koinInject()
 * service.cloneVoice("elevenlabs", "my-voice", sampleBase64)
 *     .onSuccess { voiceId -> /* 保存到 MediaConfig.ttsVoice */ }
 *     .onError { msg, _ -> MuseToast.show(msg) }
 * ```
 */
interface VoiceCloningProvider {
    /** Provider 唯一标识(如 "elevenlabs"),与 [VoiceCloningService] 注册键对应。 */
    val providerId: String

    /**
     * 上传样本音频,创建克隆语音,返回 voiceId。
     *
     * @param name 用户可读的语音名称
     * @param sampleAudioBase64 样本音频的 base64 编码(支持 mp3/wav/m4a 等)
     * @return 成功返回新建 voiceId,失败返回 [Result.Error]
     */
    suspend fun cloneVoice(name: String, sampleAudioBase64: String): Result<String>

    /** 列出已克隆的语音。 */
    suspend fun listClonedVoices(): Result<List<ClonedVoice>>

    /** 删除克隆语音。 */
    suspend fun deleteVoice(voiceId: String): Result<Unit>
}

/**
 * 克隆语音数据模型。
 *
 * @param voiceId Provider 返回的 voice id(用于 TTS 合成时引用)
 * @param name 用户可读名称
 * @param createdAt 创建时间戳(毫秒),由 Provider 返回(无则 0)
 * @param category Provider 分类标签(默认 "cloned",便于与预设 voice 区分)
 */
data class ClonedVoice(
    val voiceId: String,
    val name: String,
    val createdAt: Long,
    val category: String = "cloned",
)

/**
 * 语音克隆服务 — 按 providerId 分发到具体 [VoiceCloningProvider]。
 *
 * 构造时注入 providerId → Provider 映射(由 Koin 装配),
 * 所有方法都在 [AppDispatchers.io] 上执行,保证 UI 线程不阻塞。
 */
class VoiceCloningService(
    private val providers: Map<String, VoiceCloningProvider>,
) {
    companion object {
        private const val TAG = "VoiceCloningService"
    }

    /** 获取已注册的 Provider 列表(供 UI 渲染服务商选择器)。 */
    fun availableProviders(): List<String> = providers.keys.toList()

    /**
     * 克隆新语音。
     *
     * @param providerId 服务商标识(必须已注册)
     * @param name 语音名称
     * @param sampleAudioBase64 样本音频 base64
     */
    suspend fun cloneVoice(
        providerId: String,
        name: String,
        sampleAudioBase64: String,
    ): Result<String> = withContext(AppDispatchers.io) {
        val provider = providers[providerId]
        if (provider == null) {
            Logger.w(TAG, "未知 voice cloning provider: $providerId")
            return@withContext Result.Error("Unknown provider: $providerId")
        }
        provider.cloneVoice(name, sampleAudioBase64)
    }

    /** 列出指定 Provider 的已克隆语音。 */
    suspend fun listClonedVoices(providerId: String): Result<List<ClonedVoice>> =
        withContext(AppDispatchers.io) {
            val provider = providers[providerId]
            if (provider == null) {
                Logger.w(TAG, "未知 voice cloning provider: $providerId")
                return@withContext Result.Error("Unknown provider: $providerId")
            }
            provider.listClonedVoices()
        }

    /** 删除指定 Provider 的克隆语音。 */
    suspend fun deleteVoice(providerId: String, voiceId: String): Result<Unit> =
        withContext(AppDispatchers.io) {
            val provider = providers[providerId]
            if (provider == null) {
                Logger.w(TAG, "未知 voice cloning provider: $providerId")
                return@withContext Result.Error("Unknown provider: $providerId")
            }
            provider.deleteVoice(voiceId)
        }
}
