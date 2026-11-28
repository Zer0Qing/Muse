package io.zer0.muse.data.agentbot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 示范学习（Teach Mode）录制器。
 *
 * 用户开启示范模式后，记录其在手机或对话中演示的关键步骤（点击应用、输入动作或口述步骤），
 * 结束时自动整理成结构化的惯例模板，交由 [AgentRoutineEngine] 持久化为可复用 Routine。
 */
class AgentTeachRecorder {

    data class RecordedStep(
        val index: Int,
        val action: String,
        val targetApp: String = "",
        val targetPackage: String = "",
        val targetText: String = "",
        val contentDesc: String = "",
        val resourceIdHint: String = "",
        val actionKind: String = "",
        val timestamp: Long = System.currentTimeMillis(),
    )

    data class State(
        val recording: Boolean = false,
        val titleDraft: String = "",
        val botId: String = "",
        val startedAt: Long = 0L,
        val steps: List<RecordedStep> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun start(botId: String, titleDraft: String = "") {
        _state.value = State(
            recording = true,
            titleDraft = titleDraft.trim(),
            botId = botId,
            startedAt = System.currentTimeMillis(),
            steps = emptyList(),
        )
    }

    fun resume(snapshot: State) {
        if (!snapshot.recording) return
        _state.value = snapshot
    }

    fun recordStep(action: String, targetApp: String = "") {
        recordStructuredStep(action = action, targetApp = targetApp)
    }

    /** 记录带结构化无障碍线索的一步操作；字段全可选，保留无障碍节点包名、文案与控件标识。 */
    @Suppress("LongParameterList")
    fun recordStructuredStep(
        action: String,
        targetApp: String = "",
        targetPackage: String = "",
        targetText: String = "",
        contentDesc: String = "",
        resourceIdHint: String = "",
        actionKind: String = "",
    ) {
        val cleaned = action.trim()
        if (cleaned.isBlank()) return
        _state.update { current ->
            if (!current.recording) return@update current
            val next = RecordedStep(
                index = current.steps.size + 1,
                action = cleaned.take(180),
                targetApp = targetApp.trim().take(60),
                targetPackage = targetPackage.trim().take(120),
                targetText = targetText.trim().take(80),
                contentDesc = contentDesc.trim().take(80),
                resourceIdHint = resourceIdHint.trim().take(120),
                actionKind = actionKind.trim().take(40),
            )
            current.copy(steps = current.steps + next)
        }
    }

    fun updateTitle(title: String) {
        _state.update { it.copy(titleDraft = title.take(60)) }
    }

    fun cancel() {
        _state.value = State()
    }

    /** 结束录制并产出可供惯例引擎直接保存的摘要与目标模板。 */
    fun finish(): FinishedDraft? {
        val current = _state.value
        _state.value = State()
        if (!current.recording || current.steps.isEmpty()) return null
        val title = current.titleDraft.ifBlank {
            current.steps.firstOrNull()?.action?.take(24).orEmpty().ifBlank { "自动流程" }
        }
        val summary = current.steps.joinToString(" → ") { step ->
            if (step.targetApp.isNotBlank()) "[${step.targetApp}] ${step.action}" else step.action
        }
        val goalPrompt = buildString {
            appendLine("请按我之前录制的以下流程完成「$title」：")
            current.steps.forEach { step ->
                val prefix = if (step.targetApp.isNotBlank()) "（${step.targetApp}）" else ""
                val meta = formatStructuredMeta(step)
                appendLine("${step.index}. $prefix${step.action}$meta")
            }
            append("如遇登录、验证码或高风险确认步骤，暂停并通知我接管或拍板。")
        }
        return FinishedDraft(
            botId = current.botId,
            title = title,
            goalPrompt = goalPrompt,
            stepsSummary = summary,
        )
    }

    private fun formatStructuredMeta(step: RecordedStep): String {
        val parts = buildList {
            if (step.targetPackage.isNotBlank()) add("pkg=${sanitizeMetaValue(step.targetPackage)}")
            if (step.actionKind.isNotBlank()) add("kind=${sanitizeMetaValue(step.actionKind)}")
            if (step.targetText.isNotBlank()) add("text=${sanitizeMetaValue(step.targetText)}")
            if (step.contentDesc.isNotBlank()) add("desc=${sanitizeMetaValue(step.contentDesc)}")
            if (step.resourceIdHint.isNotBlank()) add("id=${sanitizeMetaValue(step.resourceIdHint)}")
        }
        return if (parts.isEmpty()) "" else " [meta: ${parts.joinToString("; ")}]"
    }

    private fun sanitizeMetaValue(raw: String): String = raw.replace("[", "(").replace("]", ")").replace(";", ",").trim()

    data class FinishedDraft(
        val botId: String,
        val title: String,
        val goalPrompt: String,
        val stepsSummary: String,
    )
}
