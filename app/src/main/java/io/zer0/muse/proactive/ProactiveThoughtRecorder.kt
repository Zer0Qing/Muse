package io.zer0.muse.proactive

import io.zer0.memory.ai.ParsedProactiveThought
import io.zer0.muse.data.proactive.PendingThoughtEntity
import io.zer0.muse.data.proactive.ProactiveThoughtRepository
import io.zer0.muse.data.proactive.ThoughtKind
import io.zer0.muse.data.proactive.ThoughtSource
import io.zer0.muse.data.proactive.ThoughtUrgency
import io.zer0.muse.transformer.InternalMarkupSanitizer

/**
 * 主动牵挂账本写入协调器：统一处理对话回合末尾与记忆编译阶段提取出的牵挂。
 *
 * 写入账本时同步解析可选唤醒时间 `triggerAt`；若为未来时间戳，立即通知 [ProactiveScheduler]
 * 通过 WorkManager 预约一次性定点唤醒。
 */
object ProactiveThoughtRecorder {

    suspend fun persistFromModelOutput(
        repo: ProactiveThoughtRepository,
        scheduler: ProactiveScheduler?,
        assistantId: String,
        sourceIdPrefix: String,
        rawContent: String,
        now: Long = System.currentTimeMillis(),
    ): Int {
        val extracted = InternalMarkupSanitizer.extractProactiveThoughts(rawContent)
        if (extracted.isEmpty()) return 0
        val openNormalized = repo.getOpen(assistantId).mapTo(HashSet()) { it.seed.trim().lowercase() }
        var added = 0
        var earliestTriggerAt: Long? = null
        extracted.take(1).forEachIndexed { idx, item ->
            val cleanSeed = item.seed.trim().take(200)
            if (cleanSeed.length < 3 || !openNormalized.add(cleanSeed.lowercase())) return@forEachIndexed
            val triggerAt = item.resolveTriggerAtMillis(now)
            val expiresAt = resolveExpiresAt(
                now = now,
                dueHours = item.dueHours,
                triggerAt = triggerAt,
                urgency = parseUrgency(item.urgency),
            )
            val entity = PendingThoughtEntity(
                id = "${sourceIdPrefix}_$idx",
                assistantId = assistantId,
                kind = parseKind(item.kind).name,
                seed = cleanSeed,
                source = ThoughtSource.CONVERSATION.name,
                sourceRef = "turn:$sourceIdPrefix:$idx",
                createdAt = now,
                expiresAt = expiresAt,
                triggerAt = triggerAt,
                urgency = parseUrgency(item.urgency).name,
                weight = 0.72f,
            )
            if (repo.note(entity)) {
                added++
                if (triggerAt != null && (earliestTriggerAt == null || triggerAt < earliestTriggerAt!!)) {
                    earliestTriggerAt = triggerAt
                }
            }
        }
        earliestTriggerAt?.let { scheduler?.scheduleWakeUpForThought(it) }
        return added
    }

    suspend fun persistFromMemorySummary(
        repo: ProactiveThoughtRepository,
        scheduler: ProactiveScheduler?,
        assistantId: String,
        sessionId: String,
        thoughts: List<ParsedProactiveThought>,
        now: Long = System.currentTimeMillis(),
    ): Int {
        if (thoughts.isEmpty()) return 0
        val existingSeeds = repo.getOpen(assistantId).mapTo(HashSet()) { it.seed.trim().lowercase() }
        var added = 0
        thoughts.take(2).forEach { parsed ->
            val cleanSeed = parsed.seed.trim().take(200)
            if (cleanSeed.length < 3 || !existingSeeds.add(cleanSeed.lowercase())) return@forEach
            val urgency = parseUrgency(parsed.urgency)
            val entity = PendingThoughtEntity(
                id = "mem_${sessionId}_${cleanSeed.hashCode().toUInt()}",
                assistantId = assistantId,
                kind = parseKind(parsed.kind).name,
                seed = cleanSeed,
                source = ThoughtSource.MEMORY.name,
                sourceRef = "summary:$sessionId:${cleanSeed.hashCode()}",
                createdAt = now,
                expiresAt = resolveExpiresAt(now = now, dueHours = parsed.dueHours, triggerAt = null, urgency = urgency),
                triggerAt = null,
                urgency = urgency.name,
                weight = parsed.weight.coerceIn(0.1f, 1.0f),
            )
            if (repo.note(entity)) added++
        }
        if (added > 0 && scheduler != null) {
            // 无显式 triggerAt 的记忆牵挂仍按既有周期检查，这里不做额外短周期加塞。
        }
        return added
    }

    private fun parseKind(raw: String?): ThoughtKind = ThoughtKind.entries.firstOrNull {
        it.name.equals(raw, ignoreCase = true)
    } ?: ThoughtKind.FOLLOW_UP

    private fun parseUrgency(raw: String?): ThoughtUrgency = ThoughtUrgency.entries.firstOrNull {
        it.name.equals(raw, ignoreCase = true)
    } ?: ThoughtUrgency.WINDOW

    private fun resolveExpiresAt(now: Long, dueHours: Float?, triggerAt: Long?, urgency: ThoughtUrgency): Long? {
        val baseExpires = when {
            dueHours != null && dueHours > 0f ->
                now + (dueHours * 3_600_000f).toLong().coerceIn(1_800_000L, 30L * 86_400_000L)
            urgency == ThoughtUrgency.EVERGREEN -> null
            urgency == ThoughtUrgency.IMMEDIATE -> now + 24 * 3_600_000L
            else -> now + 7L * 86_400_000L
        }
        // 若指定了较晚的 triggerAt，确保过期时间至少晚于触发点 6 小时，防止尚未唤醒就已经过期。
        return if (triggerAt != null && baseExpires != null && baseExpires < triggerAt + 6 * 3_600_000L) {
            triggerAt + 24 * 3_600_000L
        } else {
            baseExpires
        }
    }
}
