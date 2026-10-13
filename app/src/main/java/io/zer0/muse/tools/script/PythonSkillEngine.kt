package io.zer0.muse.tools.script

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.runtime.PythonRuntime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.UUID

/**
 * v2.7 扩展运行时（Python）：基于内置 Python 运行时的 [SkillEngine] 实现。
 *
 * 与 [ProcessSkillEngine]（Node）同构：把脚本包一层 → 子进程执行 → 解析 stdout 的
 * 结构化结果。输出协议保持一致（单行 JSON：`{ ok, value, logs, error }`），
 * 因此上层工具、大输出落盘、超时处理等机制可以直接复用。
 *
 * ## 与 Node 版的差异
 *
 *  - 包装脚本是 Python 而非 JS（捕获 stdout、注入结果协议）；
 *  - 用 [PythonRuntime.execPython] 执行；
 *  - `host.getConfig` 桥接以 Python 函数形式注入（对齐 Node 版语义）。
 *
 * ## 输出协议
 *
 * 包装器在脚本末尾打印单行 JSON：
 * ```
 * {"ok": true, "value": <任意可 JSON 化对象>, "logs": ["..."]}
 * ```
 * 脚本异常时为 `{"ok": false, "error": "...", "logs": [...]}`。
 */
class PythonSkillEngine(private val context: Context) : SkillEngine {

