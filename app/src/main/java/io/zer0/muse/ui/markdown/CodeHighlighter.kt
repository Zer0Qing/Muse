package io.zer0.muse.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import io.zer0.muse.ui.theme.codeColors
import java.util.concurrent.ConcurrentHashMap

/**
 * 轻量代码高亮(自实现,不引入第三方高亮库)。
 *
 * E6 升级: 语言集从 10 扩展至 21 主流语言 + diff 行级高亮。
 * 支持语言: kotlin / java / python / javascript / typescript / go / rust / shell /
 *           sql / json / xml(默认) / c / cpp / csharp / ruby / swift / php / dart /
 *           html / css / yaml / diff
 * 高亮维度:
 *  - 关键字(keyword): 粗体 + 蓝紫
 *  - 字符串(string): 绿
 *  - 注释(comment): 灰斜体
 *  - 数字(number): 橙
 *  - 注解/装饰器(annotation): 黄
 *  - diff(diff): 行级着色 — hunk 头蓝 / 新增行绿 / 删除行红(带半透明行底色)
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
        // E6 升级: 主流 C 系 / 脚本 / 前端语言集
        "c" to setOf(
            "auto", "break", "case", "char", "const", "continue", "default", "do", "double",
            "else", "enum", "extern", "float", "for", "goto", "if", "int", "long", "register",
            "return", "short", "signed", "sizeof", "static", "struct", "switch", "typedef",
            "union", "unsigned", "void", "volatile", "while",
        ),
        "cpp" to setOf(
            "auto", "break", "case", "catch", "char", "class", "const", "constexpr", "continue",
            "default", "delete", "do", "double", "else", "enum", "explicit", "extern", "false",
            "float", "for", "friend", "goto", "if", "inline", "int", "long", "namespace", "new",
            "nullptr", "operator", "private", "protected", "public", "register", "return",
            "short", "signed", "sizeof", "static", "struct", "switch", "template", "this",
            "throw", "true", "try", "typedef", "typename", "union", "unsigned", "using",
            "virtual", "void", "volatile", "while",
        ),
        "csharp" to setOf(
            "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char",
            "checked", "class", "const", "continue", "decimal", "default", "delegate", "do",
            "double", "else", "enum", "event", "explicit", "extern", "false", "finally",
            "fixed", "float", "for", "foreach", "goto", "if", "implicit", "in", "int",
            "interface", "internal", "is", "lock", "long", "namespace", "new", "null",
            "object", "operator", "out", "override", "params", "private", "protected",
            "public", "readonly", "ref", "return", "sbyte", "sealed", "short", "sizeof",
            "stackalloc", "static", "string", "struct", "switch", "this", "throw", "true",
            "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort", "using",
            "virtual", "void", "volatile", "while",
        ),
        "ruby" to setOf(
            "def", "class", "module", "if", "elsif", "else", "end", "for", "while", "until",
            "do", "case", "when", "then", "return", "yield", "self", "nil", "true", "false",
            "begin", "rescue", "ensure", "raise", "require", "include", "extend", "lambda",
            "proc", "new", "super", "and", "or", "not",
        ),
        "swift" to setOf(
            "func", "var", "let", "class", "struct", "enum", "protocol", "extension", "import",
            "return", "if", "else", "guard", "for", "while", "repeat", "switch", "case",
            "break", "continue", "defer", "do", "catch", "throw", "throws", "try", "in", "as",
            "is", "nil", "true", "false", "self", "super", "init", "deinit", "static",
            "private", "public", "internal", "open", "fileprivate", "lazy", "weak", "unowned",
            "where",
        ),
        "php" to setOf(
            "function", "class", "interface", "extends", "implements", "public", "private",
            "protected", "static", "final", "const", "return", "if", "else", "elseif", "for",
            "foreach", "while", "do", "switch", "case", "break", "continue", "try", "catch",
            "finally", "throw", "new", "echo", "print", "require", "require_once", "include",
            "include_once", "namespace", "use", "as", "true", "false", "null", "this",
            "global",
        ),
        "dart" to setOf(
            "class", "interface", "extends", "implements", "mixin", "abstract", "factory",
            "const", "final", "var", "void", "return", "if", "else", "for", "while", "do",
            "switch", "case", "break", "continue", "try", "catch", "finally", "throw", "new",
            "null", "true", "false", "this", "super", "static", "import", "export", "library",
            "part", "async", "await",
        ),
        "html" to setOf(
            "html", "head", "body", "div", "span", "p", "a", "img", "ul", "ol", "li",
            "table", "tr", "td", "th", "form", "input", "button", "select", "option",
            "script", "style", "meta", "link", "title", "h1", "h2", "h3", "h4", "h5", "h6",
            "br", "hr", "section", "article", "nav", "header", "footer", "main", "aside",
        ),
        "css" to setOf(
            "@media", "@import", "@keyframes", "@font-face", "@supports", "@charset", "@page",
        ),
        "yaml" to setOf("true", "false", "null", "yes", "no", "on", "off"),
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
        // E6 升级: 更多常见别名
        "bash" to "shell",
        "zsh" to "shell",
        "yml" to "yaml",
        "c++" to "cpp",
        "h" to "c",
        "cs" to "csharp",
        "objc" to "c",
        "objective-c" to "c",
        "objectivec" to "c",
        "rb" to "ruby",
        "html5" to "html",
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
        /** diff 新增行。 */
        val diffAdded: Color,
        /** diff 删除行。 */
        val diffRemoved: Color,
        /** diff hunk 头(+++/---/@@)。 */
        val diffHunk: Color,
    )

    /**
     * Phase 12: 从 MaterialTheme 派生高亮配色。
     *
     * v1.0.52: 色板上移至 ui/theme/StatusColors.kt 的 [MuseCodeColors],
     * 由 MuseTheme 按深浅色注入 CompositionLocal,这里只做字段映射。
     * 后续自定义主题可覆盖代码高亮色。
     */
    @Composable
    fun highlightColors(): HighlightColors {
        val c = MaterialTheme.codeColors
        return HighlightColors(
            keyword = c.keyword,
            string = c.string,
            comment = c.comment,
            number = c.number,
            annotation = c.annotation,
            diffAdded = c.diffAdded,
            diffRemoved = c.diffRemoved,
            diffHunk = c.diffHunk,
        )
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
        // E6 升级: diff 识别 — fence 语言可能是 "diff" / "git diff" / "unified diff"
        val isDiff = rawLang != null && rawLang.contains("diff")
        // L3 修复: 别名归一化(js→javascript 等),再查关键字集与缓存
        val normalizedLang = if (isDiff) "diff" else LANG_ALIASES[rawLang] ?: rawLang
        val colors = highlightColors()
        if (isDiff) {
            return remember(code, colors) { highlightDiff(code, colors) }
        }
        return remember(code, language, colors) {
            buildAnnotatedString {
                val regex = getRegex(normalizedLang)
                var lastEnd = 0
                regex.findAll(code).forEach { match ->
                    if (match.range.first > lastEnd) {
                        append(code.substring(lastEnd, match.range.first))
                    }
                    appendHighlightedToken(match.value, match, colors)
                    lastEnd = match.range.last + 1
                }
                if (lastEnd < code.length) {
                    append(code.substring(lastEnd))
                }
            }
        }
    }

    /**
     * 按捕获组类型着色单个 token。
     * 提取为独立函数以控制 highlight() 的圈复杂度(6 个捕获组分支)。
     */
    private fun AnnotatedString.Builder.appendHighlightedToken(
        token: String,
        match: MatchResult,
        colors: HighlightColors,
    ) {
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
    }

    /** E6: diff 行分类 — 供行级渲染与单测使用。 */
    internal enum class DiffLineKind { HUNK_HEADER, ADDED, REMOVED, CONTEXT, EMPTY }

    /**
     * E6: 分类单行 diff 文本。
     *
     * 判定顺序: hunk 头(@@ 或 +++/---)优先于 +/- 单字符行,
     * 避免 "+++ b/File" 被误判为新增行。
     */
    internal fun classifyDiffLine(line: String): DiffLineKind {
        if (line.isEmpty()) return DiffLineKind.EMPTY
        return when {
            line.startsWith("@@") -> DiffLineKind.HUNK_HEADER
            line.startsWith("+++") || line.startsWith("---") -> DiffLineKind.HUNK_HEADER
            line.startsWith("+") -> DiffLineKind.ADDED
            line.startsWith("-") -> DiffLineKind.REMOVED
            else -> DiffLineKind.CONTEXT
        }
    }

    /**
     * E6: diff 行级高亮 — hunk 头蓝 / 新增行绿 / 删除行红(带半透明行底色)。
     * 逐行独立着色,与 MarkdownText 的逐行高亮调用方式兼容。
     */
    private fun highlightDiff(code: String, colors: HighlightColors): AnnotatedString =
        buildAnnotatedString {
            code.split("\n").forEachIndexed { index, line ->
                if (index > 0) append("\n")
                val kind = classifyDiffLine(line)
                val style = when (kind) {
                    DiffLineKind.HUNK_HEADER -> SpanStyle(
                        color = colors.diffHunk,
                        fontWeight = FontWeight.Bold,
                    )
                    DiffLineKind.ADDED -> SpanStyle(
                        color = colors.diffAdded,
                        background = colors.diffAdded.copy(alpha = 0.12f),
                    )
                    DiffLineKind.REMOVED -> SpanStyle(
                        color = colors.diffRemoved,
                        background = colors.diffRemoved.copy(alpha = 0.12f),
                    )
                    // CONTEXT / EMPTY 保持默认样式
                    else -> null
                }
                if (style != null) {
                    withStyle(style) { append(line) }
                } else {
                    append(line)
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
        //  - kotlin/java/js/ts/go/rust/c/cpp/csharp/swift/php/dart: 行注释 // + 块注释 /* */
        //  - python/shell/ruby/yaml: 行注释 #,无块注释
        //  - sql: 行注释 --,无块注释
        //  - 其他(含 json/xml/html/css): 无注释分支(用 NEVER_MATCH 占位,保持 6 组结构)
        val (lineComment, blockComment) = when (lang) {
            // E6 升级: 新增 C 系 / swift / php / dart 沿用 // + /* */
            "kotlin", "java", "javascript", "typescript", "go", "rust",
            "c", "cpp", "csharp", "swift", "php", "dart" -> "//[^\\n]*" to "/\\*[\\s\\S]*?\\*/"
            "python", "shell", "ruby", "yaml" -> "#[^\\n]*" to NEVER_MATCH
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
