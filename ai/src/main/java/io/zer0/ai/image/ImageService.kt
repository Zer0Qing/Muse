package io.zer0.ai.image

import io.zer0.ai.ProviderConfigStore
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.common.ErrorCode
import io.zer0.common.Logger
import io.zer0.common.toMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 5-G / v1.0.18: 图片生成服务。
 *
 * v1.0.18 重构:移除硬性的 OPENAI / OPENAI_RESPONSES 类型检查,改为通过
 * [ImageProviderRegistry] 按 [ProviderConfig] 选择合适的 [ImageProvider],
 * 让 Agnes / Gemini / 国产中转站等都能各自走适配器实现。
 *
 * 设计:
 *  - 调用方仍用 [generate] 拿到 `List<String>`(URL 或 data URI),保持向后兼容;
 *  - 内部把 [ImageGenParams] 转为 [ImageGenRequest],委托 [ImageProvider] 完成 HTTP 调用;
 *  - 同步任务(provider.submit 直接返回 images)立即返回;
 *  - 异步任务(provider.submit 返回 taskId)启动轮询,参考 QingTian poller:
 *      5s tick、按任务年龄自适应频率(<2min 每 tick / 2-10min 每 3 tick / 10min+ 每 6 tick)、
 *      连续 5 次错误才判定失败。
 *  - 默认模型不再硬编码 dall-e-3,改为从 [ProviderConfig.models] 筛选
 *    outputModalities 含 "image" 的模型;找不到则用 provider 自身默认值。
 */
