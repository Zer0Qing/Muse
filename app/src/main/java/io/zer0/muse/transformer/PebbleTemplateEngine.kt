package io.zer0.muse.transformer

import io.zer0.common.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 9.2 (M22): 轻量 Pebble 兼容模板引擎(独立实现,零依赖)。
 *
 * 参考 Pebble 公开文档的语法规范,自实现子集,不引入 Pebble 依赖
 * (Pebble 是 JVM 库,Android 上不兼容且会增加 APK 体积)。
 *
 * 支持语法(对标 Pebble 子集):
 *  - 变量: {{ var }} / {{ user.name }}(点号路径访问 Map)
 *  - 字面量: 'string' / "string" / 123 / 1.5 / true / false / null
 *  - 过滤器(管道链式):
 *      {{ var | upper }}                  转大写
 *      {{ var | lower }}                  转小写
 *      {{ var | length }}                 长度(CharSequence/Collection/Map)
 *      {{ var | default('foo') }}         为 null/空时回退
 *      {{ var | trim }}                   去首尾空白
 *      {{ list | join(', ') }}            用分隔符连接
 *      {{ var | replace('a', 'b') }}      替换
 *      {{ var | capitalize }}             首字母大写
 *      {{ list | first }} / {{ list | last }}  取首/尾
 *  - 条件:
 *      {% if cond %}...{% elif cond2 %}...{% else %}...{% endif %}
 *      条件表达式: ==  !=  >  <  >=  <=  and  or  not
 *      测试: x is defined / x is empty / x is null / x is even / x is odd
 *      否定测试: x is not empty
 *  - 循环:
 *      {% for item in items %}...{% endfor %}
 *      loop 上下文变量:
 *        {{ loop.index }}    1-based 序号
 *        {{ loop.index0 }}   0-based 序号
 *        {{ loop.first }}    是否首个
 *        {{ loop.last }}     是否末个
 *        {{ loop.length }}   总长度
 *  - 注释: {# comment #}(丢弃不输出)
 *
 * 用法:
 * ```
 * val engine = PebbleTemplateEngine()
 * val out = engine.render(
 *     "你好 {{ name }},今天 {% if weather == 'rain' %}下雨{% else %}晴{% endif %}",
 *     mapOf("name" to "Alice", "weather" to "rain"),
 * )
 * // → "你好 Alice,今天下雨"
 * ```
 *
 * 设计原则:
 *  - 零依赖(纯 Kotlin,不引入 Pebble/Jinja 库)
 *  - 安全(模板沙箱,无反射无代码执行)
 *  - 简洁(单文件 ~700 行)
 *  - Pebble 语法兼容(便于用户参考 Pebble 文档)
 *
 * 安全防护(M-TPL1/M-TPL2/M-PE1):
 *  - AST 缓存: 相同模板字符串只 lex+parse 一次(M-TPL1)
 *  - 迭代次数上限: for 循环最多迭代 MAX_ITERATIONS 次(M-TPL2,DoS 防护)
 *  - 嵌套深度上限: Parser/Evaluator 嵌套不超过 MAX_NESTING_DEPTH 层(M-PE1,StackOverflow 防护)
 *
 * 排坑:
 *  - Lexer 用贪婪匹配 {{ }} / {% %} / {# #},不支持嵌套
 *  - 表达式解析用递归下降,优先级: or < and < not < is < compare < filter < primary
 *  - 变量路径用点号访问 Map;非 Map 对象返回 null(不反射)
 *  - truthy 规则: null/空/0/false → false,其他 → true
 */
class PebbleTemplateEngine {

    // M-TPL1: AST 缓存,以模板字符串为 key,避免每轮重新 lex+parse
    private val astCache = ConcurrentHashMap<String, List<Node>>()

    /**
     * 渲染模板。
     *
     * @param template 模板字符串
     * @param context  变量映射(支持嵌套 Map,如 mapOf("user" to mapOf("name" to "Alice")))
     * @return 渲染后的字符串
     */
    fun render(template: String, context: Map<String, Any?>): String {
        val ast = astCache.computeIfAbsent(template) {
            val tokens = Lexer(it).tokenize()
            Parser(tokens).parse()
        }
        return Evaluator(context).eval(ast)
    }
}

// ============================================================================
// 常量(M-TPL2/M-PE1 安全上限)
// ============================================================================

/** M-PE1: Parser/Evaluator 最大嵌套深度,防止深层嵌套 StackOverflow。 */
private const val MAX_NESTING_DEPTH = 50

/** M-TPL2: for 循环最大迭代次数,防止 DoS。 */
private const val MAX_ITERATIONS = 10000

// ============================================================================
// Lexer — 词法分析
// ============================================================================

private enum class TokenType { TEXT, VAR, TAG }

// L-PE9: Token 携带行号,Parser 报错时附带
private data class Token(val type: TokenType, val content: String, val line: Int)

/**
 * 把模板字符串拆成 Token 列表。
 * - TEXT: 纯文本(原样输出)
 * - VAR: `{{ expr }}`(表达式求值后输出)
 * - TAG: `{% statement %}`(控制流,不输出)
 * - 注释 `{# ... #}` 直接丢弃
 */
private class Lexer(private val src: String) {
    private val out = mutableListOf<Token>()
    private var i = 0
    private var line = 1  // L-PE9: 行号追踪(1-based)

    fun tokenize(): List<Token> {
        val text = StringBuilder()
        while (i < src.length) {
            when {
                match("{{") -> {
                    flushText(text)
                    val tokenLine = line
                    out.add(Token(TokenType.VAR, readUntil("}}").trim(), tokenLine))
                }
                match("{%") -> {
                    flushText(text)
                    val tokenLine = line
                    out.add(Token(TokenType.TAG, readUntil("%}").trim(), tokenLine))
                }
                match("{#") -> {
                    flushText(text)
                    readUntil("#}")  // 注释丢弃
                }
                else -> {
                    if (src[i] == '\n') line++
                    text.append(src[i])
                    i++
                }
            }
        }
        flushText(text)
        return out
    }

    private fun match(s: String): Boolean {
        if (i + s.length > src.length) return false
        if (src.substring(i, i + s.length) != s) return false
        i += s.length
        return true
    }

    // L-PE8: 识别字符串字面量内的定界符,跳过引号内的 }} / %} / #}
    private fun readUntil(end: String): String {
        val start = i
        while (i + end.length <= src.length && src.substring(i, i + end.length) != end) {
            val c = src[i]
            if (c == '\'' || c == '"') {
                // 跳过字符串字面量(不识别引号内的定界符)
                val quote = c
                i++  // 消耗开头引号
                while (i < src.length && src[i] != quote) {
                    if (src[i] == '\n') line++
                    if (src[i] == '\\' && i + 1 < src.length) i += 2 else i++
                }
                if (i < src.length) {
                    if (src[i] == '\n') line++
                    i++  // 消耗结尾引号
                }
            } else {
                if (c == '\n') line++
                i++
            }
        }
        val content = if (i + end.length <= src.length) src.substring(start, i) else src.substring(start)
        i += end.length
        return content
    }

    private fun flushText(b: StringBuilder) {
        if (b.isNotEmpty()) {
            out.add(Token(TokenType.TEXT, b.toString(), line))
            b.clear()
        }
    }
}

// ============================================================================
// AST 节点
// ============================================================================

private sealed class Node {
    /** 纯文本节点。 */
    data class Text(val text: String) : Node()
    /** 表达式输出节点。 */
    data class Output(val expr: Expr) : Node()
    /**
     * if/elif/else 条件节点。
     * - branches 是 (条件?, body) 列表,条件为 null 表示 else 分支
     * - 求值时按顺序找第一个条件为真的分支,执行其 body
     */
    data class If(val branches: List<Pair<Expr?, List<Node>>>) : Node()
    /**
     * for 循环节点。
     * - item 是循环变量名
     * - list 是待迭代表达式(需为 Iterable)
     * - body 是循环体(可访问 item 和 loop 变量)
     */
    data class For(val item: String, val list: Expr, val body: List<Node>) : Node()
    /** Phase 12: {% set var = expr %} ???????*/
    data class Set(val name: String, val value: Expr) : Node()
}

private sealed class Expr {
    /** 字面量(字符串/数字/布尔/null)。 */
    data class Literal(val value: Any?) : Expr()
    /** 变量路径(如 user.name → ["user", "name"])。 */
    data class Var(val path: List<String>) : Expr()
    /** 过滤器调用(input | filter_name(args))。 */
    data class Filter(val input: Expr, val name: String, val args: List<Expr>) : Expr()
    /** 二元运算(==/!=/>/</>=/<=/and/or)。 */
    data class Binary(val op: String, val left: Expr, val right: Expr) : Expr()
    /** 一元运算(not)。 */
    data class Unary(val op: String, val operand: Expr) : Expr()
    /** 测试(is defined / is empty / is null / is even / is odd)。 */
    data class Test(val operand: Expr, val name: String, val negated: Boolean) : Expr()
}

// ============================================================================
// Parser — 语法分析(递归下降)
// ============================================================================

/**
 * 把 Token 列表解析为 AST 节点列表。
 * - 顶层调用 parse() 返回 List<Node>
 * - parseNodes(stopOn) 解析到 stopOn 关键字时返回(用于 if/for 嵌套)
 *
 * M-PE1: 嵌套深度追踪,超过 MAX_NESTING_DEPTH 抛 IllegalStateException。
 */
private class Parser(private val tokens: List<Token>) {
    private var i = 0
    private var depth = 0  // M-PE1: 嵌套深度追踪

    fun parse(): List<Node> = parseNodes(stopOn = null)

    private fun parseNodes(stopOn: ((String) -> Boolean)?): List<Node> {
        depth++
        if (depth > MAX_NESTING_DEPTH) {
            throw IllegalStateException("模板嵌套深度超过上限($MAX_NESTING_DEPTH)")
        }
        try {
            val nodes = mutableListOf<Node>()
            while (i < tokens.size) {
                val t = tokens[i]
                when (t.type) {
                    TokenType.TEXT -> {
                        nodes.add(Node.Text(t.content))
                        i++
                    }
                    TokenType.VAR -> {
                        nodes.add(Node.Output(ExprParser(t.content).parse()))
                        i++
                    }
                    TokenType.TAG -> {
                        val keyword = t.content.split(WHITESPACE_REGEX).firstOrNull() ?: ""
                        if (stopOn != null && stopOn(keyword)) return nodes
                        when (keyword) {
                            "if" -> {
                                i++  // 消耗 if token
                                nodes.add(parseIf(t))
                            }
                            "for" -> {
                                i++  // 消耗 for token
                                nodes.add(parseFor(t))
                            }
                            "set" -> {
                                i++
                                nodes.add(parseSet(t))
                            }
                            else -> throw IllegalStateException("未知模板标签: $keyword (行 ${t.line}, 完整内容: ${t.content})")
                        }
                    }
                }
            }
            return nodes
        } finally {
            depth--
        }
    }

    /** 解析 if/elif/else/endif 块。i 已消耗 if token。 */
    private fun parseIf(ifToken: Token): Node.If {
        val branches = mutableListOf<Pair<Expr?, List<Node>>>()
        // if 分支
        val ifCond = ExprParser(ifToken.content.removePrefix("if").trim()).parse()
        val ifBody = parseNodes(stopOn = { it == "elif" || it == "else" || it == "endif" })
        branches.add(ifCond to ifBody)

        // elif / else / endif 分支关键字处理
        while (i < tokens.size && tokens[i].type == TokenType.TAG) {
            val content = tokens[i].content
            val kw = content.split(WHITESPACE_REGEX).first()
            when (kw) {
                "elif" -> {
                    i++  // 消耗 elif token
                    val cond = ExprParser(content.removePrefix("elif").trim()).parse()
                    val body = parseNodes(stopOn = { it == "elif" || it == "else" || it == "endif" })
                    branches.add(cond to body)
                }
                "else" -> {
                    i++  // 消耗 else token
                    val body = parseNodes(stopOn = { it == "endif" })
                    branches.add(null to body)
                }
                "endif" -> {
                    i++  // 消耗 endif token
                    break
                }
                else -> break
            }
        }
        return Node.If(branches)
    }

    /** 解析 for/endfor 块。i 已消耗 for token。 */
    private fun parseFor(forToken: Token): Node.For {
        // 语法: for <var> in <expr>
        val m = FOR_REGEX.matchEntire(forToken.content)
            ?: throw IllegalStateException("无效 for 语法 (行 ${forToken.line}): ${forToken.content}")
        val item = m.groupValues[1]
        val listExpr = ExprParser(m.groupValues[2].trim()).parse()
        val body = parseNodes(stopOn = { it == "endfor" })
        // 消耗 endfor
        if (i < tokens.size && tokens[i].type == TokenType.TAG && tokens[i].content.startsWith("endfor")) {
            i++
        }
        return Node.For(item, listExpr, body)
    }

    /** Phase 12: ?? {% set var = expr %}?*/
    private fun parseSet(setToken: Token): Node.Set {
        val content = setToken.content.removePrefix("set").trim()
        val eqIdx = content.indexOf('=')
        if (eqIdx < 0) {
            throw IllegalStateException("?? set ?? (?${setToken.line}): ${setToken.content}")
        }
        val name = content.substring(0, eqIdx).trim()
        if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' }) {
            throw IllegalStateException("?? set ??? (?${setToken.line}): '$name'")
        }
        val exprStr = content.substring(eqIdx + 1).trim()
        val expr = ExprParser(exprStr).parse()
        return Node.Set(name, expr)
    }

    companion object {
        // L-PE3: Regex 抽到 companion object 常量,避免重复编译
        val WHITESPACE_REGEX = Regex("\\s+")
        val FOR_REGEX = Regex("""for\s+(\w+)\s+in\s+(.+)""", RegexOption.DOT_MATCHES_ALL)
    }
}

// ============================================================================
// ExprParser — 表达式解析(递归下降,优先级 climbing)
// ============================================================================

/**
 * 表达式解析器。
 *
 * 优先级(从低到高):
 *   or  <  and  <  not  <  is(test)  <  compare(==/!=/>/</>=/<=)  <  filter(|)  <  primary(由低到高)
 *
 * 例:`a == b or c is empty and not d`
 *   → or(and(is(c, empty, _), not(d)), ==(a, b))
 */
private class ExprParser(private val src: String) {
    private var pos = 0

    fun parse(): Expr {
        val e = parseOr()
        skipSpace()
        if (pos < src.length) {
            throw IllegalStateException("表达式未消费完: 剩余 '${src.substring(pos)}'")
        }
        return e
    }

    private fun parseOr(): Expr {
        var left = parseAnd()
        while (matchKeyword("or")) {
            val right = parseAnd()
            left = Expr.Binary("or", left, right)
        }
        return left
    }

    private fun parseAnd(): Expr {
        var left = parseNot()
        while (matchKeyword("and")) {
            val right = parseNot()
            left = Expr.Binary("and", left, right)
        }
        return left
    }

    private fun parseNot(): Expr {
        if (matchKeyword("not")) {
            return Expr.Unary("not", parseNot())
        }
        return parseTest()
    }

    private fun parseTest(): Expr {
        val left = parseCompare()
        skipSpace()
        if (matchKeyword("is")) {
            val negated = matchKeyword("not")
            val name = readWord()
            return Expr.Test(left, name, negated)
        }
        return left
    }

    private fun parseCompare(): Expr {
        val left = parseFilter()
        skipSpace()
        val op = matchCompareOp() ?: return left
        val right = parseFilter()
        return Expr.Binary(op, left, right)
    }

    private fun parseFilter(): Expr {
        var left = parsePrimary()
        skipSpace()
        while (pos < src.length && src[pos] == '|') {
            pos++  // 消耗 |
            skipSpace()
            val name = readWord()
            val args = mutableListOf<Expr>()
            skipSpace()
            if (pos < src.length && src[pos] == '(') {
                pos++  // 消耗 (
                skipSpace()
                while (pos < src.length && src[pos] != ')') {
                    args.add(parsePrimary())
                    skipSpace()
                    if (pos < src.length && src[pos] == ',') {
                        pos++
                        skipSpace()
                    }
                }
                if (pos < src.length && src[pos] == ')') pos++  // 消耗 )
            }
            left = Expr.Filter(left, name, args)
            skipSpace()
        }
        return left
    }

    private fun parsePrimary(): Expr {
        skipSpace()
        if (pos >= src.length) throw IllegalStateException("表达式意外结束")
        val c = src[pos]
        // 字符串字面量
        if (c == '\'' || c == '"') return Expr.Literal(readString())
        // 数字字面量
        if (c.isDigit() || (c == '-' && pos + 1 < src.length && src[pos + 1].isDigit())) {
            return Expr.Literal(readNumber())
        }
        // 括号分组
        if (c == '(') {
            pos++  // 消耗 (
            val e = parseOr()
            skipSpace()
            if (pos < src.length && src[pos] == ')') pos++  // 消耗 )
            return e
        }
        // 关键字 / 变量路径
        val word = peekWord()
        when (word) {
            "true" -> { pos += 4; return Expr.Literal(true) }
            "false" -> { pos += 5; return Expr.Literal(false) }
            "null", "none" -> { pos += 4; return Expr.Literal(null) }
        }
        if (word.isNotEmpty() && (word.first().isLetter() || word.first() == '_')) {
            pos += word.length
            val path = mutableListOf(word)
            while (pos < src.length && src[pos] == '.') {
                pos++  // 消耗 .
                val seg = readWord()
                path.add(seg)
            }
            return Expr.Var(path)
        }
        throw IllegalStateException("无法解析表达式: '${src.substring(pos)}'")
    }

    // ---- 辅助函数 ----

    private fun skipSpace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    // L-PE7: 删除 peek() 和 peek(offset) 死代码

    /** 看当前单词(不消费),单词 = 字母/数字/下划线。 */
    private fun peekWord(): String {
        var j = pos
        while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_')) j++
        return src.substring(pos, j)
    }

    /** 读一个单词(消费),单词 = 字母/数字/下划线。 */
    private fun readWord(): String {
        skipSpace()
        val start = pos
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
        return src.substring(start, pos)
    }

    private fun readString(): String {
        val quote = src[pos]
        pos++  // 消耗开头引号
        val sb = StringBuilder()
        while (pos < src.length && src[pos] != quote) {
            if (src[pos] == '\\' && pos + 1 < src.length) {
                // 转义:\' → '  \" → "  \\ → \  \n → 换行  \t → tab
                pos++
                when (src[pos]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    else -> sb.append(src[pos])
                }
            } else {
                sb.append(src[pos])
            }
            pos++
        }
        if (pos < src.length) pos++  // 消耗结尾引号
        return sb.toString()
    }

    // L-PE2: 遇第二个点即停止数字解析(原实现允许多个小数点)
    private fun readNumber(): Number {
        val start = pos
        if (src[pos] == '-') pos++
        var hasDot = false
        while (pos < src.length && (src[pos].isDigit() || (src[pos] == '.' && !hasDot))) {
            if (src[pos] == '.') hasDot = true
            pos++
        }
        val s = src.substring(start, pos)
        return if (hasDot) s.toDouble() else s.toLong()
    }

    /** 匹配关键字(单词边界,避免 'and' 匹配 'android')。 */
    private fun matchKeyword(kw: String): Boolean {
        skipSpace()
        val word = peekWord()
        if (word == kw) {
            pos += kw.length
            return true
        }
        return false
    }

    private fun matchCompareOp(): String? {
        skipSpace()
        val ops = listOf("==", "!=", ">=", "<=", ">", "<")
        for (op in ops) {
            if (pos + op.length <= src.length && src.substring(pos, pos + op.length) == op) {
                pos += op.length
                return op
            }
        }
        return null
    }
}

// ============================================================================
// Evaluator — 求值
// ============================================================================

/**
 * 用上下文对 AST 求值,返回渲染后的字符串。
 * - 支持 for 循环时通过 scopeStack 压入循环变量作用域
 * - 变量查找从内层 scope 向外层
 *
 * M-PE1: 嵌套深度追踪,超过 MAX_NESTING_DEPTH 抛 IllegalStateException。
 */
private class Evaluator(root: Map<String, Any?>) {
    private val scopeStack = ArrayDeque<Map<String, Any?>>()
    private var depth = 0  // M-PE1: 嵌套深度追踪

    init {
        scopeStack.addLast(root)
    }

    fun eval(nodes: List<Node>): String {
        val sb = StringBuilder()
        for (n in nodes) evalNode(n, sb)
        return sb.toString()
    }

    private fun evalNode(n: Node, sb: StringBuilder) {
        depth++
        if (depth > MAX_NESTING_DEPTH) {
            throw IllegalStateException("模板求值嵌套深度超过上限($MAX_NESTING_DEPTH)")
        }
        try {
            when (n) {
                is Node.Text -> sb.append(n.text)
                is Node.Set -> {
                    val value = evalExpr(n.value)
                    if (scopeStack.isNotEmpty()) {
                        // 原地修改最内层作用域,避免破坏 for 循环引用
                        val inner = scopeStack.removeLast()
                        val mutable = if (inner is MutableMap) inner else inner.toMutableMap()
                        mutable[n.name] = value
                        scopeStack.addLast(mutable)
                    }
                }
                is Node.Output -> sb.append(stringify(evalExpr(n.expr)))
                is Node.If -> {
                    for ((cond, body) in n.branches) {
                        if (cond == null || truthy(evalExpr(cond))) {
                            for (child in body) evalNode(child, sb)
                            return
                        }
                    }
                }
                is Node.For -> {
                    val list = evalExpr(n.list)
                    if (list is Iterable<*>) {
                        val allItems = list.toList()
                        val totalLen = allItems.size
                        // M-TPL2: 限制最大迭代次数,防止 DoS
                        val truncated = totalLen > MAX_ITERATIONS
                        if (truncated) {
                            Logger.w("PebbleTemplateEngine", "for 循环迭代次数($totalLen)超过上限($MAX_ITERATIONS),已截断")
                        }
                        val items = if (truncated) allItems.take(MAX_ITERATIONS) else allItems
                        val len = items.size
                        val loopScope = mutableMapOf<String, Any?>()
                        scopeStack.addLast(loopScope)
                        items.forEachIndexed { idx, item ->
                            loopScope.clear()
                            loopScope[n.item] = item
                            loopScope["loop"] = mapOf(
                                "index" to idx + 1,
                                "index0" to idx,
                                "first" to (idx == 0),
                                "last" to (idx == len - 1),
                                "length" to len,
                            )
                            for (child in n.body) evalNode(child, sb)
                        }
                        scopeStack.removeLast()
                    }
                }
            }
        } finally {
            depth--
        }
    }

    private fun evalExpr(e: Expr): Any? = when (e) {
        is Expr.Literal -> e.value
        is Expr.Var -> resolveVar(e.path)
        is Expr.Filter -> applyFilter(evalExpr(e.input), e.name, e.args.map { evalExpr(it) })
        is Expr.Binary -> evalBinary(e.op, evalExpr(e.left), evalExpr(e.right))
        is Expr.Unary -> when (e.op) {
            "not" -> !truthy(evalExpr(e.operand))
            else -> null
        }
        is Expr.Test -> evalTest(e.name, evalExpr(e.operand), e.negated)
    }

    private fun resolveVar(path: List<String>): Any? {
        if (path.isEmpty()) return null
        // 第一段:从内层 scope 向外查
        var cur: Any? = null
        var found = false
        for (scope in scopeStack.reversed()) {
            if (scope.containsKey(path[0])) {
                cur = scope[path[0]]
                found = true
                break
            }
        }
        if (!found) return null
        // 后续段:点号访问 Map
        for (k in path.drop(1)) {
            cur = when (cur) {
                is Map<*, *> -> cur[k]
                else -> null
            }
            if (cur == null) break
        }
        return cur
    }

    private fun evalBinary(op: String, l: Any?, r: Any?): Any? = when (op) {
        "and" -> truthy(l) && truthy(r)
        "or" -> truthy(l) || truthy(r)
        // Phase 12 修复:Number 统一用 toDouble() 比较,否则 Int vs Long 用 equals 会返回 false
        "==" -> if (l is Number && r is Number) l.toDouble() == r.toDouble() else l == r
        "!=" -> if (l is Number && r is Number) l.toDouble() != r.toDouble() else l != r
        ">", "<", ">=", "<=" -> compareNums(op, l, r)
        else -> null
    }

    private fun compareNums(op: String, l: Any?, r: Any?): Boolean {
        val ln = (l as? Number)?.toDouble() ?: return false
        val rn = (r as? Number)?.toDouble() ?: return false
        return when (op) {
            ">" -> ln > rn
            "<" -> ln < rn
            ">=" -> ln >= rn
            "<=" -> ln <= rn
            else -> false
        }
    }

    private fun evalTest(name: String, value: Any?, negated: Boolean): Boolean {
        val result = when (name) {
            "defined" -> value != null
            "undefined" -> value == null
            "empty" -> when (value) {
                null -> true
                is CharSequence -> value.isEmpty()
                is Collection<*> -> value.isEmpty()
                is Map<*, *> -> value.isEmpty()
                else -> false
            }
            "null", "none" -> value == null
            "even" -> (value as? Number)?.toInt()?.rem(2) == 0
            "odd" -> (value as? Number)?.toInt()?.rem(2) == 1
            "true" -> value == true
            "false" -> value == false
            else -> false
        }
        return if (negated) !result else result
    }

    private fun applyFilter(input: Any?, name: String, args: List<Any?>): Any? = when (name) {
        "upper" -> input?.toString()?.uppercase()
        "lower" -> input?.toString()?.lowercase()
        "length" -> when (input) {
            is CharSequence -> input.length
            is Collection<*> -> input.size
            is Map<*, *> -> input.size
            is Array<*> -> input.size
            else -> 0
        }
        // L-PE5: 与 Pebble 语义有差异。Pebble 的 default 仅对 null/undefined 回退;
        // 本实现对 null/空字符串/空集合都回退(向后兼容现有模板,改动语义可能破坏已有模板)
        "default" -> if (input == null || input == "" || (input is Collection<*> && input.isEmpty())) {
            args.firstOrNull()
        } else input
        "trim" -> input?.toString()?.trim()
        "first" -> when (input) {
            is List<*> -> input.firstOrNull()
            is CharSequence -> input.firstOrNull()?.toString()
            is Array<*> -> input.firstOrNull()
            else -> null
        }
        "last" -> when (input) {
            is List<*> -> input.lastOrNull()
            is CharSequence -> input.lastOrNull()?.toString()
            is Array<*> -> input.lastOrNull()
            else -> null
        }
        // L-PE6: CharSequence 按 char 迭代 join
        "join" -> when (input) {
            is Iterable<*> -> input.joinToString(args.firstOrNull()?.toString() ?: ", ") { it?.toString() ?: "" }
            is Array<*> -> input.joinToString(args.firstOrNull()?.toString() ?: ", ") { it?.toString() ?: "" }
            is CharSequence -> input.toList().joinToString(args.firstOrNull()?.toString() ?: ", ") { it.toString() }
            else -> input?.toString()
        }
        "replace" -> {
            if (args.size >= 2) {
                input?.toString()?.replace(args[0].toString(), args[1].toString())
            } else input
        }
        "capitalize" -> input?.toString()?.replaceFirstChar { it.uppercase() }
        "abs" -> (input as? Number)?.let { kotlin.math.abs(it.toDouble()) }
        "reverse" -> when (input) {
            is List<*> -> input.reversed()
            is CharSequence -> input.reversed()
            else -> input
        }
        // L-PE4: Number 优先数值比较,非数字再回退字符串比较
        "sort" -> (input as? List<*>)?.sortedWith { a, b ->
            val an = a as? Number
            val bn = b as? Number
            if (an != null && bn != null) {
                an.toDouble().compareTo(bn.toDouble())
            } else {
                (a?.toString() ?: "").compareTo(b?.toString() ?: "")
            }
        }
        else -> input  // 未知过滤器原样返回
    }

    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is CharSequence -> v.isNotEmpty()
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        is Array<*> -> v.isNotEmpty()
        else -> true
    }

    private fun stringify(v: Any?): String = when (v) {
        null -> ""
        is Boolean -> if (v) "true" else "false"
        is Number -> {
            // 整数去掉 .0 后缀
            val d = v.toDouble()
            if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
        }
        else -> v.toString()
    }
}
