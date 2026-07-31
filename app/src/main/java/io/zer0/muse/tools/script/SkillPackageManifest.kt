package io.zer0.muse.tools.script

import kotlinx.serialization.Serializable

/**
 * .skillpkg 包清单文件 (manifest.json) 数据结构 (P3-1)。
 *
 * .skillpkg 是 ZIP 格式的 Skill 包，结构如下：
 * ```
 * my_tool.skillpkg (ZIP)
 * ├── manifest.json     (必需，包元数据 + 工具声明)
 * ├── main.js           (必需，JS 入口文件)
 * ├── README.md         (可选，文档)
 * └── lib/              (可选，辅助 JS 模块)
 *     └── helper.js
 * ```
 *
 * manifest.json 示例：
 * ```json
 * {
 *   "id": "my_calculator",
 *   "name": "我的计算器",
 *   "version": "1.0.0",
 *   "author": "user",
 *   "description": "高级计算器工具",
 *   "entry": "main.js",
 *   "tools": [
 *     {
 *       "name": "calculate",
 *       "description": "执行数学计算",
 *       "parametersJson": "{\"type\":\"object\",...}",
 *       "requiredJson": "[\"expr\"]",
 *       "functionName": "calculate"
 *     }
 *   ]
 * }
 * ```
 *
 * 参考: Operit manifest.json 设计，适配 Muse 的 SkillEntity 体系。
 */
@Serializable
data class SkillPackageManifest(
    /** 包 id（slug 格式，作为 SkillEntity.id）。 */
    val id: String,
    /** 显示名。 */
    val name: String,
    /** 版本号（语义化版本，如 "1.0.0"）。 */
    val version: String = "1.0.0",
    /** 作者。 */
    val author: String = "",
    /** 包描述。 */
    val description: String = "",
    /** JS 入口文件名（相对于包根目录，如 "main.js"）。 */
    val entry: String = "main.js",
    /** 包含的工具列表（一个包可暴露多个工具）。 */
    val tools: List<ToolDeclaration> = emptyList(),
)

/**
 * 工具声明 — 一个 .skillpkg 可暴露多个工具。
 *
 * 每个工具对应 SkillExecutor 中的一个可调用入口，通过 [functionName]
 * 路由到 JS 入口文件中定义的同名函数。
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
