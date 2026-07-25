package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 代码执行工具(execute_javascript)。
 *
 * 让 AI Agent 能在沙盒中执行 JavaScript 代码:数学计算、数据处理、简单算法等,
 * 扩展 Agent 能力。底层委托 [JsSandbox](WebView evaluateJavascript),不新增依赖。
 *
 * 输入参数:
 *  - code: 必填,JS 代码字符串
 *  - timeout_ms: 可选,超时毫秒数,默认 10000(10 秒)
 *
 * 返回结构(JSON):
 *  - result: 执行返回值(JSON 字符串形式)
 *  - logs: console.log/warn/error 收集到的日志数组
 *  - error: 错误信息(超时/JS 异常);执行成功时为 null
 *
 * 桥接说明:
 *  - [execute] 是 suspend 函数(任务要求接口),供 SkillExecutor 等协程调用方直接调用
 *  - [executeFromArgs] 是同步函数,适配 ToolRegistry 的 ToolFn 签名 `(Map<String,String>) -> String`,
 *    内部用 runBlocking 桥接到 suspend — 因 ToolRegistry.execute 被
 *    GenerationHandler.executeTool(suspend)在 IO 协程中调用,阻塞 IO 线程
 *    等待主线程 WebView 回调,主线程未被阻塞,无死锁风险
 */
object CodeExecutionTool {

    /** 工具名(注册到 ToolRegistry 的 id)。 */
    const val TOOL_NAME = "execute_javascript"

    /** 默认超时毫秒数。 */
    private const val DEFAULT_TIMEOUT_MS = 10000L

    /**
     * 工具定义(注册到 ToolRegistry)。
     */
    fun toolDef() = ToolRegistry.ToolDef(
        name = TOOL_NAME,
        description = "执行 JavaScript 代码并返回结果。可访问 Math/JSON/Array 等标准库,不可访问网络。超时 10 秒。",
        parameters = mapOf(
            "code" to "必填,要执行的 JavaScript 代码(如 '1+2*3' / 'Math.max(1,2,3)' / 'JSON.stringify([1,2,3])')",
            "timeout_ms" to "可选,超时毫秒数,默认 10000(10 秒)",
        ),
        required = setOf("code"),
        category = "built-in",
        parameterTypes = mapOf("timeout_ms" to "integer"),
        riskLevel = ToolRiskLevel.HIGH,
    )

    /**
     * 执行 JavaScript 代码(suspend 接口,供 SkillExecutor 等协程调用)。
     *
     * @param input 包含 code / timeout_ms 的 JSON 对象
     * @return JSON 对象,包含 result / logs / error 字段
     */
    suspend fun execute(input: JsonObject): JsonObject {
        // 解析必填 code 参数
        val codeElement = input["code"]
        val code = (codeElement as? JsonPrimitive)?.content
            ?: return buildResult(
                result = null,
                logs = emptyList(),
                error = "参数 code 缺失或非字符串",
            )
        if (code.isBlank()) {
            return buildResult(
                result = null,
                logs = emptyList(),
                error = "参数 code 不能为空",
            )
        }

        // 解析可选 timeout_ms 参数
        val timeoutMs = (input["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: DEFAULT_TIMEOUT_MS
        if (timeoutMs <= 0 || timeoutMs > 60_000L) {
            return buildResult(
                result = null,
                logs = emptyList(),
                error = "参数 timeout_ms 须在 1..60000 范围内",
            )
        }

        // 调用 JsSandbox 执行
        val result = JsSandbox.execute(code, timeoutMs)
        return result.fold(
            onSuccess = { jsResult ->
                buildResult(
                    result = jsResult.value,
                    logs = jsResult.consoleLogs,
                    error = jsResult.error,
                )
            },
            onFailure = { e ->
                Logger.e("CodeExecutionTool", "JsSandbox 执行失败: ${e.message}", e)
                buildResult(
                    result = null,
                    logs = emptyList(),
                    error = e.message ?: "未知执行错误",
                )
            },
        )
    }

    /**
     * 同步桥接:适配 ToolRegistry 的 ToolFn 签名。
     *
     * 内部用 [runBlocking] 调用 suspend [execute]:
     *  - ToolRegistry.execute 由 GenerationHandler.executeTool(suspend)在 IO 协程中调用,
     *    当前线程为 IO 线程,阻塞等待主线程 WebView 回调时主线程空闲,无死锁风险
     *  - 必须先调 [JsSandbox.init] 注入 Application Context(幂等)
     *
     * @param args 参数 map(code / timeout_ms)
     * @param context 用于初始化 JsSandbox
     * @return JSON 字符串,结构同 [execute] 的 JsonObject 序列化结果
     */
    fun executeFromArgs(args: Map<String, String>, context: Context): String {
        JsSandbox.init(context)
        // 把 Map<String, String> 转为 JsonObject 后委托给 suspend execute
        val input = buildJsonObject {
            args["code"]?.let { put("code", it) }
            args["timeout_ms"]?.let { put("timeout_ms", it) }
        }
        return runBlocking {
            val output = execute(input)
            output.toString()
        }
    }

    /**
     * 构造执行结果 JsonObject。
     *
     * 字段约定(任务要求):
     *  - result: string — 执行返回值(JSON 字符串形式,null 表示无返回值)
     *  - logs: array — console 日志数组,每项为字符串
     *  - error: string? — 错误信息,执行成功时为 null
     */
    private fun buildResult(
        result: Any?,
        logs: List<String>,
        error: String?,
    ): JsonObject = buildJsonObject {
        // result 始终为 string(任务要求);无返回值时用空字符串
        val resultStr = when (result) {
            null -> ""
            is String -> result
            else -> result.toString()
        }
        put("result", resultStr)
        // logs 数组
        put("logs", buildJsonArray {
            logs.forEach { add(JsonPrimitive(it)) }
        })
        // error 可为 null
        if (error != null) {
            put("error", error)
        } else {
            put("error", JsonNull)
        }
    }
}
