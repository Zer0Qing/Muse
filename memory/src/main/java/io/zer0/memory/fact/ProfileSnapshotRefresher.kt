package io.zer0.memory.fact

import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * v20(M3): 画像快照刷新器 —— 把事实账本按规则投影成六段画像,**不调 LLM**。
 *
 * 为什么不用 LLM 生成画像:
 *  - 这一步每轮注入都要跑,后台 LLM 调用在手机上意味着电、费与不可预测的等待;
 *  - 分组本身是确定性规则(category 映射 + 置信度 + 时间),LLM 只会让结果不可复现;
 *  - 错了的画像比没有画像更伤信任 —— 规则版宁可朴素,不可瞎编。
 *
 * 段落归属优先级:低置信 → [ProfileSection.UNCERTAIN_NOTES](临时/推测的东西绝不
 * 下沉到稳定段);其余按 category 映射;无明确 category 时按创建时间兜底
 * (72 小时内 → [ProfileSection.RECENT_INTERACTIONS],更早 → 稳定事实)。
 */
class ProfileSnapshotRefresher(
    private val dao: ProfileSnapshotDao,
) {

    /** 刷新结果,供注入路径决定是否读表。 */
    enum class Outcome {
        /** 指纹未变或未到 TTL,只续了检查时间。 */
        UNCHANGED,

        /** 至少一个段落被重写。 */
        REFRESHED,

        /** 刷新失败(表不可用等),注入方应退回上一份快照。 */
        FAILED,
    }

    /**
     * 重算并落库某 (scope, space) 的画像快照。
     *
     * @param facts 该边界内**可注入**的事实(调用方已过滤过期/出场/被取代/被合并的行)
     * @param ttl 距上次刷新多久内直接跳过(指纹短路之外的一级节流)
     */
    suspend fun refresh(
        scope: String,
        spaceId: String,
        facts: List<FactStore.Fact>,
        ttl: Duration = DEFAULT_TTL,
        now: Instant = Instant.now(),
    ): Outcome = resultOf {
        val existing = dao.getByScopeAndSpace(scope, spaceId).associateBy { it.section }
        // TTL 节流:上次真正刷新过之后不足 ttl,不做重算(用户手改不受影响 —— 覆写存独立列)。
        val newestRefresh = existing.values.mapNotNull { it.lastRefreshAt?.let(Instant::parse) }.maxOrNull()
        if (newestRefresh != null && Duration.between(newestRefresh, now) < ttl) {
            return@resultOf Outcome.UNCHANGED
        }

        val sections = assignSections(facts, now)
        var changed = false
        for (section in ProfileSection.ORDER) {
            val items = sections[section].orEmpty()
            val content = items.joinToString("\n") { "- ${it.first}" }
            val fingerprint = fingerprintOf(items)
            val old = existing[section]
            when {
                old == null && content.isEmpty() -> Unit // 空段落不落库
                old != null && old.evidenceFingerprint == fingerprint ->
                    dao.touchRefreshedAt(scope, spaceId, section, now.toString())

                else -> {
                    // 用户覆写存独立列,跨刷新保留
                    dao.upsert(
                        ProfileSnapshotEntity(
                            id = old?.id ?: 0,
                            scope = scope,
                            spaceId = spaceId,
                            section = section,
                            autoContent = content,
                            manualContent = old?.manualContent,
                            sourceFactIds = "[" + items.joinToString(",") { "\"${it.second}\"" } + "]",
                            evidenceFingerprint = fingerprint,
                            updatedAt = now.toString(),
                            lastRefreshAt = now.toString(),
                        ),
                    )
                    if (content.isNotEmpty()) changed = true
                }
            }
        }
        if (changed) Outcome.REFRESHED else Outcome.UNCHANGED
    }.let { outcome ->
        when (outcome) {
            is Result.Success -> outcome.data
            is Result.Error -> {
                Logger.w(
                    "ProfileSnapshotRefresher",
                    "画像快照刷新失败(注入用上一份): ${outcome.message}",
                    outcome.throwable,
                )
                Outcome.FAILED
            }
        }
    }

    /**
     * 纯规则分段(暴露给测试;不触库)。
     *
     * @return section → (证据行文本, 来源事实 id) 列表,每段最多 [MAX_ITEMS_PER_SECTION] 条
     */
    fun assignSections(facts: List<FactStore.Fact>, now: Instant): Map<String, List<Pair<String, Long>>> {
        val buckets = ProfileSection.ORDER.associateWith { mutableListOf<Pair<String, Long>>() }
        val recentCutoff = now.minus(RECENT_WINDOW)
        for (
        fact in facts.sortedWith(
            // 段内排序:置顶/关键在前,其次时间新 —— 预算有限时先保重要的
            compareByDescending<FactStore.Fact> { it.pinnedAt != null }
                .thenByDescending { it.importance }
                .thenByDescending { it.time ?: it.createdAt },
        )
        ) {
            val line = fact.fact.trim().take(MAX_LINE_CHARS)
            if (line.isEmpty()) continue
            val section = when {
                fact.confidence < UNCERTAIN_CONFIDENCE -> ProfileSection.UNCERTAIN_NOTES
                else -> when (fact.category) {
                    "identity" -> ProfileSection.IDENTITY_SETTINGS
                    "relationship" -> ProfileSection.RELATIONSHIP_SETTINGS
                    "preference" -> ProfileSection.INTERACTION_PREFERENCES
                    "event" -> ProfileSection.RECENT_INTERACTIONS
                    else -> when {
                        // 无明确分类的近期内容按"最近发生"算,更早的进稳定事实
                        runCatching { Instant.parse(fact.createdAt) }
                            .map { it.isAfter(recentCutoff) }.getOrDefault(false) ->
                            ProfileSection.RECENT_INTERACTIONS

                        else -> ProfileSection.STABLE_FACTS
                    }
                }
            }
            val bucket = buckets.getValue(section)
            if (bucket.size < MAX_ITEMS_PER_SECTION) {
                bucket += line to fact.id
            }
        }
        return buckets
    }

    /** 段落指纹:逐条 "id:内容哈希" 排序后拼接 —— 同集合换顺序不算变化。 */
    private fun fingerprintOf(items: List<Pair<String, Long>>): String {
        val joined = items.sortedBy { it.second }
            .joinToString("|") { (line, id) -> "$id:${sha256(line).take(12)}" }
        return sha256(joined)
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    companion object {
        /** 默认刷新周期:画像不是实时数据,6 小时足够。 */
        val DEFAULT_TTL: Duration = Duration.ofHours(6)

        /** 「近期互动」窗口:72 小时内的事还没有"稳定"资格。 */
        val RECENT_WINDOW: Duration = Duration.ofHours(72)

        /** 每段最多几条 —— 再多就是转述对话,不是画像。 */
        const val MAX_ITEMS_PER_SECTION = 12

        /** 单条证据行截断长度。 */
        const val MAX_LINE_CHARS = 80

        /** 低于此置信度一律进「不确定笔记」。 */
        const val UNCERTAIN_CONFIDENCE = 0.6f
    }
}
