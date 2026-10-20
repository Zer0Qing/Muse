package io.zer0.muse.data.relationship

import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.flow.Flow

/**
 * 角色关系状态仓库（P1-1）。
 *
 * 对外只暴露"语义化"操作，不让调用方直接碰 DAO：
 * 私信概率、朋友圈互动加权、提示注入都从这里取口径，
 * 避免三个地方各自算一遍系数而导致行为不一致。
 */
class AssistantRelationshipRepository(
    private val dao: AssistantRelationshipDao,
) {
    fun observeAll(): Flow<List<AssistantRelationshipEntity>> = dao.observeAll()

    suspend fun getAll(): List<AssistantRelationshipEntity> = resultOf { dao.getAll() }
        .onError { msg, t -> Logger.w(TAG, "读取关系列表失败: ${t?.message ?: msg}") }
        .getOrNull() ?: emptyList()

    /**
     * 取某角色的关系状态；没有记录时返回**默认值**而不是 null。
     *
     * 不写库：避免为每个助手写入空行。真正发生互动时才落库。
     */
    suspend fun getOrDefault(assistantId: String): AssistantRelationshipEntity = resultOf { dao.get(assistantId) }.getOrNull()
        ?: AssistantRelationshipEntity(assistantId = assistantId)

    suspend fun upsert(entity: AssistantRelationshipEntity) {
        val now = System.currentTimeMillis()
        val normalized = entity.copy(
            affinity = entity.affinity.coerceIn(0, 100),
            createdAt = if (entity.createdAt == 0L) now else entity.createdAt,
            updatedAt = now,
        )
        resultOf { dao.upsert(normalized) }
            .onError { msg, t -> Logger.w(TAG, "保存关系失败: ${t?.message ?: msg}") }
    }

    /**
     * 记录一次真实互动并微量提升亲密度。
     *
     * 记录不存在时先建行再累加 —— 用 upsert 保证"第一次互动"也能落库，
     * 否则 bumpAffinity 的 UPDATE 会命中 0 行且静默失败。
     */
    suspend fun recordInteraction(assistantId: String, affinityDelta: Int = AFFINITY_PER_INTERACTION) {
        val now = System.currentTimeMillis()
        val existing = resultOf { dao.get(assistantId) }.getOrNull()
        if (existing == null) {
            resultOf {
                dao.upsert(
                    AssistantRelationshipEntity(
                        assistantId = assistantId,
                        affinity = affinityDelta.coerceIn(0, 100),
                        lastInteractionAt = now,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }.onError { msg, t -> Logger.w(TAG, "初始化关系失败: ${t?.message ?: msg}") }
            return
        }
        resultOf { dao.bumpAffinity(assistantId, affinityDelta, now) }
            .onError { msg, t -> Logger.w(TAG, "更新亲密度失败: ${t?.message ?: msg}") }
    }

    suspend fun delete(assistantId: String) {
        resultOf { dao.delete(assistantId) }
            .onError { msg, t -> Logger.w(TAG, "删除关系失败: ${t?.message ?: msg}") }
    }

    /**
     * 计算某角色主动私信的概率。
     *
     * 旧实现是写死 40%，与角色关系完全无关 —— 陌生人跟女朋友一样粘人。
     * 现在：基础概率 × 亲密度系数 × 意愿档位系数，并夹在 [MIN_PROBABILITY, MAX_PROBABILITY]。
     *
     * @param baseProbability 调用方给的基础概率（保持既有行为量级）
     */
    fun proactiveProbability(relationship: AssistantRelationshipEntity, baseProbability: Float = BASE_PROBABILITY): Float {
        if (!relationship.proactiveEnabled) return 0f
        val raw = baseProbability * relationship.affinityFactor * relationship.proactiveFactor
        return raw.coerceIn(MIN_PROBABILITY, MAX_PROBABILITY)
    }

    /**
     * 按亲密度给助手加权挑选。
     *
     * 朋友圈互动此前是 `Random.nextInt(size)` 纯随机，导致"从没聊过的人"和
     * "天天聊的人"一样频繁来点赞。这里用亲密度做权重，权重下限保证新角色也有机会出现。
     */
    fun pickWeighted(
        candidates: List<String>,
        relationships: Map<String, AssistantRelationshipEntity>,
        random: kotlin.random.Random = kotlin.random.Random,
    ): String? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        val weights = candidates.map { id ->
            val affinity = relationships[id]?.affinity ?: 0
            (MIN_PICK_WEIGHT + affinity / 100f).toDouble()
        }
        val total = weights.sum()
        if (total <= 0.0) return candidates.random(random)
        var cursor = random.nextDouble(total)
        for (i in candidates.indices) {
            cursor -= weights[i]
            if (cursor <= 0.0) return candidates[i]
        }
        return candidates.last()
    }

    companion object {
        private const val TAG = "AssistantRelation"

        /** 每次真实互动的亲密度增量。约 50 次互动到满。 */
        const val AFFINITY_PER_INTERACTION = 2

        /** 旧实现的固定私信概率，作为基础概率保持量级不变。 */
        const val BASE_PROBABILITY = 0.4f

        const val MIN_PROBABILITY = 0.02f
        const val MAX_PROBABILITY = 0.9f

        /** 互动加权的最小权重：保证亲密度 0 的角色仍有约 1/(n*~1.0) 的机会。 */
        const val MIN_PICK_WEIGHT = 0.15f
    }
}
