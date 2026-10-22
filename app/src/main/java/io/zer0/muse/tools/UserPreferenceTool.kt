package io.zer0.muse.tools

import io.zer0.muse.data.PreferenceStore

/**
 * 用户偏好工具 —— 把「以后别…」变成硬约束。
 *
 * ## 为什么需要它
 *
 * 助手自审第 20 条：「用户偏好学习：比如你习惯我别用空泛鼓励、别列三点总结，
 * 这些能沉淀成硬约束而不是靠我临场判断。」
 *
 * 现状：用户说「以后别用鼓励的话」，模型这一轮记住了，下一轮就忘 ——
 * 因为没有任何地方把这句话存下来。这个工具补的就是这一步。
 *
 * ## 何时调用（写进 description，让模型自己判断）
 *
 * 只在用户**明确表达长期要求**时调用：「以后都…」「别再…」「我不喜欢…」。
 * 一次性的要求（「这次简短点」）不该存 —— 存了会污染后续所有对话。
 */
object UserPreferenceTool {

    const val TOOL_NAME = "remember_preference"

    fun toolDef() = ToolRegistry.ToolDef(
        name = TOOL_NAME,
        description = "Save a lasting user preference so it applies to all future conversations. " +
            "Call ONLY when the user states a durable rule about how you should behave " +
            "(e.g. \"stop using generic encouragement\", \"don't end with a 3-point summary\", " +
            "\"always answer in Chinese\"). " +
            "Do NOT call for one-off requests like \"be brief this time\" — those apply to this turn only.",
        parameters =
        mapOf(
            "rule" to "Required. The preference as an imperative sentence in the user's own language, " +
                "e.g. \"回答不要用空泛的鼓励\".",
            "source" to "Optional. The user's original wording, for traceability.",
        ),
        required = setOf("rule"),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    suspend fun execute(args: Map<String, String>, store: PreferenceStore): String {
        val rule = args["rule"]?.trim().orEmpty()
        val tooLong = rule.length > MAX_RULE_LENGTH
        return when {
            rule.isEmpty() -> "Error: rule parameter is required."
            tooLong ->
                "Error: rule is too long (${rule.length} chars). Keep it under $MAX_RULE_LENGTH — " +
                    "one short imperative sentence."
            store.add(rule, args["source"]?.trim().orEmpty()) ->
                "Saved preference: \"$rule\". It will apply to all future conversations."
            else -> "Preference already saved: \"$rule\". No duplicate added."
        }
    }

    /** 单条偏好长度上限 —— 太长的「偏好」多半是需求描述，不是约束。 */
    private const val MAX_RULE_LENGTH = 200
}
