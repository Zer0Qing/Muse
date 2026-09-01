package io.zer0.muse.vision

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderError
import io.zer0.ai.core.ProviderException
import io.zer0.ai.core.UIMessage
import io.zer0.ai.core.VisionCapabilities
import io.zer0.ai.core.inferFromMessage
import io.zer0.ai.core.providerError
import io.zer0.ai.registry.ModelRegistry
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.util.retryOnNetworkError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 视觉分析异常。
 *
 * v1.135: 视觉辅助失败时不再静默返回 null,而是抛出此异常,
 * 由调用方(如 [io.zer0.muse.ui.ChatViewModel])捕获并注入失败提示、清空图片,
 * 避免把原图发给纯文本模型导致 HTTP 400。
 */
class VisionAnalysisException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 正文为空时保留 provider 返回的 reasoning，避免视觉模型结果被误判为空。 */
internal fun effectiveVisionResponseText(text: String, reasoningContent: String?): String =
    text.ifBlank { reasoningContent.orEmpty() }

/** 为视觉能力不匹配提供可诊断的供应商、模型和注册表原因。 */
internal fun visionUnsupportedReason(
    providerName: String,
    modelId: String,
    inputModalities: Set<String>,
): String {
    val detected = inputModalities.sorted().joinToString(", ").ifBlank { "unknown" }
    return "供应商 $providerName 的模型 $modelId 不支持图片输入(已识别能力: $detected, 请更换真正支持视觉输入的模型)"
}

/**
 * v1.0.4: 视觉辅助 prepare 阶段的结果。
 *
 * 按 既有实现 的 `prepareVisionInputForTextOnlyModel` 设计。
 * 成功时 [text] 含注入的 `<vision-context>` 描述,[images] 清空(避免向纯文本模型发图)。
 * 失败时 [text] 含失败提示,[images] 清空,让文本模型明确知道本轮无图片。
 *
 * @param text 处理后的用户消息文本(含视觉描述或失败提示)
 * @param images 处理后的图片列表(成功/失败均清空,因为纯文本模型不能接收图片)
 * @param success 是否成功分析(失败时 text 是降级提示)
 * @param descriptionCount 成功分析的图片数量(失败时为 0)
 */
data class VisionPrepareResult(
    val text: String,
    val images: List<String>,
    val success: Boolean,
    val descriptionCount: Int,
)

/**
 * 视觉辅助桥接器 — 让纯文本模型通过视觉模型"看到"图片。
 *
 * # v1.0.5 重写(按 既有实现)
 *
 * 工作原理:
 *  1. 检测当前模型是否支持视觉输入([supportsVision])
 *  2. 若不支持且视觉辅助已启用,用配置的视觉模型分析图片
 *  3. 获取结构化文字描述(含 user_request 针对性回答),以 `<vision-context>` 标签注入用户消息
 *  4. 清空原消息的图片列表,避免向纯文本模型发图导致 HTTP 400
 *
 * # v1.0.5 重写修复的问题
 *
 *  1. **集成 [VisionImagePreprocessor]**:图片在送视觉模型前先压缩(2000×2000 + JPEG 80),
 *     超大图不再直接丢弃,而是压缩到预算内(对齐 既有实现 的 MODEL_IMAGE_INPUT_POLICY)
 *  2. **缓存 key 语义明确**:改用 [VisionCache.CacheKey] 数据类,不再复用 visionModelId 字段塞 suffix
 *  3. **移除 mimeType dead parameter**:[analyzeImage] 不再接收未使用的 mimeType 参数
 *  4. **grounding 兜底改进**:JSON 解析失败时把原始响应作为 `image_overview` 而非 `evidence`,
 *     让文本模型读到主要信息(原版 7 字段全填"无"信息利用率低)
 *  5. **visionCache 改为非空**:移除 `visionCache: VisionCache? = null` 的可空设计,减少 `?.` 防御
 *
 * # 保留的优势(比 既有实现 更好)
 *
 *  - **并发分析**:多图 `coroutineScope { async }.awaitAll` 并发(既有实现 是串行)
 *  - **超时+重试**:`withTimeout(60s)` + `retryOnNetworkError(3次)`(既有实现 仅 120s 超时无重试)
 *  - **streamChat 降级**:Provider 不支持 completeText 时降级 streamChat 兜底
 *  - **进度 StateFlow**:`progressFlow` 暴露分析进度供 UI 实时展示
 */
