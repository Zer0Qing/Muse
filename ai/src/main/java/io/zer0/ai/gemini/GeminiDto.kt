package io.zer0.ai.gemini

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Google Gemini API 数据传输对象。
 *
 * API: generateContent (非流式) / streamGenerateContent?alt=sse (流式)
 *  - URL 格式: /v1beta/models/{model}:generateContent?key=...
 *  - 认证: API key 作为 query param
 *  - systemInstruction 是顶层字段(parts 数组)
 *  - 流式: 用 alt=sse 返回标准 SSE,每个 data: 是一个 GenerateContentResponse
 *  - 内容增量: candidates[0].content.parts[0].text
 *
 * Phase 8.6 扩展:
 *  - GeminiPart 支持 inlineData(图片输入 / 图片输出)
 *  - GeminiGenerationConfig 支持 responseModalities(开启绘图模式)
 *  - responseModalities = ["TEXT", "IMAGE"] 让 Gemini 返回文本 + 图片混合内容
 *
 * H-GEM2/H-GEM4 扩展(v1.80):
 *  - tools / functionDeclarations / functionCall / functionResponse(函数调用)
 *  - safetySettings(安全阈值设置)
 *  - promptFeedback.blockReason(提示级安全拦截)
 */

@Serializable
internal data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null,
    val generationConfig: GeminiGenerationConfig? = null,
    /** H-GEM2: 工具定义列表,启用 function calling。 */
    val tools: List<GeminiTool>? = null,
    /** H-GEM4: 安全阈值设置(可选,控制各风险类别的拦截等级)。 */
    val safetySettings: List<GeminiSafetySetting>? = null,
)

@Serializable
internal data class GeminiContent(
    val role: String,  // "user" / "model"(Gemini 用 "model" 而非 "assistant")
    val parts: List<GeminiPart>,
)

/**
 * Part 支持文本、图片(inlineData)、文件(fileData)、函数调用(functionCall)、函数响应(functionResponse)。
 *
 * - 单纯文本: 只填 [text]
 * - 图片输入/输出: 只填 [inlineData]
 * - 视频等大文件: 只填 [fileData](V-GEM1,引用 Files API 上传后的 uri)
 * - 模型发起的工具调用: 只填 [functionCall](H-GEM2)
 * - 回填工具执行结果: 只填 [functionResponse](H-GEM3)
 * - 同一 part 不会同时含多种载荷(Gemini 协议要求)
 *
 * 序列化时,空字段用默认值(空字符串),由 Gemini 服务端忽略。
 */
@Serializable
internal data class GeminiPart(
    val text: String = "",
    val inlineData: GeminiInlineData? = null,
    /** V-GEM1: 引用 Files API 上传后的文件(视频/音频等大文件)。 */
    val fileData: GeminiFileData? = null,
    /** H-GEM2: 模型决策调用工具时返回。 */
    val functionCall: GeminiFunctionCall? = null,
    /** H-GEM3: 回填工具执行结果给模型。 */
    val functionResponse: GeminiFunctionResponse? = null,
)

@Serializable
internal data class GeminiInlineData(
    // L-GEM9: mimeType 默认 image/png
    val mimeType: String = "image/png",
    val data: String,  // base64 编码(无 data: 前缀)
)

/**
 * V-GEM1: 引用 Files API 上传后的文件。
 *
 * 上传流程:
 *  1. POST https://generativelanguage.googleapis.com/upload/v1beta/files?key=...
 *     (multipart: metadata + 文件字节) → 返回 file.uri / file.name
 *  2. GET https://generativelanguage.googleapis.com/v1beta/files/{name}?key=...
 *     轮询直到 state=ACTIVE,文件可被 generateContent 引用
 *  3. 在 part 中用 [fileUri] 引用(fileData.fileUri == 上传返回的 file.uri)
 *
 * 仅 generativelanguage API 支持;Vertex AI 用 inlineData 或 GCS uri。
 */
@Serializable
internal data class GeminiFileData(
    val mimeType: String,
    val fileUri: String,
)

/** H-GEM2: 模型发起的函数调用请求。 */
@Serializable
internal data class GeminiFunctionCall(
    val name: String,
    val args: JsonElement? = null,
)

