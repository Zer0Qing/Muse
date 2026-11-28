package io.zer0.muse.data.agentbot

import io.zer0.muse.R
import kotlinx.serialization.Serializable

/** 语音/文本之外，Agent 主舞台可主动展示的轻量建议类型。 */
enum class AgentSuggestionKind {
    MORNING_BRIEF,
    AFTERNOON_PROGRESS,
    EVENING_REVIEW,
    PENDING_APPROVAL,
    ACTIVE_TASK,
    ROUTINE,
    CALENDAR,
}

/** Dream 夜间整理在次日可转成的建议类别。 */
@Serializable
enum class AgentDreamSignalKind {
    CALENDAR,
    WATCHER,
    GOAL,
}

/**
 * Dream 产出的次日建议信号。
 *
 * 只携带类别、数量与排序权重：文案仍由 Compose 层按资源 id 解析，
 * 报告持久化时不会把某一种语言的文案固化进数据层。
 */
@Serializable
data class AgentDreamNextDaySignal(
    val kind: AgentDreamSignalKind,
    val itemCount: Int,
    val priority: Int = 0,
)

/**
 * 一条动态建议。
 *
 * 只保存资源 id 与数量参数，文案在 Compose 层通过 stringResource 解析，保证多语言与
 * 主题切换不会把文案固化在数据层。
 */
data class AgentSuggestion(
    val id: String,
    val kind: AgentSuggestionKind,
    val titleRes: Int,
    val promptRes: Int,
    val titleCount: Int? = null,
    val promptCount: Int? = null,
    val priority: Int = 0,
    val titleText: String? = null,
    val promptText: String? = null,
)

/** 生成建议所需的纯数据快照。 */
data class AgentSuggestionInput(
    val hourOfDay: Int,
    val activeTaskCount: Int = 0,
    val pendingApprovalCount: Int = 0,
    val enabledRoutineCount: Int = 0,
    val todayCalendarCount: Int = 0,
    val pendingReminderCount: Int = 0,
    val hasConversationHistory: Boolean = false,
    /**
     * Dream 夜间整理产出的次日建议。
     *
     * 调用方从 [AgentDreamStore.lastReport] 或 [AgentDreamReport.nextDaySignals] 读取后传入；
     * 非空时优先占用建议位，时段问候只在剩余位置补位。
     */
    val dreamNextDaySignals: List<AgentDreamNextDaySignal> = emptyList(),
)

/**
 * 根据当前时段与 Agent 工作状态生成最多三条建议。
 *
 * 优先级固定为：待拍板 > Dream 次日建议 > 进行中任务 > 日历/提醒 > 已启用自动化 > 时段问候。
 * 同一类建议使用稳定 id，供界面在用户关闭后按 id 记住本次隐藏。
 */
