package io.zer0.muse.ui.settings

import io.zer0.muse.web.AUTO_CHAIN_PROVIDER_KEYS

internal object WebSearchOrderState {

    /** 全部可排序键：自定义引擎在前（与链首一致），其后是内置顺序表。 */
    fun allKeys(customKeys: List<String>): List<String> = customKeys + AUTO_CHAIN_PROVIDER_KEYS

    /**
     * 列表当前该怎么排。
     *
     * 已保存的顺序优先，但只保留**现在还认得的键**：引擎被删、自定义引擎改名之后，
     * 陈旧条目要静默丢掉而不是在界面上留一行幽灵，也不能因为一个失效键报错。
     * 没被保存顺序覆盖到的键，保持内置相对顺序追加在后面（新功能不该因为"用户没排过它"就消失）。
     */
    fun displayedOrder(customKeys: List<String>, saved: List<String>): List<String> {
        val all = allKeys(customKeys)
        if (saved.isEmpty()) return all
        val picked = saved.mapNotNull { key -> all.firstOrNull { it == key } }
        val pickedSet = picked.toSet()
        return picked + all.filterNot { pickedSet.contains(it) }
    }

    /** 上下移动：越界返回原表（首行不能再上移、末行不能再下移），不抛异常。 */
    fun moved(order: List<String>, index: Int, delta: Int): List<String> {
        if (index !in order.indices) return order
        val target = index + delta
        if (target !in order.indices) return order
        return order.toMutableList().apply {
            this[index] = this[target].also { this[target] = this[index] }
        }
    }

    /** 列表里显示的引擎名（`custom:` 前缀只是键，不该出现在界面上）。 */
    fun displayName(key: String): String = key.removePrefix("custom:")

    /**
     * 「恢复默认顺序」= 存一张空表。
     *
     * 空表在链侧的含义就是"沿用内置顺序"（见 `WebSearchConfig.providerOrder` 与
     * `orderWebSearchChain`），所以重置不需要另造一个哨兵值，也不会与
     * "用户真的想排成某个顺序"混淆。
     */
    val defaultOrder: List<String> = emptyList()
}
