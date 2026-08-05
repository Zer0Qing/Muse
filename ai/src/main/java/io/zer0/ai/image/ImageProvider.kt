package io.zer0.ai.image

/**
 * v1.0.18: 图片生成供应商抽象接口。
 *
 * 参考 QingTian(参考开源项目)的 AdapterRegistry 模式,让 OpenAI / Agnes / Gemini 等各自实现,
 * 避免把绘图硬绑到 OpenAI 协议。ImageService 通过 [ImageProviderRegistry] 选择合适的 provider,
 * 再委托其完成实际的 HTTP 调用与响应解析。
 *
 * 设计要点(对齐 参考开源项目 plugins/image-gen):
 *  - 同步任务: [submit] 直接在 [ImageSubmitResult.images] 中返回结果,[taskId] 为 null。
 *  - 异步任务: [submit] 返回 [ImageSubmitResult.taskId],由调用方轮询 [poll] 直至终态。
 *  - 参考图(图生图): 通过 [ImageGenRequest.referenceImages] 传入(base64 或 URL),
 *    provider 自行决定如何编码(multipart / extra_body.image 等)。
 */
interface ImageProvider {
    /** 供应商标识,如 "openai" / "agnes" / "gemini"。 */
    val providerId: String

    /** 是否支持图生图(参考图)。 */
    val supportsImageEdit: Boolean

    /** 是否支持异步任务(需要轮询)。 */
    val supportsAsync: Boolean

    /**
     * 提交图片生成任务。
     *
     * @param request 生成请求(prompt / model / size / 参考图等)
     * @return 同步任务直接返回 [ImageSubmitResult.images];异步任务返回 [ImageSubmitResult.taskId]。
     */
    suspend fun submit(request: ImageGenRequest): ImageSubmitResult

    /**
     * 查询异步任务状态(仅 [supportsAsync]=true 时调用)。
     *
     * @param taskId [submit] 返回的任务 ID
     * @return 含 [ImagePollResult.status] 与(终态时)图片列表。
     */
    suspend fun poll(taskId: String): ImagePollResult
}

/**
 * 图片生成请求(面向 [ImageProvider] 的统一参数)。
 *
 * [size] 既支持像素值("1024x1024")也支持比例("1:1"),由各 provider 自行映射。
 * [referenceImages] 元素可为 base64(无 data: 前缀)或 http/https/file URL,provider 按需解析。
 */
data class ImageGenRequest(
    val prompt: String,
    val model: String,
    val size: String,  // "1024x1024" 或 "1:1" 比例
    val quality: String = "standard",
    val style: String = "",
    val n: Int = 1,
    /** 参考图(base64 或 URL),非空时触发图生图路径。 */
    val referenceImages: List<String> = emptyList(),
    /** 期望返回格式:"b64_json" / "url";空串表示不强制(provider 自行决定)。 */
    val responseFormat: String = "b64_json",
    /** 透传给 provider 的额外上下文(apiKey / baseUrl / specific 等),由 ImageService 组装。 */
    val config: io.zer0.ai.core.ProviderConfig? = null,
)

/**
 * [submit] 结果:同步任务直接含 images,异步任务含 taskId。
 */
data class ImageSubmitResult(
    /** 异步任务 ID(仅 [isAsync]=true 时有值)。 */
    val taskId: String? = null,
    /** 同步结果图片列表(仅 [isAsync]=false 时有值)。 */
    val images: List<GeneratedImage> = emptyList(),
    val isAsync: Boolean = false,
)

/**
 * [poll] 结果。
 */
data class ImagePollResult(
    /** 任务状态:PENDING / SUCCESS / FAILED。 */
    val status: PollStatus,
    val images: List<GeneratedImage> = emptyList(),
    val errorMessage: String? = null,
)

enum class PollStatus { PENDING, SUCCESS, FAILED }

/**
 * 生成结果图片。
 * - [base64]: 图片 base64(无 data: 前缀);与 [url] 至少其一非空。
 * - [url]: 图片可访问 URL(http/https)。
 */
data class GeneratedImage(
    val base64: String? = null,
    val url: String? = null,
)
