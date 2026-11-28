package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.computenode.ComputeTarget
import io.zer0.muse.computenode.ComputeTargetRouter
import io.zer0.muse.computenode.ExecKind
import io.zer0.muse.computenode.ExecRequest
import io.zer0.muse.computenode.MuseNodeException
import io.zer0.muse.computenode.RemoteComputeRuntime
import io.zer0.muse.computenode.TaskCategory
import io.zer0.muse.runtime.PythonRuntime
import io.zer0.muse.runtime.RuntimeBridge
import io.zer0.muse.runtime.RuntimeMissing
import io.zer0.muse.tools.script.PythonSkillEngine
import io.zer0.muse.tools.script.SkillEngineResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Python 脚本执行工具（execute_python_script）。
 *
 * 通过 proot 在已安装的 Ubuntu rootfs 中运行 Python 3，并把应用 filesDir 挂载到 guest。
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
        description = "在已安装的 Ubuntu 运行环境中执行 Python 3 脚本(标准库、文件读写与网络)。" +
            "target=auto 时有在线云端电脑优先在云端执行，local 走本机 proot，cloud 指定云端。" +
            "把要返回的结果赋给变量 result。返回 JSON(result/logs/error)。超时默认 30 秒。" +
            "应用 filesDir 会映射到 guest 的 /muse-files;脚本内需要起子进程时使用 " +
            "sys.executable 或 os.environ['MUSE_PYTHON_BIN']。",
        parameters = mapOf(
            "code" to "Python 脚本代码(可 import 标准库;结果赋给变量 result)",
            "timeout_ms" to "超时毫秒数,默认 30000,最大 300000",
            "target" to "执行位置:auto(默认,有在线云端电脑时优先云端)/local(本机 proot)/cloud(指定云端电脑)",
        ),
        required = setOf("code"),
        category = "built-in",
        parameterTypes = mapOf("timeout_ms" to "integer"),
        riskLevel = ToolRiskLevel.HIGH,
    )

    suspend fun execute(
        context: Context,
        args: Map<String, String>,
        remoteRuntime: RemoteComputeRuntime? = null,
        onRemoteExecStarted: (suspend (nodeId: String, remoteRunId: String) -> Unit)? = null,
    ): String {
        val code = args["code"].orEmpty()
        val timeoutMs = args["timeout_ms"]?.toLongOrNull()?.coerceIn(1L, MAX_TIMEOUT_MS) ?: DEFAULT_TIMEOUT_MS
        if (code.isBlank()) return errorJson("参数 code 缺失或为空")
        if (code.length > MAX_SCRIPT_CHARS) return errorJson("脚本超过最大长度($MAX_SCRIPT_CHARS 字符)")
        val requested = args["target"].orEmpty().trim().ifBlank { ComputeTargetRouter.TARGET_AUTO }
        if (requested.equals(ComputeTargetRouter.TARGET_CLOUD, ignoreCase = true) && remoteRuntime == null) {
            return errorJson("云端电脑未配置")
        }
        val computeTarget = try {
            remoteRuntime?.resolve(requested, TaskCategory.PYTHON) ?: ComputeTarget.LocalProot
        } catch (e: CancellationException) {
            throw e
        } catch (e: MuseNodeException) {
            Logger.w("PythonScriptTool", "解析算力目标失败: ${e.message}", e)
            return errorJson("云端电脑不可用: ${e.message}")
        } catch (e: RuntimeException) {
            Logger.w("PythonScriptTool", "解析算力目标失败: ${e.message}", e)
            return errorJson("云端电脑不可用: ${e.message}")
        }
        if (computeTarget is ComputeTarget.RemoteNode) {
            val runtime = remoteRuntime ?: return errorJson("云端电脑未配置")
            return executeRemote(code, timeoutMs, computeTarget, runtime, onRemoteExecStarted)
        }
        return when {
            !RuntimeBridge.isReady(context) -> RuntimeMissing.toJson()
            else -> formatResultJson(evaluate(context, code, timeoutMs))
        }
    }

    /** 在云端节点执行同一段脚本，把输出映射为统一的 result/logs/error JSON。 */
    private suspend fun executeRemote(
        code: String,
        timeoutMs: Long,
        target: ComputeTarget.RemoteNode,
        runtime: RemoteComputeRuntime,
        onRemoteExecStarted: (suspend (nodeId: String, remoteRunId: String) -> Unit)?,
    ): String {
        val outcome = try {
            runtime.execute(
                target = target,
                request = ExecRequest(
                    kind = ExecKind.PYTHON,
                    code = code,
                    cwd = "~/muse-workspace",
                    timeoutSec = (timeoutMs / 1000).coerceIn(1L, 3600L).toInt(),
                ),
                onRemoteExecStarted = onRemoteExecStarted,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: MuseNodeException) {
            Logger.w("PythonScriptTool", "云端执行失败: ${e.message}", e)
            return errorJson("云端执行失败: ${e.message}")
        } catch (e: RuntimeException) {
            Logger.w("PythonScriptTool", "云端执行失败: ${e.message}", e)
            return errorJson("云端执行失败: ${e.message}")
        }
        val logs = outcome.stdout.lines().filter { it.isNotBlank() }
        val error = if (outcome.exitCode == 0) {
            null
        } else {
            outcome.stderr.trim().ifBlank { "exit code ${outcome.exitCode}" }
        }
        return resultJson(outcome.stdout.trimEnd(), logs, error)
    }

    /** 共享执行路径（供直接脚本工具与手机 Agent 工作流复用）。 */
    suspend fun evaluate(context: Context, code: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): SkillEngineResult {
        return when {
            code.isBlank() -> SkillEngineResult.Error("参数 code 缺失或为空")
            code.length > MAX_SCRIPT_CHARS -> SkillEngineResult.Error("脚本超过最大长度($MAX_SCRIPT_CHARS 字符)")
            !RuntimeBridge.isReady(context) -> SkillEngineResult.Error(RuntimeMissing.toJson())
            !PythonRuntime.isReady(context) -> SkillEngineResult.Error("已安装的运行环境没有 Ubuntu Python 3")
            else -> {
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
    private val remoteRuntime: RemoteComputeRuntime? = null,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.registerOutcomeWithContext(PythonScriptTool.toolDef()) { args, executionContext ->
            ToolOutcome.ok(PythonScriptTool.execute(context, args, remoteRuntime, executionContext?.onRemoteExecStarted))
        }
    }
}
