package io.zer0.muse.data.plugin.market

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v2.0.1: 目录信任根的进程内缓存（Koin 单例）。
 *
 * 插件管理页（UI）与后台工具（plugin_market_search / plugin_market_install）必须
 * 共享同一份验签信任根：[PluginCatalogRepository] 的 `trustRootKeys` 是同步 lambda，
 * 而信任根读取（DataStore）是挂起操作，因此用本缓存做桥。
 *
 * 刷新时机：进入插件管理页、保存/移除信任根、工具调用前（[reload]），低频调用。
 */
class PluginMarketTrustRoots(
    /** 信任根设置读写入口；与市场其他组件共享同一 DataStore 封装。 */
    val settings: PluginMarketSettings,
) {
    private val _keys = MutableStateFlow(PluginMarketDefaults.catalogRootKeys)

    /** 当前生效信任根（内置官方根 + 用户追加项）；UI 可订阅该状态刷新界面。 */
    val keys: StateFlow<Map<String, String>> = _keys.asStateFlow()

    /** 同步读取当前信任根（供 [PluginCatalogRepository] 的同步 lambda 使用）。 */
    fun current(): Map<String, String> = _keys.value

    /** 从 DataStore 重新加载信任根（挂起；低频调用）。 */
    suspend fun reload() {
        _keys.value = settings.catalogRootKeys()
    }
}
