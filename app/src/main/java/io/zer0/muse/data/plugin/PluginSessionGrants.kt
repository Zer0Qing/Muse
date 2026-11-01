package io.zer0.muse.data.plugin

/**
 * 一次性 / 会话性授权表：**只活在当前进程**，重启即失效。
 *
 * 与 [PluginPermissionStore]（落盘的 always 账本）分开，是因为两者生命周期完全不同：
 * 混在一张表里就会有人不小心把 once 写进磁盘，变成"临时允许"实际永久允许。
 */
class PluginSessionGrants {

    private val tiers = mutableMapOf<String, PluginPermissionTier>()
    private val onceRemaining = mutableMapOf<String, Int>()

    /** 授予或撤销一个会话档；[allowed] 为 false 等于收回。 */
    fun put(pluginId: String, capability: String, tier: PluginPermissionTier, allowed: Boolean) {
        val key = key(pluginId, capability)
        tiers.remove(key)
        onceRemaining.remove(key)
        if (!allowed) return
        tiers[key] = tier
        if (tier == PluginPermissionTier.ONCE) onceRemaining[key] = ONCE_GRANTS
    }

    fun clear(pluginId: String) {
        val prefix = "$pluginId|"
        tiers.keys.filter { it.startsWith(prefix) }.forEach { tiers.remove(it) }
        onceRemaining.keys.filter { it.startsWith(prefix) }.forEach { onceRemaining.remove(it) }
    }

    /** 仍有效的会话能力；对 ONCE 会**消费一次**。 */
    fun take(pluginId: String, capability: String): Boolean {
        val key = key(pluginId, capability)
        return when (tiers[key]) {
            null -> false
            PluginPermissionTier.ONCE -> {
                val remaining = onceRemaining.getOrDefault(key, 0)
                if (remaining <= 0) false else { onceRemaining[key] = remaining - 1; true }
            }
            else -> true
        }
    }

    /** 只读判断（不消费 ONCE 次数），供"当前可用能力集合"这类列举场景使用。 */
    fun liveCapabilities(pluginId: String): Set<String> {
        val prefix = "$pluginId|"
        return tiers.entries.filter { (key, _) -> key.startsWith(prefix) }
            .map { (key, tier) ->
                val remaining = onceRemaining.getOrDefault(key, 0)
                key.substringAfter('|') to (tier != PluginPermissionTier.ONCE || remaining > 0)
            }
            .filter { it.second }
            .map { it.first }
            .toSet()
    }

    private companion object {
        const val ONCE_GRANTS = 1
        fun key(pluginId: String, capability: String) = "$pluginId|$capability"
    }
}
