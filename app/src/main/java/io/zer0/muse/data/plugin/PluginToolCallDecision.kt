package io.zer0.muse.data.plugin

/**
 * 解析插件对"这次工具调用能不能过"的回答（插件计划 P3b 决策型钩子）。
 *
 * 约定插件返回一个 JSON 对象：
 * ```json
 * { "action": "allow" }
 * { "action": "block", "reason": "这个网站不在白名单里" }
 * ```
 *
 * 三条判据，都是**保守**方向：
 *  - 只有明确写 `block` 且带非空 `reason` 才算阻止 —— 没有原因就阻止，用户只会看到"被拦了"；
 *  - 其它任何情况（空、非 JSON、未知 action、reason 为空）一律**放行**，
 *    因为一个解析不出来的回答不该让用户的操作凭空失败；
 *  - 不做"改写参数"：能改参数的钩子等于能替用户做决定，那不是这一层该有的权力。
 */
object PluginToolCallDecision {

    /** 阻止原因的上限：它会显示给用户，太长会被截断成噪音。 */
    const val MAX_REASON_CHARS = 200

    /**
     * @return null 表示放行；非 null 是给用户看的阻止原因
     */
    fun blockReason(raw: String?): String? {
        val parsed = runCatching {
            io.zer0.common.AppJson.parseToJsonElement(raw?.trim().orEmpty())
        }.getOrNull() as? kotlinx.serialization.json.JsonObject
        val action = (parsed?.get("action") as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.trim()?.lowercase()
        val reason = (parsed?.get("reason") as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.trim()
        return if (action == "block" && !reason.isNullOrEmpty()) reason.take(MAX_REASON_CHARS) else null
    }
}
