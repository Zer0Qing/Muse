package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.runtime.PythonRuntime
import io.zer0.muse.tools.script.PythonSkillEngine
import io.zer0.muse.tools.script.SkillEngineResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * v2.7 扩展运行时（Python）：Python 脚本执行工具（execute_python_script）。
 *
 * 在内置 Python 运行时中执行 Python 脚本：完整标准库（含 ssl/sqlite3/ctypes 等
 * C 扩展）、文件读写、网络。可 `import` 运行时 site-packages 区
 * （filesDir/muse-python/lib/python3.14/site-packages）中的第三方纯 Python 包。
 *
 * 与 [NodeScriptTool]（execute_node_script）同构：高风险工具，ASK / STRICT 模式
 * 要求审批；TRUSTED 模式依用户明确选择免确认。
 *
 * 返回结构（JSON，与 execute_node_script / execute_javascript 对齐）：
 *  - result: string — 脚本 `result` 变量的 JSON 字符串形式
 *  - logs: array — print 输出行数组
 *  - error: string | null
 */
object PythonScriptTool {

    const val TOOL_NAME = "execute_python_script"

    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val MAX_TIMEOUT_MS = 300_000L
    const val MAX_SCRIPT_CHARS = 128_000

    fun toolDef() = ToolRegistry.ToolDef(
        name = TOOL_NAME,
        description = "在内置 Python 3.14 运行时中执行 Python 脚本(完整标准库:ssl/sqlite3/" +
            "ctypes/json/re 等;文件读写、网络;可 import 第三方纯 Python 包)。" +
            "把要返回的结果赋给变量 result。返回 JSON(result/logs/error)。超时默认 30 秒。" +
            "这是运行 Python 的唯一入口:内置运行时没有 python/python3 命令,别用 " +
            "terminal_exec/execute_shell 去找解释器或翻 bin 目录(那里是空的);" +
            "脚本内需要起子进程时用 sys.executable 或 os.environ['MUSE_PYTHON_BIN']。",
        parameters = mapOf(
            "code" to "必填,Python 脚本代码(可 import 标准库;结果赋给变量 result)",
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

    /** 共享执行路径（供直接脚本工具与手机 Agent 工作流复用）。 */
    suspend fun evaluate(context: Context, code: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): SkillEngineResult {
        return when {
            code.isBlank() -> SkillEngineResult.Error("参数 code 缺失或为空")
            code.length > MAX_SCRIPT_CHARS -> SkillEngineResult.Error("脚本超过最大长度($MAX_SCRIPT_CHARS 字符)")
            else -> {
                // 确保运行时数据（stdlib）已解压；首次调用会解压一次。
                PythonRuntime.ensureData(context).getOrElse { e ->
                    return SkillEngineResult.Error("Python 运行时数据未就绪: ${e.message}")
                }
                PythonSkillEngine(context).eval(
                    script = code,
                    timeoutMs = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS),
                    scopeKey = "builtin-python-script",
                    pluginConfigJson = null,
                )
            }
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

/**
 * v2.7 扩展运行时（Python）：Python 脚本执行工具注册器。
 */
class PythonScriptToolRegistrar(
    private val toolRegistry: ToolRegistry,
    private val context: Context,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(PythonScriptTool.toolDef()) { args ->
            PythonScriptTool.executeFromArgs(args, context)
        }
    }
}
