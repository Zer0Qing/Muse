package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.tools.script.ProcessSkillEngine
import io.zer0.muse.tools.script.SkillEngineResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P1-C: Node 脚本执行工具（execute_node_script）。
 *
 * 在内置 Node 运行时中执行 JavaScript 脚本：完整能力（文件读写 / 网络 / 子进程），
 * 可 require 运行时 node_modules 区（filesDir/muse-runtime/node_modules）中的 npm 包。
 *
 * 高风险工具：ASK / STRICT 模式要求审批；TRUSTED 模式依用户明确选择免确认。
 *
 * 返回结构（JSON，与 execute_javascript 对齐）：
 *  - result: string — 执行返回值（JSON 字符串形式）
 *  - logs: array — console 日志数组
 *  - error: string | null
 */
object NodeScriptTool {

    const val TOOL_NAME = "execute_node_script"

    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val MAX_TIMEOUT_MS = 300_000L
    const val MAX_SCRIPT_CHARS = 128_000

    fun toolDef() = ToolRegistry.ToolDef(
        name = TOOL_NAME,
        description = "在内置 Node.js 运行时中执行 JavaScript 脚本(完整能力:文件读写、网络、子进程;" +
            "可 require 第三方 npm 模块)。返回 JSON(result/logs/error)。超时默认 30 秒。",
        parameters = mapOf(
            "code" to "必填,Node 脚本代码(表达式或语句块;可用 require/fs/fetch 等)",
            "timeout_ms" to "可选,超时毫秒数,默认 30000,最大 300000",
        ),
        required = setOf("code"),
        category = "built-in",
        parameterTypes = mapOf("timeout_ms" to "integer"),
        riskLevel = ToolRiskLevel.HIGH,
    )

    suspend fun execute(context: Context, args: Map<String, String>): String {
        val code = args["code"].orEmpty()
        val timeoutMs = args["timeout_ms"]?.toLongOrNull()?.coerceIn(1L, MAX_TIMEOUT_MS) ?: DEFAULT_TIMEOUT_MS
        return when {
            code.isBlank() -> errorJson("参数 code 缺失或为空")
            code.length > MAX_SCRIPT_CHARS -> errorJson("脚本超过最大长度($MAX_SCRIPT_CHARS 字符)")
            else -> formatResultJson(evaluate(context, code, timeoutMs))
        }
    }

    /** Shared execution path for the direct script tool and durable phone-agent workflows. */
    suspend fun evaluate(context: Context, code: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): SkillEngineResult {
        return when {
            code.isBlank() -> SkillEngineResult.Error("参数 code 缺失或为空")
            code.length > MAX_SCRIPT_CHARS -> SkillEngineResult.Error("脚本超过最大长度($MAX_SCRIPT_CHARS 字符)")
            else ->
                ProcessSkillEngine(context).eval(
                    script = code,
                    timeoutMs = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS),
                    scopeKey = "builtin-node-script",
                    pluginConfigJson = null,
                )
        }
    }

    fun formatResultJson(result: SkillEngineResult): String = when (result) {
        is SkillEngineResult.Success -> resultJson(result.valueJson, result.consoleLogs, null)
        is SkillEngineResult.Error -> errorJson(result.message, result.consoleLogs)
    }

    /** 同步桥接（适配 ToolRegistry 的 `(Map<String,String>) -> String` 签名）。 */
    fun executeFromArgs(args: Map<String, String>, context: Context): String = runBlocking {
        execute(context, args)
    }

    private fun resultJson(result: String, logs: List<String>, error: String?): String = buildJsonObject {
        put("result", result)
        put("logs", buildJsonArray { logs.forEach { add(JsonPrimitive(it)) } })
        put("error", error?.let { JsonPrimitive(it) } ?: JsonNull)
    }.toString()

    private fun errorJson(error: String, logs: List<String> = emptyList()): String = buildJsonObject {
        put("result", "")
        put("logs", buildJsonArray { logs.forEach { add(JsonPrimitive(it)) } })
        put("error", JsonPrimitive(error))
    }.toString()
}
