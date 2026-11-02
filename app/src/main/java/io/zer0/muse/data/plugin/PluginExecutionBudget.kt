package io.zer0.muse.data.plugin

/**
 * 插件单次执行的超时预算（`runtime.long`）。
 *
 * 默认上限 10 秒：绝大多数插件动作是"查一下、算一下、写一下"，超过十秒基本说明它卡住了。
 * 但有些正当场景需要更久 —— 批量处理文件、拉一个较大的列表、跑一次本地脚本 ——
 * 这些在 10 秒里做不完，而用户装这个插件就是为了让它做完。
 *
 * 所以给出第二档：**拿到 `runtime.long` 授权**的插件可以跑到 10 分钟。
 *
 * ## 为什么"没授权时连调用方传的大超时也要夹住"
 *
 * 超时是这道闸的唯一意义。如果调用方（某个工具、某个 hook、某段以后的代码）
 * 传一个很大的 `timeoutMs` 就能绕过去，那 `runtime.long` 只是一句装饰。
 * 所以这里的方向是**收窄**：没授权就夹到默认值，授权了才允许放宽。
 */
object PluginExecutionBudget {

    /** 默认单次上限（与 [io.zer0.muse.tools.script.SkillEngine.DEFAULT_TIMEOUT_MS] 一致）。 */
    const val DEFAULT_TIMEOUT_MS = 10_000L

    /** 长任务上限：10 分钟。再久就不是"一次调用"，而是该做后台任务了。 */
    const val LONG_TASK_TIMEOUT_MS = 10 * 60 * 1000L

    /**
     * 本次调用真正使用的超时。
     *
     * @param requested 调用方希望的超时
     * @param longTaskAllowed 该插件是否拿到了 `runtime.long` 授权
     */
    fun effectiveTimeoutMs(requested: Long, longTaskAllowed: Boolean): Long {
        val wanted = requested.coerceAtLeast(1L)
        return if (longTaskAllowed) {
            // 授权后可以放宽，但仍然封顶：10 分钟是"一次调用"的上限
            wanted.coerceIn(1L, LONG_TASK_TIMEOUT_MS)
        } else {
            // 没授权就收窄到默认值 —— 调用方传多大都不作数
            wanted.coerceIn(1L, DEFAULT_TIMEOUT_MS)
        }
    }
}
