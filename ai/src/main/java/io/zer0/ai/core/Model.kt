package io.zer0.ai.core

import kotlinx.serialization.Serializable

/**
 * 模型标识。同一 Provider 下多个模型通过 [id] 区分,
 * 调用时填入 OpenAI 的 `model` 字段。
 *
 * Phase 8.1 扩展: 加 [abilities] / [tools] / [inputModalities] / [outputModalities] 字段,
 * 独立编写。
 * 用于声明模型能力(工具调用/推理/视觉),让 UI 和 Provider 据此决定可用功能。
 *
 * @param abilities 模型能力集合(空集表示未声明,按保守"不支持"兜底)
 * @param tools 内置工具声明(如 Gemini 的 Search/UrlContext/ImageGeneration)
 * @param inputModalities 输入模态(默认 ["text"]);视觉模型加 "image"
 * @param outputModalities 输出模态(默认 ["text"]);绘图模型加 "image"
 */
@Serializable
data class Model(
    val id: String,
    val name: String = id,
    val providerId: String,
    val maxOutputTokens: Int? = null,
    /**
     * v0.45: 模型上下文窗口大小(token 数)。
     *
     * 用于 UI 层展示"上下文占用小圈"(当前对话 token / contextWindow 比例)。
     * null 或 0 表示未知,UI 不显示占用圆环。
     * 序列化兼容:旧 JSON 无此字段时回退到 null 默认值。
     */
    val contextWindow: Int? = null,
    val supportsVision: Boolean = false,
    val supportsStreaming: Boolean = true,
    val supportsVideo: Boolean = false,
    val abilities: Set<ModelAbility> = emptySet(),
    val tools: Set<BuiltInTool> = emptySet(),
    val inputModalities: Set<String> = setOf("text"),
    val outputModalities: Set<String> = setOf("text"),
    /**
     * v1.0.4: 视觉能力声明(grounding 坐标定位 + 输出格式)。
     *
     * 参考 openhanako 的 `visionCapabilities` 字段。当模型支持在图片上定位物体
     * (返回坐标框)时设为非 null,VisionBridge 会走 structured+primitives 路径,
     * 让视觉模型不仅描述图片,还返回归一化坐标,纯文本模型可基于坐标说"点击 [320,240]"。
     *
     * null 或 grounding=false 表示模型只能描述图片(note-only 路径)。
     */
    val visionCapabilities: VisionCapabilities? = null,
) {
    /**
     * 便捷判断:是否支持工具调用。
     * v1.80 (M-CORE6): 空集(未声明)改为保守 false,避免向不支持的模型发送 tools 字段导致 400。
     */
    fun supportsToolCalling(): Boolean = ModelAbility.TOOL in abilities

    /** 便捷判断:是否支持推理。v1.80: 同 M-CORE6,空集保守 false。 */
    fun supportsReasoning(): Boolean = ModelAbility.REASONING in abilities

    /** 便捷判断:是否支持视觉输入。 */
    fun supportsVisionInput(): Boolean =
        supportsVision || "image" in inputModalities

    /** 便捷判断:是否支持图片输出(绘图模型)。 */
    fun supportsImageOutput(): Boolean = "image" in outputModalities

    /** 便捷判断:是否支持视频输出。 */
    fun supportsVideoOutput(): Boolean = supportsVideo

    /** v1.0.4: 便捷判断:是否支持视觉 grounding(坐标定位)。 */
    fun supportsVisionGrounding(): Boolean =
        visionCapabilities?.grounding == true
}

/**
 * v1.0.4: 视觉能力声明。
 *
 * 参考 openhanako 的 `visionCapabilities` 字段设计。
 *
 * @param grounding 是否支持在图片上定位物体(返回坐标框)
 * @param outputFormat 坐标输出格式,决定提示词要求和归一化逻辑:
 *  - "gemini": box_2d: [ymin, xmin, ymax, xmax](Gemini 原生 yxyx,归一化 0-1000)
 *  - "qwen": bbox_2d: [x1, y1, x2, y2] + point_2d: [x, y](Qwen-VL 原生)
 *  - "anchor": visual_anchors 带 role/center/box(Claude computer-use 风格)
 *  - "hanako": box: [x1, y1, x2, y2](Hana 自定义统一格式,xyxy + norm-1000)
 *  - null: 未指定,按 "hanako" 默认格式处理
 */
@Serializable
data class VisionCapabilities(
    val grounding: Boolean = false,
    val outputFormat: String? = null,
)

/**
 * 模型能力枚举。
 * - [TOOL]: 支持函数调用(function calling)
 * - [REASONING]: 支持推理链(thinking / o1 系列)
 */
@Serializable
enum class ModelAbility { TOOL, REASONING }

/**
 * 内置工具枚举(由 Provider 原生支持,非 function calling)。
 * 主要用于 Gemini 原生工具。
 * - [SEARCH]: Google Search(Gemini grounding)
 * - [URL_CONTEXT]: URL 内容提取(Gemini)
 * - [IMAGE_GENERATION]: 内置绘图(Gemini Imagen)
 */
@Serializable
enum class BuiltInTool { SEARCH, URL_CONTEXT, IMAGE_GENERATION }
