package io.zer0.muse.tools

import io.zer0.muse.data.plugin.market.PluginMarketToolGateway

/**
 * v2.0.1: 插件市场工具注册器 — 让助手直接检索插件市场并按审批安装插件。
 *
 *  - [TOOL_MARKET_SEARCH] 只读检索（SAFE）：列出市场插件与安装/信任状态；
 *  - [TOOL_MARKET_INSTALL] 下载并安装（HIGH，必须走会话审批）：发行者未受信任时
 *    先返回待确认信息，用户确认后带 trust_publisher=true 重试。
 *
 * 安全：安装复用插件管理页同一套审查链路（目录验签、发行者签名、内容摘要、
 * 安装协调器），不提供绕过用户审批或信任根的任何路径。见
 * [io.zer0.muse.data.plugin.market.PluginMarketToolGateway]。
 */
class PluginMarketToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val marketService: PluginMarketToolGateway,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_MARKET_SEARCH,
                description = "搜索 Muse 插件市场中的插件（外部工具/声明式皮肤包）。返回插件 id、名称、版本、说明、" +
                    "发行者与安装/信任状态。找到合适的插件后把关键信息展示给用户，再用 plugin_market_install 安装" +
                    "（安装会弹审批卡）。",
                parameters = mapOf("query" to "可选,关键词（匹配 id/名称/说明）；留空列出全部"),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            marketService.search(args["query"].orEmpty())
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_MARKET_INSTALL,
                description = "安装插件市场中的插件（plugin_id 用 plugin_market_search 获取）。执行会下载安装包、" +
                    "校验发行者签名与内容摘要后安装，安装前会弹用户的审批卡片。" +
                    "发行者尚未受信任时，本工具会返回待确认信息：请先把信息展示给用户，用户确认后带 " +
                    "trust_publisher=true 再次调用。",
                parameters = mapOf(
                    "plugin_id" to "必填,插件 id（见 plugin_market_search）",
                    "trust_publisher" to "可选,true/false：用户已确认信任该插件发行者时传 true（首次安装未受信任发行者的插件需要）",
                ),
                required = setOf("plugin_id"),
                category = "built-in",
                parameterTypes = mapOf("trust_publisher" to "boolean"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val pluginId = args["plugin_id"].orEmpty()
            if (pluginId.isBlank()) return@register "安装失败：缺少 plugin_id。"
            val trustPublisher = args["trust_publisher"]?.equals("true", ignoreCase = true) == true
            marketService.install(pluginId, trustPublisher)
        }
    }

    companion object {
        /** 检索插件市场（只读）。 */
        const val TOOL_MARKET_SEARCH = "plugin_market_search"

        /** 安装插件（HIGH，走审批）。 */
        const val TOOL_MARKET_INSTALL = "plugin_market_install"
    }
}
