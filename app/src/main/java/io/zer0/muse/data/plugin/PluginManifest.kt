package io.zer0.muse.data.plugin

import kotlinx.serialization.Serializable

/**
 * 插件清单 (openhanako plugins/ manifest 移植)。
 *
 * 每个插件通过 manifest 声明元数据、能力、激活事件和配置。
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "0.1.0",
    val description: String = "",
    /** 信任级别: full-access / sandboxed */
    val trust: String = "sandboxed",
    /** 是否在 UI 隐藏 */
    val hidden: Boolean = false,
    /** 声明的能力 (resource.read / resource.write / network / ui) */
    val capabilities: List<String> = emptyList(),
    /** 激活事件: onStartup / onCommand / onFileType */
    val activationEvents: List<String> = listOf("onStartup"),
    /** 是否已启用 */
    val enabled: Boolean = true,
) {
    companion object {
        val BUILT_IN: List<PluginManifest> = listOf(
            PluginManifest(
                id = "image-gen",
                name = "图片生成",
                description = "支持 OpenAI DALL-E / Gemini Imagen 图片生成",
                trust = "full-access",
                capabilities = listOf("network", "resource.write"),
            ),
            PluginManifest(
                id = "beautify",
                name = "审美增强",
                description = "Markdown 渲染美化、封面风格说明",
                capabilities = listOf("resource.read"),
            ),
            PluginManifest(
                id = "media",
                name = "媒体处理",
                description = "音频/视频/文档解析与处理",
                capabilities = listOf("resource.read", "resource.write"),
            ),
            PluginManifest(
                id = "mcp-bridge",
                name = "MCP 桥接",
                description = "Model Context Protocol 外部连接器",
                trust = "full-access",
                capabilities = listOf("network"),
                activationEvents = listOf("onStartup"),
            ),
            PluginManifest(
                id = "office",
                name = "办公工具",
                description = "PDF/文档/表格处理",
                capabilities = listOf("resource.read", "resource.write"),
            ),
        )
    }
}