    @Suppress("TooGenericExceptionCaught") // Python 子进程失败的统一兜底（超时/启动/IO）
    override suspend fun eval(
        script: String,
        timeoutMs: Long,
        scopeKey: String?,
        pluginConfigJson: String?,
    ): SkillEngineResult {
        val work = File(PythonRuntime.runtimeDir(context), "scripts").apply { mkdirs() }
        val tmp = File(work, "eval-${UUID.randomUUID()}.py")
        return try {
            tmp.writeText(buildWrapper(script, pluginConfigJson))
            val r = PythonRuntime.execPython(
                context,
                args = listOf(tmp.absolutePath),
                timeoutMs = timeoutMs,
            )
            interpret(r)
        } catch (e: Exception) {
            Logger.w(TAG, "Python 脚本执行失败: ${e.message}", e)
            SkillEngineResult.Error(message = e.message ?: "执行异常")
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /** 把子进程执行结果解释为引擎结果（拆出以控制返回语句数量）。 */
    private fun interpret(r: PythonRuntime.ExecResult): SkillEngineResult {
        val savedOutput = listOf(r.stdout, r.stderr).filter { it.isNotBlank() }.joinToString("\n")
        return when {
            r.captureError != null ->
                SkillEngineResult.Error(
                    message = "Python 完整输出保存失败: ${r.captureError}" +
                        if (savedOutput.isNotBlank()) "\n$savedOutput" else "",
                )
            r.timedOut -> SkillEngineResult.Error(message = "执行超时" + savedOutput)
            r.stdoutFile != null -> parseLargePayload(r.stdoutFile, r.stdout)
            else -> parseResult(r.stdout, r.stderr, r.exitCode, r.timedOut)
        }
    }

    override fun interrupt() {
        // 尽力而为：子进程由执行侧超时控制负责终止；此处不持有进程引用。
        Logger.d(TAG, "interrupt() 被调用（等待执行侧超时终止）")
    }

    /**
     * v2.7 插件：调用 Python 插件入口模块中定义的函数。
     *
     * 与 [SkillEngine.callFunction]（JS 语义）对应：加载入口代码到独立命名空间，
     * 取出同名函数并以参数 dict 调用。插件工具的参数 JSON（单对象）作为函数首参。
     *
     * @param entryCode 插件入口 Python 源码
     * @param functionName 入口内定义的函数名（接受一个 dict 参数）
     * @param argsJson 调用参数数组 JSON（形如 `[{"x":1}]`；取首元素作为函数参数）
     * @param scopeKey 插件 id（隔离熔断/命名空间）
     * @param pluginConfigJson 插件配置 JSON（host.getConfig 用）
     */
    @Suppress("LongParameterList") // 与 SkillEngine.callFunction 同型：入口/函数名/参数/超时/作用域/配置，接口语义固定
    suspend fun callPluginFunction(
        entryCode: String,
        functionName: String,
        argsJson: String,
        timeoutMs: Long = SkillEngine.DEFAULT_TIMEOUT_MS,
        scopeKey: String? = null,
        pluginConfigJson: String? = null,
    ): SkillEngineResult {
        val wrapper = buildPluginCallWrapper(entryCode, functionName, argsJson, pluginConfigJson)
        return eval(wrapper, timeoutMs, scopeKey, pluginConfigJson)
    }

    /**
     * 生成插件函数调用包装：加载入口 → 取函数 → 以首参调用 → 输出结果协议。
     *
     * 入口代码可能定义多个函数；函数不存在时给出可读错误（而不是静默失败）。
     */
    private fun buildPluginCallWrapper(entryCode: String, functionName: String, argsJson: String, pluginConfigJson: String?): String {
        val entryJson = JsonPrimitive(entryCode).toString()
        val fnJson = JsonPrimitive(functionName).toString()
        val argsJsonLiteral = JsonPrimitive(argsJson).toString()
        val configPy = pluginConfigJson ?: "None"
        return """
            |import json as __json
            |__muse_plugin_config = $configPy
            |__muse_entry = $entryJson
            |__muse_fn_name = $fnJson
            |__muse_args_raw = $argsJsonLiteral
            |
            |__muse_ns = {"__name__": "__muse_plugin__"}
            |exec(compile(__muse_entry, "<muse-plugin>", "exec"), __muse_ns)
            |__muse_fn = __muse_ns.get(__muse_fn_name)
            |if __muse_fn is None:
            |    raise AttributeError("插件入口未定义函数 %s" % __muse_fn_name)
            |__muse_args = __json.loads(__muse_args_raw)
            |__muse_arg = __muse_args[0] if __muse_args else {}
            |result = __muse_fn(__muse_arg)
            |
        """.trimMargin()
    }

    /**
     * 生成执行包装：
     *  - 注入 host.getConfig 桥接（对齐 Node 沙盒语义）
     *  - 捕获 stdout/stderr 输出为 logs
     *  - 执行用户脚本（顶层代码 / 表达式结果）
     *  - 输出单行 JSON 结果协议
     *
     * 包装策略：把用户脚本放进一个独立命名空间执行，取其返回值（若有）或
     * 模块级变量 `result`。脚本若自身打印 JSON 协议行，则优先采用。
     */
    private fun buildWrapper(script: String, pluginConfigJson: String?): String {
        val scriptJson = JsonPrimitive(script).toString()
        // 插件配置：JS 的 null 在 Python 里是 None。传 null 时直接给 None。
        val configPy = pluginConfigJson ?: "None"
        return """
            |# muse-python-skill-wrapper (auto-generated)
            |import sys, json, io
            |
            |__muse_plugin_config = $configPy
            |
            |class __MuseHost:
            |    def getConfig(self, key):
            |        v = (__muse_plugin_config or {}).get(key)
            |        return json.dumps(v) if v is not None else None
            |    def getPluginId(self):
            |        return None
            |
            |host = __MuseHost()
            |
            |__muse_script = $scriptJson
            |__muse_logs = []
            |
            |def __muse_emit(ok, value=None, error=None):
            |    payload = {"ok": ok, "value": value, "logs": __muse_logs}
            |    if error is not None:
            |        payload["error"] = error
            |    sys.stdout.write(json.dumps(payload, ensure_ascii=False))
            |    sys.stdout.flush()
            |
            |try:
            |    __muse_ns = {"host": host, "__name__": "__muse_script__", "logs": __muse_logs}
            |    __muse_buf = io.StringIO()
            |    __muse_old_stdout = sys.stdout
            |    sys.stdout = __muse_buf
            |    try:
            |        exec(compile(__muse_script, "<muse-script>", "exec"), __muse_ns)
            |    finally:
            |        sys.stdout = __muse_old_stdout
            |        __muse_printed = __muse_buf.getvalue()
            |        if __muse_printed:
            |            __muse_logs.append(__muse_printed.rstrip("\n"))
            |    __muse_value = __muse_ns.get("result", None)
            |    try:
            |        json.dumps(__muse_value, ensure_ascii=False)
            |    except (TypeError, ValueError):
            |        __muse_value = str(__muse_value)
            |    __muse_emit(True, __muse_value)
            |except SystemExit as e:
            |    __muse_emit(True, None if e.code is None else e.code)
            |except BaseException as e:
            |    __muse_emit(False, None, "%s: %s" % (type(e).__name__, e))
        """.trimMargin()
    }

    private fun parseResult(stdout: String, stderr: String, exitCode: Int, timedOut: Boolean): SkillEngineResult {
        if (timedOut) {
            return SkillEngineResult.Error(message = "执行超时")
        }
        val line = stdout.lines().lastOrNull { it.trim().startsWith("{") }?.trim()
        return if (line == null) {
            SkillEngineResult.Error(
                message = "无有效输出(exit=$exitCode)" +
                    if (stderr.isNotBlank()) ": ${stderr.take(300)}" else "",
            )
        } else {
            parseLine(line)
        }
    }

    /** 解析包装协议输出行（{ ok, value, logs, error }）。 */
    private fun parseLine(line: String): SkillEngineResult = runCatching {
        val obj = AppJson.parseToJsonElement(line) as? JsonObject
        if (obj == null) {
            SkillEngineResult.Error(message = "输出解析失败: ${line.take(200)}")
        } else {
            val logs = (obj["logs"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            val ok = (obj["ok"] as? JsonPrimitive)?.content?.toBoolean() == true
            if (ok) {
                SkillEngineResult.Success(
                    valueJson = obj["value"]?.toString() ?: "null",
                    consoleLogs = logs,
                )
            } else {
                SkillEngineResult.Error(
                    message = (obj["error"] as? JsonPrimitive)?.contentOrNull ?: "未知错误",
                    consoleLogs = logs,
                )
            }
        }
    }.getOrElse { e ->
        SkillEngineResult.Error(message = "结果解析异常: ${e.message}")
    }

    companion object {
        private const val TAG = "PythonSkillEngine"
    }
}

/**
 * 大输出场景：Python 结果协议行已落到输出文件，此处只读取 `ok` 状态，
 * 避免把超大 value 全量载入内存。
 */
internal fun parseLargePayload(file: File, outputReference: String): SkillEngineResult {
    val succeeded =
        runCatching {
            var success: Boolean? = null
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                val protocolLine = lines.lastOrNull { it.trim().startsWith("{") }
                    ?: error("Python 输出缺少协议行")
                val obj = AppJson.parseToJsonElement(protocolLine.trim()) as? JsonObject
                success = (obj?.get("ok") as? JsonPrimitive)?.content?.toBoolean()
            }
            success ?: error("Python 输出缺少 ok 状态")
        }.getOrNull()

    return when (succeeded) {
        true ->
            SkillEngineResult.Success(
                valueJson = JsonPrimitive(outputReference).toString(),
                consoleLogs = emptyList(),
            )
        false ->
            SkillEngineResult.Error(
                message = "Python 脚本执行失败；完整输出可通过 read_file 分段读取:\n$outputReference",
            )
        null ->
            SkillEngineResult.Error(
                message = "Python 输出解析失败；原始输出仍已完整保存:\n$outputReference",
            )
    }
}
