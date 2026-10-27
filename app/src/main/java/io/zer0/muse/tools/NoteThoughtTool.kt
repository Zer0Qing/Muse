package io.zer0.muse.tools

import io.zer0.muse.data.proactive.PendingThoughtEntity
import io.zer0.muse.data.proactive.ProactiveThoughtRepository
import io.zer0.muse.data.proactive.ThoughtKind
import io.zer0.muse.data.proactive.ThoughtSource
import io.zer0.muse.data.proactive.ThoughtUrgency

/**
 * 牵挂记账工具 —— 「记下一件以后想问他的事」。
 *
 * 这是主动消息新机制的主要燃料来源。整套设计的假设是：
 * 助手在日常对话里顺手记下「隔几天再问会显得有人味」的事，
 * 之后在这些事上主动开口。没有这个工具，账本永远是空的，
 * 主动消息就退回成「定时问候」。
 *
 * 门槛刻意调高。系统提示里反复强调「宁可不记」：
 * 账本一旦塞满碎事，主动消息就会显得廉价 ——
 * 而这套机制的全部说服力就建立在「它说的每句话都有由头」上。
 */
object NoteThoughtTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "note_thought",
        description = "记下一件以后值得主动问起的事。在对话里自然出现时使用：" +
            "他说了将来要做的事、正困扰他的事、或者明确表达过的期待。" +
            "不要记日常寒暄和无关紧要的细节 —— 只有隔几天再问起会显得自然的事才值得记。",
        parameters = mapOf(
            "seed" to "必填。那件事是什么，一句话，用你自己的话写，不要照抄原话。",
            "kind" to "必填。类型：reminder（他有具体要做的事）/ follow_up（没聊完的话题）/" +
                "care（他的状态，比如身体或情绪）/ share（你自己想分享的事）/" +
                "milestone（你们之间的节点）。",
            "urgency" to "可选。immediate（过了时间点就没意义）/ window（这几天说都行，默认）/" +
                "evergreen（什么时候说都行）。",
            "weight" to "可选。你有多在意这件事，0 到 1 之间的小数，默认 0.6。" +
                "真正要紧的事才给 0.8 以上。",
            "within_hours" to "可选。如果是 immediate 或 window，多少小时内说都算合适。默认 168（一周）。",
        ),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    suspend fun execute(
        args: Map<String, String>,
        thoughts: ProactiveThoughtRepository,
        executionContext: ToolExecutionContext,
    ): String {
        val parsed = ParsedArgs.from(args)
        if (parsed.seed.isBlank()) return "Error: seed 不能为空"

        val now = System.currentTimeMillis()
        val assistantId = resolveAssistantId(executionContext)

        // 用 seed 的哈希做来源标识：同一件事重复被提起时不会记成两条。
        // 不用会话 id 或时间戳，那样每次都会产生新的「来源」，去重就等于没做。
        val ref = "note_${parsed.seed.hashCode().toUInt().toString(16)}"

        val recorded = thoughts.note(
            PendingThoughtEntity(
                id = "th_${now}_${ref.hashCode().toUInt().toString(16)}",
                assistantId = assistantId,
                kind = parsed.kind.name,
                seed = parsed.seed.take(200),
                source = ThoughtSource.CONVERSATION.name,
                sourceRef = ref,
                createdAt = now,
                expiresAt = if (parsed.urgency == ThoughtUrgency.EVERGREEN) {
                    null
                } else {
                    now + parsed.withinHours * 3_600_000L
                },
                urgency = parsed.urgency.name,
                weight = parsed.weight,
            ),
        )

        return if (recorded) {
            "已记下。之后合适的时候我会问起这件事。"
        } else {
            "这件事我已经记着了，不用重复记。"
        }
    }

    /** 归属助手：优先执行上下文里的，其次作用域，最后 default。 */
    private fun resolveAssistantId(executionContext: ToolExecutionContext): String =
        executionContext.assistantId?.takeIf { it.isNotBlank() }
            ?: executionContext.scope.takeIf { it.isNotBlank() && it != "main" }
            ?: "default"

    /**
     * 参数解析。
     *
     * 单独拎出来是因为参数的容错规则（拼错的 kind 该归到哪一类、
     * 越界的 weight 要不要夹住）值得独立成一段可读的逻辑，
     * 混在工具主体里会让主体被一串 when 淹掉。
     */
    private data class ParsedArgs(
        val seed: String,
        val kind: ThoughtKind,
        val urgency: ThoughtUrgency,
        val weight: Float,
        val withinHours: Long,
    ) {
        companion object {
            fun from(args: Map<String, String>): ParsedArgs = ParsedArgs(
                seed = args["seed"]?.trim().orEmpty(),
                kind = parseKind(args["kind"]),
                urgency = parseUrgency(args["urgency"]),
                weight = args["weight"]?.trim()?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.6f,
                withinHours = args["within_hours"]?.trim()?.toLongOrNull()?.coerceIn(1L, 720L) ?: 168L,
            )

            private fun parseKind(raw: String?): ThoughtKind =
                ThoughtKind.entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
                    ?: ThoughtKind.FOLLOW_UP

            private fun parseUrgency(raw: String?): ThoughtUrgency =
                ThoughtUrgency.entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
                    ?: ThoughtUrgency.WINDOW
        }
    }
}
