package io.zer0.muse.tools

import io.zer0.memory.ticker.MemoryConfig
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * 记忆系统参数工具。
 *
 * 此前助手能读写记忆**内容**（save_memory / search_memory / pin_memory），
 * 却改不了记忆**怎么工作**——token 预算、遗忘速度、编译阈值这些只能在设置页手拖。
 *
 * 这两个工具补上配置侧：一个看现状，一个改参数。改完的配置仍要过
 * [MemoryConfig.validate]，非法组合会被拒绝并返回原因，不会写进 DataStore。
 */
object MemoryConfigTool {

    const val NAME_GET = "memory_config_get"
    const val NAME_SET = "memory_config_set"

    fun getDef() = ToolRegistry.ToolDef(
        name = NAME_GET,
        description =
        "读取记忆系统的当前参数(token 预算 / 遗忘速度 / 编译阈值等)及其含义。" +
            "想调整记忆行为前先调它看现状,不要凭猜测改。",
        parameters = emptyMap(),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    fun setDef() = ToolRegistry.ToolDef(
        name = NAME_SET,
        description =
        "调整记忆系统参数。只改传入的字段,其余保持不变;改完会校验合法性,非法则整体拒绝。" +
            "常用场景:记忆老是忘事→调小 decay_per_day 或调大 hit_bonus;" +
            "记忆内容太长挤占上下文→调小 token_budget;" +
            "只想记住重要的事→调大 compile_threshold。",
        parameters =
        mapOf(
            "token_budget" to "可选,memory.md 注入 system prompt 的 token 预算,正整数,默认 2500。",
            "decay_per_day" to "可选,衰减系数 λ(每天),越大忘得越快。0.01≈19天归零 / 0.02=默认 / 0.04≈10天。",
            "hit_bonus" to "可选,每次被命中的加分,默认 5。调大让常被提起的记忆更难消失。",
            "base_importance" to "可选,新记忆的初始分,须 > 0,默认 10。",
            "compile_threshold" to "可选,低于此分不进 memory.md,默认 4.5。调大只保留重要记忆。",
            "forget_speed" to "可选,遗忘倍率,1.0 正常,2.0 忘得快一倍,默认 1.0。",
            "conversation_recall" to
                "可选,true/false。是否允许记忆管线回读会话完整原文(而非只看当前窗口)。",
        ),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    suspend fun get(settings: SettingsRepository): String {
        val c = settings.memoryConfigFlow.first()
        return buildString {
            appendLine("当前记忆参数:")
            appendLine("token_budget = ${c.tokenBudget}  (memory.md 注入预算,默认 2500)")
            appendLine("decay_per_day = ${c.decayPerDay}  (衰减系数,默认 0.02;越大忘得越快)")
            appendLine("hit_bonus = ${c.hitBonus}  (命中加分,默认 5)")
            appendLine("base_importance = ${c.baseImportance}  (新记忆初始分,默认 10)")
            appendLine("compile_threshold = ${c.compileThreshold}  (入 memory.md 的最低分,默认 4.5)")
            appendLine("forget_speed = ${c.forgetSpeed}  (遗忘倍率,默认 1.0)")
            append("conversation_recall = ${c.conversationRecallEnabled}  (是否回读会话原文)")
        }
    }

    /** 读一个可选参数；缺省返回 null，解析失败把原因记进 [errors]。 */
    private inline fun <T> pick(args: Map<String, String>, key: String, errors: MutableList<String>, parse: (String) -> T?): T? {
        val raw = args[key]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = parse(raw)
        if (parsed == null) errors.add("$key 取值无法解析: $raw")
        return parsed
    }

    /** 把 args 叠加到 [current] 上，返回新配置与解析错误。 */
    private fun merge(args: Map<String, String>, current: MemoryConfig): Pair<MemoryConfig, List<String>> {
        val errors = mutableListOf<String>()
        val next =
            MemoryConfig(
                tokenBudget = pick(args, "token_budget", errors) { it.toIntOrNull() } ?: current.tokenBudget,
                conversationRecallEnabled =
                pick(args, "conversation_recall", errors) { it.toBooleanStrictOrNull() }
                    ?: current.conversationRecallEnabled,
                decayPerDay = pick(args, "decay_per_day", errors) { it.toFloatOrNull() } ?: current.decayPerDay,
                hitBonus = pick(args, "hit_bonus", errors) { it.toFloatOrNull() } ?: current.hitBonus,
                baseImportance =
                pick(args, "base_importance", errors) { it.toFloatOrNull() } ?: current.baseImportance,
                compileThreshold =
                pick(args, "compile_threshold", errors) { it.toFloatOrNull() } ?: current.compileThreshold,
                forgetSpeed = pick(args, "forget_speed", errors) { it.toFloatOrNull() } ?: current.forgetSpeed,
            )
        return next to errors
    }

    /** 列出相对 [current] 发生变化的行。 */
    private fun diff(current: MemoryConfig, next: MemoryConfig): List<String> = buildList {
        if (next.tokenBudget != current.tokenBudget) {
            add("- token_budget: ${current.tokenBudget} → ${next.tokenBudget}")
        }
        if (next.decayPerDay != current.decayPerDay) {
            add("- decay_per_day: ${current.decayPerDay} → ${next.decayPerDay}")
        }
        if (next.hitBonus != current.hitBonus) {
            add("- hit_bonus: ${current.hitBonus} → ${next.hitBonus}")
        }
        if (next.baseImportance != current.baseImportance) {
            add("- base_importance: ${current.baseImportance} → ${next.baseImportance}")
        }
        if (next.compileThreshold != current.compileThreshold) {
            add("- compile_threshold: ${current.compileThreshold} → ${next.compileThreshold}")
        }
        if (next.forgetSpeed != current.forgetSpeed) {
            add("- forget_speed: ${current.forgetSpeed} → ${next.forgetSpeed}")
        }
        if (next.conversationRecallEnabled != current.conversationRecallEnabled) {
            add(
                "- conversation_recall: ${current.conversationRecallEnabled} → " +
                    "${next.conversationRecallEnabled}",
            )
        }
    }

    suspend fun set(args: Map<String, String>, settings: SettingsRepository): String {
        val current = settings.memoryConfigFlow.first()
        val (next, errors) = merge(args, current)
        val invalid = MemoryConfig.validate(next)

        return when {
            errors.isNotEmpty() -> "Error: ${errors.joinToString("; ")}"
            invalid != null -> "Error: 参数组合不合法,未保存 —— $invalid"
            next == current -> "OK: 参数与当前一致,无需改动。"
            else -> {
                settings.saveMemoryConfig(next)
                val changes = diff(current, next)
                buildString {
                    appendLine("OK: 记忆参数已更新。")
                    changes.forEach { appendLine(it) }
                    append("参数在下一轮记忆编译时生效。")
                }
            }
        }
    }
}
