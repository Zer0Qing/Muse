package io.zer0.muse.tools.script

import kotlinx.serialization.Serializable

/**
 * 工具声明 — 一个 `.muse-plugin` 插件包可暴露多个工具。
 *
 * 生产链路：由 [io.zer0.muse.data.plugin.PluginPackageLoader] 解析 `.muse-plugin`
 * 包的 manifest.json 得到，插件安装后注册到 ToolRegistry（工具名加 pluginId 前缀）。
 *
 * 每个工具对应一个可调用入口，通过 [functionName] 路由到 JS 入口文件中定义的
 * 同名函数（经 [WebViewSkillEngine] 执行）。
 */
@Serializable
data class ToolDeclaration(
    /** 工具名（slug 格式，LLM 调用时使用）。 */
    val name: String,
    /** 工具描述（LLM 据此决定是否调用）。 */
    val description: String,
    /** 参数 JSON Schema（OpenAI 兼容格式）。 */
    val parametersJson: String = "{}",
    /** 必填参数名数组（JSON 字符串，如 `["expr"]`）。 */
    val requiredJson: String = "[]",
    /** JS 入口文件中定义的函数名（如 "calculate"）。 */
    val functionName: String,
)
