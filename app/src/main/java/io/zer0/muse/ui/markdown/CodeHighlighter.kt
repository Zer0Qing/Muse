package io.zer0.muse.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import java.util.concurrent.ConcurrentHashMap

/**
 * 轻量代码高亮(自实现,不引入第三方高亮库)。
 *
 * 支持语言: kotlin / java / python / js / ts / go / rust / shell / sql / json / xml(默认)
 * 高亮维度:
 *  - 关键字(keyword): 粗体 + 蓝紫
 *  - 字符串(string): 绿
 *  - 注释(comment): 灰斜体
 *  - 数字(number): 橙
 *  - 注解/装饰器(annotation): 黄
 *
 * 策略: 按语言选关键字集,正则 alternation 一次扫描,按匹配类型着色。
 * 不做完整语法树分析,覆盖常见高亮场景即可。
 *
 * Phase 12: 配色从硬编码改为 @Composable 取 MaterialTheme.colorScheme,
 * 暗色主题自动用亮化版色,保证对比度。
 */
object CodeHighlighter {

    /** 各语言关键字集(小写)。 */
    private val KEYWORDS: Map<String, Set<String>> = mapOf(
        "kotlin" to setOf(
            "fun", "val", "var", "class", "object", "interface", "enum", "sealed", "data",
            "companion", "override", "private", "public", "protected", "internal", "open",
            "abstract", "final", "lateinit", "const", "vararg", "suspend", "inline", "reified",
            "import", "package", "return", "if", "else", "when", "for", "while", "do", "break",
            "continue", "try", "catch", "finally", "throw", "in", "is", "as", "out", "inout",
            "null", "true", "false", "this", "super", "it", "by", "get", "set", "init",
        ),
        "java" to setOf(
            "public", "private", "protected", "class", "interface", "enum", "extends", "implements",
            "static", "final", "void", "int", "long", "double", "float", "boolean", "char",
            "byte", "short", "new", "return", "if", "else", "for", "while", "do", "switch",
            "case", "break", "continue", "try", "catch", "finally", "throw", "throws",
            "import", "package", "this", "super", "null", "true", "false", "instanceof",
            "synchronized", "volatile", "transient", "native", "abstract",
        ),
        "python" to setOf(
            "def", "class", "if", "elif", "else", "for", "while", "return", "import", "from",
            "as", "try", "except", "finally", "raise", "with", "pass", "break", "continue",
            "lambda", "yield", "global", "nonlocal", "assert", "del", "in", "is", "not",
            "and", "or", "None", "True", "False", "self", "cls", "async", "await",
        ),
        "javascript" to setOf(
            "function", "var", "let", "const", "class", "extends", "return", "if", "else",
            "for", "while", "do", "switch", "case", "break", "continue", "try", "catch",
            "finally", "throw", "new", "delete", "typeof", "instanceof", "in", "of",
            "this", "super", "null", "undefined", "true", "false", "async", "await",
            "import", "export", "default", "from", "as",
        ),
        "typescript" to setOf(
            "function", "var", "let", "const", "class", "extends", "implements", "interface",
            "type", "enum", "return", "if", "else", "for", "while", "do", "switch", "case",
            "break", "continue", "try", "catch", "finally", "throw", "new", "delete",
            "typeof", "instanceof", "in", "of", "this", "super", "null", "undefined",
            "true", "false", "async", "await", "import", "export", "default", "from", "as",
            "public", "private", "protected", "readonly", "abstract", "static",
        ),
        "go" to setOf(
            "func", "var", "const", "type", "struct", "interface", "package", "import",
            "return", "if", "else", "for", "switch", "case", "default", "break", "continue",
            "defer", "go", "chan", "range", "select", "map", "nil", "true", "false",
        ),
        "rust" to setOf(
            "fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl",
            "pub", "priv", "use", "mod", "crate", "self", "super", "return", "if", "else",
            "for", "while", "loop", "match", "break", "continue", "in", "ref", "as",
            "move", "async", "await", "dyn", "unsafe", "true", "false", "Some", "None",
            "Ok", "Err",
        ),
        "shell" to setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case",
            "esac", "function", "return", "echo", "export", "local", "readonly",
        ),
        "sql" to setOf(
            "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "CREATE", "TABLE",
            "DROP", "ALTER", "INDEX", "VIEW", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER",
            "ON", "AS", "AND", "OR", "NOT", "NULL", "ORDER", "BY", "GROUP", "HAVING",
            "LIMIT", "OFFSET", "DISTINCT", "UNION", "ALL", "INTO", "VALUES", "SET",
            "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "DEFAULT", "CHECK",
        ),
        // L3 修复: 补全 json 关键字集(true/false/null);xml 无关键字,走 DEFAULT_KEYWORDS
        "json" to setOf("true", "false", "null"),
    )

    /** 通用关键字 fallback(shell/sql 以外的默认)。 */
    private val DEFAULT_KEYWORDS = setOf(
        "function", "return", "if", "else", "for", "while", "break", "continue",
        "true", "false", "null", "none", "nil", "undefined",
    )

    /** L3 修复: 语言别名归一化映射,把缩写映射到 KEYWORDS 中的标准名。 */
    private val LANG_ALIASES: Map<String, String> = mapOf(
        "js" to "javascript",
        "ts" to "typescript",
        "py" to "python",
        "sh" to "shell",
        "golang" to "go",
    )

    /** L4 修复: 永不匹配的正则片段(用于关闭某语言的注释分支)。 */
    private const val NEVER_MATCH = "[^\\s\\S]"

    /** M3 修复: 按语言缓存编译后的高亮正则,避免大代码块逐行重编译。用 ConcurrentHashMap 保证线程安全。 */
    private val regexCache = ConcurrentHashMap<String, Regex>()

    /** M3 修复: 取(或构建并缓存)指定语言的高亮正则。 */
    private fun getRegex(lang: String?): Regex {
        val key = lang ?: ""
        return regexCache.getOrPut(key) {
            buildRegex(KEYWORDS[lang] ?: DEFAULT_KEYWORDS, lang)
        }
    }

    /**
     * Phase 12: 高亮配色数据类。
     *
     * 亮色主题用饱和度较高的色,暗色主题用亮化版色,保证对比度。
     *
     * M-MD12 说明: 配色为代码高亮功能性语义色,已集中在 HighlightColors 中,
     * 由 highlightColors() 根据 MaterialTheme.colorScheme 派生亮/暗两套。
     * 这些色值不属于品牌主题色,保留独立硬编码以保证代码块语义对比度,
     * 不与"深夜台灯"主题铁律冲突。如需调整,统一改 highlightColors() 即可。
     */
    data class HighlightColors(
        val keyword: Color,
        val string: Color,
        val comment: Color,
        val number: Color,
        val annotation: Color,
    )

    /**
     * Phase 12: 从 MaterialTheme 派生高亮配色。
     *
     * 代码高亮色属于"功能性语义色",非品牌色,因此保留独立硬编码色板
     * 以保证代码块语义对比度,不与"深夜台灯"主题铁律冲突。
     *
     * - keyword(蓝灰): 关键字
     * - string(青绿): 字符串
     * - outline(暖灰): 注释(暗色主题自动亮化)
     * - tertiary + 橙色混合: 数字
     * - primaryContainer: 注解
     */
    @Composable
    fun highlightColors(): HighlightColors {
        val scheme = MaterialTheme.colorScheme
        // 暗色主题判断(背景 luminance < 0.5 = 暗色)
        val isDark = scheme.background.luminance() < 0.5f
        return if (isDark) {
            // 暗色主题:亮化版配色,保证深底可读
            HighlightColors(
                keyword = Color(0xFF8AB4CC),      // 蓝灰亮化
                string = Color(0xFF7FD4B0),       // 青绿亮化
                comment = Color(0xFFA89F8E),      // 暖灰亮化
                number = Color(0xFFD49060),       // 橙亮化
                annotation = Color(0xFFC8B860),   // 黄亮化
            )
        } else {
            // 亮色主题:原配色
            HighlightColors(
                keyword = Color(0xFF537D96),      // 蓝灰
                string = Color(0xFF10A37F),       // 青绿
                comment = Color(0xFF8A8275),      // 暖灰
                number = Color(0xFFB8702C),       // 橙
                annotation = Color(0xFF9C8A2C),   // 黄褐
            )
        }
    }

    /**
     * 高亮代码文本,返回 AnnotatedString。
     *
     * Phase 12: 改为 @Composable,从 MaterialTheme 取色,暗色主题自动适配。
     *
     * @param code 原始代码
     * @param language 语言标识(小写,可为 null)
     */
    @Composable
    fun highlight(code: String, language: String?): AnnotatedString {
        val rawLang = language?.lowercase()?.trim()
        // L3 修复: 别名归一化(js→javascript 等),再查关键字集与缓存
        val normalizedLang = LANG_ALIASES[rawLang] ?: rawLang
        val colors = highlightColors()
        return remember(code, language, colors) {
            buildAnnotatedString {
                val regex = getRegex(normalizedLang)
                var lastEnd = 0
                regex.findAll(code).forEach { match ->
                    if (match.range.first > lastEnd) {
                        append(code.substring(lastEnd, match.range.first))
                    }
                    val token = match.value
                    when {
                        match.groups[1] != null -> {
                            // 行注释(按语言: // 或 # 或 --,详见 buildRegex)
                            withStyle(SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)) {
                                append(token)
                            }
                        }
                        match.groups[2] != null -> {
                            // 块注释 /* */
                            withStyle(SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)) {
                                append(token)
                            }
                        }
                        match.groups[3] != null -> {
                            // 字符串 "..." 或 '...'
                            withStyle(SpanStyle(color = colors.string)) {
                                append(token)
                            }
                        }
                        match.groups[4] != null -> {
                            // 关键字
                            withStyle(SpanStyle(color = colors.keyword, fontWeight = FontWeight.Bold)) {
                                append(token)
                            }
                        }
                        match.groups[5] != null -> {
                            // 数字
                            withStyle(SpanStyle(color = colors.number)) {
                                append(token)
                            }
                        }
                        match.groups[6] != null -> {
                            // 注解 @...
                            withStyle(SpanStyle(color = colors.annotation)) {
                                append(token)
                            }
                        }
                        else -> append(token)
                    }
                    lastEnd = match.range.last + 1
                }
                if (lastEnd < code.length) {
                    append(code.substring(lastEnd))
                }
            }
        }
    }

    /** 构建高亮正则。 */
    private fun buildRegex(keywords: Set<String>, lang: String?): Regex {
        // v0.42: 修复 IndexOutOfBoundsException — 原先用非捕获组 (?:...),
        // 但 highlight() 通过 groups[1..6] 判断 token 类型,正则必须提供 6 个捕获组,
        // 否则 match.groups[N] 会抛 "No group N"。
        // SQL 关键字大写,统一加 \b 词边界避免误匹配(原 SQL 分支无 \b 会把 ON 匹配进 CONFIGURATION)。
        val kwPattern = keywords.joinToString("|") { Regex.escape(it) }
        // 分组: 1=行注释 2=块注释 3=字符串 4=关键字 5=数字 6=注解
        // 全部用捕获组 (...),与 highlight() 中 groups[1..6] 一一对应
        // L4 修复: 注释规则按语言开关,避免 # 在 kotlin/java 中被误判为注释、// 在 python 中被误判。
        //  - kotlin/java/js/ts/go/rust/c/cpp/csharp: 行注释 // + 块注释 /* */
        //  - python/shell/ruby: 行注释 #,无块注释
        //  - sql: 行注释 --,无块注释
        //  - 其他(含 json/xml): 无注释分支(用 NEVER_MATCH 占位,保持 6 组结构)
        val (lineComment, blockComment) = when (lang) {
            "kotlin", "java", "javascript", "typescript", "go", "rust",
            "c", "cpp", "csharp" -> "//[^\\n]*" to "/\\*[\\s\\S]*?\\*/"
            "python", "shell", "ruby" -> "#[^\\n]*" to NEVER_MATCH
            "sql" -> "--[^\\n]*" to NEVER_MATCH
            else -> NEVER_MATCH to NEVER_MATCH
        }
        // M-MD10 修复: 不再对所有语言统一 IGNORE_CASE。
        // SQL 关键字以大写存储(SELECT/FROM...),用户可能小写输入,需要 IGNORE_CASE;
        // 其余语言(kotlin/java/python/js/ts/go/rust/shell)关键字大小写敏感
        // (如 kotlin 的 fun 不应匹配 Fun),不加 IGNORE_CASE。
        val options = if (lang == "sql") setOf(RegexOption.IGNORE_CASE) else emptySet()
        return Regex(
            """($lineComment)|($blockComment)|("[^"]*"|'[^']*'|`[^`]*`)|(\b(?:$kwPattern)\b)|(\b\d+\.?\d*[fFlL]?\b)|(@\w+)""",
            options,
        )
    }
}
