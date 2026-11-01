package io.zer0.muse.data.plugin

import io.zer0.common.Logger

/**
 * 能力判定审计环形缓冲（供权限矩阵页展示，P0-3）。
 *
 * 从 [PluginCapabilityGateway] 拆出来是因为网关本身已经背着映射、账本、会话表三件事，
 * 再挤进日志缓冲就会超 detekt 的函数数上限 —— 拆开后各自单一职责，也更好测。
 */
class PluginCapabilityAudit(private val now: () -> Long = System::currentTimeMillis) {

    /** 一次能力判定：动作、对应能力、结果。 */
    data class Event(
        val timestamp: Long,
        val pluginId: String,
        val action: String,
        val capability: String,
        val allowed: Boolean,
    )

    private val events = mutableListOf<Event>()

    fun record(pluginId: String, action: String, capability: String, allowed: Boolean) {
        Logger.d(TAG, "plugin=$pluginId action=$action capability=$capability allowed=$allowed")
        synchronized(events) {
            events += Event(now(), pluginId, action, capability, allowed)
            if (events.size > MAX_EVENTS) events.removeAt(0)
        }
    }

    fun recent(limit: Int = 50): List<Event> = synchronized(events) { events.takeLast(limit).toList() }

    private companion object {
        const val TAG = "PluginCapability"
        const val MAX_EVENTS = 200
    }
}
