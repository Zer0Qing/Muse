package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * P1-3b 拆域：核心基础工具注册器。
 *
 * 从 ToolRegistry.kt 抽出的 get_current_time / calculator / echo，
 * 只依赖 Context 字符串资源，不持有 ToolRegistry 内部状态。
 */
class CoreToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_current_time",
                description = "获取当前时间。可指定时区(如 Asia/Shanghai、UTC、America/New_York),自动标注 DST 夏令时状态。",
                parameters = mapOf(
                    "timezone" to "可选,IANA 时区标识(如 Asia/Shanghai/UTC/America/New_York),默认 Asia/Shanghai。传 UTC 可得协调世界时",
                    "format" to "可选,自定义时间格式(Java SimpleDateFormat 语法),默认 yyyy-MM-dd HH:mm:ss z。如 'yyyy/MM/dd'、'HH:mm'。",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val tzId = args["timezone"]?.takeIf { it.isNotBlank() } ?: "Asia/Shanghai"
            val tz = if (TimeZone.getAvailableIDs().contains(tzId)) {
                TimeZone.getTimeZone(tzId)
            } else {
                return@register context.getString(R.string.tool_unknown_timezone, tzId)
            }
            val pattern = args["format"]?.takeIf { it.isNotBlank() } ?: "yyyy-MM-dd HH:mm:ss z"
            val fmt = resultOf { SimpleDateFormat(pattern, Locale.getDefault()) }
                .onError { msg, _ -> Logger.w("CoreTools", "时间格式无效: $msg(pattern=$pattern)") }
                .getOrNull() ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
            fmt.timeZone = tz
            val dstLabel = if (tz.inDaylightTime(Date())) {
                context.getString(R.string.tool_dst)
            } else {
                context.getString(R.string.tool_non_dst)
            }
            context.getString(R.string.tool_current_time, tzId, fmt.format(Date()), dstLabel)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "calculator",
                description = "简易计算器,支持加减乘除和括号。返回 '表达式 = 结果' 文本。仅支持四则运算与括号,不支持幂/取模/单位换算。",
                parameters = mapOf("expression" to "必填,数学表达式,如 1+2*3"),
                required = setOf("expression"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val expr = args["expression"]?.takeIf { it.isNotBlank() }
                ?: return@register context.getString(R.string.tool_missing_param_expression)
            val result = try {
                Calculator.eval(expr)
            } catch (e: ArithmeticException) {
                return@register context.getString(R.string.tool_calc_error, e.message ?: "")
            } catch (e: IllegalArgumentException) {
                return@register context.getString(R.string.tool_expr_invalid, e.message ?: "")
            }
            if (result.isInfinite() || result.isNaN()) {
                return@register context.getString(R.string.tool_calc_error_divzero, result.toString())
            }
            "$expr = $result"
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "echo",
                description = "回显输入内容(测试用)。",
                parameters = mapOf("text" to "必填,要回显的文本"),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            args["text"] ?: ""
        }
    }
}

/**
 * 简易计算器:支持 + - * / ( ) 和空格。
 * 不引入第三方表达式引擎,用递归下降解析。
 * 不用 javax.script(Nashorn 在 JDK 15+ 移除,Android 无)。
 */
private object Calculator {
    fun eval(input: String): Double {
        val chars = input.filterNot { it.isWhitespace() }.toCharArray()
        val pos = intArrayOf(0)
        val result = parseExpr(chars, pos)
        if (pos[0] != chars.size) throw IllegalArgumentException("cannot parse: ${input.substring(pos[0])}")
        return result
    }

    private fun parseExpr(chars: CharArray, pos: IntArray): Double {
        var v = parseTerm(chars, pos)
        while (pos[0] < chars.size) {
            when (chars[pos[0]]) {
                '+' -> { pos[0]++; v += parseTerm(chars, pos) }
                '-' -> { pos[0]++; v -= parseTerm(chars, pos) }
                else -> break
            }
        }
        return v
    }

    private fun parseTerm(chars: CharArray, pos: IntArray): Double {
        var v = parseFactor(chars, pos)
        while (pos[0] < chars.size) {
            when (chars[pos[0]]) {
                '*' -> { pos[0]++; v *= parseFactor(chars, pos) }
                '/' -> { pos[0]++; v /= parseFactor(chars, pos) }
                else -> break
            }
        }
        return v
    }

    private fun parseFactor(chars: CharArray, pos: IntArray): Double {
        if (pos[0] >= chars.size) throw IllegalArgumentException("unexpected end of expression")
        if (chars[pos[0]] == '-') {
            pos[0]++
            return -parseFactor(chars, pos)
        }
        if (chars[pos[0]] == '(') {
            pos[0]++
            val v = parseExpr(chars, pos)
            if (pos[0] >= chars.size || chars[pos[0]] != ')') throw IllegalArgumentException("missing closing parenthesis")
            pos[0]++
            return v
        }
        val start = pos[0]
        while (pos[0] < chars.size && (chars[pos[0]].isDigit() || chars[pos[0]] == '.')) pos[0]++
        if (start == pos[0]) throw IllegalArgumentException("expected number, got: ${chars[pos[0]]}")
        val len = pos[0] - start
        return String(chars, start, len).toDouble()
    }
}
