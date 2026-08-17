package io.zer0.ai.core

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 消息角色。Phase 1 只用 SYSTEM/USER/ASSISTANT,
 * TOOL 留给 Phase 3 工具调用。
 */
@Serializable
enum class MessageRole {
    SYSTEM, USER, ASSISTANT, TOOL
}

/**
 * 一次工具调用请求(LLM 决策调用工具时产生)。
 *
 * OpenAI 协议字段:
 *  - [id] 工具调用唯一 id(用于回填 tool_call_id)
 *  - [name] 函数名
 *  - [arguments] 参数 JSON 字符串(可能是流式增量片段)
 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/**
 * v0.47: 工具调用卡片信息(用于 MessageBubble 渲染折叠卡片)。
 *
 * 不持久化到 MessageEntity(重启后丢失,可接受,工具调用卡片是即时反馈,
 * 历史会话用纯文本 content 兜底)。
 */
@Immutable
@Serializable
data class ToolCallInfo(
    val toolName: String,
    val arguments: String,
    val result: String,
    val isSuccess: Boolean,
)

/**
 * v1.0.47: 消息附件引用(结构化持久化原始文件元数据)。
 *
 * 现状:文档解析后合并进 content,原始文件元数据丢弃。
 * 本字段保留原始附件信息,UI 可还原文档芯片(点击查看全文),
 * Provider 发送请求时仍用 content(兼容旧 Provider)。
 *
 * @param id 附件唯一 id
 * @param name 文件名
 * @param mimeType MIME 类型
 * @param size 文件大小(字节)
 * @param sourceType 来源类型(FILE=本地文件 / URL=网络链接)
 * @param extractedText 提取的文本内容(文档解析后)
 */
@Immutable
@Serializable
data class AttachmentRef(
    val id: String = kotlin.uuid.Uuid.random().toString(),
    val name: String,
    val mimeType: String = "application/octet-stream",
    val size: Long = 0L,
    val sourceType: String = "FILE",
    val extractedText: String? = null,
)

/**
 * UI 层使用的消息体。独立于任何 Provider 的请求格式,
 * 由各 Provider 自己把它翻译成对应 API 的 payload。
 *
 * Phase 1 仅支持纯文本;[parts] 的多模态结构留给 Phase 3。
 *
 * Phase 5-G: [imageUrls] 用于图片生成结果(ASSISTANT 消息),
 *            Provider 发送请求时忽略此字段(只发送文本 content)。
 *
 * Phase 7: [toolCalls] 用于 LLM 决策调用工具(ASSISTANT 消息),
 *          [toolCallId] 用于回填工具执行结果(TOOL 消息,对应 tool_call_id)。
 *
 * Phase 8.3: [favorite] 收藏标记(纯 UI 层,Provider 发送请求时忽略)。
 *
 * Phase 8.4: [citationUrls] 联网搜索结果 URL 列表(assistant 消息专用)。
 *            当 AI 回复中出现 [N] 引用编号时,UI 据此把 [N] 渲染为可点击链接,
 *            点击跳转到 citationUrls[N-1]。Provider 发送请求时忽略此字段。
 *
 * v1.133: [ragCitations] 知识库检索引用列表(assistant 消息专用)。
 *         由 RagService.buildInjectionContextWithCitations 返回,
 *         MessageBubble 渲染为可点击 chip(点击展开 snippet,长按跳转文档详情)。
 *         Provider 发送请求时忽略此字段。
 *
 * Phase 8.6: [imageBase64List] USER 消息附带的本地图片 base64 列表(无 data: 前缀),
 *            用于发送给视觉模型(Gemini inlineData / OpenAI image_url)。
 *            ASSISTANT 消息也可能携带(Gemini 绘图返回的 inlineData)。
 *            UI 据此渲染 USER 消息图片缩略图,Provider 据此构造多模态请求。
 */
