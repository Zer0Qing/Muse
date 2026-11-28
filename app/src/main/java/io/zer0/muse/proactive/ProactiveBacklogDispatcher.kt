package io.zer0.muse.proactive

import io.zer0.common.ProcessWriteGate
import io.zer0.muse.data.proactive.BacklogPublishResult
import io.zer0.muse.data.proactive.ProactiveBacklogPublisher
import io.zer0.muse.data.proactive.ProactiveBacklogRepository
import io.zer0.muse.data.proactive.ProactiveTimingEngine
import io.zer0.muse.data.proactive.ThoughtStatus
import io.zer0.muse.schedule.GenerationGate
import io.zer0.muse.schedule.readScheduleWorkEnabledOrFalse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

sealed interface BacklogDispatchOutcome {
    data object Sent : BacklogDispatchOutcome
    data object NotDue : BacklogDispatchOutcome
    data object Empty : BacklogDispatchOutcome
    data object Deferred : BacklogDispatchOutcome
    data object ClaimLost : BacklogDispatchOutcome
    data class Retried(val nextAt: Long) : BacklogDispatchOutcome
    data class Failed(val reason: String) : BacklogDispatchOutcome
}

class ProactiveBacklogDispatcher(
    private val backlog: ProactiveBacklogRepository,
    private val stores: ProactiveStores,
    private val runtime: ProactiveRuntime,
    private val loader: ProactiveContextLoader,
    private val composer: ProactiveComposer,
    private val publisher: ProactiveBacklogPublisher,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val onCuePublished: (suspend (assistantId: String, sessionId: String, title: String, content: String) -> Unit)? = null,
) {
    suspend fun dispatchOne(now: Long = nowProvider()): BacklogDispatchOutcome {
        if (!isEnabled()) return BacklogDispatchOutcome.Deferred
        val config = runtime.settings.proactiveMessageConfigFlow.first()
        val persistedNextAt = backlog.nextBacklogDispatchAt()
        if (now < persistedNextAt) return BacklogDispatchOutcome.NotDue

        val claim = backlog.claimOne(now) ?: return BacklogDispatchOutcome.Empty
        if (claim.dispatch.attemptCount >= MAX_BACKLOG_ATTEMPTS) {
            return fail(claim, now, "达到最大重试次数(${MAX_BACKLOG_ATTEMPTS})")
        }
        val thought = claim.thought
        val assistant = runtime.assistants.getById(claim.dispatch.assistantId)
        if (assistant == null) return fail(claim, now, "目标助手不存在")
        if (config.agentId.isNotBlank() && config.agentId != assistant.id) {
            return fail(claim, now, "非当前主动发送助手")
        }
        if (thought.status != ThoughtStatus.OPEN.name || thought.lastSurfacedAt != null ||
            (thought.expiresAt != null && thought.expiresAt <= now)
        ) {
            return fail(claim, now, "牵挂已处理或过期")
        }

        val relation = stores.relations.getOrCreate(assistant.id, now)
        val intensity = relation.intensityEnum()
        if (!intensity.allows(thought.kindEnum())) {
            return fail(claim, now, "当前主动等级不允许该类型牵挂")
        }
        if (stores.outcomes.isKindUnpopular(assistant.id, thought.kindEnum())) {
            return fail(claim, now, "历史反馈屏蔽该类型")
        }
        if (runtime.generations.activeGenerations.value.isNotEmpty()) {
            return retry(claim, now, "正在生成回复")
        }
        val allowedAt = safeWindowStart(now, config.allowedHourStart, config.allowedHourEnd, config.allDayAllowed)
        val notBefore = maxOf(now + ProactiveBacklogRepository.DISPATCH_GAP_MS, relation.cooldownUntil, allowedAt)
        if (relation.cooldownUntil > now || allowedAt > now) {
            return retry(claim, now, "免打扰或冷却窗口", notBefore)
        }
        if (ProactiveTimingEngine.remainingBudget(relation) < 1f) {
            return retry(claim, now, "当日额度已用完", nextLocalMidnight(now))
        }

        val sessionId = claim.dispatch.sessionId.takeIf { it.isNotBlank() }
            ?: loader.resolveSession(assistant)
            ?: return fail(claim, now, "没有可用会话")
        val scopedClaim = backlog.bindClaimedSession(claim, sessionId, now) ?: return BacklogDispatchOutcome.ClaimLost
        val context = loader.loadSessionContext(sessionId, assistant.id)
        if (context.lastUserMessageAt > 0L &&
            now - context.lastUserMessageAt < ProactiveTimingEngine.USER_ACTIVE_WINDOW_MS
        ) {
            return retry(claim, now, "用户近期活跃，稍后派发")
        }
        val route = ProactiveModelPicker.pick(runtime.settings, assistant)
        val lastOutcome = stores.outcomes.getRecent(assistant.id, 1).firstOrNull()
        val minutesSinceChat = context.recentMessages.maxOfOrNull { it.createdAt }
            ?.let { (now - it).coerceAtLeast(0L) / 60_000L }
        val minutesSinceProactive = lastOutcome?.let { (now - it.sentAt).coerceAtLeast(0L) / 60_000L }
        val localeTag = java.util.Locale.getDefault().toLanguageTag().ifBlank { "zh-CN" }
        val generated = withTimeoutOrNull(COMPOSE_TIMEOUT_MS) {
            GenerationGate.withPermit {
                composer.compose(
                    input = ProactiveComposer.Input(
                        assistant = assistant,
                        sessionId = sessionId,
                        candidates = listOf(thought),
                        recentMessages = context.recentMessages,
                        recentProactiveMessages = context.recentProactiveTexts,
                        now = now,
                        minutesSinceLastChat = minutesSinceChat,
                        minutesSinceLastProactive = minutesSinceProactive,
                        intensity = intensity,
                        locale = localeTag,
                    ),
                    model = route?.second,
                    providerConfig = route?.first,
                    temperature = config.temperature,
                )
            }
        } ?: io.zer0.muse.proactive.ProactiveReplyParser.Result.Failed("生成超时")

        return when (generated) {
            is ProactiveReplyParser.Result.Failed -> retry(scopedClaim, now, generated.reason)
            is ProactiveReplyParser.Result.Skip -> retry(scopedClaim, now, "模型跳过: ${generated.reason}")
            is ProactiveReplyParser.Result.Message -> when (publisher.publish(scopedClaim, generated.content, assistant.name, now)) {
                is BacklogPublishResult.Published -> {
                    runCatching {
                        onCuePublished?.invoke(
                            assistant.id,
                            sessionId,
                            thought.seed.ifBlank { assistant.name }.take(40),
                            generated.content,
                        )
                    }.onFailure { err ->
                        io.zer0.common.Logger.w("ProactiveBacklog", "写入流内主动简报卡失败: ${err.message}")
                    }
                    BacklogDispatchOutcome.Sent
                }
                BacklogPublishResult.ClaimLost -> BacklogDispatchOutcome.ClaimLost
                BacklogPublishResult.BudgetUnavailable -> retry(scopedClaim, now, "并发额度已用完", nextLocalMidnight(now))
                BacklogPublishResult.TargetUnavailable -> fail(scopedClaim, now, "牵挂或会话已不可用")
            }
        }
    }

    suspend fun recoverExpiredClaims(now: Long = nowProvider()): Int = backlog.releaseExpiredClaims(now)

    suspend fun hasUnfinishedDispatches(): Boolean = backlog.hasUnfinishedDispatches()

    suspend fun nextBacklogDispatchAt(): Long = backlog.nextBacklogDispatchAt()

    suspend fun nextWorkAt(now: Long = nowProvider()): Long = backlog.nextWorkAt(now)

    suspend fun canSchedule(): Boolean = isEnabled()

    private suspend fun isEnabled(): Boolean {
        if (ProcessWriteGate.restoring) return false
        val workEnabled = readScheduleWorkEnabledOrFalse { runtime.settings.scheduleWorkEnabledFlow.first() }
        return workEnabled && runtime.settings.proactiveMessageConfigFlow.first().enabled
    }

    private suspend fun retry(
        claim: io.zer0.muse.data.proactive.ClaimedBacklog,
        now: Long,
        reason: String,
        allowedAt: Long = 0L,
    ): BacklogDispatchOutcome {
        val nextAttempt = claim.dispatch.attemptCount + 1
        if (nextAttempt >= MAX_BACKLOG_ATTEMPTS) {
            return fail(claim, now, "$reason (已达重试图上限)")
        }
        val backoffMultiplier = 1L shl (nextAttempt - 1).coerceIn(0, 3)
        val backoffMs = ProactiveBacklogRepository.DISPATCH_GAP_MS * backoffMultiplier
        val next = maxOf(now + backoffMs, allowedAt)
        return if (backlog.retry(claim, now, reason, next) == 1) {
            BacklogDispatchOutcome.Retried(next)
        } else {
            BacklogDispatchOutcome.ClaimLost
        }
    }

    private companion object {
        const val COMPOSE_TIMEOUT_MS = 60_000L
        const val MAX_BACKLOG_ATTEMPTS = 3
    }

    private suspend fun fail(claim: io.zer0.muse.data.proactive.ClaimedBacklog, now: Long, reason: String): BacklogDispatchOutcome =
        if (backlog.fail(claim, now, reason) == 1) {
            BacklogDispatchOutcome.Failed(reason)
        } else {
            BacklogDispatchOutcome.ClaimLost
        }

    private fun safeWindowStart(now: Long, start: Int, end: Int, allDayAllowed: Boolean): Long {
        val hour = ProactiveTimingEngine.hourOfDay(now)
        if (allDayAllowed || !ProactiveTimingEngine.isInQuietHours(hour, start, end)) return now
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, start.coerceIn(0, 23))
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= now) calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis
    }

    private fun nextLocalMidnight(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
