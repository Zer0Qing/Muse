package io.zer0.muse.data.plugin.market

/**
 * v2.0.1: 插件市场工具的能力边界。
 *
 * 工具层（`plugin_market_search` / `plugin_market_install`）只依赖本接口，
 * 不接触市场内部的下载/验签/安装实现（实现是 internal 的
 * [PluginMarketToolService]），避免把内部组件类型扩散到工具注册器与 Koin 之外。
 */
interface PluginMarketToolGateway {

    /** 检索插件目录；返回给模型阅读的文本清单。 */
    suspend fun search(query: String): String

    /**
     * 下载并安装指定插件。
     *
     * @param entryId 目录条目 id
     * @param trustPublisher 用户是否已明确确认信任该插件发行者（首次安装未受信任
     *   发行者的插件时为 false，工具会返回待确认信息而不落盘）
     */
    suspend fun install(entryId: String, trustPublisher: Boolean): String
}