@Immutable
@Serializable
data class UIMessage(
    val id: Uuid = Uuid.random(),
    val role: MessageRole,
    val content: String,
    val reasoning: String? = null,
    /**
     * v1.80 (M-ANT4): Anthropic thinking 块的签名(多轮 thinking 对话回放必需)。
     *
     * Anthropic extended thinking 在 thinking 块结束时下发 signature_delta,
     * 下一轮请求需在 assistant 消息的 thinking 块里回传该 signature 才能继续思考链。
     * 由 ChatViewModel 从 [io.zer0.ai.core.ChatStreamEvent.ReasoningDelta.signature] 累积并存入此字段。
     * v1.121: AnthropicProvider.splitSystem 已处理 reasoning/thinkingSignature 回传,
 * ASSISTANT 消息的 thinking content block 包含 thinking+signature,服务端可验证前序思考完整性。
     * 其他 Provider 忽略此字段。
     */
    val thinkingSignature: String? = null,
    /** B5-03: OpenAI Responses reasoning item 的 encrypted_content。 */
    val thinkingEncryptedContent: String? = null,
    val modelId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val imageUrls: List<String> = emptyList(),
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val favorite: Boolean = false,
    /**
     * v1.104 U7: 收藏分组标签(用户自定义,如"灵感"/"代码片段")。
     *
     * 仅在 [favorite]=true 时有意义;null 表示未分组(收藏夹显示在"全部"下)。
     * 由 FavoritesScreen 长按卡片弹"设置分组"对话框写入。
     */
    val favoriteTag: String? = null,
    val citationUrls: List<String> = emptyList(),
    /** v1.133: 知识库检索引用列表(assistant 消息专用,Provider 发送请求时忽略)。 */
    val ragCitations: List<RagCitation> = emptyList(),
    /** Phase 8.6: 本地图片 base64 列表(无 data: 前缀,默认 image/jpeg)。 */
    val imageBase64List: List<String> = emptyList(),
    /**
     * V-GEM1: 视频附件(已上传到 Gemini Files API 的 uri),仅 Gemini Provider 当前会读取。
     *
     * - [videoFileUri] 形如 "https://generativelanguage.googleapis.com/v1beta/files/abc123"
     *   (uploadFile 返回的 file.uri),由调用方在上传成功后填入
     * - [videoMimeType] 上传时声明的 MIME(如 "video/mp4"),与 fileData.mimeType 一致
     * - 非 Gemini Provider 当前忽略此字段;未来 OpenAI 等支持视频时可在 Provider 层扩展
     */
    val videoFileUri: String? = null,
    val videoMimeType: String? = null,
    /** v1.43: 消息关联的 Artifact id 列表(由 ArtifactExtractor 生成)。 */
    val artifactIds: List<String> = emptyList(),
    /**
     * v0.30-b: MOOD 块(LLM 在正文前输出的内部腹稿,6 步工作流第 2 步)。
     *
     * Muse 简化为单一 <mood> 标签 + 4 字段(Vibe/Sparks/Reflections/Will)。
     * 由 MoodTagTransformer 从 content 中剥离后存入此字段。
     * UI 渲染为可折叠卡片(默认折叠,类似深度思考块)。
     */

    /** B6-03/B6-02: 情绪皮肤标识(rage/rage2/desire/vuoto/moonlight/off),与 <moodfx> 标签对应。 */
    val moodSkin: String? = null,
    val mood: String? = null,
    /**
     * v0.32 实验性 selfReflection:LLM 在回复末尾输出的自我反思块。
     *
     * 由 MoodTagTransformer / ChatViewModel.updateAssistant 从 content 中
     * 剥离 `<reflection>...</reflection>` 后存入此字段。
     * 3 字段(准确性/完整性/语气)。UI 渲染先不做,后续 UI 任务再展示。
     */
    val reflection: String? = null,
    /**
     * v0.47: 工具调用卡片信息(用于 MessageBubble 渲染折叠卡片,替代纯文本"调用工具 xxx"消息)。
     *
     * 非空时 MessageBubble 优先用 ToolCallCard 渲染,忽略 content。
     * 不持久化到 MessageEntity(历史会话回放时该字段为 null,content 仍保留纯文本兜底)。
     */
    val toolCallInfo: ToolCallInfo? = null,
    /**
     * 引用回复:被引用消息的内容(仅 UI 层使用,不持久化到数据库)。
     * 持久化时通过 content 开头的 `> ` 标记承载。
     */
    val quotedContent: String? = null,
    /** 功能1: 消息表情回应键(ThumbUp/Favorite/SentimentSatisfied/SentimentDissatisfied/MoodBad/Bolt,null=无)。 */
    val reaction: String? = null,
    /** v1.0.30: 变体组 ID（同位置多个 assistant 回复共享），null = 非变体消息。 */
    val variantGroupId: String? = null,
    /** v1.0.30: 变体序号（0-based，在当前组中的位置）。 */
    val variantIndex: Int = 0,
    /** v1.0.30: 变体总数（组内消息数）。 */
    val variantCount: Int = 1,
    /**
     * P0 对话树: 助手变体所属的用户提问变体组 ID(parentGroupId)。
     * 用于把 assistant 回复精确挂载到对应 user 变体下,避免跨提问重试/切换污染。
     * 旧数据为 null 时由 ConversationTreeBuilder 按时间顺序推断父节点。
     */
    val parentGroupId: String? = null,
    /** v1.0.47: 消息附件列表(结构化持久化原始文件元数据,Provider 发送请求时忽略,用 content)。 */
    val attachments: List<AttachmentRef> = emptyList(),
    /**
     * A5: 消息生成元数据(信息展示用,Provider 发送请求时忽略)。
     *
     * 由 ChatViewModel 在生成结束时回填(provider 实测优先,缺失时 UI 本地估算展示):
     *  - [durationMs] 本消息生成总耗时(毫秒,含工具循环)
     *  - [promptTokens] provider 实测输入 token(流式 UsageDelta 末值)
     *  - [completionTokens] provider 实测输出 token
     *  - [reasoningTokens] provider 实测推理 token(o1/DeepSeek-R1 等)
     *  - [cachedTokens] provider 实测 prompt 缓存命中 token(Anthropic/OpenAI/Gemini)
     * 均为 null 表示无实测/未回填(旧数据或 provider 未返回 usage)。
     */
    val durationMs: Long? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
    /**
     * H11: 翻译保留原文 — 译文消息指向被翻译的源消息 id(字符串形式)。
     * 非 null 时 UI 在译文气泡下方提供"查看原文"折叠,可与原文对照。
     * 仅对话内即时对照用,provider 发送请求时忽略。
     */
    val translationSourceId: String? = null,
) {
    /** 拼出用于显示的纯文本(不含推理过程)。 */
    fun toText(): String = content

    /**
     * v1.80 (L-CORE4): 拼出用于摘要/上下文匹配的文本(含推理过程)。
     * memory 摘要、context 匹配等场景可能需要 reasoning 信息。
     */
    fun toSummaryText(): String = buildString {
        append(content)
        reasoning?.takeIf { it.isNotBlank() }?.let { append("\n[reasoning]").append(it) }
    }
}

