package io.zer0.muse.data.agentbot

import io.zer0.common.Logger
import io.zer0.muse.data.schedule.ScheduledTaskDao
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.schedule.CronExpression
import io.zer0.muse.tools.SkillImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * 一次任务沉淀的产物：技能负责「怎么做」，惯例负责「什么时候做」。
 *
 * [skill] 写入全局技能库，可被所有队友复用；[routine] 归属具体 botId，
 * 通过 [BotRoutineEntity.skillId] 引用技能，两者解耦后互不拖累。
 */
data class SkillRoutineBundle(
    val skill: SkillEntity,
    val routine: BotRoutineEntity,
)

/**
 * 一次例行运行的判定结果。
 *
 * [hasError] / [hasSubstantiveChange] / [needsConfirmation] 任一为真时才需要
 * 回到主对话；三者都为空时只做本地归档，避免固定动作天天刷屏。
 */
data class RoutineRunOutcome(
    val taskId: String,
    val status: String,
    val hasError: Boolean = false,
    val hasSubstantiveChange: Boolean = false,
    val needsConfirmation: Boolean = false,
    val changeSummary: String = "",
) {
    /** 是否需要把本次结果呈现到主对话。 */
    val shouldNotifyMainConversation: Boolean
        get() = hasError || hasSubstantiveChange || needsConfirmation

    /** 是否应当静默归档。 */
    val isSilentArchive: Boolean
        get() = !shouldNotifyMainConversation
}

/** 本地运行档案的一行；静默与需呈现的运行都保留，供工作台追溯。 */
data class RoutineRunArchiveEntry(
    val routineId: String,
    val taskId: String,
    val status: String,
    val summary: String,
    val silent: Boolean,
    val archivedAt: Long,
)

/**
 * Agent 惯例（Routine）引擎。
 *
 * 将「跑通的任务 / 用户一句话定制 / 示范操作」沉淀为可复用的惯例，并统一桥接：
 * - MANUAL：工作台或对话中一键执行；
 * - CRON：与既有 `scheduled_tasks` 联动调度，不造第二套后台定时引擎；
 * - NOTIFICATION / CHANNEL：供通知或渠道事件命中时自动触发预准备与建议卡。
 *
 * 技能与惯例是两层：技能存全局 `skills` 表，跨队友共享；惯例按 botId 隔离，
 * 只保存触发条件与 [BotRoutineEntity.skillId] 引用。
 */
