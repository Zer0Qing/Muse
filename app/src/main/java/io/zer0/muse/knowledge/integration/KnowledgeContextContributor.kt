package io.zer0.muse.knowledge.integration

import io.zer0.common.Logger
import io.zer0.muse.knowledge.domain.KnowledgeScope
import io.zer0.muse.knowledge.domain.ResolvedPolicy
import io.zer0.muse.knowledge.retrieval.EvidenceAssembler
import io.zer0.muse.knowledge.retrieval.EvidenceBundle
import io.zer0.muse.knowledge.retrieval.KnowledgeEntryType
import io.zer0.muse.knowledge.retrieval.KnowledgeRetriever
import io.zer0.muse.knowledge.retrieval.KnowledgeSearchRequest
import io.zer0.muse.knowledge.retrieval.RetrievalOutcome

/**
 * R7 消费入口接线 — 设计 §12「所有消费入口的合同」与计划 R7。
 *
 * 每个入口用**可信上下文**调用 [prepareEvidence],不共享全局 ChatViewModel 可变状态:
 *  - A2A 默认不读知识(`tools=null` 的既有策略),显式配置才开放;
 *  - 渠道只有用户已允许的资料范围才注入,不默认扩大私有资料外发;
 *  - 内置帮助只选 BuiltinHelp,普通自动检索不可见;
 *  - 群聊按每个参与助手的自身选择,证据不进入其它成员私有上下文。
 *
 * 返回类型化结果:证据 / 明确跳过(附原因) / 失败(附原因),不返回空串冒充"检索过"。
 */
data class TrustedGenerationContext(
    val entry: KnowledgeEntryType,
    val assistantId: String? = null,
    val sessionId: String? = null,
    /** 该入口已获许可的范围;任何入口不得越过它(由 R1 合同保证)。 */
    val allowedScope: KnowledgeScope,
    /** 入口是否已获准读取知识;false 时直接 Skipped,不做任何检索。 */
    val knowledgeReadAllowed: Boolean = true,
)

sealed interface ContributionResult {
    data class Evidence(val bundle: EvidenceBundle) : ContributionResult

    /** 明确跳过(入口未开放/无预算/无范围),不是失败。 */
    data class Skipped(val reason: String) : ContributionResult

    /** 检索或装配失败;调用方应如实报告,不伪造"没有相关资料"。 */
    data class Failed(val reason: String) : ContributionResult
}

class KnowledgeContextContributor(
    private val retriever: KnowledgeRetriever,
    private val assembler: EvidenceAssembler,
    private val policyProvider: suspend (TrustedGenerationContext) -> ResolvedPolicy,
) {

    /**
     * 为一次生成准备证据。
     *
     * @param budgetTokens 本轮全局上下文留给知识的预算;<=0 → Skipped。
     */
    suspend fun prepareEvidence(
        context: TrustedGenerationContext,
        query: String,
        budgetTokens: Int,
        requestId: String,
    ): ContributionResult {
        if (!context.knowledgeReadAllowed) {
            return ContributionResult.Skipped("入口未开放知识读取")
        }
        if (context.entry == KnowledgeEntryType.A2A) {
            return ContributionResult.Skipped("A2A 默认不读知识(tools=null 策略)")
        }
        if (budgetTokens <= 0) {
            return ContributionResult.Skipped("知识预算为 0")
        }
        if (query.isBlank()) {
            return ContributionResult.Skipped("空查询不触发自动检索")
        }
        if (context.allowedScope is KnowledgeScope.None ||
            context.allowedScope is KnowledgeScope.Invalid ||
            context.allowedScope is KnowledgeScope.Failed
        ) {
            return ContributionResult.Skipped("入口范围为空/无效")
        }

        val policy = policyProvider(context)
        val request = KnowledgeSearchRequest(
            requestId = requestId,
            query = query,
            entry = context.entry,
            scope = context.allowedScope,
            policy = policy.retrieval,
            datasetEpoch = 0L,
            deadlineMs = Long.MAX_VALUE,
        )

        return try {
            when (val outcome = retriever.search(request)) {
                is RetrievalOutcome.Found -> {
                    val bundle = assembler.assemble(outcome, budgetTokens, traceId = requestId)
                    if (bundle.isEmpty) ContributionResult.Skipped("检索命中为空")
                    else ContributionResult.Evidence(bundle)
                }
                is RetrievalOutcome.Partial -> {
                    val bundle = assembler.assemble(outcome, budgetTokens, traceId = requestId)
                    if (bundle.isEmpty) ContributionResult.Failed("检索部分降级且无可发送证据: ${outcome.missingPaths.joinToString()}")
                    else ContributionResult.Evidence(bundle)
                }
                is RetrievalOutcome.NoMatch -> ContributionResult.Skipped("范围内无匹配资料")
                is RetrievalOutcome.EmptyScope -> ContributionResult.Skipped("空范围: ${outcome.reason}")
                is RetrievalOutcome.Unavailable -> ContributionResult.Failed("检索不可用: ${outcome.reason}")
                is RetrievalOutcome.Failed -> ContributionResult.Failed("检索失败: ${outcome.reason}")
            }
        } catch (e: Exception) {
            Logger.w("KnowledgeContextContributor", "prepareEvidence 异常(request=$requestId): ${e.message}", e)
            ContributionResult.Failed("证据准备异常: ${e.message}")
        }
    }
}
