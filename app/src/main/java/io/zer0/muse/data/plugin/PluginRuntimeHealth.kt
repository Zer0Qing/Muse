package io.zer0.muse.data.plugin

import io.zer0.common.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * v2.6.23(PL1): 插件运行期健康度 —— 按插件熔断,坏插件不再拖垮宿主交互。
 *
 * 背景:插件函数每次失败都要完整跑一遍引擎(WebView 档还有桥接与超时等待),
 * 坏插件在被停用或修复前会被反复触发(工具调用、Hook、启动激活三条路径),
 * 用户看到的只是"点了没反应"。本类把失败显式化:连续失败后短路一段时间,
 * 返回可读原因而不是继续空跑。
 *
 * 语义:
 *  - 连续 [FAILURE_THRESHOLD] 次失败(任何引擎层 Error,含超时) → 熔断 [baseCooldownMs],
 *    之后每多一次失败翻倍退避,封顶 [MAX_COOLDOWN_MS];
 *  - 冷却到期后**半开**:放一条请求去真实执行,成功即清零,失败重新计时;
 *  - 成功(哪怕插件业务返回错误文本)记为引擎可用,直接关闭熔断;
 *  - 只影响该插件,不影响其它插件与宿主。
 *
 * 进程内状态(重启即清零):熔断是止损,不是封禁 —— 封禁走权限与 KillSwitch,
 * 那是用户显式决策,不该由运行期统计越权。
 */
class PluginRuntimeHealth(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** 单个插件的熔断窗口。 */
    data class PluginState(
        val consecutiveFailures: Int = 0,
        /** 冷却截止时刻;0 = 正常。 */
        val openUntilMs: Long = 0L,
        /** 半开标记:冷却到期后放一条真实请求去探测。 */
        val probing: Boolean = false,
    )

    private val states = ConcurrentHashMap<String, PluginState>()

    /** 该插件此刻是否被熔断;返回给用户/日志的可读原因,null = 放行。 */
    fun blockReason(pluginId: String): String? {
        val state = states[pluginId] ?: return null
        val now = nowMillis()
        if (state.openUntilMs <= 0L) return null
        if (now >= state.openUntilMs) {
            // 到期转半开:放行一条真实执行作为探测,不再继续短路
            states[pluginId] = state.copy(probing = true)
            return null
        }
        val remainSeconds = ((state.openUntilMs - now + 999) / 1000).toInt()
        return "该插件连续 $FAILURE_THRESHOLD 次执行失败,已临时隔离 $remainSeconds 秒" +
            "（到期后会自动再试一次;可在插件页停用或反馈作者）"
    }

    fun recordSuccess(pluginId: String) {
        states.remove(pluginId)
    }

    fun recordFailure(pluginId: String) {
        val now = nowMillis()
        val previous = states[pluginId]
        val failures = (previous?.consecutiveFailures ?: 0) + 1
        if (failures < FAILURE_THRESHOLD) {
            states[pluginId] = PluginState(consecutiveFailures = failures)
            return
        }
        val exponent = (failures - FAILURE_THRESHOLD).coerceAtMost(MAX_COOLDOWN_EXPONENT)
        val cooldown = minOf(baseCooldownMs shl exponent, MAX_COOLDOWN_MS)
        states[pluginId] = PluginState(
            consecutiveFailures = failures,
            openUntilMs = now + cooldown,
            probing = false,
        )
        Logger.w(TAG, "插件熔断: $pluginId 连续 $failures 次失败,隔离 ${cooldown / 1000}s")
    }

    /** 半开探测后仍失败:重新开闸(退避继续翻倍)。 */
    fun markProbeFailed(pluginId: String) {
        recordFailure(pluginId)
    }

    /** 仅供测试与 UI 展示当前窗口。 */
    fun stateOf(pluginId: String): PluginState? = states[pluginId]

    fun clear(pluginId: String) {
        states.remove(pluginId)
    }

    companion object {
        private const val TAG = "PluginRuntimeHealth"

        /** 连续失败次数阈值。 */
        const val FAILURE_THRESHOLD = 3

        /** 首次熔断冷却。 */
        internal const val baseCooldownMs = 60_000L

        /** 退避封顶。 */
        internal const val MAX_COOLDOWN_MS = 5 * 60_000L

        private const val MAX_COOLDOWN_EXPONENT = 3

        /** 全宿主共享单实例:三处调用点(工具/Hook/激活)必须看到同一份账。 */
        val shared: PluginRuntimeHealth by lazy { PluginRuntimeHealth() }
    }
}
