package io.zer0.muse.ui.chat

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.RagCitation
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.ExperimentsConfig
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.privacy.PiiGuard
import io.zer0.muse.transformer.TransformContext
import kotlin.uuid.Uuid

/**
 * 流式生成过程中的可变状态容器。
 *
 * v1.97: builder/reasoningBuilder/currentAssistantId/piiMatches 提到 try 块外声明(现收入本类),
 * 让 catch 块也能访问 —— 切页后 catch 块用 builder 内容 + currentAssistantId 构造部分回复落盘,
 * PII Guard 还原占位符避免 [PHONE_001] 等占位符被持久化到数据库。
 *
 * v1.0.27 Phase 4-A.1: 从 ChatViewModel 内部 private class 抽出到独立文件,可见性改为 internal,
 * 以便 ChatStreamCoordinator 接管流式 6 步准备函数后能直接访问 state 字段。
 * ChatViewModel 仍保留 StreamRunState 实例化与 catch 块访问,until 后续 step 把 catch 也移走。
 */
internal class StreamRunState(
    val sessionId: String,
    val assistantId: Uuid,
    val isNewBranch: Boolean,
) {
    // Phase A: prepareHistory
    var streamStartedAt: Long = 0L
    var sessionTitle: String = ""
    var experiments: ExperimentsConfig = ExperimentsConfig()
    var assistant: AssistantEntity? = null
    var requestedReasoningLevel: ReasoningLevel = ReasoningLevel.OFF
    var effectiveTemperature: Float = 0f
    var contextSize: Int = 20
    var rawHistory: List<UIMessage> = emptyList()
    var truncatedHistory: List<UIMessage> = emptyList()

    // Phase B: buildSystemPromptForStream
    var systemMessages: List<UIMessage> = emptyList()
    var prefixMessages: List<UIMessage> = emptyList()
    var pendingRagCitations: List<RagCitation> = emptyList()

    // Phase D: applyTransformers
    var transformedMessages: List<UIMessage> = emptyList()
    // v1.x: 三钩子接入 — 保存 applyTransformers 构造的 context,
    // 供后续 applyVisualTransform / applyOnGenerationFinish 复用,避免重复构造
    var transformContext: TransformContext? = null

    // Phase E: resolveToolsAndModel
    var tools: List<ToolDefinition> = emptyList()
    var skillMap: Map<String, SkillEntity> = emptyMap()
    // C-12: 主模型(可能支持视觉)—— 供视觉辅助判定与最终回复轮使用
    var effectiveModel: Model? = null
    var effectiveProviderConfig: ProviderConfig? = null
    // C-12: 工具模型(用户配置的轻量 toolModel,可为 null)——
    //  仅"工具轮"(上一轮结果含 toolCalls 的续接轮)使用,最终回复轮切回主模型,
    //  避免主模型支持视觉也被 toolModel 降级为纯文本路由。
    var toolModel: Model? = null
    var toolProviderConfig: ProviderConfig? = null
    var reasoningLevel: ReasoningLevel = ReasoningLevel.OFF
    var conversationHistory: MutableList<UIMessage> = mutableListOf()

    // Phase F: applyPiiGuard — 也需被 catch 块通过 unmaskPii 访问
    var piiMatches: List<PiiGuard.PiiMatch> = emptyList()

    // Phase H: runToolLoop 结果(也供 finalizeResponse 读取)
    var currentAssistantId: Uuid = assistantId
    var round: Int = 0
    var totalCharCount: Int = 0
    var totalToolCallCount: Int = 0
    var firstTokenTime: Long = 0L

    /**
     * A5: 本轮流式的 provider 实测 token 用量。
     * streamRound 的 UsageDelta 分支写入(跨轮累积,多轮工具循环中每轮都可能发),
     * finalizeResponse 持久化时取最后一次非 null(最后一轮 = 最终回复轮)。
     * provider 未返回 usage 时为 null,UI 回退本地估算。
     */
    var usageTokens: io.zer0.ai.core.UsageTokens? = null

    // builder/reasoningBuilder:catch 块用(流式过程中 streamRound 使用 params.builder,
    // 此处 builder 保留与原实现一致的行为)
    val builder: StringBuilder = StringBuilder()
    val reasoningBuilder: StringBuilder = StringBuilder()

    /**
     * B-24: 本代生成的序号 — launchStream 启动时从 ChatViewModel.streamGenerationSerial
     * 捕获。finalizeResponse/错误路径清零 isStreaming 前校验自己仍是"最新生成",
     * 防止快速连发时 gen-1 收尾把 gen-2 的流式状态清掉。
     */
    var generationSerial: Long = 0L

    fun unmaskPii(text: String): String =
        if (piiMatches.isEmpty()) text else PiiGuard.unmask(text, piiMatches)
}
