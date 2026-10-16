package io.zer0.memory.ai

import io.zer0.common.Logger
import io.zer0.memory.fact.FactStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * v2.6.2（用户反馈）: 记忆星座「补边」构建器。
 *
 * 背景：星座图此前几乎连不成线 —— 唯一的产边点是会话结束时 LLM 抽出的 `links`，
 * 它要求 source/target 标题精确匹配同轮抽出的实体，门槛过高，绝大多数记忆是孤立点。
 *
 * 本构建器用**离线确定性算法**补齐边，让星座连成网络（视觉密度优先，允许弱边）：
 *
 *  1. **同实体键**（强）：同 entityKey 的事实两两连 `related_to`（weight 高）
 *  2. **同会话**（弱）：同一 sessionId 抽出的事实连边（weight 低）
 *  3. **同分类 + 时间接近**（弱）：同 category 且创建时间接近的连边（weight 低）
 *  4. **语义近邻**（中）：对新增事实用 embedding 找存量近邻（可关，依赖 embedding 质量）
 *
 * 设计要点：
 *  - **增量**：只对「新增事实 × 存量事实」算边，不重算全量，几千条记忆也可控。
 *  - **幂等**：同一对事实已有边时跳过（不重复插入）。
 *  - **去重**：无向去重（A-B 与 B-A 视为同一条）。
 *  - **可关**：语义近邻依赖 embedding，本地关键词 embedding 效果有限，允许关闭。
 */
