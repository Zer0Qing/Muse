package io.zer0.muse.data.agentbot

import io.zer0.common.Logger
import io.zer0.muse.data.schedule.ScheduledTaskDao
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.schedule.CronExpression
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Agent 惯例（Routine）引擎。
 *
 * 将「跑通的任务 / 用户一句话定制 / 示范操作」沉淀为可复用的惯例，并统一桥接：
 * - MANUAL：工作台或对话中一键执行；
 * - CRON：与既有 `scheduled_tasks` 联动调度，不造第二套后台定时引擎；
 * - NOTIFICATION / CHANNEL：供通知或渠道事件命中时自动触发预准备与建议卡。
 */
class AgentRoutineEngine(
    private val repository: AgentBotRepository,
    private val scheduler: BotTaskScheduler,
    private val scheduledTaskDao: ScheduledTaskDao? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    fun observeByBot(botId: String): Flow<List<BotRoutineEntity>> =
        repository.observeRoutines(botId)

    fun observeAll(): Flow<List<BotRoutineEntity>> =
        repository.observeAllRoutines()

    /** 从一条已完成的任务提炼并沉淀成可复用惯例。 */
    suspend fun saveFromTask(
        task: BotTaskEntity,
        title: String = "",
        triggerType: String = RoutineTriggerType.MANUAL,
        triggerExpr: String = "",
    ): BotRoutineEntity {
        val steps = runCatching { repository.listTaskSteps(task.taskId) }.getOrDefault(emptyList())
        val stepsSummary = steps
            .map { it.displayLabel.ifBlank { it.toolName } }
            .filter(String::isNotBlank)
            .distinct()
            .take(8)
            .joinToString(" → ")
        return createOrUpdate(
            botId = task.botId,
            title = title.trim().ifBlank { task.goal.trim().take(28) },
            goalPrompt = task.goal.trim(),
            stepsSummary = stepsSummary,
            resourceScope = task.resourceScope,
            triggerType = triggerType,
            triggerExpr = triggerExpr,
            sourceTaskId = task.taskId,
        )
    }

    /** 新建或保存一条惯例；若为 CRON 类型则自动同步写入 scheduled_tasks。 */
    suspend fun createOrUpdate(
        botId: String,
        title: String,
        goalPrompt: String,
        stepsSummary: String = "",
        resourceScope: String = "",
        triggerType: String = RoutineTriggerType.MANUAL,
        triggerExpr: String = "",
        sourceTaskId: String = "",
        skillId: String = "",
        originSessionId: String = "",
    ): BotRoutineEntity {
        val normalizedTrigger = if (triggerType in RoutineTriggerType.all) {
            triggerType
        } else {
            RoutineTriggerType.MANUAL
        }
        val ts = now()
        val routineId = UUID.randomUUID().toString()
        var linkedScheduleId = ""
        if (normalizedTrigger == RoutineTriggerType.CRON && scheduledTaskDao != null && triggerExpr.isNotBlank()) {
            linkedScheduleId = "routine:$routineId"
            val nextRunAt = runCatching {
                CronExpression.parse(triggerExpr)?.nextRunAfter(ts) ?: (ts + ONE_DAY_MS)
            }.onFailure {
                Logger.w(TAG, "解析惯例 Cron 表达式失败($triggerExpr): ${it.message}")
            }.getOrDefault(ts + ONE_DAY_MS)
            scheduledTaskDao.upsert(
                ScheduledTaskEntity(
                    id = linkedScheduleId,
                    name = title.trim().ifBlank { goalPrompt.take(24) },
                    prompt = goalPrompt.trim(),
                    assistantId = botId.ifBlank { "default" },
                    interval = "cron",
                    cronExpr = triggerExpr.trim(),
                    enabled = true,
                    nextRunAt = nextRunAt,
                    dedicatedSessionId = originSessionId,
                    createdBy = "assistant",
                    createdAt = ts,
                    updatedAt = ts,
                ),
            )
        }
        val entity = BotRoutineEntity(
            routineId = routineId,
            botId = botId,
            title = title.trim().ifBlank { goalPrompt.trim().take(28) },
            goalPrompt = goalPrompt.trim(),
            stepsSummary = stepsSummary.trim(),
            resourceScope = resourceScope.trim(),
            triggerType = normalizedTrigger,
            triggerExpr = triggerExpr.trim(),
            scheduledTaskId = linkedScheduleId,
            sourceTaskId = sourceTaskId,
            skillId = skillId,
            enabled = true,
            createdAt = ts,
            updatedAt = ts,
        )
        repository.upsertRoutine(entity)
        return entity
    }

    /** 立即运行一条惯例：创建后台任务并 0ms 唤醒调度器。 */
    suspend fun runNow(
        routine: BotRoutineEntity,
        originSessionId: String,
    ): BotTaskEntity? {
        if (originSessionId.isBlank() || routine.goalPrompt.isBlank()) return null
        val acceptance = if (routine.stepsSummary.isNotBlank()) {
            "按惯例步骤执行并清晰汇报结果：${routine.stepsSummary}"
        } else {
            "完成惯例「${routine.title}」并汇报真实结果"
        }
        val task = repository.createTask(
            botId = routine.botId,
            originSessionId = originSessionId,
            requestMessageId = "",
            goal = routine.goalPrompt,
            acceptanceCriteria = acceptance,
            resourceScope = routine.resourceScope,
            priority = 1,
            dedupeKey = "routine-run:${routine.routineId}:${now()}",
        )
        repository.recordRoutineExecution(routine.routineId, BotTaskStatus.QUEUED)
        scheduler.requestImmediateTick()
        return task
    }

    suspend fun toggleEnabled(routine: BotRoutineEntity, enabled: Boolean) {
        repository.setRoutineEnabled(routine.routineId, enabled)
        val dao = scheduledTaskDao
        if (routine.scheduledTaskId.isNotBlank() && dao != null) {
            runCatching {
                val existing = dao.getById(routine.scheduledTaskId)
                if (existing != null) {
                    dao.upsert(existing.copy(enabled = enabled, updatedAt = now()))
                }
            }.onFailure {
                Logger.w(TAG, "同步定时任务开关失败: ${it.message}")
            }
        }
    }

    suspend fun delete(routine: BotRoutineEntity) {
        repository.deleteRoutine(routine.routineId)
        val dao = scheduledTaskDao
        if (routine.scheduledTaskId.isNotBlank() && dao != null) {
            runCatching { dao.delete(routine.scheduledTaskId) }
                .onFailure { Logger.w(TAG, "删除关联定时任务失败: ${it.message}") }
        }
    }

    /**
     * 匹配通知或外部渠道事件触发器：命中已启用惯例时生成一条主动建议卡，
     * 不在后台静默替用户执行高危外部动作。
     */
    suspend fun evaluateEventTrigger(
        triggerType: String,
        sourceLabel: String,
        eventText: String,
    ): List<BotRoutineEntity> {
        val candidates = repository.listEnabledRoutinesByTrigger(triggerType)
        if (candidates.isEmpty()) return emptyList()
        val haystack = "$sourceLabel $eventText".lowercase()
        return candidates.filter { routine ->
            val keywords = routine.triggerExpr
                .split(",", "，", ";", "|")
                .map { it.trim().lowercase() }
                .filter(String::isNotBlank)
            keywords.isEmpty() || keywords.any { kw -> haystack.contains(kw) }
        }
    }

    companion object {
        private const val TAG = "AgentRoutineEngine"
        private const val ONE_DAY_MS = 24 * 3600_000L
    }
}
