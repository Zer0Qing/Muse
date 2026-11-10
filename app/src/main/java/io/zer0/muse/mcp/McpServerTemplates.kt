package io.zer0.muse.mcp

import androidx.annotation.StringRes
import io.zer0.muse.R

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
    /** STDIO 模板的默认命令（transportType == STDIO 时使用）。 */
    val commandPlaceholder: String = "",
    @StringRes val displayNameResId: Int? = null,
    @StringRes val summaryResId: Int? = null,
    @StringRes val urlPlaceholderResId: Int? = null,
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
        displayNameResId = R.string.audit_mcp_custom_name,
        summary = "手动填写 endpoint、传输方式和授权信息",
        summaryResId = R.string.audit_mcp_custom_summary,
        transportType = McpTransportType.STREAMABLE_HTTP,
        urlPlaceholder = "https://server.example.com/mcp",
    )

    val all: List<McpServerTemplate> = listOf(
        // P1-A: 本地 MCP（内置 Node 运行时，stdio 传输；首次运行需联网下载依赖包）
        McpServerTemplate(
            id = "local_filesystem",
            displayName = "本地文件系统",
            displayNameResId = R.string.audit_mcp_local_filesystem_name,
            summary = "本地 MCP：让 AI 读写手机上的文件夹（内置运行时）",
            summaryResId = R.string.audit_mcp_local_filesystem_summary,
            transportType = McpTransportType.STDIO,
            urlPlaceholder = "",
            commandPlaceholder = "npx -y @modelcontextprotocol/server-filesystem /sdcard/Documents",
        ),
        McpServerTemplate(
            id = "local_memory",
            displayName = "记忆知识图",
            displayNameResId = R.string.audit_mcp_local_memory_name,
            summary = "本地 MCP：知识图谱式长期记忆（内置运行时）",
            summaryResId = R.string.audit_mcp_local_memory_summary,
            transportType = McpTransportType.STDIO,
            urlPlaceholder = "",
            commandPlaceholder = "npx -y @modelcontextprotocol/server-memory",
        ),
        McpServerTemplate(
            id = "local_sequential_thinking",
            displayName = "顺序思考",
            displayNameResId = R.string.audit_mcp_local_sequential_thinking_name,
            summary = "本地 MCP：分步推理辅助（内置运行时）",
            summaryResId = R.string.audit_mcp_local_sequential_thinking_summary,
            transportType = McpTransportType.STDIO,
            urlPlaceholder = "",
            commandPlaceholder = "npx -y @modelcontextprotocol/server-sequential-thinking",
        ),
        McpServerTemplate(
            id = "feishu_remote",
            displayName = "飞书知识库",
            displayNameResId = R.string.audit_mcp_feishu_remote_name,
            summary = "远程 MCP：搜索和读取飞书云文档",
            summaryResId = R.string.audit_mcp_feishu_remote_summary,
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "粘贴飞书远程 MCP 服务地址",
            urlPlaceholderResId = R.string.audit_mcp_feishu_remote_url_hint,
            // 默认只读,需要写入记忆时由用户主动在高级字段追加 update/create 工具。
            defaultHeaders = mapOf(
                "X-Lark-MCP-Allowed-Tools" to
                    "search-doc,fetch-doc,list-docs,fetch-file,get-comments",
            ),
        ),
        McpServerTemplate(
            id = "feishu_cli_bridge",
            displayName = "飞书 CLI 桥接",
            displayNameResId = R.string.audit_mcp_feishu_cli_bridge_name,
            summary = "本地 CLI 通过桌面桥接转成 HTTP/SSE",
            summaryResId = R.string.audit_mcp_feishu_cli_bridge_summary,
            transportType = McpTransportType.SSE,
            urlPlaceholder = "http://电脑地址:端口/sse",
            urlPlaceholderResId = R.string.audit_mcp_feishu_cli_bridge_url_hint,
        ),
        McpServerTemplate(
            id = "dingtalk_knowledge",
            displayName = "钉钉知识库",
            displayNameResId = R.string.audit_mcp_dingtalk_knowledge_name,
            summary = "远程 MCP 或自建桥接：读取钉钉文档和知识库",
            summaryResId = R.string.audit_mcp_dingtalk_knowledge_summary,
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "粘贴钉钉 MCP/桥接服务地址",
            urlPlaceholderResId = R.string.audit_mcp_dingtalk_knowledge_url_hint,
        ),
        McpServerTemplate(
            id = "wecom_knowledge",
            displayName = "企业微信知识库",
            displayNameResId = R.string.audit_mcp_wecom_knowledge_name,
            summary = "自建桥接：连接企业微信文档、群机器人或应用接口",
            summaryResId = R.string.audit_mcp_wecom_knowledge_summary,
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "粘贴企业微信 MCP 桥接地址",
            urlPlaceholderResId = R.string.audit_mcp_wecom_knowledge_url_hint,
        ),
        McpServerTemplate(
            id = "yuque_knowledge",
            displayName = "语雀知识库",
            displayNameResId = R.string.audit_mcp_yuque_knowledge_name,
            summary = "自建桥接：用语雀开放接口封装搜索和读取工具",
            summaryResId = R.string.audit_mcp_yuque_knowledge_summary,
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "粘贴语雀 MCP 桥接地址",
            urlPlaceholderResId = R.string.audit_mcp_yuque_knowledge_url_hint,
        ),
        McpServerTemplate(
            id = "tencent_docs",
            displayName = "腾讯文档",
            displayNameResId = R.string.audit_mcp_tencent_docs_name,
            summary = "自建桥接：把腾讯文档检索接口暴露为 MCP",
            summaryResId = R.string.audit_mcp_tencent_docs_summary,
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "粘贴腾讯文档 MCP 桥接地址",
            urlPlaceholderResId = R.string.audit_mcp_tencent_docs_url_hint,
        ),
    )

    /** 按 id 查找模板,找不到时返回自定义模板。 */
    fun find(id: String): McpServerTemplate = all.firstOrNull { it.id == id } ?: custom
}
