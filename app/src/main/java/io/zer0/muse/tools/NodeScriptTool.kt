package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.computenode.ComputeTarget
import io.zer0.muse.computenode.ComputeTargetRouter
import io.zer0.muse.computenode.ExecKind
import io.zer0.muse.computenode.ExecRequest
import io.zer0.muse.computenode.RemoteComputeRuntime
import io.zer0.muse.computenode.TaskCategory
import io.zer0.muse.runtime.NodeLauncher
import io.zer0.muse.runtime.RuntimeMissing
import io.zer0.muse.tools.script.ProcessSkillEngine
import io.zer0.muse.tools.script.SkillEngineResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P1-C: Node 脚本执行工具（execute_node_script）。
 *
 * 在 Linux 沙盒运行时（按需安装）的 Node.js 环境中执行 JavaScript 脚本：完整能力（文件读写 / 网络 / 子进程），
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
        description = "在 Linux 沙盒运行时（按需安装）的 Node.js 环境中执行 JavaScript 脚本(完整能力:文件读写、网络、子进程;" +
            "可 require 第三方 npm 模块)。target=auto 时有在线云端电脑优先在云端执行，local 走本机，cloud 指定云端。" +
            "返回 JSON(result/logs/error)。超时默认 30 秒。",
        parameters = mapOf(
            "code" to "Node 脚本代码(表达式或语句块;可用 require/fs/fetch 等)",
            "timeout_ms" to "超时毫秒数,默认 30000,最大 300000",
            "target" to "执行位置:auto(默认,有在线云端电脑时优先云端)/local(本机 proot)/cloud(指定云端电脑)",
        ),
        required = setOf("code"),
        category = "built-in",
        parameterTypes = mapOf("timeout_ms" to "integer"),
        riskLevel = ToolRiskLevel.HIGH,
    )

    suspend fun execute(context: Context, args: Map<String, String>): String {
        val code = args["code"].orEmpty()
        val timeoutMs = args["timeout_ms"]?.toLongOrNull()?.coerceIn(1L, MAX_TIMEOUT_MS) ?: DEFAULT_TIMEOUT_MS
        if (code.isBlank()) return errorJson("参数 code 缺失或为空")
        if (code.length > MAX_SCRIPT_CHARS) return errorJson("脚本超过最大长度($MAX_SCRIPT_CHARS 字符)")
        val requested = args["target"].orEmpty().trim().ifBlank { ComputeTargetRouter.TARGET_AUTO }
        val remoteRuntime = remoteRuntimeOrNull()
        val computeTarget = try {
            remoteRuntime?.resolve(requested, TaskCategory.NODE)
                ?: if (requested == ComputeTargetRouter.TARGET_CLOUD) {
                    return errorJson("云端电脑未配置")
                } else {
                    ComputeTarget.LocalProot
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("NodeScriptTool", "解析算力目标失败: ${e.message}", e)
            return errorJson("云端电脑不可用: ${e.message}")
        }
        if (computeTarget is ComputeTarget.RemoteNode) {
            val runtime = remoteRuntime ?: return errorJson("云端电脑未配置")
            return executeRemote(code, timeoutMs, computeTarget, runtime)
        }
        return when {
            !NodeLauncher.isAvailable(context) -> RuntimeMissing.toJson()
            else -> formatResultJson(evaluate(context, code, timeoutMs))
        }
    }

    /** 在云端节点执行同一段脚本，把输出映射为统一的 result/logs/error JSON。 */
    private suspend fun executeRemote(
        code: String,
        timeoutMs: Long,
        target: ComputeTarget.RemoteNode,
        runtime: RemoteComputeRuntime,
    ): String {
        val outcome = try {
            runtime.execute(
                target = target,
                request = ExecRequest(
                    kind = ExecKind.NODE,
                    code = code,
                    cwd = "~/muse-workspace",
                    timeoutSec = (timeoutMs / 1000).coerceIn(1L, 3600L).toInt(),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("NodeScriptTool", "云端执行失败: ${e.message}", e)
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

    /**
     * 通过应用级依赖图取云端执行入口。
     *
     * 该工具在 ToolRegistry 初始化时就已注册，无法经构造参数注入；运行时按需解析单例，
     * 未启动 Koin 的纯单测路径返回 null 并自然回退本机。
     */
    private fun remoteRuntimeOrNull(): RemoteComputeRuntime? =
        runCatching { org.koin.core.context.GlobalContext.getOrNull()?.get<RemoteComputeRuntime>() }
            .onFailure { Logger.w("NodeScriptTool", "远程运行入口解析失败: ${it.message}") }
            .getOrNull()

    /** Shared execution path for the direct script tool and durable phone-agent workflows. */
    suspend fun evaluate(context: Context, code: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): SkillEngineResult {
        return when {
            code.isBlank() -> SkillEngineResult.Error("参数 code 缺失或为空")
            code.length > MAX_SCRIPT_CHARS -> SkillEngineResult.Error("脚本超过最大长度($MAX_SCRIPT_CHARS 字符)")
            !NodeLauncher.isAvailable(context) -> SkillEngineResult.Error(RuntimeMissing.toJson())
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
