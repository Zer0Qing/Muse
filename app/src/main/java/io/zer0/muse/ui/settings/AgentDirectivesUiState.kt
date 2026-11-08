package io.zer0.muse.ui.settings

import io.zer0.muse.data.assistant.AgentDirective
import io.zer0.muse.data.assistant.AgentDirectiveStore
import io.zer0.muse.data.assistant.DirectivePolarity
import io.zer0.muse.proactive.AgentDirectivesPrompt

/**
 * 约定页的可测内核（纯函数，不碰 Compose 也不碰 Koin）。
 *
 * 为什么要单独切出来：页面本身注入 `AssistantRepository`，要整体渲染就得在测试里
 * 起一整套 Koin + Room；那套脚手架本身更容易坏，测到的东西却更少。
 * 真正会出事的三个判断都在这里，可以逐条钉住并做变异验证。
 */
internal object AgentDirectivesUiState {

    /** 当前编辑哪个助手：用户选过的优先，否则落到列表第一个；空列表为空串（界面无规则可编辑）。 */
    fun resolveAgentId(selected: String?, assistantIds: List<String>): String {
        if (selected != null && selected in assistantIds) return selected
        return assistantIds.firstOrNull() ?: ""
    }

    /** 某个助手的两组规则；各自按 position 排，装配层保证「不要做」在上。 */
    data class Sections(val forbidden: List<AgentDirective>, val desired: List<AgentDirective>)

    fun sectionsFor(all: List<AgentDirective>, agentId: String): Sections {
        val mine = all.filter { it.agentId == agentId }
        return Sections(
            forbidden = mine.filter { it.polarity == DirectivePolarity.MUST_NOT }.sortedBy { it.position },
            desired = mine.filter { it.polarity == DirectivePolarity.SHOULD }.sortedBy { it.position },
        )
    }

    /**
     * 没生效的条数。
     *
     * **必须复用装配层同一个函数**：界面说"都生效了"而模型那边被截断，
     * 是这个功能最坏的失败模式（用户以为自己提了要求）。
     */
    fun omittedCount(mine: List<AgentDirective>): Int = AgentDirectivesPrompt.build(mine, AgentDirectiveStore.MAX_TOTAL_CHARS).omitted.size
}