class ImageService(
    private val configStore: ProviderConfigStore,
    private val registry: ImageProviderRegistry,
) {

    /**
     * 生成图片。返回图片 URL / data URI 列表(通常 1 张,n=1)。
     *
     * 若 [params.referenceImageUri] 非空,则触发图生图路径;
     * 否则走文生图。具体端点与编码方式由所选 [ImageProvider] 决定。
     *
     * @param prompt 图片描述 / 修改指令
     * @param params 绘图参数(模型 / 尺寸 / 质量 / 风格 / 数量 / 参考图等)
     * @param providerConfig 显式指定 Provider;null 时用 configStore 当前激活 Provider
     */
    suspend fun generate(
        prompt: String,
        params: ImageGenParams = ImageGenParams(),
        providerConfig: ProviderConfig? = null,
    ): List<String> = withContext(Dispatchers.IO) {
        val config = providerConfig ?: configStore.get()
            ?: error(ErrorCode.NO_PROVIDER_CONFIGURED.toMessage())
        if (config.apiKey.isBlank() && !config.allowMissingApiKey) {
            error(ErrorCode.IMAGE_API_KEY_MISSING.toMessage())
        }

        // v1.0.18: 通过 Registry 选择 provider,不再硬性要求 OPENAI 类型
        val provider = registry.selectFor(config)
            ?: error(ErrorCode.IMAGE_UNSUPPORTED_MODEL.toMessage("no_provider_for_${config.id}"))

        // 模型选择优先级:
        //  1. params.model 显式传入;
        //  2. ProviderSpecificConfig.OpenAI.imageModel(OpenAI 兼容 provider);
        //  3. ProviderConfig.models 中首个 outputModalities 含 "image" 的模型;
        //  4. provider 自身默认值(AgnesImageProvider.DEFAULT_MODEL_ID / ImageModelCatalog.DEFAULT_MODEL_ID)
        val effectiveModel = resolveModelId(params, config, provider)

        Logger.i(
            TAG,
            "generate: provider=${provider.providerId} model=$effectiveModel " +
                "size=${params.size} n=${params.n} ref=${!params.referenceImageUri.isNullOrBlank()}",
        )

        val referenceImages = params.referenceImageUri?.takeIf { it.isNotBlank() }?.let { listOf(it) }
            ?: emptyList()
        val request = ImageGenRequest(
            prompt = prompt,
            model = effectiveModel,
            size = params.size,
            quality = params.quality,
            style = params.style,
            n = params.n,
            referenceImages = referenceImages,
            responseFormat = params.responseFormat,
            config = config,
        )

        val result = try {
            provider.submit(request)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // error() 抛出的业务错误原样传播
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "image submit failed: ${e.message}")
            error(ErrorCode.IMAGE_GEN_FAILED.toMessage(e.message ?: ""))
        }

        // 同步任务:直接转换 images
        val finalImages = if (!result.isAsync || result.taskId == null) {
            result.images
        } else {
            // 异步任务:轮询直到终态
            pollUntilDone(provider, result.taskId)
        }
        if (finalImages.isEmpty()) error(ErrorCode.IMAGE_NO_RESULTS.toMessage())
        convertToOutputStrings(finalImages)
    }

    /**
     * 解析最终模型 ID(不再硬编码 dall-e-3)。
     */
    private fun resolveModelId(
        params: ImageGenParams,
        config: ProviderConfig,
        provider: ImageProvider,
    ): String {
        // 1. 显式传入
        params.model.takeIf { it.isNotBlank() }?.let { return it }

        // 2. ProviderSpecificConfig.OpenAI.imageModel
        val specific = config.resolvedSpecific()
        if (specific is ProviderSpecificConfig.OpenAI) {
            specific.imageModel.takeIf { it.isNotBlank() }?.let { return it }
        }

        // 3. ProviderConfig.models 中首个支持图片输出的模型
        config.models.firstOrNull { it.supportsImageOutput() }?.let { return it.id }

        // 4. provider 自身默认值
        return when (provider.providerId) {
            AgnesImageProvider.PROVIDER_ID -> AgnesImageProvider.DEFAULT_MODEL_ID
            else -> ImageModelCatalog.DEFAULT_MODEL_ID
        }
    }

    /**
     * 异步任务轮询,参考 QingTian poller:
     *  - 5s tick;
     *  - 按任务年龄自适应频率(<2min 每 tick / 2-10min 每 3 tick / 10min+ 每 6 tick);
     *  - 连续 5 次错误才判定失败;
     *  - 总超时 [POLL_TIMEOUT_MS](10 分钟)。
     */
    private suspend fun pollUntilDone(
        provider: ImageProvider,
        taskId: String,
    ): List<GeneratedImage> {
        val createdAt = System.currentTimeMillis()
        var tick = 0
        var consecutiveErrors = 0
        // 记录任务失败次数(并发安全,对齐 QingTian _errorCounts)
        errorCounts.remove(taskId)
        while (true) {
            val ageMs = System.currentTimeMillis() - createdAt
            if (ageMs > POLL_TIMEOUT_MS) {
                error(ErrorCode.IMAGE_GEN_FAILED.toMessage("poll timeout taskId=$taskId"))
            }
            tick++
            if (!shouldCheckThisTick(ageMs, tick)) {
                delay(TICK_MS)
                continue
            }
            try {
                val pollResult = provider.poll(taskId)
                consecutiveErrors = 0
                errorCounts.remove(taskId)
                when (pollResult.status) {
                    PollStatus.SUCCESS -> return pollResult.images
                    PollStatus.FAILED -> {
                        val msg = pollResult.errorMessage ?: "image generation failed"
                        error(ErrorCode.IMAGE_GEN_FAILED.toMessage(msg))
                    }
                    PollStatus.PENDING -> {
                        // 继续轮询
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                // provider 用 error() 抛出的业务错误,原样传播
                throw e
            } catch (e: Exception) {
                consecutiveErrors++
                errorCounts[taskId] = consecutiveErrors
                Logger.w(
                    TAG,
                    "poll $taskId failed ($consecutiveErrors/$MAX_CONSECUTIVE_ERRORS): ${e.message}",
                )
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    errorCounts.remove(taskId)
                    error(ErrorCode.IMAGE_GEN_FAILED.toMessage("poll failed ${consecutiveErrors}x: ${e.message}"))
                }
            }
            delay(TICK_MS)
        }
    }

    /**
     * 决定当前 tick 是否触发真实查询(对齐 QingTian shouldCheckThisTick)。
     */
    private fun shouldCheckThisTick(ageMs: Long, tickCount: Int): Boolean {
        return when {
            ageMs < TWO_MINUTES_MS -> true                 // < 2 min: 每 tick
            ageMs < TEN_MINUTES_MS -> tickCount % 3 == 0   // 2-10 min: 每 3 tick
            else -> tickCount % 6 == 0                     // 10 min+: 每 6 tick
        }
    }

    /**
     * 把 [GeneratedImage] 列表转为 `List<String>`(向后兼容旧调用方)。
     *
     *  - base64 优先 → 拼 data URI(若 provider 在 base64 前缀带了 mime,如 "image/png|xxx",则剥离);
     *  - 否则返回 url;
     *  - 两者均空则跳过。
     */
    private fun convertToOutputStrings(images: List<GeneratedImage>): List<String> {
        return images.mapNotNull { img ->
            when {
                !img.base64.isNullOrBlank() -> {
                    // OpenAIImageProvider 会在 base64 前加 "mime|" 前缀携带 mime;此处剥离
                    val (mime, raw) = if (img.base64.contains("|")) {
                        img.base64.substringBefore("|") to img.base64.substringAfter("|")
                    } else {
                        "image/png" to img.base64
                    }
                    "data:$mime;base64,$raw"
                }
                !img.url.isNullOrBlank() -> img.url
                else -> null
            }
        }
    }

    companion object {
        private const val TAG = "ImageService"

        /** 轮询 tick 间隔(5 秒,对齐 QingTian TICK_MS)。 */
        private const val TICK_MS = 5_000L

        /** 2 分钟(对齐 QingTian TWO_MINUTES)。 */
        private const val TWO_MINUTES_MS = 2L * 60 * 1000

        /** 10 分钟(对齐 QingTian TEN_MINUTES)。 */
        private const val TEN_MINUTES_MS = 10L * 60 * 1000

        /** 轮询总超时(10 分钟,与 TEN_MINUTES_MS 对齐)。 */
        private const val POLL_TIMEOUT_MS = 10L * 60 * 1000

        /** 连续错误上限(对齐 QingTian MAX_CONSECUTIVE_ERRORS)。 */
        private const val MAX_CONSECUTIVE_ERRORS = 5

        /** taskId → 连续错误计数(对齐 QingTian _errorCounts,并发安全)。 */
        private val errorCounts = ConcurrentHashMap<String, Int>()
    }
}

/**
 * v0.34: 图片生成请求参数(调用方使用)。
 *
 * v1.0.18: 内部由 [ImageService] 转为 [ImageGenRequest] 后再委托 [ImageProvider]。
 * 保留此数据类是为了向后兼容 [io.zer0.muse.ui.ChatViewModel] /
 * [io.zer0.muse.ui.chat.ImageGenCoordinator] / [io.zer0.muse.tools.SkillExecutor] 等调用方。
 */
data class ImageGenParams(
    /** 绘图模型 ID,如 dall-e-3 / gpt-image-1 / agnes-image-2.1-flash。 */
    val model: String = "",
    /** 图片尺寸,如 1024x1024 / 1792x1024 / 1:1(比例,Agnes 支持)。 */
    val size: String = "1024x1024",
    /** 图片质量:standard / hd。 */
    val quality: String = "standard",
    /** 图片风格:vivid / natural。 */
    val style: String = "vivid",
    /** 返回格式:url / b64_json。 */
    val responseFormat: String = "url",
    /** 生成数量。 */
    val n: Int = 1,
    /**
     * 参考图 URI(http/https/data URI/本地 file)。
     * 非空时触发图生图路径。
     */
    val referenceImageUri: String? = null,
)