object AgentSuggestionEngine {
    fun generate(input: AgentSuggestionInput): List<AgentSuggestion> {
        val candidates = buildList {
            input.dreamNextDaySignals
                .filter { it.itemCount > 0 }
                .distinctBy { it.kind }
                .sortedByDescending { it.priority }
                .forEach { signal -> add(signal.toSuggestion()) }
            if (input.pendingApprovalCount > 0) {
                add(
                    AgentSuggestion(
                        id = "pending_approval",
                        kind = AgentSuggestionKind.PENDING_APPROVAL,
                        titleRes = R.string.agent_suggestion_pending_title,
                        promptRes = R.string.agent_suggestion_pending_prompt,
                        titleCount = input.pendingApprovalCount,
                        priority = 100,
                    ),
                )
            }
            if (input.activeTaskCount > 0) {
                add(
                    AgentSuggestion(
                        id = "active_task",
                        kind = AgentSuggestionKind.ACTIVE_TASK,
                        titleRes = R.string.agent_suggestion_active_task_title,
                        promptRes = R.string.agent_suggestion_active_task_prompt,
                        titleCount = input.activeTaskCount,
                        priority = 90,
                    ),
                )
            }
            val calendarCount = input.todayCalendarCount + input.pendingReminderCount
            if (calendarCount > 0) {
                add(
                    AgentSuggestion(
                        id = "calendar",
                        kind = AgentSuggestionKind.CALENDAR,
                        titleRes = R.string.agent_suggestion_calendar_title,
                        promptRes = R.string.agent_suggestion_calendar_prompt,
                        titleCount = calendarCount,
                        priority = 80,
                    ),
                )
            }
            if (input.enabledRoutineCount > 0) {
                add(
                    AgentSuggestion(
                        id = "routine",
                        kind = AgentSuggestionKind.ROUTINE,
                        titleRes = R.string.agent_suggestion_routine_title,
                        promptRes = R.string.agent_suggestion_routine_prompt,
                        titleCount = input.enabledRoutineCount,
                        priority = 70,
                    ),
                )
            }
            if (!input.hasConversationHistory) {
                when (input.hourOfDay) {
                    in 5..11 -> add(
                        AgentSuggestion(
                            id = "morning_brief",
                            kind = AgentSuggestionKind.MORNING_BRIEF,
                            titleRes = R.string.agent_suggestion_morning_title,
                            promptRes = R.string.agent_suggestion_morning_prompt,
                            priority = 60 - input.hourOfDay,
                        ),
                    )
                    in 12..17 -> add(
                        AgentSuggestion(
                            id = "afternoon_progress",
                            kind = AgentSuggestionKind.AFTERNOON_PROGRESS,
                            titleRes = R.string.agent_suggestion_afternoon_title,
                            promptRes = R.string.agent_suggestion_afternoon_prompt,
                            priority = 50 - (input.hourOfDay - 12),
                        ),
                    )
                    in 18..22 -> add(
                        AgentSuggestion(
                            id = "evening_review",
                            kind = AgentSuggestionKind.EVENING_REVIEW,
                            titleRes = R.string.agent_suggestion_evening_title,
                            promptRes = R.string.agent_suggestion_evening_prompt,
                            priority = 40 - (input.hourOfDay - 18),
                        ),
                    )
                    else -> Unit
                }
            }
        }
        return candidates
            .sortedWith(compareByDescending<AgentSuggestion> { it.priority }.thenBy { it.id })
            .take(3)
    }

    /** 消费一份 Dream 报告；报告没有次日信号时退化为普通生成。 */
    fun generate(input: AgentSuggestionInput, report: AgentDreamReport?): List<AgentSuggestion> =
        if (report == null || report.nextDaySignals.isEmpty()) {
            generate(input)
        } else {
            generate(input.copy(dreamNextDaySignals = report.nextDaySignals))
        }

    private fun AgentDreamNextDaySignal.toSuggestion(): AgentSuggestion = when (kind) {
        AgentDreamSignalKind.CALENDAR -> AgentSuggestion(
            id = "dream_calendar",
            kind = AgentSuggestionKind.CALENDAR,
            titleRes = R.string.agent_suggestion_calendar_title,
            promptRes = R.string.agent_suggestion_calendar_prompt,
            titleCount = itemCount,
            priority = priority,
        )
        AgentDreamSignalKind.WATCHER -> AgentSuggestion(
            id = "dream_watcher",
            kind = AgentSuggestionKind.ROUTINE,
            titleRes = R.string.agent_suggestion_routine_title,
            promptRes = R.string.agent_suggestion_routine_prompt,
            titleCount = itemCount,
            priority = priority,
        )
        AgentDreamSignalKind.GOAL -> AgentSuggestion(
            id = "dream_goal",
            kind = AgentSuggestionKind.ACTIVE_TASK,
            titleRes = R.string.agent_suggestion_active_task_title,
            promptRes = R.string.agent_suggestion_active_task_prompt,
            titleCount = itemCount,
            priority = priority,
        )
    }
}