@Suppress("LongParameterList", "ReturnCount", "LoopWithTooManyJumpStatements")
class MemoryEdgeBuilder(
    private val linkDao: MemoryLinkDao,
    /**
     * v2.6.2（用户反馈）: LLM 深化关联（可选）。
     *
     * 开启后，对“语义近邻但关系不明”的边，用大工具模型判定具体关系类型
     * （causes / contradicts / part_of）。每条边一次 LLM 调用，成本高，由开关控制。
     */
    private val llmClient: io.zer0.memory.llm.MemoryLlmClient? = null,
) {

    /** 补边结果。 */
    data class BuildResult(
        val entityEdges: Int = 0,
        val sessionEdges: Int = 0,
        val categoryEdges: Int = 0,
        val semanticEdges: Int = 0,
    ) {
        val total: Int get() = entityEdges + sessionEdges + categoryEdges + semanticEdges
    }

    /** 补边请求参数（收敛 buildEdges 的参数，便于阅读与演进）。 */
    data class BuildRequest(
        val newFacts: List<FactStore.Fact>,
        val existingFacts: List<FactStore.Fact>,
        val scope: String,
        val spaceId: String,
        val semanticProvider: SemanticProvider? = null,
        val semanticThreshold: Float = DEFAULT_SEMANTIC_THRESHOLD,
        val semanticTopK: Int = DEFAULT_SEMANTIC_TOPK,
        /** 是否用 LLM 深化弱边的具体关系类型（需 llmClient 非空）。 */
        val llmEnhance: Boolean = false,
        val model: io.zer0.ai.core.Model? = null,
    )

    /** 兼容旧签名的便捷入口。 */
    suspend fun buildEdges(
        newFacts: List<FactStore.Fact>,
        existingFacts: List<FactStore.Fact>,
        scope: String,
        spaceId: String,
        semanticProvider: SemanticProvider? = null,
        semanticThreshold: Float = DEFAULT_SEMANTIC_THRESHOLD,
        semanticTopK: Int = DEFAULT_SEMANTIC_TOPK,
        llmEnhance: Boolean = false,
        model: io.zer0.ai.core.Model? = null,
    ): BuildResult = buildEdges(
        BuildRequest(
            newFacts = newFacts,
            existingFacts = existingFacts,
            scope = scope,
            spaceId = spaceId,
            semanticProvider = semanticProvider,
            semanticThreshold = semanticThreshold,
            semanticTopK = semanticTopK,
            llmEnhance = llmEnhance,
            model = model,
        ),
    )

    /**
     * 为一批「新增事实」补齐与存量事实之间的边。
     *
     * 只做增量：新事实 × 存量（含其他新事实）。已有边（无向）跳过，保证幂等。
     */
    @Suppress("CyclomaticComplexMethod")
    suspend fun buildEdges(req: BuildRequest): BuildResult = withContext(Dispatchers.IO) {
        if (req.newFacts.isEmpty() || req.existingFacts.size < 2) return@withContext BuildResult()

        // 已有边集合（无向去重）—— 避免重复插入
        val existingPairs = linkDao.listBySpaceAndScope(req.spaceId, req.scope)
            .mapTo(mutableSetOf()) { pairKey(it.sourceFactId, it.targetFactId) }
        val ctx = MutableBuildContext(req, existingPairs)

        // 1) 同实体键（强）
        val byEntity = req.existingFacts.filter { !it.entityKey.isNullOrBlank() }.groupBy { it.entityKey!! }
        for (newFact in req.newFacts) {
            val key = newFact.entityKey?.takeIf { it.isNotBlank() } ?: continue
            byEntity[key]?.forEach { other ->
                if (other.id != newFact.id) ctx.tryAdd(newFact, other, ENTITY_WEIGHT, Kind.ENTITY)
            }
        }

        // 2) 同会话（弱）
        val bySession = req.existingFacts.filter { !it.sessionId.isNullOrBlank() }.groupBy { it.sessionId!! }
        for (newFact in req.newFacts) {
            val sid = newFact.sessionId?.takeIf { it.isNotBlank() } ?: continue
            bySession[sid]?.forEach { other ->
                if (other.id != newFact.id) ctx.tryAdd(newFact, other, SESSION_WEIGHT, Kind.SESSION)
            }
        }

        // 3) 同分类 + 时间接近（弱）
        val byCategory = req.existingFacts.groupBy { it.category }
        for (newFact in req.newFacts) {
            byCategory[newFact.category]?.forEach { other ->
                if (other.id == newFact.id) return@forEach
                if (withinWindow(newFact.createdAt, other.createdAt)) {
                    ctx.tryAdd(newFact, other, CATEGORY_WEIGHT, Kind.CATEGORY)
                }
            }
        }

        // 4) 语义近邻（中）—— 依赖 embedding，可能失败，失败即跳过
        val semanticCount = if (req.semanticProvider != null) {
            runCatching { ctx.buildSemanticEdges() }
                .onFailure { Logger.w(TAG, "语义补边失败(跳过): ${it.message}") }
                .getOrDefault(0)
        } else {
            0
        }

        if (ctx.pending.isNotEmpty()) {
            // v2.6.2: LLM 深化 —— 对弱边判定具体关系类型。失败逐条降级，不影响补边主体。
            if (req.llmEnhance && llmClient != null) {
                enhanceWithLlm(ctx.pending, req.model)
            }
            runCatching { linkDao.insertBatch(ctx.pending) }
                .onFailure { Logger.w(TAG, "补边落库失败: ${it.message}") }
        }
        Logger.i(
            TAG,
            "补边完成: 实体=${ctx.counts[Kind.ENTITY]} 会话=${ctx.counts[Kind.SESSION]} " +
                "分类=${ctx.counts[Kind.CATEGORY]} 语义=$semanticCount",
        )
        BuildResult(
            entityEdges = ctx.counts[Kind.ENTITY] ?: 0,
            sessionEdges = ctx.counts[Kind.SESSION] ?: 0,
            categoryEdges = ctx.counts[Kind.CATEGORY] ?: 0,
            semanticEdges = semanticCount,
        )
    }

    /** 边类别（用于统计）。 */
    private enum class Kind { ENTITY, SESSION, CATEGORY }

    /** 一次补边的可变状态。 */
    private inner class MutableBuildContext(
        private val req: BuildRequest,
        private val existingPairs: MutableSet<Long>,
    ) {
        val pending = mutableListOf<MemoryLinkEntity>()
        val counts = mutableMapOf<Kind, Int>()
        private val now = Instant.now().toString()

        fun tryAdd(a: FactStore.Fact, b: FactStore.Fact, weight: Float, kind: Kind) {
            if (a.id == b.id) return
            val key = pairKey(a.id, b.id)
            if (!existingPairs.add(key)) return
            pending.add(makeEdge(a.id, b.id, a.fact, b.fact, "related_to", weight))
            counts[kind] = (counts[kind] ?: 0) + 1
        }

        fun makeEdge(sourceId: Long, targetId: Long, sourceText: String, targetText: String, type: String, weight: Float) =
            MemoryLinkEntity(
                sourceFactId = sourceId,
                targetFactId = targetId,
                sourceTitle = sourceText.take(TITLE_MAX),
                targetTitle = targetText.take(TITLE_MAX),
                linkType = type,
                weight = weight.coerceIn(0f, 1f),
                spaceId = req.spaceId,
                scope = req.scope,
                createdAt = now,
            )

        suspend fun buildSemanticEdges(): Int {
            val provider = req.semanticProvider ?: return 0
            val others = req.existingFacts
            if (others.isEmpty()) return 0
            val texts = req.newFacts.map { it.fact } + others.map { it.fact }
            val vectors = provider.embed(texts)
            if (vectors.size != texts.size) return 0
            val newVectors = vectors.subList(0, req.newFacts.size)
            val otherVectors = vectors.subList(req.newFacts.size, vectors.size)
            var count = 0
            for (i in req.newFacts.indices) {
                val nv = newVectors[i]
                val scored = others.mapIndexed { j, other -> other to cosine(nv, otherVectors[j]) }
                    .filter { it.second >= req.semanticThreshold && it.first.id != req.newFacts[i].id }
                    .sortedByDescending { it.second }
                    .take(req.semanticTopK)
                for ((other, sim) in scored) {
                    if (!existingPairs.add(pairKey(req.newFacts[i].id, other.id))) continue
                    pending.add(
                        makeEdge(req.newFacts[i].id, other.id, req.newFacts[i].fact, other.fact, "related_to", sim),
                    )
                    count++
                }
            }
            return count
        }
    }

    /** 语义向量提供者抽象（避免 memory 模块依赖 app 的 EmbeddingService）。 */
    fun interface SemanticProvider {
        suspend fun embed(texts: List<String>): List<FloatArray>
    }

    /**
     * v2.6.2: 用 LLM 深化弱边的具体关系类型。
     *
     * 只处理 linkType==related_to 的边（实体键/已有类型不动）。逐条调用，失败降级保留原类型。
     */
    private suspend fun enhanceWithLlm(pending: MutableList<MemoryLinkEntity>, model: io.zer0.ai.core.Model?) {
        val client = llmClient ?: return
        for (i in pending.indices) {
            val edge = pending[i]
            if (edge.linkType != "related_to") continue
            val judged = runCatching {
                val raw = client.callText(
                    systemPrompt = EDGE_ENHANCE_SYSTEM_PROMPT,
                    userContent = "A: ${edge.sourceTitle}\nB: ${edge.targetTitle}",
                    model = model,
                    temperature = 0.2f,
                    maxTokens = 32,
                    timeoutMs = 20_000L,
                )
                parseLinkType(raw)
            }.getOrNull() ?: continue
            if (judged != null) {
                pending[i] = edge.copy(linkType = judged)
            }
        }
    }

    /** 从 LLM 输出中解析关系类型；识别不到返回 null。 */
    private fun parseLinkType(raw: String): String? {
        val lower = raw.lowercase()
        return when {
            "contradict" in lower -> "contradicts"
            "causes" in lower || "因果" in raw || "because" in lower -> "causes"
            "part_of" in lower || "part of" in lower || "包含" in raw || "属于" in raw -> "part_of"
            "related" in lower || "关联" in raw -> "related_to"
            else -> null
        }
    }

    private fun pairKey(a: Long, b: Long): Long {
        val lo = min(a, b)
        val hi = max(a, b)
        // 组合成唯一键：用 64 位；id 为自增正数，冲突概率可忽略。
        return (lo shl 32) or (hi and 0xFFFFFFFFL)
    }

    private fun withinWindow(a: String, b: String): Boolean {
        val ta = runCatching { Instant.parse(a).toEpochMilli() }.getOrNull() ?: return false
        val tb = runCatching { Instant.parse(b).toEpochMilli() }.getOrNull() ?: return false
        return kotlin.math.abs(ta - tb) <= CATEGORY_TIME_WINDOW_MS
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
        return if (denom <= 0f) 0f else dot / denom
    }

    companion object {
        private const val TAG = "MemoryEdgeBuilder"
        private const val TITLE_MAX = 60

        /** 同实体键：强关系。 */
        const val ENTITY_WEIGHT = 0.85f

        /** 同会话：中弱。 */
        const val SESSION_WEIGHT = 0.45f

        /** 同分类 + 时间接近：弱。 */
        const val CATEGORY_WEIGHT = 0.3f

        /** 语义近邻默认阈值（偏松，追求 B 视觉密度）。 */
        const val DEFAULT_SEMANTIC_THRESHOLD = 0.55f

        /** 每条新事实最多连几个语义近邻。 */
        const val DEFAULT_SEMANTIC_TOPK = 5

        /** 同分类 + 时间窗口（24 小时）。 */
        private const val CATEGORY_TIME_WINDOW_MS = 24 * 60 * 60 * 1000L

        /** v2.6.2: LLM 深化关系类型的 system prompt。 */
        private const val EDGE_ENHANCE_SYSTEM_PROMPT =
            "你是记忆关系分析器。给定两条事实 A 与 B，判断它们的关系类型。" +
                "只回复一个词：causes（A 导致 B）/ contradicts（互相矛盾）/ " +
                "part_of（B 是 A 的一部分或反之）/ related_to（普通关联）。不要解释。"
    }
}
