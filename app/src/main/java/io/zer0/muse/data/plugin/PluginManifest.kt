package io.zer0.muse.data.plugin

import io.zer0.muse.tools.script.ToolDeclaration
import kotlinx.serialization.Serializable

/**
 * 插件清单 (参考开源项目 plugins/ manifest 移植 + B6-01 外部插件包扩展)。
 *
 * 每个插件通过 manifest 声明元数据、能力、激活事件、入口文件和工具列表。
 * 外部 `.muse-plugin` / ZIP 包必须包含本清单；`PluginManifest.BUILT_IN` 仅作内置能力展示。
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "0.1.0",
    val description: String = "",
    /** 作者/来源。 */
    val author: String = "",
    /** 最低兼容 App 版本(语义化版本,当前不做强制阻断,保留字段供未来校验)。 */
    val minAppVersion: String = "1.0.0",
    /** JS 入口文件名(相对包根目录,默认 main.js)。 */
    val entry: String = "main.js",
    /** 插件类型: tool / ui-skin / provider。 */
    val kind: String = "tool",
    /** 信任级别: full-access / sandboxed。 */
    val trust: String = "sandboxed",
    /** 是否在 UI 隐藏。 */
    val hidden: Boolean = false,
    /** 声明的能力 (resource.read / resource.write / network / ui / ui.mood)。 */
    val capabilities: List<String> = emptyList(),
    /** 声明的权限(与 capabilities 对齐,额外用于恶意清单校验)。 */
    val permissions: List<String> = emptyList(),
    /** 激活事件: onStartup / onCommand / onFileType。 */
    val activationEvents: List<String> = listOf("onStartup"),
    /** 是否已启用。 */
    val enabled: Boolean = true,
    /** 插件暴露的工具列表(LLM 可调用,注册时加 pluginId 前缀)。 */
    val tools: List<ToolDeclaration> = emptyList(),
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
