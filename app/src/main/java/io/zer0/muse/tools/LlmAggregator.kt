package io.zer0.muse.tools

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v1.201: LLM 综合评审聚合器。
 *
 * 把多个子 Agent 的结果交给一个独立 LLM 调用做综合评审,
 * 产出融合后的最终输出(标注冲突点 / 互补视角 / 综合答案)。
 *
 * - 通过 Koin 注入 [ChatService] 与 [SettingsRepository]
 * - 评审模型取自 [SettingsRepository.multiAgentConfigCache] 的 reviewModelId,
 *   未配置则用 active provider 的默认模型
 * - temperature=0.3(评审要稳定),maxTokens=2000
 * - 调用超时 30s,超时返回 null 让上游降级为 EXPERT_REVIEW
 * - 用 [resultOf]{} 包裹,正确重抛 CancellationException
 *
 * 该类是无状态 @Single,可被 [TeamWorkflowExecutor] / [SkillExecutor] 共享。
 */
class LlmAggregator(
    private val chatService: ChatService,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * 对多 Agent 候选结果做综合评审,返回融合后的文本。
     *
     * @param candidates 候选结果列表(已过滤空内容,调用方负责)
     * @param question 原始任务/问题,作为评审上下文;null 视为 "未提供"
     * @return 融合后的最终输出文本;调用失败 / 超时 / 空响应返回 null,由上游降级处理
     */
    suspend fun review(
        candidates: List<AgentResultAggregator.Candidate>,
        question: String?,
    ): String? {
        if (candidates.isEmpty()) return null

        val prompt = buildReviewPrompt(candidates, question)
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = SYSTEM_PROMPT),
            UIMessage(role = MessageRole.USER, content = prompt),
        )

        // H-LLM2: resultOf{} 包裹 suspend 调用,正确重抛 CancellationException;
        // withTimeoutOrNull 包裹整体执行,超时返回 null
        val completion = resultOf {
            withTimeoutOrNull(REVIEW_TIMEOUT_MS) {
                val (model, providerConfig) = resolveReviewModel()
                chatService.completeText(
                    messages = messages,
                    model = model,
                    temperature = REVIEW_TEMPERATURE,
                    maxTokens = REVIEW_MAX_TOKENS,
                    providerConfig = providerConfig,
                )
            }
        }.onError { msg, t ->
            Logger.w("LlmAggregator", "review 调用异常: $msg", t)
        }.getOrNull() ?: return null

        val text = completion.text.trim()
        return text.ifBlank { null }
    }

    /**
     * 解析评审模型。
     *
     * - reviewModelId 为空:返回 (null, null),由 ChatService 用 active provider 的首个模型
     * - reviewModelId 非空:遍历所有 provider 查找匹配模型,找到则返回 (Model, ProviderConfig)
     * - 找不到匹配模型:返回 (null, null),回退到默认行为并记录日志
     */
    private suspend fun resolveReviewModel(): Pair<Model?, ProviderConfig?> {
        val modelId = settingsRepository.multiAgentConfigCache.reviewModelId
        if (modelId.isNullOrBlank()) return null to null
        val providers = settingsRepository.getAllProviders()
        for (provider in providers) {
            val matched = provider.models.firstOrNull { it.id == modelId }
            if (matched != null) {
                return matched to provider
            }
        }
        Logger.w("LlmAggregator", "reviewModelId=$modelId 在所有 Provider 中未找到,回退默认模型")
        return null to null
    }

    /** 构造评审 prompt(中文)。 */
    private fun buildReviewPrompt(
        candidates: List<AgentResultAggregator.Candidate>,
        question: String?,
    ): String {
        val candidatesBlock = candidates.mapIndexed { i, c ->
            val meta = buildString {
                append("来源: ${c.source}")
                c.confidence?.let { append(", 置信度: $it") }
            }
            "### 候选 ${i + 1} ($meta)\n${c.content}"
        }.joinToString("\n\n")

        return """
你是多 Agent 结果评审员。以下是多个助手对同一任务的回复,请综合所有视角,
标注冲突点,产出最终融合输出。

## 原始任务
${question ?: "未提供"}

## 候选结果
$candidatesBlock

## 输出要求
1. 若有冲突,先列出冲突点(简洁)
2. 然后给出综合后的最终答案(不分段标题,直接内容)
3. 不要复述候选内容,要融合而不是拼接
4. 控制在 1500 字以内
        """.trimIndent()
    }

    companion object {
        private const val REVIEW_TIMEOUT_MS = 30_000L
        private const val REVIEW_TEMPERATURE = 0.3f
        private const val REVIEW_MAX_TOKENS = 2000

        private const val SYSTEM_PROMPT = "你是严谨的多 Agent 结果评审员,擅长融合不同视角、消解冲突、产出高质量综合答案。"
    }
}
