package io.zer0.muse.proactive

import io.zer0.muse.data.assistant.AgentDirective
import io.zer0.muse.data.assistant.DirectivePolarity

/**
 * 用户约定的提示词块（纯函数，便于把优先级与预算这两件容易写错的事钉死）。
 *
 * 两条规则不是风格偏好，是用户会不会信这个功能：
 *  1. **「不要做」永远排在「要做」前面，并且冲突时以它为准** ——
 *     参照平台的空态示例就是「别叫我宝贝」。这种要求被覆盖一次，用户就不会再往这里写东西了。
 *  2. **超预算不许静默截断** —— 装不下的条目要原样交回给界面说明"这几条这次没生效"。
 *     静默丢掉等于让用户以为说了有用。
 */
internal object AgentDirectivesPrompt {

    /** 一次装配的结果：要拼进提示词的文本块，以及没生效的条目。 */
    data class Result(val block: String, val omitted: List<AgentDirective>)

    /** 预算只算条目正文，标题与提示行不计 —— 免得标题长短挤掉用户真正写的内容。 */
    fun build(directives: List<AgentDirective>, totalBudgetChars: Int): Result {
        val active = directives.filter { it.enabled && it.text.isNotBlank() }
        if (active.isEmpty() || totalBudgetChars <= 0) return Result("", emptyList())

        // 「不希望」在前，保证模型先读到禁止项
        val ordered = active.filter { it.polarity == DirectivePolarity.MUST_NOT } +
            active.filter { it.polarity == DirectivePolarity.SHOULD }

        val taken = LinkedHashSet<AgentDirective>()
        val omitted = mutableListOf<AgentDirective>()
        var used = 0
        for (directive in ordered) {
            val cost = directive.text.trim().length
            if (used + cost > totalBudgetChars) {
                // 超量的条目不再生效，但**必须**继续被检查一遍：
                // 不能因为一条写太长就把后面的小条目也一起挤掉（用户会以为"我明明只有这一条没生效"）
                omitted += directive
                continue
            }
            used += cost
            taken += directive
        }
        if (taken.isEmpty()) return Result("", omitted)

        val forbidden = taken.filter { it.polarity == DirectivePolarity.MUST_NOT }
        val desired = taken.filter { it.polarity == DirectivePolarity.SHOULD }
        val block = buildString {
            appendLine("这位用户对你的具体要求（优先于一般语气设定）：")
            if (forbidden.isNotEmpty()) {
                appendLine("绝对不要：")
                forbidden.forEach { appendLine("- ${it.text.trim()}") }
            }
            if (desired.isNotEmpty()) {
                appendLine("尽量做到：")
                desired.forEach { appendLine("- ${it.text.trim()}") }
            }
            if (forbidden.isNotEmpty() && desired.isNotEmpty()) {
                appendLine("两类要求冲突时，以「绝对不要」为准。")
            }
        }.trimEnd()
        return Result(block, omitted)
    }
}
