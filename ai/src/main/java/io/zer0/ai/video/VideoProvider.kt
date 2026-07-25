package io.zer0.ai.video

/**
 * v1.137: 视频生成 Provider 统一接口。
 *
 * 重构目标:
 *  - 解耦 providerId 路由逻辑(交给 [VideoProviderRegistry] 按 specId / host / type 选择)
 *  - 统一异步任务模型:submit 返回 taskId(或同步结果),poll 查询状态
 *  - 支持异步 API(DashScope / MiniMax / Agnes)与同步 API(Kling 同步返回)两种形态
 *
 * 每个 Provider 实现:
 *  - [submit]: 提交生成任务,返回 [VideoSubmitResult](异步 taskId 或同步 videoUrl)
 *  - [poll]: 查询任务状态,返回 [VideoPollResult]
 *
 * Provider 不负责轮询调度 — 由 [VideoGenerationService] 统一执行自适应轮询。
 */
interface VideoProvider {
    /** Provider 唯一标识(如 "kling" / "agnes" / "openai_video_generic")。 */
    val providerId: String

    /** 是否支持图生视频(单图)。 */
    val supportsImageToVideo: Boolean

    /** 是否支持多图生视频(2+ 图)。 */
    val supportsMultiFrameToVideo: Boolean

    /**
     * 提交视频生成任务。
     *
     * @param request 生成请求(含 prompt / model / 参考图 / 凭证等)
     * @return [VideoSubmitResult]:
     *   - isAsync=true 时 taskId 非空,后续走 [poll] 轮询
     *   - isAsync=false 时 videoUrl 非空(同步返回结果,无需轮询)
     */
    suspend fun submit(request: VideoGenRequest): VideoSubmitResult

    /**
     * 查询异步任务状态。
     *
     * 仅当 [submit] 返回 isAsync=true 时调用。
     *
     * @param taskId 由 [submit] 返回的任务 ID
     * @return [VideoPollResult],status=SUCCESS 时 videoUrl 非空
     */
    suspend fun poll(taskId: String): VideoPollResult
}

/**
 * 视频生成请求参数(Provider 无关)。
 *
 * @param prompt 视频描述/提示词
 * @param model 模型 ID(如 kling-v1 / agnes-video-v2.0)
 * @param width 视频宽度(Agnes 默认 1152)
 * @param height 视频高度(Agnes 默认 768)
 * @param frameRate 帧率(Agnes 默认 24,范围 1-60)
 * @param numFrames 帧数(Agnes 必须是 8n+1,范围 [81, 441];默认 121 ≈ 5s@24fps)
 * @param referenceImages 参考图列表(data URI 或 URL):
 *   - 空 → 文生视频
 *   - 1 张 → 图生视频
 *   - 2+ 张 → 多图生视频(仅 Agnes 等支持)
 * @param apiKey Provider API Key(由 [VideoGenerationService] 从 ProviderConfig 注入)
 * @param baseUrl Provider 基础 URL(留空时由 Provider 取默认值)
 * @param videoGenerationsPath 视频生成端点路径(仅通用 OpenAI 兼容 Provider 使用)
 * @param duration 视频时长(秒,Kling 等使用;Agnes 通过 numFrames 推导)
 * @param resolution 分辨率字符串(如 "720p",Kling 等使用)
 */
data class VideoGenRequest(
    val prompt: String,
    val model: String,
    val width: Int = 1152,
    val height: Int = 768,
    val frameRate: Int = 24,
    val numFrames: Int = 121,
    val referenceImages: List<String> = emptyList(),
    val apiKey: String = "",
    val baseUrl: String? = null,
    val videoGenerationsPath: String? = null,
    val duration: Int = 5,
    val resolution: String = "720p",
)

/**
 * 任务提交结果。
 *
 * @param taskId 异步任务 ID(isAsync=true 时非空)
 * @param videoUrl 同步返回的视频 URL(isAsync=false 时非空)
 * @param isAsync 是否为异步任务:
 *   - true → 调用方需轮询 [VideoProvider.poll] 直到 SUCCESS/FAILED
 *   - false → 已同步返回 videoUrl,无需轮询
 * @param modelName 提交时使用的模型名(用于 Agnes 等查询时需要 model_name 的场景)
 */
data class VideoSubmitResult(
    val taskId: String? = null,
    val videoUrl: String? = null,
    val isAsync: Boolean = true,
    val modelName: String? = null,
)

/**
 * 任务轮询状态。
 */
enum class PollStatus {
    /** 排队 / 处理中(继续轮询)。 */
    PENDING,

    /** 成功(视频已生成,videoUrl 非空)。 */
    SUCCESS,

    /** 失败(任务出错或被取消,errorMessage 非空)。 */
    FAILED,
}

/**
 * 任务轮询结果。
 *
 * @param status 任务状态
 * @param videoUrl 视频下载 URL(status=SUCCESS 时非空)
 * @param errorMessage 错误信息(status=FAILED 时非空)
 */
data class VideoPollResult(
    val status: PollStatus,
    val videoUrl: String? = null,
    val errorMessage: String? = null,
)
