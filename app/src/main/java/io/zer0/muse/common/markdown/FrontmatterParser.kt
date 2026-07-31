package io.zer0.muse.common.markdown

/**
 * v1.0.53: Markdown frontmatter 解析器(对标 Beautify 封面工作流前置能力)。
 *
 * 支持 `---` 包裹的简单键值对(不引入 YAML 库,手写解析):
 * ```
 * ---
 * title: 我的文档
 * cover: covers/xxx.jpg
 * description: 一句话描述
 * ---
 * ```
 *
 * 解析失败时返回 null(调用方降级为无元数据渲染),不影响正文渲染。
 * 仅解析平铺键值对,不支持数组/嵌套结构(忽略该类行)。
 */
data class Frontmatter(
    /** 文档标题。 */
    val title: String?,
    /** 封面引用(相对路径或 file:// URI)。 */
    val cover: String?,
    /** 一句话描述。 */
    val description: String?,
    /** 完整键值(小写 key → 原值,供未来扩展)。 */
    val raw: Map<String, String>,
)

/**
 * frontmatter 解析器(纯函数,无状态,可单测)。
 */
object FrontmatterParser {

    private const val DELIMITER = "---"

    /**
     * 解析 frontmatter。
     *
     * @return 解析结果;无 frontmatter 或格式非法返回 null
     */
    fun parse(markdown: String): Frontmatter? {
        val body = stripBom(markdown)
        if (!body.startsWith("$DELIMITER\n") && !body.startsWith("$DELIMITER\r\n")) return null

        // 定位第一个行尾
        val firstLineEnd = body.indexOf('\n')
        if (firstLineEnd < 0) return null

        // 找第二个 --- 行(行首 --- 且行尾无其他字符)
        val rest = body.substring(firstLineEnd + 1)
        val lines = rest.split('\n')
        val kv = LinkedHashMap<String, String>()
        var closingIdx = -1

        for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trimEnd('\r')
            if (trimmed.trim() == DELIMITER) {
                closingIdx = idx
                break
            }
            // 解析 key: value
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx > 0) {
                val key = trimmed.substring(0, colonIdx).trim().lowercase()
                var value = trimmed.substring(colonIdx + 1).trim()
                // 去掉匹配的首尾引号
                value = stripQuotes(value)
                // 空值行(如 "tags:" 数组标记)忽略,不入 raw
                if (key.isNotBlank() && value.isNotEmpty()) kv[key] = value
            }
            // 其他行(数组/嵌套/注释)忽略
        }

        // 第二个 --- 缺失 → 整体返回 null(不是部分解析)
        if (closingIdx < 0) return null

        return Frontmatter(
            title = kv["title"]?.takeIf { it.isNotBlank() },
            cover = kv["cover"]?.takeIf { it.isNotBlank() },
            description = kv["description"]?.takeIf { it.isNotBlank() },
            raw = kv,
        )
    }

    /**
     * 剥离 frontmatter 后返回正文(无 frontmatter 时原样返回)。
     */
    fun strip(markdown: String): String {
        val body = stripBom(markdown)
        if (!body.startsWith("$DELIMITER\n") && !body.startsWith("$DELIMITER\r\n")) return markdown

        val firstLineEnd = body.indexOf('\n')
        if (firstLineEnd < 0) return markdown

        val rest = body.substring(firstLineEnd + 1)
        val lines = rest.split('\n')
        for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trimEnd('\r')
            if (trimmed.trim() == DELIMITER) {
                // 返回关闭行之后的内容(保留原 BOM 前缀语义:剥离 BOM 后返回,调用方自行处理)
                return lines.drop(idx + 1).joinToString("\n").trimStart('\n')
            }
        }
        return markdown
    }

    /**
     * 设置 cover 字段并序列化回 frontmatter;无 frontmatter 时新建。
     *
     * 用于 AI 封面生成后写回文档(Phase 3)。
     *
     * @param markdown 原文档
     * @param coverPath 封面路径(建议 "covers/xxx.jpg" 相对路径)
     * @return 写回后的完整 markdown
     */
    fun withCover(markdown: String, coverPath: String): String {
        val existing = parse(markdown)
        if (existing == null) {
            // 无 frontmatter:新建,title 从正文首个 # 标题提取(无则省略)
            val body = stripBom(markdown)
            val title = body.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("# ") }
                ?.removePrefix("# ")?.trim()
            val sb = StringBuilder()
            sb.appendLine(DELIMITER)
            if (!title.isNullOrBlank()) sb.appendLine("title: \"${escapeValue(title)}\"")
            sb.appendLine("cover: $coverPath")
            sb.appendLine(DELIMITER)
            sb.append(body.trimStart('\n'))
            return sb.toString()
        }

        // 已有 frontmatter:替换/插入 cover 行
        val body = stripBom(markdown)
        val firstLineEnd = body.indexOf('\n')
        val rest = body.substring(firstLineEnd + 1)
        val lines = rest.split('\n')
        val out = mutableListOf<String>()
        out.add(DELIMITER)
        var inserted = false
        for (line in lines) {
            val trimmed = line.trimEnd('\r')
            if (trimmed.trim() == DELIMITER) {
                if (!inserted) {
                    out.add("cover: $coverPath")
                    inserted = true
                }
                out.add(trimmed)
                break
            }
            val colonIdx = trimmed.indexOf(':')
            val key = if (colonIdx > 0) trimmed.substring(0, colonIdx).trim().lowercase() else ""
            if (key == "cover") {
                out.add("cover: $coverPath")
                inserted = true
            } else {
                out.add(trimmed)
            }
        }
        if (!inserted) out.add("cover: $coverPath") // 防御:未找到关闭行
        // 保留关闭行之后的正文
        return out.joinToString("\n") + "\n" + lines.dropWhile { it.trimEnd('\r').trim() != DELIMITER }.drop(1).joinToString("\n")
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────

    private fun stripBom(text: String): String =
        if (text.startsWith('\uFEFF')) text.substring(1) else text

    /** 去掉匹配的首尾引号("xxx" / 'xxx'),不匹配则原样返回。 */
    private fun stripQuotes(value: String): String {
        if (value.length >= 2) {
            val first = value.first()
            val last = value.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length - 1)
            }
        }
        return value
    }

    /** 值中含引号时转义(写回时用)。 */
    private fun escapeValue(value: String): String = value.replace("\"", "\\\"")
}
