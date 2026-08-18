package io.zer0.muse.mcp

/**
 * MCP Server 配置模板。
 *
 * 模板只提供连接方式和用途提示,不内置第三方服务地址、账号或令牌。
 * 这样既能减少首次配置成本,也不会把某个厂商的 endpoint 当成永久契约。
 */
data class McpServerTemplate(
    val id: String,
    val displayName: String,
    val summary: String,
    val transportType: McpTransportType,
    val urlPlaceholder: String,
    val defaultHeaders: Map<String, String> = emptyMap(),
)

/**
 * 国内常用办公/知识库软件的 MCP 配置模板。
 *
 * 其中“远程 MCP”可直接填写厂商提供的 HTTP endpoint；
 * “桥接”表示需要在电脑或服务器上运行一个 MCP adapter,再把它暴露为 SSE/Streamable HTTP。
 */
object McpServerTemplates {
    const val CUSTOM_ID = "custom"

    val custom = McpServerTemplate(
        id = CUSTOM_ID,
        displayName = "自定义 MCP Server",
        summary = "手动填写 endpoint、传输方式和授权信息",
        transportType = McpTransportType.STREAMABLE_HTTP,
        urlPlaceholder = "https://server.example.com/mcp",
    )

    val all: List<McpServerTemplate> = listOf(
        McpServerTemplate(
            id = "feishu_remote",
            displayName = "飞书知识库",
            summary = "远程 MCP：搜索和读取飞书云文档",
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "粘贴飞书远程 MCP 服务地址",
            // 默认只读,需要写入记忆时由用户主动在高级字段追加 update/create 工具。
            defaultHeaders = mapOf(
                "X-Lark-MCP-Allowed-Tools" to
                    "search-doc,fetch-doc,list-docs,fetch-file,get-comments",
            ),
        ),
        McpServerTemplate(
            id = "feishu_cli_bridge",
            displayName = "飞书 CLI 桥接",
            summary = "本地 CLI 通过桌面桥接转成 HTTP/SSE",
            transportType = McpTransportType.SSE,
            urlPlaceholder = "http://电脑地址:端口/sse",
        ),
        McpServerTemplate(
            id = "dingtalk_knowledge",
            displayName = "钉钉知识库",
            summary = "远程 MCP 或自建桥接：读取钉钉文档和知识库",
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "粘贴钉钉 MCP/桥接服务地址",
        ),
        McpServerTemplate(
            id = "wecom_knowledge",
            displayName = "企业微信知识库",
            summary = "自建桥接：连接企业微信文档、群机器人或应用接口",
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "粘贴企业微信 MCP 桥接地址",
        ),
        McpServerTemplate(
            id = "yuque_knowledge",
            displayName = "语雀知识库",
            summary = "自建桥接：用语雀开放接口封装搜索和读取工具",
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "粘贴语雀 MCP 桥接地址",
        ),
        McpServerTemplate(
            id = "tencent_docs",
            displayName = "腾讯文档",
            summary = "自建桥接：把腾讯文档检索接口暴露为 MCP",
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "粘贴腾讯文档 MCP 桥接地址",
        ),
    )

    /** 按 id 查找模板,找不到时返回自定义模板。 */
    fun find(id: String): McpServerTemplate =
        all.firstOrNull { it.id == id } ?: custom
}