/** H-GEM3: 回填给模型的函数执行结果。 */
@Serializable
internal data class GeminiFunctionResponse(
    val name: String,
    val response: JsonElement? = null,
)

/** H-GEM2: 工具定义(Gemini 用单个 tool 包裹 functionDeclarations 数组)。 */
@Serializable
internal data class GeminiTool(
    val functionDeclarations: List<GeminiFunctionDeclaration>,
)

@Serializable
internal data class GeminiFunctionDeclaration(
    val name: String,
    val description: String,
    /** 参数 JSON Schema(OpenAI 兼容格式,由 Provider 解析为 JsonElement)。 */
    val parameters: JsonElement? = null,
)

/** H-GEM4: 单个风险类别的安全阈值设置。 */
@Serializable
internal data class GeminiSafetySetting(
    val category: String,
    val threshold: String,
)

@Serializable
internal data class GeminiGenerationConfig(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    /** Phase 8.5 修复: Gemini 2.5 系列的思考预算(thinkingConfig.thinkingBudget)。 */
    val thinkingConfig: GeminiThinkingConfig? = null,
    /**
     * Phase 8.6: 响应模态。
     *  - ["TEXT"]: 默认,纯文本
     *  - ["TEXT", "IMAGE"]: 文本 + 图片(Gemini 2.5 Flash Image / Nano Banana 等)
     *  - ["IMAGE"]: 纯图片(Imagen via generateContent 接口)
     *
     *  M-GEM12: 统一用大写。
     */
    val responseModalities: List<String>? = null,
)

@Serializable
internal data class GeminiThinkingConfig(
    val thinkingBudget: Int,
)

@Serializable
internal data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    /** H-GEM4: 提示级安全反馈(含 blockReason 时表示请求被整体拦截)。 */
    val promptFeedback: GeminiPromptFeedback? = null,
)

/** H-GEM4: 提示级安全反馈。 */
@Serializable
internal data class GeminiPromptFeedback(
    val blockReason: String? = null,
)

@Serializable
internal data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
    /** v1.80 (L-GEM3): 候选级安全评级(可选,用于风控/调试展示)。 */
    val safetyRatings: List<GeminiSafetyRating>? = null,
)

/** v1.80 (L-GEM3): 单个风险类别的安全评级(候选级)。 */
@Serializable
internal data class GeminiSafetyRating(
    val category: String? = null,
    val probability: String? = null,
    /** M-L-GEM3: 模型是否因该类别被拦截(true 时该类别内容未输出)。 */
    val blocked: Boolean? = null,
)

// ── 错误 ──

@Serializable
internal data class GeminiErrorBody(
    val error: GeminiErrorDetail? = null,
)

@Serializable
internal data class GeminiErrorDetail(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null,
)

/**
 * GET /v1beta/models 响应。models[].name 形如 "models/gemini-1.5-pro"。
 * v1.80 (M-GEM5): 增加 nextPageToken 支持分页拉取。
 */
@Serializable
internal data class GeminiModelsResponse(
    val models: List<GeminiModelInfo> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
internal data class GeminiModelInfo(
    val name: String = "",
    val displayName: String? = null,
    val supportedGenerationMethods: List<String>? = null,
)

// ── V-GEM1: Files API(视频等大文件上传)──

/**
 * POST /upload/v1beta/files 响应。
 *
 * 上传成功后返回 [file] 资源(含 [name] 用于轮询状态,[uri] 用于 generateContent 引用)。
 */
@Serializable
internal data class GeminiFileUploadResponse(
    val file: GeminiFileResource? = null,
)

/**
 * Files API 资源。
 *
 * - [name] 形如 "files/abc123",用于 GET /v1beta/files/{name} 轮询状态
 * - [uri] 形如 "https://generativelanguage.googleapis.com/v1beta/files/abc123",
 *   作为 [GeminiFileData.fileUri] 在 generateContent 中引用
 * - [state] 上传后初始为 PROCESSING,变 ACTIVE 后才能被 generateContent 使用
 * - [mimeType] 上传时指定的 MIME(如 video/mp4)
 */
@Serializable
internal data class GeminiFileResource(
    val name: String = "",
    val uri: String = "",
    val mimeType: String = "",
    val state: String = "",
    val error: GeminiErrorDetail? = null,
)