/**
 * 工具依赖感知的上下文截断 — 截断时倒推 tool_call 依赖,避免"截断后 tool_call 无 result"。
 *
 * 通过依赖图倒推截断点。
 *
 * 背景:简单的 `takeLast(N)` 可能从 assistant(tool_call) 与 tool(result) 之间切开,
 * 导致截断后的首条消息是孤儿 tool result 或孤儿 tool_call,触发 provider HTTP 400。
 * 本函数在切点处倒推:若切点首条消息涉及工具(tool_call / tool_result),则继续往前回退,
 * 直到切点首条消息是不涉及工具的普通消息(或回退到列表起点)。
 *
 * 行为:
 *  - maxSize <= 0 或 size <= maxSize:原样返回
 *  - 切点首条消息是 ASSISTANT.toolCalls 非空 或 role=TOOL: startIndex-- 继续
 *  - 否则停止回退,返回 subList(startIndex, size)
 *
 * 注意:回退后实际保留的消息数可能略多于 maxSize(为保完整性),不会少于。
 * 极端情况下(整段历史都是 tool 调用链)startIndex 会回退到 0,保留全部历史。
 *
 * @param maxSize 期望保留的最大消息条数(实际可能略多)
 */
fun List<UIMessage>.limitContextWithContext(maxSize: Int): List<UIMessage> {
    if (maxSize <= 0 || this.size <= maxSize) return this
    var startIndex = this.size - maxSize

    // 倒推调整 startIndex,确保不破坏 tool_call / tool_result 配对
    while (startIndex > 0) {
        val current = this[startIndex]
        val hasToolCalls = current.toolCalls?.isNotEmpty() == true
        val isToolResult = current.role == MessageRole.TOOL

        if (hasToolCalls || isToolResult) {
            // 当前消息涉及工具,往前找到配对的 USER 或 ASSISTANT(tool_call)
            startIndex--
            continue
        }
        break
    }

    return subList(startIndex, size)
}
