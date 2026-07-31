package io.zer0.muse.tools.script

import io.zer0.common.Logger

/**
 * METADATA 注释块解析器 (P3-1)。
 *
 * 支持在 JS 文件顶部用 JSDoc 风格注释声明 Skill 元数据，使单文件 JS 也能作为
 * Skill 包被加载（无需打包成 .skillpkg ZIP）。
 *
 * 格式示例（JS 文件顶部用块注释包裹以下标签）：
 * ```
 * @skillpkg
 * @id my_calculator
 * @name 我的计算器
 * @version 1.0.0
 * @description 高级计算器工具
 * @entry main.js
 * @tool calculate 执行数学计算 ["expr"]
 * ```
 * 后跟 JS 函数实现，如 `function calculate(expr) { return eval(expr); }`。
 *
 * 支持的标签：
 *  - `@skillpkg`     标记此注释块为 Skill 元数据（必需，否则不解析）
 *  - `@id`           包 id（slug 格式）
 *  - `@name`         显示名
 *  - `@version`      版本号
 *  - `@author`       作者
 *  - `@description`  包描述
 *  - `@entry`        JS 入口文件名（单文件场景默认为当前文件名）
 *  - `@tool`         工具声明，格式：`@tool <name> <description> [<required_params_json>]`
 *
 * 解析失败（无 @skillpkg 标签）时返回 null。
 */
object MetadataParser {

    private const val TAG = "MetadataParser"

    /** METADATA 块必须包含 @skillpkg 标签才会被解析。 */
    private const val SKILLPKG_MARKER = "@skillpkg"

    /**
     * 从 JS 源码中解析 METADATA 注释块。
     *
     * @param jsSource JS 源码
     * @param defaultEntry 默认入口文件名（单文件场景，无 @entry 标签时使用）
     * @return 解析成功返回 [SkillPackageManifest]，否则 null
     */
    fun parse(jsSource: String, defaultEntry: String = "main.js"): SkillPackageManifest? {
        // 提取第一个 /** ... */ 块
        val blockComment = extractFirstBlockComment(jsSource) ?: return null
        // 必须包含 @skillpkg 标记
        if (!blockComment.contains(SKILLPKG_MARKER)) {
            return null
        }

        return runCatching {
            parseBlockComment(blockComment, defaultEntry)
        }.onFailure { e ->
            Logger.w(TAG, "METADATA 解析失败: ${e.message}")
        }.getOrNull()
    }

    /**
     * 提取第一个块注释（支持 JSDoc 风格与普通块注释起始序列）。
     */
    private fun extractFirstBlockComment(source: String): String? {
        val start = source.indexOf("/*")
        if (start < 0) return null
        val end = source.indexOf("*/", startIndex = start + 2)
        if (end < 0) return null
        return source.substring(start, end + 2)
    }

    /**
     * 解析块注释中的 @标签。
     */
    private fun parseBlockComment(comment: String, defaultEntry: String): SkillPackageManifest {
        val lines = comment.lines()
        var id: String? = null
        var name: String? = null
        var version = "1.0.0"
        var author = ""
        var description = ""
        var entry = defaultEntry
        val tools = mutableListOf<ToolDeclaration>()

        for (line in lines) {
            val trimmed = line.trim().removePrefix("*").trim()
            when {
                trimmed.startsWith("@id ") -> id = trimmed.removePrefix("@id ").trim()
                trimmed.startsWith("@name ") -> name = trimmed.removePrefix("@name ").trim()
                trimmed.startsWith("@version ") -> version = trimmed.removePrefix("@version ").trim()
                trimmed.startsWith("@author ") -> author = trimmed.removePrefix("@author ").trim()
                trimmed.startsWith("@description ") -> description = trimmed.removePrefix("@description ").trim()
                trimmed.startsWith("@entry ") -> entry = trimmed.removePrefix("@entry ").trim()
                trimmed.startsWith("@tool ") -> parseToolLine(trimmed.removePrefix("@tool "))?.let { tools.add(it) }
            }
        }

        requireNotNull(id) { "METADATA 缺少 @id 标签" }
        requireNotNull(name) { "METADATA 缺少 @name 标签" }
        require(tools.isNotEmpty()) { "METADATA 至少需要声明一个 @tool" }

        return SkillPackageManifest(
            id = id,
            name = name,
            version = version,
            author = author,
            description = description,
            entry = entry,
            tools = tools,
        )
    }

    /**
     * 解析 @tool 行。
     * 格式: `<name> <description> [<required_params_json>]`
     * 示例: `calculate 执行数学计算 ["expr"]`
     *       `weather_query 查询天气 ["city", "date"]`
     *       `simple_tool 简单工具`  (无必填参数)
     */
    private fun parseToolLine(line: String): ToolDeclaration? {
        // 尝试匹配三段式: name description [requiredJson]
        val tripleMatch = Regex("""^(\S+)\s+(.+?)\s+(\[[^\]]*\])\s*$""").find(line)
        if (tripleMatch != null) {
            val (name, desc, requiredJson) = tripleMatch.destructured
            return ToolDeclaration(
                name = name,
                description = desc,
                requiredJson = requiredJson,
                functionName = name,
            )
        }

        // 两段式: name description (无必填参数)
        val doubleMatch = Regex("""^(\S+)\s+(.+)$""").find(line)
        if (doubleMatch != null) {
            val (name, desc) = doubleMatch.destructured
            return ToolDeclaration(
                name = name,
                description = desc,
                requiredJson = "[]",
                functionName = name,
            )
        }

        Logger.w(TAG, "无法解析 @tool 行: $line")
        return null
    }
}
