package io.zer0.muse.data.proactive

import io.zer0.memory.fact.FactStore
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 从记忆和里程碑里推导「值得惦记的事」。
 *
 * 这一步是**纯本地规则**，不调用任何模型，因此可以随每次调度免费跑。
 * 这是整个设计能有燃料却不烧钱的关键：记账不产生额外 token 成本。
 *
 * 规则刻意保守。账本里塞满碎事会毁掉整套机制 ——
 * 一旦用户发现「它记的东西我自己都不觉得重要」，所有主动消息都会显得廉价。
 * 所以宁可漏记：这里只认三类信号（未来事件、身体状态、未成行的打算），
 * 而日常闲聊里的琐碎由对话内的 `note_thought` 工具自己决定要不要记。
 */
object ThoughtDeriver {

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

    /** 未来事件提前多久开始惦记。太早记下来会变成长期噪音。 */
    private const val EVENT_LOOKAHEAD_DAYS = 3L

    /** 事件结束后再多留 26 小时（含当天），之后这条彻底作废。 */
    private const val EVENT_TAIL_HOURS = 26L

    /** 碰过的记忆只在这么久之内参与推导，老记忆不重复惦记。 */
    private const val FACT_FRESHNESS_DAYS = 7L

    /** 身体状态类关键词。命中即当作 CARE。 */
    private val CARE_KEYWORDS = listOf(
        "失眠", "睡不着", "熬夜", "感冒", "发烧", "生病", "头疼", "头痛",
        "焦虑", "压力", "难过", "低落", "emo", "累了", "好累", "疲惫", "失恋", "吵架",
    )

    /** 未成行的打算类关键词。命中即当作 FOLLOW_UP。 */
    private val INTENT_KEYWORDS = listOf(
        "想去看", "打算", "准备去", "计划", "要去", "想试试", "想学",
        "考虑", "琢磨", "再看吧", "等我",
    )

    /**
     * 从一批记忆推导牵挂。
     *
     * @param alreadyTracked 已有关联来源 id，跳过已记过的（DAO 层也会兜一次）
     */
    fun fromFacts(
        facts: List<FactStore.Fact>,
        assistantId: String,
        now: Long,
        alreadyTracked: Set<String> = emptySet(),
    ): List<PendingThoughtEntity> = facts.mapNotNull { fact ->
        deriveOne(fact, assistantId, now, factRef(fact), alreadyTracked)
    }

    /** 单条记忆 → 至多一条牵挂。三道规则按优先级依次尝试。 */
    private fun deriveOne(
        fact: FactStore.Fact,
        assistantId: String,
        now: Long,
        ref: String,
        alreadyTracked: Set<String>,
    ): PendingThoughtEntity? {
        val text = fact.fact.trim()
        // 太老的记忆不惦记：一个上周提过的小毛病，这周再问反而奇怪
        val age = parseMillis(fact.createdAt)?.let { now - it } ?: 0L
        val eligible = text.isNotBlank() && ref !in alreadyTracked && age <= FACT_FRESHNESS_DAYS * DAY_MS
        val base = Base(assistantId, ref, text, now, ThoughtSource.MEMORY)
        return if (eligible) {
            asEvent(base, fact) ?: asCare(base) ?: asIntent(base)
        } else {
            null
        }
    }

    /** ① 未来 1~3 天内的事 → 立即类提醒，过期即作废，绝不补发。 */
    private fun asEvent(base: Base, fact: FactStore.Fact): PendingThoughtEntity? {
        val date = parseDate(fact.time)
        val days = date?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }
        val isUpcoming = date != null && days != null && days in 1..EVENT_LOOKAHEAD_DAYS
        return if (isUpcoming) {
            draft(
                base = base,
                kind = ThoughtKind.REMINDER,
                urgency = ThoughtUrgency.IMMEDIATE,
                expiresAt = date.atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli() + EVENT_TAIL_HOURS * HOUR_MS,
                weight = 0.85f,
            )
        } else {
            null
        }
    }

    /** ② 身体/情绪状态 → 窗口类关心，48 小时内说都合适。 */
    private fun asCare(base: Base): PendingThoughtEntity? {
        if (CARE_KEYWORDS.none { base.seed.contains(it) }) return null
        return draft(
            base = base,
            kind = ThoughtKind.CARE,
            urgency = ThoughtUrgency.WINDOW,
            expiresAt = base.now + 2 * DAY_MS,
            weight = 0.7f,
        )
    }

    /** ③ 没成行的打算 → 宽窗口跟进，一周内都可以问一句「后来去了吗」。 */
    private fun asIntent(base: Base): PendingThoughtEntity? {
        if (INTENT_KEYWORDS.none { base.seed.contains(it) }) return null
        return draft(
            base = base,
            kind = ThoughtKind.FOLLOW_UP,
            urgency = ThoughtUrgency.WINDOW,
            expiresAt = base.now + 7 * DAY_MS,
            weight = 0.6f,
        )
    }

    /**
     * 从里程碑推导。
     *
     * 只取最近 7 天内、且没被用户关掉的，且最多一条。
     * 里程碑一周来一次已经是上限，再多就变成成就播报，不像人说话。
     */
    fun fromMilestones(
        milestones: List<MilestoneRecord>,
        assistantId: String,
        now: Long,
        alreadyTracked: Set<String> = emptySet(),
    ): List<PendingThoughtEntity> = milestones
        .asSequence()
        .filter { it.dismissedAt == null }
        .filter { now - it.createdAt <= 7 * DAY_MS }
        .filter { "ms_${it.id}" !in alreadyTracked }
        .take(1)
        .map { m ->
            draft(
                base = Base(assistantId, "ms_${m.id}", "${m.title}：${m.message}", now, ThoughtSource.MILESTONE),
                kind = ThoughtKind.MILESTONE,
                urgency = ThoughtUrgency.EVERGREEN,
                expiresAt = null,
                weight = 0.45f,
            )
        }
        .toList()

    /** 里程碑的最小投影，避免让本文件依赖 Room 实体。 */
    data class MilestoneRecord(
        val id: Long,
        val title: String,
        val message: String,
        val createdAt: Long,
        val dismissedAt: Long?,
    )

    // ══════════════════════════════════════════════════════════════
    // 工具
    // ══════════════════════════════════════════════════════════════

    /** 各规则共用的那部分字段，避免每个 draft 都重复传一遍。 */
    private data class Base(
        val assistantId: String,
        val ref: String,
        val seed: String,
        val now: Long,
        val source: ThoughtSource,
    )

    private fun draft(
        base: Base,
        kind: ThoughtKind,
        urgency: ThoughtUrgency,
        expiresAt: Long?,
        weight: Float,
    ) = PendingThoughtEntity(
        id = "th_${base.now}_${base.ref.hashCode().toUInt().toString(16)}",
        assistantId = base.assistantId,
        kind = kind.name,
        seed = base.seed.take(200),
        source = base.source.name,
        sourceRef = base.ref,
        createdAt = base.now,
        expiresAt = expiresAt,
        urgency = urgency.name,
        weight = weight.coerceIn(0f, 1f),
    )

    /** 记忆的稳定标识，用于去重与回溯。 */
    private fun factRef(fact: FactStore.Fact): String = "fact_${fact.id}"

    private fun parseMillis(iso: String?): Long? =
        iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun parseDate(raw: String?): LocalDate? {
        val s = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        // 记忆里的 time 可能是纯日期，也可能是完整 ISO 时间戳
        return runCatching { LocalDate.parse(s.substringBefore("T")) }.getOrNull()
    }
}