class AgentRoutineEngine(
    private val repository: AgentBotRepository,
    private val scheduler: BotTaskScheduler,
    private val scheduledTaskDao: ScheduledTaskDao? = null,
    private val skillRepository: SkillRepository? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val _archivedRuns = MutableStateFlow<List<RoutineRunArchiveEntry>>(emptyList())

    /** 最近 200 次例行运行的本地档案；不写主对话，也不参与提示词注入。 */
    val archivedRuns: StateFlow<List<RoutineRunArchiveEntry>> = _archivedRuns.asStateFlow()

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
        val steps = loadTaskSteps(task.taskId)
        return createOrUpdate(
            botId = task.botId,
            title = title.trim().ifBlank { task.goal.trim().take(MAX_TITLE_LENGTH) },
            goalPrompt = task.goal.trim(),
            stepsSummary = summarizeSteps(steps),
            resourceScope = task.resourceScope,
            triggerType = triggerType,
            triggerExpr = triggerExpr,
            sourceTaskId = task.taskId,
        )
    }

    /**
     * 「做过即学会」：把一条已完成任务同时沉淀为共享技能与当前队友的惯例。
     *
     * 技能 id 由 taskId 派生，重复点击只会更新同一行；同一 sourceTaskId 的惯例
     * 也只保留一条，避免用户多次点击后工作台出现多张一样的卡片。
     *
     * @return 技能与惯例的组合；任务 id / 目标为空或技能写入失败时返回 null。
     */
    suspend fun saveTaskAsSkillAndRoutine(
        task: BotTaskEntity,
        title: String = "",
        triggerType: String = RoutineTriggerType.MANUAL,
        triggerExpr: String = "",
        originSessionId: String = "",
    ): SkillRoutineBundle? {
        if (task.taskId.isBlank() || task.goal.isBlank()) return null
        val steps = loadTaskSteps(task.taskId)
        val resolvedTitle = title.trim().ifBlank { task.goal.trim().take(MAX_TITLE_LENGTH) }
        if (resolvedTitle.isBlank()) return null
        val stepsSummary = summarizeSteps(steps)
        val timestamp = now()
        val skillRepo = resolveSkillRepository()
        val skillId = taskSkillId(task.taskId)
        val existingSkill = skillRepo?.getById(skillId)
        val skill = buildTaskSkill(
            task = task,
            title = resolvedTitle,
            steps = steps,
            timestamp = timestamp,
            existing = existingSkill,
        )
        if (skillRepo == null) {
            Logger.w(TAG, "技能仓库不可用，任务沉淀只保留惯例: ${task.taskId}")
        } else {
            try {
                skillRepo.upsert(skill)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "任务沉淀技能写入失败: ${e.message}", e)
                return null
            }
        }
        val linkedSkillId = if (skillRepo != null) skill.id else ""
        val existingRoutine = findRoutineBySourceTask(task.taskId)
        val routine = if (existingRoutine != null) {
            val updated = existingRoutine.copy(
                skillId = linkedSkillId.ifBlank { existingRoutine.skillId },
                updatedAt = timestamp,
            )
            if (updated != existingRoutine) repository.upsertRoutine(updated)
            updated
        } else {
            createOrUpdate(
                botId = task.botId,
                title = resolvedTitle,
                goalPrompt = task.goal.trim(),
                stepsSummary = stepsSummary,
                resourceScope = task.resourceScope,
                triggerType = triggerType,
                triggerExpr = triggerExpr,
                sourceTaskId = task.taskId,
                skillId = linkedSkillId,
                originSessionId = originSessionId.ifBlank { task.originSessionId },
            )
        }
        return SkillRoutineBundle(skill = skill, routine = routine)
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
        val normalizedTrigger = normalizeTrigger(triggerType)
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
            title = title.trim().ifBlank { goalPrompt.trim().take(MAX_TITLE_LENGTH) },
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
            "按自动化步骤执行并清晰汇报结果：${routine.stepsSummary}"
        } else {
            "完成自动化「${routine.title}」并汇报真实结果"
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
        repository.recordRoutineExecution(routine.routineId, RoutineRunStatus.QUEUED)
        scheduler.requestImmediateTick()
        return task
    }

    /**
     * 例行运行结束后的归档判定。
     *
     * 默认按执行步骤推断「是否有实质变化」：有成功写入类步骤才算变化。
     * 调用方若能从结果正文判断出变化，可显式传 [hasSubstantiveChange] 与
     * [changeSummary] 覆盖推断结果。
     *
     * @return 判定结果；仅当 [RoutineRunOutcome.shouldNotifyMainConversation] 为真时，
     *   调用方应把摘要呈现到主对话，否则只在本地留档。
     */
    suspend fun archiveRoutineRun(
        routine: BotRoutineEntity,
        task: BotTaskEntity,
        hasSubstantiveChange: Boolean? = null,
        changeSummary: String = "",
    ): RoutineRunOutcome {
        val steps = loadTaskSteps(task.taskId)
        val outcome = classifyRoutineRun(
            task = task,
            steps = steps,
            hasSubstantiveChangeOverride = hasSubstantiveChange,
            changeSummaryOverride = changeSummary,
        )
        repository.recordRoutineExecution(
            routineId = routine.routineId,
            status = if (outcome.isSilentArchive) RoutineRunStatus.ARCHIVED else task.status,
        )
        val summary = outcome.changeSummary.ifBlank { routine.title }
        _archivedRuns.update { current ->
            (current + RoutineRunArchiveEntry(
                routineId = routine.routineId,
                taskId = task.taskId,
                status = task.status,
                summary = summary,
                silent = outcome.isSilentArchive,
                archivedAt = now(),
            )).takeLast(MAX_ARCHIVE_ENTRIES)
        }
        if (outcome.isSilentArchive) {
            Logger.d(TAG, "例行运行静默归档: routine=${routine.routineId} task=${task.taskId} status=${task.status}")
        } else {
            Logger.d(
                TAG,
                "例行运行需要呈现: routine=${routine.routineId} task=${task.taskId} " +
                    "status=${task.status} summary=${outcome.changeSummary}",
            )
        }
        return outcome
    }

    /** 纯判定：不写库，便于调度器或测试在落库前先看结论。 */
    fun classifyRoutineRun(
        task: BotTaskEntity,
        steps: List<TaskStepEntity>,
        hasSubstantiveChangeOverride: Boolean? = null,
        changeSummaryOverride: String = "",
    ): RoutineRunOutcome {
        val hasError = task.status in ERROR_TASK_STATUSES
        val needsConfirmation = task.status == BotTaskStatus.NEEDS_USER ||
            task.status == BotTaskStatus.WAITING_APPROVAL
        val changedSteps = steps.filter { step ->
            step.status == StepExecutionStatus.SUCCEEDED && step.sideEffect != StepSideEffect.READ_ONLY
        }
        val derivedSummary = changedSteps
            .mapNotNull { step -> step.displayLabel.trim().ifBlank { step.toolName.trim() }.takeIf(String::isNotBlank) }
            .distinct()
            .take(MAX_CHANGE_ITEMS)
            .joinToString("、")
        val hasSubstantiveChange = hasSubstantiveChangeOverride ?: changedSteps.isNotEmpty()
        return RoutineRunOutcome(
            taskId = task.taskId,
            status = task.status,
            hasError = hasError,
            hasSubstantiveChange = hasSubstantiveChange,
            needsConfirmation = needsConfirmation,
            changeSummary = changeSummaryOverride.trim().ifBlank { derivedSummary },
        )
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

    private suspend fun loadTaskSteps(taskId: String): List<TaskStepEntity> =
        runCatching { repository.listTaskSteps(taskId) }
            .onFailure { Logger.w(TAG, "读取任务步骤失败($taskId): ${it.message}") }
            .getOrDefault(emptyList())

    private fun summarizeSteps(steps: List<TaskStepEntity>): String =
        steps
            .map { it.displayLabel.ifBlank { it.toolName } }
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_STEPS)
            .joinToString(" → ")

    private fun buildTaskSkill(
        task: BotTaskEntity,
        title: String,
        steps: List<TaskStepEntity>,
        timestamp: Long,
        existing: SkillEntity?,
    ): SkillEntity {
        val stepLabels = steps
            .mapNotNull { step -> step.displayLabel.trim().ifBlank { step.toolName.trim() }.takeIf(String::isNotBlank) }
            .distinct()
            .take(MAX_STEPS)
        val promptText = buildString {
            appendLine("执行技能「$title」时，按已验证的路径完成：")
            if (stepLabels.isEmpty()) {
                appendLine("1. ${task.goal.trim()}")
            } else {
                stepLabels.forEachIndexed { index, label ->
                    appendLine("${index + 1}. $label")
                }
            }
            if (task.acceptanceCriteria.isNotBlank()) {
                appendLine()
                appendLine("验收标准：${task.acceptanceCriteria.trim()}")
            }
            appendLine()
            append("遇到登录、验证码、支付、权限授权或与已录制路径不一致的页面时暂停，交给用户接管。")
        }
        return SkillEntity(
            id = taskSkillId(task.taskId),
            name = title.take(MAX_TITLE_LENGTH),
            description = "从已完成任务沉淀的可复用技能：${task.goal.trim().take(MAX_DESCRIPTION_LENGTH)}",
            parametersJson = SkillImporter.EMPTY_PARAMETERS_JSON,
            requiredJson = "[]",
            implementationKotlin = SkillImporter.encodePromptText(promptText),
            enabled = existing?.enabled ?: true,
            category = SHARED_SKILL_CATEGORY,
            createdAt = existing?.createdAt ?: timestamp,
            updatedAt = timestamp,
        )
    }

    private suspend fun findRoutineBySourceTask(taskId: String): BotRoutineEntity? =
        repository.observeAllRoutines().first().firstOrNull { it.sourceTaskId == taskId }

    private fun resolveSkillRepository(): SkillRepository? {
        skillRepository?.let { return it }
        return try {
            org.koin.core.context.GlobalContext.getOrNull()?.get<SkillRepository>()
        } catch (e: Exception) {
            Logger.w(TAG, "技能仓库解析失败: ${e.message}", e)
            null
        }
    }

    private fun normalizeTrigger(triggerType: String): String =
        if (triggerType in RoutineTriggerType.all) triggerType else RoutineTriggerType.MANUAL

    private fun taskSkillId(taskId: String): String = "task_skill_$taskId"

    companion object {
        private const val TAG = "AgentRoutineEngine"
        private const val ONE_DAY_MS = 24 * 3600_000L
        private const val MAX_TITLE_LENGTH = 40
        private const val MAX_DESCRIPTION_LENGTH = 160
        private const val MAX_STEPS = 8
        private const val MAX_CHANGE_ITEMS = 5
        private const val MAX_ARCHIVE_ENTRIES = 200
        private const val SHARED_SKILL_CATEGORY = "user"

        private val ERROR_TASK_STATUSES = setOf(
            BotTaskStatus.FAILED,
            BotTaskStatus.PARTIAL,
            BotTaskStatus.UNKNOWN,
            BotTaskStatus.CANCELLED,
        )
    }
}
