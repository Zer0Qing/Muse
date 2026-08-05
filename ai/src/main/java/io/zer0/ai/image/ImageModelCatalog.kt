package io.zer0.ai.image

/**
 * v0.36: 绘图模型元数据。
 *
 * 描述一个绘图模型支持的尺寸、质量、风格、图生图等能力,
 * 用于 UI 选项过滤与服务层参数校验。
 */
data class ImageModel(
    /** 模型 ID,如 dall-e-2 / dall-e-3 / gpt-image-1。 */
    val id: String,
    /** 显示名称。 */
    val displayName: String,
    /** 提供商,如 OpenAI / DashScope / MiniMax。 */
    val provider: String,
    /** 支持的尺寸列表。 */
    val supportedSizes: List<String>,
    /** 支持的质量选项。 */
    val supportedQualities: List<String> = emptyList(),
    /** 支持的风格选项。 */
    val supportedStyles: List<String> = emptyList(),
    /** 是否支持图生图(/images/edits)。 */
    val supportsReferenceImage: Boolean = false,
    /** 最大生成数量 n。 */
    val maxN: Int = 1,
    /** 默认尺寸。 */
    val defaultSize: String = supportedSizes.firstOrNull() ?: "1024x1024",
    /** 默认质量。 */
    val defaultQuality: String = supportedQualities.firstOrNull() ?: "",
    /** 默认风格。 */
    val defaultStyle: String = supportedStyles.firstOrNull() ?: "",
    /** 是否支持 response_format=b64_json。 */
    val supportsB64Json: Boolean = true,
    /**
     * H-IMG2: 是否支持 response_format 参数。
     * dall-e-2 / dall-e-3 = true;gpt-image-1 = false(该参数会被服务端拒绝)。
     */
    val supportsResponseFormatParam: Boolean = true,
    /**
     * M-IMG8: 输出图片 MIME 类型(用于 b64_json 拼 data URI)。
     * 当前 OpenAI 系列绘图模型输出均为 PNG。
     */
    val outputMime: String = "image/png",
)

/**
 * v0.36: 预设绘图模型目录。
 *
 * 按 既有实现 思路维护一份常用绘图模型清单,UI 据此展示可选项,
 * 避免用户手动输入模型 ID。
 */
object ImageModelCatalog {
    /**
     * v1.136: 默认绘图模型 ID。
     *
     * 当调用方未传 model、且 ProviderSpecificConfig.OpenAI.imageModel 也未配置时,
     * 回退到此模型,保证请求体始终携带 model 字段。
     */
    const val DEFAULT_MODEL_ID = "dall-e-3"

    /** 默认绘图模型元数据。 */
    fun defaultModel(): ImageModel = findById(DEFAULT_MODEL_ID) ?: models.first()

    // 注:wanx-v1(通义万相)与 minimax-image-01 已移除 —
    // 二者协议不兼容 OpenAI /images/generations,需独立 Adapter。
    // v1.0.18: agnes-image-2.1-flash 接入 AgnesImageProvider,不再走 OpenAI 兼容路径。
    private val models = listOf(
        ImageModel(
            id = "dall-e-2",
            displayName = "DALL-E 2",
            provider = "OpenAI",
            supportedSizes = listOf("256x256", "512x512", "1024x1024"),
            supportedQualities = emptyList(),
            supportedStyles = emptyList(),
            supportsReferenceImage = true,
            maxN = 10,
            supportsB64Json = true,
            supportsResponseFormatParam = true,
            // L-IMG3: 默认尺寸改为 1024x1024(原先回退到首个 256x256 偏小)
            defaultSize = "1024x1024",
        ),
        ImageModel(
            id = "dall-e-3",
            displayName = "DALL-E 3",
            provider = "OpenAI",
            supportedSizes = listOf("1024x1024", "1792x1024", "1024x1792"),
            supportedQualities = listOf("standard", "hd"),
            supportedStyles = listOf("vivid", "natural"),
            supportsReferenceImage = false,
            maxN = 1,
            supportsB64Json = true,
            supportsResponseFormatParam = true,
        ),
        ImageModel(
            id = "gpt-image-1",
            displayName = "GPT Image 1",
            provider = "OpenAI",
            supportedSizes = listOf(
                "1024x1024",
                "1024x1536",
                "1536x1024",
                "1536x1536",
            ),
            supportedQualities = listOf("high", "medium", "low", "auto"),
            supportedStyles = emptyList(),
            // M-IMG4: gpt-image-1 支持参考图(/images/edits)
            supportsReferenceImage = true,
            maxN = 1,
            supportsB64Json = true,
            // H-IMG2: gpt-image-1 不支持 response_format 参数
            supportsResponseFormatParam = false,
        ),
        ImageModel(
            // v1.0.18: Agnes AI 图片模型,走 AgnesImageProvider(独立适配器,非 OpenAI 兼容)。
            // 尺寸用比例表示(AgnesImageProvider.SIZE_RATIOS 会映射为像素值);
            // 支持参考图(通过 extra_body.image 传入);同步返回;默认 b64_json。
            id = "agnes-image-2.1-flash",
            displayName = "Agnes Image 2.1 Flash",
            provider = "Agnes",
            supportedSizes = listOf("1:1", "16:9", "21:9", "9:16", "4:3", "3:4", "3:2", "2:3"),
            supportedQualities = emptyList(),
            supportedStyles = emptyList(),
            supportsReferenceImage = true,
            maxN = 1,
            supportsB64Json = true,
            supportsResponseFormatParam = true,
            // 默认 3:2(对齐 既有实现 AGNES_IMAGE_DEFAULTS.ratio)
            defaultSize = "3:2",
            // Agnes 输出 PNG
            outputMime = "image/png",
        ),
    )

    /** 所有预设模型。 */
    fun all(): List<ImageModel> = models

    /** 按 ID 查找模型。 */
    fun findById(id: String?): ImageModel? = models.find { it.id == id }

    /** 判断某个模型 ID 是否在目录中。 */
    fun contains(id: String?): Boolean = id != null && models.any { it.id == id }

    /** 取模型默认尺寸;找不到则回退。 */
    fun defaultSizeFor(id: String?, fallback: String = "1024x1024"): String {
        return findById(id)?.defaultSize ?: fallback
    }

    /**
     * v0.36-fix: 解析绘图模型元数据。
     *
     * 对于目录中的已知模型(如 dall-e-3 / gpt-image-1)返回目录数据;
     * 对于用户自定义或未收录的模型,返回一组宽松默认值,
     * 使其仍能正常展示尺寸/质量/风格选项并走 Image API。
     */
    fun resolveById(id: String?): ImageModel? {
        if (id.isNullOrBlank()) return null
        return findById(id) ?: ImageModel(
            id = id,
            displayName = id,
            provider = "",
            supportedSizes = listOf(
                "1024x1024",
                "1792x1024",
                "1024x1792",
                "1024x1536",
                "1536x1024",
                "1536x1536",
            ),
            supportedQualities = listOf("standard", "hd", "high", "medium", "low", "auto"),
            supportedStyles = listOf("vivid", "natural"),
            supportsReferenceImage = false,
            // L-IMG10: 未知模型 maxN 取保守值 1(原先 10 易触发按次计费超额)
            maxN = 1,
            supportsB64Json = true,
            supportsResponseFormatParam = true,
        )
    }
}
