package io.zer0.muse.tools.script

import android.content.Context
import android.util.JsonReader
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.runtime.MuseRuntime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.UUID

/**
 * P1-C: 基于内置 Node 运行时的 [SkillEngine] 实现。
 *
 * 与 [WebViewSkillEngine] 的区别：
 *  - WebView 版：轻量沙盒（无文件系统 / 无 Node API；网络已放开），适合纯计算；
 *  - 本实现：真实 Node.js 环境（完整能力：fs / 网络 / child_process / npm 模块），
 *    面向「需要真本事」的脚本任务（技能脚本接入 node 的运行时基础）。
 *
 * 实现方式：把脚本包装为临时 JS 文件 → 内置 node 执行 → 解析 stdout 的结构化结果。
 * 输出协议（单行 JSON）：{ ok: boolean, value: unknown, logs: string[], error?: string }
 * 语义与 JsSandbox 的结果对齐（valueJson + consoleLogs）。
 */
class ProcessSkillEngine(private val context: Context) : SkillEngine {

    @Suppress("TooGenericExceptionCaught") // Node 子进程失败的统一兜底（超时/启动/IO）
    override suspend fun eval(script: String, timeoutMs: Long, scopeKey: String?, pluginConfigJson: String?): SkillEngineResult {
        val work = File(MuseRuntime.runtimeDir(context), "scripts").apply { mkdirs() }
        val tmp = File(work, "eval-${UUID.randomUUID()}.js")
        return try {
            tmp.writeText(buildWrapper(script, pluginConfigJson))
            val r = MuseRuntime.execNode(
                context,
                args = listOf(tmp.absolutePath),
                timeoutMs = timeoutMs,
            )
            if (r.captureError != null) {
                val savedOutput = listOf(r.stdout, r.stderr).filter { it.isNotBlank() }.joinToString("\n")
                return SkillEngineResult.Error(
                    message = "Node 完整输出保存失败: ${r.captureError}" +
                        if (savedOutput.isNotBlank()) "\n$savedOutput" else "",
                )
            }
            if (r.timedOut) {
                return SkillEngineResult.Error(
                    message = "执行超时" + listOf(r.stdout, r.stderr).filter { it.isNotBlank() }.joinToString("\n"),
                )
            }
            r.stdoutFile?.let { return parseLargeNodePayload(it, r.stdout) }
            parseResult(r.stdout, r.stderr, r.exitCode, r.timedOut)
        } catch (e: Exception) {
            Logger.w(TAG, "Node 脚本执行失败: ${e.message}", e)
            SkillEngineResult.Error(message = e.message ?: "执行异常")
        } finally {
            runCatching { tmp.delete() }
        }
    }

    override fun interrupt() {
        // 尽力而为：Node 子进程由执行侧超时控制负责终止；此处不持有进程引用。
        Logger.d(TAG, "interrupt() 被调用（等待执行侧超时终止）")
    }

    /**
     * 生成执行包装：
     *  - 脚本入口装 CPU 兜底（[NodeCpuShim]）：原生 os.cpus() 为空时按 MUSE_CPU_COUNT 合成
     *  - 注入 host.getConfig 桥接（对齐 WebView 沙盒语义）
     *  - 捕获 console.* 日志
     *  - eval 执行用户脚本（Promise 自动 await）
     *  - 输出单行 JSON 结果协议
     */
    private fun buildWrapper(script: String, pluginConfigJson: String?): String {
        val scriptJson = JsonPrimitive(script).toString()
        val configJson = pluginConfigJson ?: "null"
        val nodeModules = File(MuseRuntime.runtimeDir(context), "node_modules").absolutePath
        val body =
            """
            |// muse-node-skill-wrapper (auto-generated)
            |globalThis.__musePluginConfig = $configJson;
            |if (typeof globalThis.host === 'undefined') {
            |  globalThis.host = {
            |    getConfig: function(key) {
            |      var v = globalThis.__musePluginConfig && globalThis.__musePluginConfig[key];
            |      return v !== undefined ? JSON.stringify(v) : null;
            |    },
            |    getPluginId: function() { return null; }
            |  };
            |}
            |module.paths.push(${JsonPrimitive(nodeModules)});
            |(async function() {
            |  var __logs = [];
            |  var __fmt = function(a) {
            |    try { return typeof a === 'string' ? a : JSON.stringify(a); } catch (e) { return String(a); }
            |  };
            |  ['log','warn','error','info'].forEach(function(k) {
            |    console[k] = function() {
            |      __logs.push('[' + k + '] ' + Array.prototype.map.call(arguments, __fmt).join(' '));
            |    };
            |  });
            |  var __out;
            |  try {
            |    __out = eval($scriptJson);
            |    if (__out && typeof __out.then === 'function') { __out = await __out; }
            |    var __payload = { ok: true, value: null, logs: __logs };
            |    try { __payload.value = (__out === undefined) ? null : JSON.parse(JSON.stringify(__out)); }
            |    catch (e) { __payload.value = String(__out); }
            |    process.stdout.write(JSON.stringify(__payload));
            |  } catch (e) {
            |    process.stdout.write(JSON.stringify({ ok: false, error: String((e && e.message) || e), logs: __logs }));
            |  }
            |})();
        """.trimMargin()
        // 脚本第一句先装 CPU 兜底：原生 os.cpus() 为空时用 MUSE_CPU_COUNT 合成，
        // 否则依赖核数的 npm 包在部分设备上会按 0/1 核运行。正常脚本作用域与返回值不受影响。
        return NodeCpuShim.wrap(body)
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
        private const val TAG = "ProcessSkillEngine"
    }
}

internal fun parseLargeNodePayload(file: File, outputReference: String): SkillEngineResult {
    val succeeded =
        runCatching {
            var success: Boolean? = null
            JsonReader(file.reader(Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "ok" -> success = reader.nextBoolean()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
            success ?: error("Node 输出缺少 ok 状态")
        }.getOrNull()

    return when (succeeded) {
        true ->
            SkillEngineResult.Success(
                valueJson = JsonPrimitive(outputReference).toString(),
                consoleLogs = emptyList(),
            )
        false ->
            SkillEngineResult.Error(
                message = "Node 脚本执行失败；完整输出可通过 read_file 分段读取:\n$outputReference",
            )
        null ->
            SkillEngineResult.Error(
                message = "Node 输出解析失败；原始输出仍已完整保存:\n$outputReference",
            )
    }
}