class VisionBridge(
    private val chatService: ChatService,
    private val settings: SettingsRepository,
    private val visionCache: VisionCache,
) {

    companion object {
        private const val TAG = "VisionBridge"

        /** v1.0.2 (P0): 单张图片视觉分析超时(毫秒)。 */
        private const val ANALYSIS_TIMEOUT_MS = 60_000L

        /** v1.0.2 (P0): 网络重试参数(仅对 IOException 重试)。 */
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 500L

        /**
         * v1.0.5: VISION_PROMPT 的版本号,用于缓存 key。
         *
         * 修改提示词内容时同步递增此版本号,使旧缓存自动失效。
         * v1.0.5 重写:提示词微调 + 集成 VisionImagePreprocessor,版本号从 2 升到 3。
         */
        private const val VISION_PROMPT_VERSION = 3

        /** v1.0.4: 单条视觉笔记最大字符数(按 既有实现 MAX_NOTE_CHARS=3200)。 */
        private const val MAX_NOTE_CHARS = 3200

        /** v1.0.4: visual primitives 最大数量(按 既有实现 MAX_VISUAL_PRIMITIVES=16)。 */
        private const val MAX_VISUAL_PRIMITIVES = 16

        /** v1.0.4: primitive ref 标签最大字符数(按 既有实现 MAX_PRIMITIVE_REF_CHARS=96)。 */
        private const val MAX_PRIMITIVE_REF_CHARS = 96

        /**
         * v1.0.4: note-only 路径提示词(模型不支持 grounding)。
         *
         * 按 既有实现 的 `_analyzeImageAsNote` 提示词,8 个固定字段。
         * 关键改进:加入 `user_request` 字段,把用户的具体问题传给视觉模型,
         * 让视觉模型针对性回答(而非泛泛描述)。
         */
        private val VISION_PROMPT_NOTE_ONLY = """
            请分析这张图片,为另一个纯文本模型生成结构化描述。
            使用以下固定字段输出,每个字段独占一行,字段名后加冒号:

            image_overview: 图片的整体内容概述
            visible_text: 图片中可见的重要文字(OCR),无则写"无"
            objects_and_layout: 重要物体、位置、数量及其关系
            charts_or_data: 图表/表格/数据细节,无则写"无"
            user_request: 用一句话重述用户的请求
            user_request_answer: 根据图片内容回答用户的请求
            evidence: 支持上述回答的视觉证据
            uncertainty: 不确定、被遮挡或需要猜测的内容

            不要提及你是工具或独立模型,直接输出字段内容即可。

            用户请求:
            %s
        """.trimIndent()

        /**
         * v1.0.4: structured+primitives 路径提示词(模型支持 grounding)。
         *
         * 按 既有实现 的 `_analyzeImageWithPrimitives` 提示词。
         * 要求返回严格 JSON,含 8 字段 + visual_primitives 数组(坐标框)。
         * 坐标格式由 [primitivePromptShape] 根据模型 outputFormat 派生。
         */
        private val VISION_PROMPT_WITH_GROUNDING = """
            请分析这张图片,为另一个纯文本模型生成结构化 JSON 描述。
            返回严格的 JSON(不要 markdown 代码块),包含以下字段:

            {
              "image_overview": "图片整体内容概述",
              "visible_text": ["图片中可见的重要文字1", "文字2"],
              "objects_and_layout": "重要物体、位置、数量及其关系",
              "charts_or_data": "图表/表格/数据细节,无则写 none",
              "user_request": "用一句话重述用户的请求",
              "user_request_answer": "根据图片内容回答用户的请求",
              "evidence": "支持上述回答的视觉证据",
              "uncertainty": "不确定、被遮挡或需要猜测的内容",
              "visual_primitives": %s
            }

            坐标使用归一化 0-1000 坐标系(左上角原点)。
            不要提及你是工具或独立模型。

            用户请求:
            %s
        """.trimIndent()
    }

    /**
     * v1.0.2 (P1): 视觉分析进度 StateFlow,供 UI 实时展示。
     */
    private val _progressFlow = MutableStateFlow(VisionProgress(idle = true, index = 0, total = 0))
    val progressFlow: StateFlow<VisionProgress> = _progressFlow.asStateFlow()

    /**
     * 检查给定模型是否支持直接图像输入。
     *
     * M2.1/M2.8: 判定经 [io.zer0.ai.registry.ModelCapabilityQuery] 三态裁决 —
     *  - SUPPORTED(注册表确认视觉模型)→ 直接放行;
     *  - UNSUPPORTED(注册表确证纯文本,如 GLM-4.6)→ 拒绝,即使上游/中转
     *    误标 supportsVision=true 也不直发图片(调用方走视觉辅助降级);
     *  - UNKNOWN(未收录模型/私有部署名)→ 保守沿用模型自身声明
     *    (用户可在视觉设置页手动纳入,注册表不越权覆盖用户配置)。
     */
    fun supportsVision(model: Model): Boolean {
        val snapshot = io.zer0.ai.registry.ModelCapabilityQuery.snapshot(model.id)
        return when (snapshot.visionInput) {
            io.zer0.ai.registry.CapabilitySupport.SUPPORTED -> true
            io.zer0.ai.registry.CapabilitySupport.UNSUPPORTED -> false
            io.zer0.ai.registry.CapabilitySupport.UNKNOWN -> model.supportsVisionInput()
        }
    }

    /** 空安全便捷判定:模型未解析时返回 false(调用方无需自行判空,避免分支膨胀)。 */
    fun supportsVisionModel(model: Model?): Boolean = model != null && supportsVision(model)

    /**
     * v1.0.4: 视觉辅助 prepare 阶段入口(按 既有实现)。
     *
     * 在发送消息给纯文本模型之前调用,把图片"翻译"成文字描述注入用户消息,
     * 并清空图片列表(避免 HTTP 400)。
     *
     * v1.0.5 改进:
     *  - 集成 [VisionImagePreprocessor.prepareBatch],图片先压缩到 2000×2000 + JPEG 80
     *  - 预处理失败(总预算超限)走降级路径,清空图片 + 注入失败提示
     *
     * 失败时(视觉模型不可用/超时/限流)走降级路径:
     *  - 清空图片(纯文本模型不能接收图片)
     *  - 在用户消息文本前注入失败提示,让文本模型明确知道"本轮没有图片内容"
     *  - 返回 [VisionPrepareResult](success=false),不抛异常
     *
     * 配置错误(视觉辅助未启用/未配置/模型不支持视觉)仍抛 [VisionAnalysisException],
     * 因为这是用户配置问题,不应静默降级。
     *
     * @param text 用户消息文本
     * @param images 图片 base64 列表(无 data: 前缀)
     * @param userRequest 用户的具体请求(通常是用户消息文本),传给视觉模型做针对性分析
     * @param sessionId 当前会话 id(用于缓存);null 不使用缓存
     * @param messageId v1.0.16: 正在分析的消息 id,驱动 UI 只在该消息上显示进度
     * @param onProgress v1.0.20: 进度回调 (current, total),每完成一张图触发一次。
     *   与 [progressFlow] 并行触发,调用方可借此直接更新 UI 状态,无需单独 collect Flow。
     *   回调在 IO 线程触发,调用方如需更新 UI 应自行切主线程。
     * @return prepare 结果(成功含描述,失败含降级提示)
     */
    suspend fun prepare(
        text: String,
        images: List<String>,
        userRequest: String,
        sessionId: String? = null,
        /** v1.0.16: 正在分析的消息 id,驱动 UI 只在该消息上显示进度 */
        messageId: String? = null,
        /** v1.0.20: 进度回调,每完成一张图触发 (current=已完成数, total=总图片数) */
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): VisionPrepareResult {
        if (images.isEmpty()) {
            return VisionPrepareResult(text, images, success = true, descriptionCount = 0)
        }

        // v1.0.20: 视觉辅助异步化 — prepare 内部涉及网络 IO(图片预处理 + 视觉模型调用),
        //   全程切到 IO Dispatcher 执行,避免在 launchStream 关键路径上阻塞主线程。
        //   多张图片时延迟显著,异步化后 UI 可立即显示"分析图片 0/N…"进度。
        return withContext(Dispatchers.IO) {
            // v1.0.5: 图片预处理(压缩 + MIME 嗅探 + 预算控制)
            val preparedImages: List<VisionImagePreprocessor.PreparedImage> = try {
                VisionImagePreprocessor.prepareBatch(images)
            } catch (e: VisionImagePreprocessException) {
                Logger.w(TAG, "图片预处理失败,走降级路径: ${e.message}")
                val notice = buildFailureNotice(e.message)
                return@withContext VisionPrepareResult(
                    text = "$notice\n\n$text",
                    images = emptyList(),
                    success = false,
                    descriptionCount = 0,
                )
            }

            if (preparedImages.isEmpty()) {
                return@withContext VisionPrepareResult(text, images, success = true, descriptionCount = 0)
            }

            try {
                // v1.0.20: 进度初始化 — 立即回调 (0, total),让 UI 显示"分析图片 0/N…"
                onProgress?.invoke(0, preparedImages.size)
                val descriptions = analyzeImages(preparedImages, userRequest, sessionId, messageId, onProgress)
                if (descriptions.isEmpty()) {
                    val notice = buildFailureNotice("视觉分析返回空结果")
                    VisionPrepareResult("$notice\n\n$text", emptyList(), success = false, descriptionCount = 0)
                } else {
                    val visionPrefix = buildVisionContext(descriptions)
                    VisionPrepareResult(
                        text = visionPrefix + text,
                        images = emptyList(),
                        success = true,
                        descriptionCount = descriptions.size,
                    )
                }
            } catch (e: VisionAnalysisException) {
                Logger.w(TAG, "视觉辅助 prepare 失败,走降级路径: ${e.message}")
                val notice = buildFailureNotice(e.message)
                VisionPrepareResult(
                    text = "$notice\n\n$text",
                    images = emptyList(),
                    success = false,
                    descriptionCount = 0,
                )
            }
        }
    }

    /**
     * v1.0.5: 分析单张预处理后的图片,返回 `<vision-context>` 包裹的文字描述。
     *
     * 改进:
     *  - 接收 [VisionImagePreprocessor.PreparedImage] 而非裸 base64,内部含压缩后数据和真实 MIME
     *  - 移除未使用的 mimeType dead parameter
     *
     * @param preparedImage 预处理后的图片
     * @param userRequest 用户的具体请求,传给视觉模型做针对性分析
     * @return 视觉描述文本(含 `<vision-context>` 标签)
     * @throws VisionAnalysisException 禁用、未配置、模型不支持视觉或分析失败时抛出
     */
    private suspend fun analyzeImage(
        preparedImage: VisionImagePreprocessor.PreparedImage,
        userRequest: String = "",
    ): String {
        // 1. 检查视觉辅助是否启用
        val enabled = settings.visionEnabledFlow.first()
        if (!enabled) {
            Logger.w(TAG, "视觉分析[跳过]: 视觉辅助开关未开启")
            throw VisionAnalysisException("视觉辅助未启用(请在设置-视觉辅助中开启总开关)")
        }

        // 2. 读取视觉模型配置
        val visionModelId = settings.visionModelIdFlow.first()
        val visionProviderId = settings.visionProviderIdFlow.first()
        if (visionModelId.isNullOrBlank() || visionProviderId.isNullOrBlank()) {
            Logger.w(TAG, "视觉分析[跳过]: modelId=$visionModelId providerId=$visionProviderId")
            throw VisionAnalysisException("视觉模型或供应商未配置(请在设置-视觉辅助中选择)")
        }

        // 3. 解析 ProviderConfig 和 Model
        val allProviders = settings.getAllProviders()
        val visionProvider: ProviderConfig = allProviders.firstOrNull { it.id == visionProviderId }
            ?: throw VisionAnalysisException("视觉供应商 $visionProviderId 未找到(可能已被删除)").also {
                Logger.w(TAG, "视觉分析[跳过]: providerId=$visionProviderId 不在 ${allProviders.size} 个供应商中")
            }

        val rawVisionModel: Model = visionProvider.models.firstOrNull { it.id == visionModelId }
            ?: throw VisionAnalysisException("视觉模型 $visionModelId 未找到(供应商 ${visionProvider.displayName} 共 ${visionProvider.models.size} 个模型)").also {
                Logger.w(TAG, "视觉分析[跳过]: modelId=$visionModelId 不在供应商 ${visionProvider.displayName} 的模型列表中")
            }

        val visionModel = ModelRegistry.enhanceModel(rawVisionModel)
        Logger.i(TAG, "视觉分析[配置]: model=${visionModel.id} supportsVision=${visionModel.supportsVisionInput()} " +
            "inputModalities=${visionModel.inputModalities}")

        if (!visionModel.supportsVisionInput()) {
            throw VisionAnalysisException(
                visionUnsupportedReason(
                    providerName = visionProvider.displayName,
                    modelId = visionModel.id,
                    inputModalities = visionModel.inputModalities,
                ),
            )
        }

        // 4. 根据是否支持 grounding 选择提示词路径
        val useGrounding = visionModel.supportsVisionGrounding()
        val prompt = if (useGrounding) {
            val primitiveShape = primitivePromptShape(visionModel.visionCapabilities)
            VISION_PROMPT_WITH_GROUNDING.format(primitiveShape, userRequest.ifBlank { "(无明确请求,请整体描述图片)" })
        } else {
            VISION_PROMPT_NOTE_ONLY.format(userRequest.ifBlank { "(无明确请求,请整体描述图片)" })
        }

        val userMessage = UIMessage(
            role = MessageRole.USER,
            content = prompt,
            imageBase64List = listOf(preparedImage.base64),
        )

        return try {
            Logger.i(TAG, "开始视觉分析: model=${visionModel.id}, grounding=$useGrounding, userRequest=${userRequest.take(50)}")
            val rawResponse = withTimeout(ANALYSIS_TIMEOUT_MS) {
                retryOnNetworkError(maxRetries = MAX_RETRIES, initialDelayMs = INITIAL_RETRY_DELAY_MS) {
                    callVisionModel(userMessage, visionModel, visionProvider)
                }
            }.trim()

            if (rawResponse.isBlank()) {
                throw VisionAnalysisException("视觉模型返回空描述")
            }

            // 5. grounding 路径解析 JSON 并归一化;note-only 路径直接用原始文本
            val description = if (useGrounding) {
                parseGroundingResponse(rawResponse, visionModel.visionCapabilities)
            } else {
                truncateNote(rawResponse)
            }

            Logger.i(TAG, "视觉分析完成,描述长度: ${description.length}, grounding=$useGrounding")
            "<vision-context>\n$description\n</vision-context>"
        } catch (e: VisionAnalysisException) {
            throw e
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                Logger.w(TAG, "视觉分析超时(${ANALYSIS_TIMEOUT_MS}ms)")
                throw VisionAnalysisException("视觉模型响应超时(${ANALYSIS_TIMEOUT_MS / 1000}s),请稍后重试或更换视觉模型", e)
            }
            throw e
        } catch (e: Exception) {
            val friendlyMsg = friendlyVisionError(e)
            Logger.w(TAG, "视觉分析失败: ${e.message} -> $friendlyMsg", e)
            throw VisionAnalysisException(friendlyMsg, e)
        }
    }

    /**
     * v1.0.4: 根据 [VisionCapabilities.outputFormat] 派生 primitive 的 JSON schema 提示。
     *
     * 按 既有实现 的 `primitivePromptShape`。不同模型原生坐标格式不同:
     *  - "gemini": box_2d: [ymin, xmin, ymax, xmax](Gemini 原生 yxyx,归一化 0-1000)
     *  - "qwen": bbox_2d: [x1, y1, x2, y2] + point_2d: [x, y](Qwen-VL 原生)
     *  - "anchor": visual_anchors 带 role/center/box(Claude computer-use 风格)
     *  - "muse-box"/null: box: [x1, y1, x2, y2](统一坐标格式,xyxy + norm-1000)
     */
    private fun primitivePromptShape(caps: VisionCapabilities?): String {
        return when (resolveOutputFormat(caps?.outputFormat)) {
            "gemini" -> """
                [
                  {"type": "box", "box_2d": [ymin, xmin, ymax, xmax], "ref": "物体简短描述", "confidence": 0.9}
                ]
                box_2d 使用 [ymin, xmin, ymax, xmax] 顺序,归一化到 0-1000
            """.trimIndent()
            "qwen" -> """
                [
                  {"type": "box", "bbox_2d": [x1, y1, x2, y2], "ref": "物体简短描述", "confidence": 0.9},
                  {"type": "point", "point_2d": [x, y], "ref": "点位置描述"}
                ]
                bbox_2d 和 point_2d 使用 [x, y] 顺序,归一化到 0-1000
            """.trimIndent()
            "anchor" -> """
                [
                  {"role": "button|text|object|region", "center": [x, y], "box": [x1, y1, x2, y2], "ref": "元素描述"}
                ]
                visual_anchors 的坐标归一化到 0-1000,box 使用 [x1, y1, x2, y2] 顺序
            """.trimIndent()
            else -> """
                [
                  {"type": "box", "box": [x1, y1, x2, y2], "ref": "物体简短描述", "confidence": 0.9}
                ]
                box 使用 [x1, y1, x2, y2] 顺序(左上角原点),归一化到 0-1000
            """.trimIndent()
        }
    }

    /**
     * v1.0.4: 解析 grounding 响应,归一化坐标,生成含 `<visual-primitives>` 块的描述。
     *
     * 按 既有实现 的 `normalizeBox` + `formatInvalidStructuredNote`。
     *
     * v1.0.5 改进:解析失败时把原始响应作为 `image_overview`(而非 `evidence`),
     * 让文本模型读到主要信息(原版 7 字段全填"无"信息利用率低)。
     */
    private fun parseGroundingResponse(rawResponse: String, caps: VisionCapabilities?): String {
        // 尝试提取 JSON(模型可能返回 markdown 代码块包裹的 JSON)
        val jsonText = extractJson(rawResponse) ?: run {
            Logger.w(TAG, "grounding 响应非 JSON,走兜底 note 路径")
            return formatInvalidStructuredNote(rawResponse)
        }

        return try {
            val jsonObj = io.zer0.common.AppJson.parseToJsonElement(jsonText).let {
                it as? kotlinx.serialization.json.JsonObject ?: return formatInvalidStructuredNote(rawResponse)
            }

            val builder = StringBuilder()
            builder.append("image_overview: ").append(jsonField(jsonObj, "image_overview")).append('\n')
            builder.append("visible_text: ").append(jsonField(jsonObj, "visible_text")).append('\n')
            builder.append("objects_and_layout: ").append(jsonField(jsonObj, "objects_and_layout")).append('\n')
            builder.append("charts_or_data: ").append(jsonField(jsonObj, "charts_or_data")).append('\n')
            builder.append("user_request: ").append(jsonField(jsonObj, "user_request")).append('\n')
            builder.append("user_request_answer: ").append(jsonField(jsonObj, "user_request_answer")).append('\n')
            builder.append("evidence: ").append(jsonField(jsonObj, "evidence")).append('\n')
            builder.append("uncertainty: ").append(jsonField(jsonObj, "uncertainty")).append('\n')

            val primitives = jsonObj["visual_primitives"]
            if (primitives is kotlinx.serialization.json.JsonArray) {
                builder.append("<visual-primitives coord=\"norm-1000\" box_order=\"xyxy\" grounding=\"native\">\n")
                primitives.take(MAX_VISUAL_PRIMITIVES).forEachIndexed { idx, prim ->
                    val normalized = normalizePrimitive(prim, caps)
                    if (normalized != null) {
                        builder.append("- v${idx + 1} | type: ${normalized.type} | ${if (normalized.type == "point") "point" else "box"}: ${normalized.point ?: normalized.box} | ref: ${normalized.ref} | confidence: ${normalized.confidence}\n")
                    }
                }
                builder.append("</visual-primitives>")
            }

            truncateNote(builder.toString())
        } catch (e: Exception) {
            Logger.w(TAG, "解析 grounding JSON 失败,走兜底: ${e.message}")
            formatInvalidStructuredNote(rawResponse)
        }
    }

    /** v1.0.4: 从可能含 markdown 代码块的响应中提取 JSON 文本。 */
    private fun extractJson(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return trimmed
        val codeBlockRegex = Regex("```(?:json)?\\s*\\n?(\\{.*?})\\s*```", RegexOption.DOT_MATCHES_ALL)
        return codeBlockRegex.find(trimmed)?.groupValues?.getOrNull(1)
    }

    /** v1.0.4: 从 JsonObject 中安全提取字符串字段(数组转为逗号分隔)。 */
    private fun jsonField(obj: kotlinx.serialization.json.JsonObject, key: String): String {
        val element = obj[key] ?: return "无"
        return when (element) {
            is kotlinx.serialization.json.JsonPrimitive -> element.content
            is kotlinx.serialization.json.JsonArray -> element.mapNotNull {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            }.joinToString(", ")
            else -> element.toString()
        }
    }

    /** v1.0.4: 归一化 primitive(不同模型格式统一为 xyxy + norm-1000)。 */
    private data class NormalizedPrimitive(
        val type: String,
        val box: String,
        /** v1.0.7: point 类型专用的 2 元组坐标 "[x, y]"(对齐 既有实现 point primitive 格式)。 */
        val point: String? = null,
        val ref: String,
        val confidence: String,
    )

    /** v1.0.4: 归一化单个 primitive,返回 null 表示无法解析。 */
    private fun normalizePrimitive(prim: kotlinx.serialization.json.JsonElement, caps: VisionCapabilities?): NormalizedPrimitive? {
        val obj = prim as? kotlinx.serialization.json.JsonObject ?: return null
        val format = resolveOutputFormat(caps?.outputFormat)

        val ref = (obj["ref"] ?: obj["label"] ?: obj["text"])
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.take(MAX_PRIMITIVE_REF_CHARS) ?: "unknown"

        val confidence = (obj["confidence"])
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "0.5"

        // v1.0.7: 统一所有分支返回 NormalizedPrimitive(原 gemini/else 用 Pair 解构,
        //   与 qwen/anchor 的 NormalizedPrimitive 类型不一致导致编译失败;
        //   且末尾 NormalizedPrimitive(type, box, ref, confidence) 位置参数错位
        //   —— ref 会被填入 point 槽位,confidence 会被填入 ref 槽位)
        return when (format) {
            "gemini" -> {
                val box2d = obj["box_2d"] as? JsonArray
                if (box2d == null || box2d.size < 4) return null
                val ymin = box2d[0].jsonPrimitive.intOrNull ?: return null
                val xmin = box2d[1].jsonPrimitive.intOrNull ?: return null
                val ymax = box2d[2].jsonPrimitive.intOrNull ?: return null
                val xmax = box2d[3].jsonPrimitive.intOrNull ?: return null
                NormalizedPrimitive("box", "[$xmin, $ymin, $xmax, $ymax]", null, ref, confidence)
            }
            "qwen" -> {
                val bbox = obj["bbox_2d"] as? JsonArray
                if (bbox != null && bbox.size >= 4) {
                    NormalizedPrimitive("box", "[${bbox[0]}, ${bbox[1]}, ${bbox[2]}, ${bbox[3]}]", null, ref, confidence)
                } else {
                    val point = obj["point_2d"] as? JsonArray
                    if (point != null && point.size >= 2) {
                        val x = point[0].jsonPrimitive.intOrNull ?: return null
                        val y = point[1].jsonPrimitive.intOrNull ?: return null
                        // v1.0.7: point 输出 2 元组 "[x, y]"(对齐 既有实现 point primitive),
                        // 原版误把点复制成 4 元组 "[x, y, x, y]" 当 box,文本模型无法正确解析点坐标
                        NormalizedPrimitive("point", "[$x, $y]", "[$x, $y]", ref, confidence)
                    } else null
                }
            }
            "anchor" -> {
                val box = obj["box"] as? JsonArray
                val role = (obj["role"]?.let { (it as? JsonPrimitive)?.content }) ?: "object"
                if (box != null && box.size >= 4) {
                    NormalizedPrimitive(role, "[${box[0]}, ${box[1]}, ${box[2]}, ${box[3]}]", null, ref, confidence)
                } else {
                    val center = obj["center"] as? JsonArray
                    if (center != null && center.size >= 2) {
                        val x = center[0].jsonPrimitive.intOrNull ?: return null
                        val y = center[1].jsonPrimitive.intOrNull ?: return null
                        // v1.0.7: point 输出 2 元组(同 qwen 分支修复)
                        NormalizedPrimitive(role, "[$x, $y]", "[$x, $y]", ref, confidence)
                    } else null
                }
            }
            else -> {
                val box = obj["box"] as? kotlinx.serialization.json.JsonArray
                if (box == null || box.size < 4) return null
                NormalizedPrimitive("box", "[${box[0]}, ${box[1]}, ${box[2]}, ${box[3]}]", null, ref, confidence)
            }
        }
    }

    /**
     * v1.0.5: JSON 解析失败时的兜底 note(保留原始响应信息)。
     *
     * 改进:把原始响应作为 `image_overview`(让文本模型读到主要信息),
     * 而非原版的 `evidence`(7 字段全填"无")。
     */
    /** 解析输出格式，兼容旧值 "hanako" 并映射到 "muse-box"。 */
    private fun resolveOutputFormat(raw: String?): String = when (raw) {
        "hanako" -> "muse-box"
        else -> raw ?: "muse-box"
    }

    private fun formatInvalidStructuredNote(rawResponse: String): String {
        val truncated = if (rawResponse.length > 1200) rawResponse.take(1200) + "[truncated]" else rawResponse
        return buildString {
            append("image_overview: ").append(truncated).append('\n')
            append("visible_text: 视觉模型未返回结构化 JSON,以上为原始响应\n")
            append("objects_and_layout: 无\n")
            append("charts_or_data: 无\n")
            append("user_request: 无\n")
            append("user_request_answer: 无\n")
            append("evidence: 无\n")
            append("uncertainty: 视觉模型未返回结构化 JSON")
        }
    }

    /** v1.0.4: 截断 note 到 [MAX_NOTE_CHARS]。 */
    private fun truncateNote(note: String): String {
        return if (note.length > MAX_NOTE_CHARS) note.take(MAX_NOTE_CHARS) + "\n[truncated]" else note
    }

    /**
     * v1.0.2 (P3): 调用视觉模型,优先 completeText,不支持时降级 streamChat。
     */
    private suspend fun callVisionModel(
        userMessage: UIMessage,
        visionModel: Model,
        visionProvider: ProviderConfig,
    ): String {
        return try {
            val completion = chatService.completeText(
                messages = listOf(userMessage),
                model = visionModel,
                providerConfig = visionProvider,
                tools = null,
            )
            // v1.0.74 fix: 剥离 <think> 推理标签,防止思考内容混入 OCR/看图结果
            io.zer0.muse.transformer.stripThinkTags(
                effectiveVisionResponseText(completion.text, completion.reasoningContent),
            )
        } catch (e: UnsupportedOperationException) {
            Logger.w(TAG, "completeText 不支持,降级 streamChat: ${e.message}")
            collectStreamText(userMessage, visionModel, visionProvider)
        }
    }

    /**
     * v1.0.2 (P3): 通过 streamChat 收集完整响应文本(降级路径)。
     */
    private suspend fun collectStreamText(
        userMessage: UIMessage,
        visionModel: Model,
        visionProvider: ProviderConfig,
    ): String {
        val builder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        chatService.streamChat(
            messages = listOf(userMessage),
            model = visionModel,
            providerConfig = visionProvider,
            tools = null,
        ).collect { event ->
            when (event) {
                is ChatStreamEvent.ContentDelta -> builder.append(event.delta)
                is ChatStreamEvent.ReasoningDelta -> reasoningBuilder.append(event.delta)
                is ChatStreamEvent.Error -> throw ProviderException(
                    providerError = event.providerError ?: ProviderError.Unknown(displayMessage = event.message),
                    cause = event.throwable,
                )
                is ChatStreamEvent.Done -> return@collect
                else -> Unit
            }
        }
        return io.zer0.muse.transformer.stripThinkTags(
            effectiveVisionResponseText(builder.toString(), reasoningBuilder.toString()),
        )
    }

    /**
     * v1.0.1 (P2): 将底层异常归一化为用户友好的视觉分析错误消息。
     *
     * v1.0.27 Phase 5-B: 优先走类型路径 `(e as? ProviderException)?.providerError`,
     * 兜底才用 [inferFromMessage] 字符串推断。
     */
    private fun friendlyVisionError(e: Throwable): String {
        val message = e.message.orEmpty()
        val providerError = (e as? ProviderException)?.providerError
            ?: inferFromMessage(message, e)
        return when (providerError) {
            is ProviderError.AuthError -> "视觉模型 API Key 无效或权限不足(401/403),请检查供应商配置"
            is ProviderError.RateLimit -> "视觉模型限流(429),请稍后重试或切换视觉模型"
            is ProviderError.ServerError -> "视觉模型服务端临时不可用(${providerError.httpCode}),请稍后重试"
            is ProviderError.Network -> "视觉模型网络连接失败,请检查网络或供应商地址"
            is ProviderError.InvalidRequest -> {
                if (providerError.httpCode == 413 || message.contains("413", ignoreCase = true) ||
                    message.contains("too large", ignoreCase = true) ||
                    message.contains("payload too large", ignoreCase = true)
                ) {
                    "图片过大(413),请压缩后重试或更换支持大图的视觉模型"
                } else {
                    "视觉模型请求参数错误: ${providerError.displayMessage}"
                }
            }
            is ProviderError.Cancelled -> "视觉分析已取消"
            is ProviderError.Unknown -> "视觉分析失败: ${providerError.displayMessage}"
            null -> "视觉分析失败: $message"
        }
    }

    /**
     * v1.0.5: 批量分析多张预处理后的图片,返回所有成功描述的列表。
     *
     * 改进:
     *  - 接收 [VisionImagePreprocessor.PreparedImage] 而非裸 base64
     *  - 缓存 key 改用 [VisionCache.CacheKey] 数据类,字段语义明确
     *  - 缓存 key 加入 userRequest hash(同一张图不同问题不共享)
     *
     * 保留:
     *  - 并发执行(coroutineScope { async }.awaitAll)
     *  - 进度 StateFlow
     *  - 缓存 key 含 prompt 版本号
     *
     * @param preparedImages 预处理后的图片列表
     * @param userRequest 用户的具体请求,传给视觉模型做针对性分析
     * @param sessionId 当前会话 id;为 null 时不使用缓存
     * @param messageId v1.0.16: 正在分析的消息 id,驱动 UI 只在该消息上显示进度
     * @param onProgress v1.0.20: 进度回调 (current, total),与 [progressFlow] 并行触发
     * @return 视觉描述列表(含 `<vision-context>` 标签)
     * @throws VisionAnalysisException 全部图片分析失败时抛出
     */
    private suspend fun analyzeImages(
        preparedImages: List<VisionImagePreprocessor.PreparedImage>,
        userRequest: String = "",
        sessionId: String? = null,
        /** v1.0.16: 正在分析的消息 id,驱动 UI 只在该消息上显示进度 */
        messageId: String? = null,
        /** v1.0.20: 进度回调,每完成一张图触发 (current=已完成数, total=总图片数) */
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): List<String> {
        if (preparedImages.isEmpty()) return emptyList()

        val visionModelId = resultOf { settings.visionModelIdFlow.first() }.getOrNull() ?: ""
        val userRequestHash = VisionImagePreprocessor.hashShort(userRequest)

        val lastError = java.util.concurrent.atomic.AtomicReference<VisionAnalysisException?>(null)
        val successCount = java.util.concurrent.atomic.AtomicInteger(0)

        _progressFlow.value = VisionProgress(idle = false, index = 0, total = preparedImages.size, messageId = messageId)

        /** v1.0.20: 统一更新进度 — 同时更新 progressFlow 和 onProgress 回调 */
        fun reportProgress(done: Int) {
            _progressFlow.value = VisionProgress(idle = false, index = done, total = preparedImages.size, messageId = messageId)
            onProgress?.invoke(done, preparedImages.size)
        }

        try {
            val results = coroutineScope {
                preparedImages.mapIndexed { index, prepared ->
                    async {
                        try {
                            // v1.0.5: 缓存 key 用 CacheKey 数据类,字段语义明确
                            val cacheKey = VisionCache.CacheKey(
                                imageHash = "",  // get() 内部会算
                                modelId = visionModelId,
                                userRequestHash = userRequestHash,
                                promptVersion = VISION_PROMPT_VERSION,
                            )

                            val cached = if (sessionId != null && visionModelId.isNotBlank()) {
                                visionCache.get(sessionId, prepared.base64, cacheKey)
                            } else null

                            if (cached != null) {
                                Logger.d(TAG, "第 ${index + 1}/${preparedImages.size} 张图片命中视觉缓存")
                                val done = successCount.incrementAndGet()
                                reportProgress(done)
                                "<vision-context>\n$cached\n</vision-context>"
                            } else {
                                val description = analyzeImage(prepared, userRequest = userRequest)

                                if (sessionId != null && visionModelId.isNotBlank()) {
                                    val rawDescription = description
                                        .removePrefix("<vision-context>\n")
                                        .removeSuffix("\n</vision-context>")
                                    visionCache.put(sessionId, prepared.base64, cacheKey, rawDescription)
                                }

                                val done = successCount.incrementAndGet()
                                reportProgress(done)
                                description
                            }
                        } catch (e: VisionAnalysisException) {
                            lastError.set(e)
                            Logger.w(TAG, "第 ${index + 1}/${preparedImages.size} 张图片分析失败: ${e.message}")
                            null
                        }
                    }
                }.map { it.await() }
            }

            val descriptions = results.filterNotNull()
            val error = lastError.get()
            if (descriptions.isEmpty() && error != null) {
                throw error
            }
            return descriptions
        } finally {
            // 确保协程被取消时 progressFlow 也重置,避免 UI 卡在"分析中"
            _progressFlow.value = VisionProgress(idle = true, index = preparedImages.size, total = preparedImages.size, messageId = null)
        }
    }

    /**
     * 构建视觉上下文前缀,注入到用户消息开头。
     */
    fun buildVisionContext(descriptions: List<String>): String {
        if (descriptions.isEmpty()) return ""
        return descriptions.joinToString("\n\n") + "\n\n"
    }

    /**
     * 构建视觉辅助失败提示文本。
     *
     * 按 既有实现 的 `visionFailureNotice`:把失败信息注入到用户消息中,
     * 让纯文本模型明确知道"本轮没有图片内容",并引导它请求用户重试或检查配置。
     */
    fun buildFailureNotice(reason: String?): String {
        val detail = if (reason.isNullOrBlank()) "" else "($reason)"
        return """
            <vision-context>
            [图片分析失败:辅助视觉模型暂时不可用,本轮不会把图片内容传给文本模型。请明确说明你没有看到图片,并请用户稍后重试或检查视觉模型配置$detail。]
            </vision-context>
        """.trimIndent()
    }
}

/**
 * v1.0.2 (P1): 视觉分析进度数据类。
 *
 * @param idle 是否空闲(true=未在分析,false=分析中)
 * @param index 已完成的图片数量(0..total)
 * @param total 总图片数量
 */
data class VisionProgress(
    val idle: Boolean,
    val index: Int,
    val total: Int,
    /** v1.0.16: 正在分析的消息 id,用于 UI 只在该消息上显示进度,避免所有 USER 消息同时显示"分析中" */
    val messageId: String? = null,
) {
    val isActive: Boolean get() = !idle && total > 0
    val ratio: Float get() = if (total > 0) index.toFloat() / total else 0f
}
