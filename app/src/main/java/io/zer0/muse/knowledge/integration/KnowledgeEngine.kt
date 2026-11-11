package io.zer0.muse.knowledge.integration

import io.zer0.muse.knowledge.retrieval.EvidenceAssembler
import io.zer0.muse.knowledge.retrieval.KnowledgeRetriever
import io.zer0.muse.knowledge.retrieval.KnowledgeSearchRequest
import io.zer0.muse.knowledge.retrieval.RetrievalOutcome

/**
 * R7 知识引擎薄门面 — 设计 §5「KnowledgeEngine 是薄门面，不是新的 RagService 大类」。
 *
 * 只做委托:检索 → 证据装配 → 入口贡献。不持有全局可变状态,不在门面内重新实现算法。
 * 单个边界(检索/装配/入口)可以独立替换与测试。
 */
class KnowledgeEngine(
    private val retriever: KnowledgeRetriever,
    private val assembler: EvidenceAssembler,
    private val contributorFactory: (KnowledgeContextContributorDeps) -> KnowledgeContextContributor,
) {

    suspend fun search(request: KnowledgeSearchRequest): RetrievalOutcome = retriever.search(request)

    fun assemble(outcome: RetrievalOutcome, budgetTokens: Int, traceId: String) =
        assembler.assemble(outcome, budgetTokens, traceId = traceId)

    fun contributor(deps: KnowledgeContextContributorDeps): KnowledgeContextContributor =
        contributorFactory(deps)
}

/** 贡献器依赖束 — 由装配层提供(策略解析器按入口取配置)。 */
data class KnowledgeContextContributorDeps(
    val policyProvider: suspend (TrustedGenerationContext) -> io.zer0.muse.knowledge.domain.ResolvedPolicy,
)
