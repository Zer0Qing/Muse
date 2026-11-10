package io.zer0.muse.tools.script

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.runtime.MuseRuntime
import io.zer0.muse.runtime.classifyProcessExit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 插件 JS 的**子进程运行时**（P0-6 / P0-7-1）：内置 Node + `vm` 无网络上下文 + **多轮桥接**。
 *
 * ## 为什么 WebView 不够，必须换进程
 *
 * P0-4 真机量到：同一 App 内所有 WebView **共用一个渲染进程**，一个 `while(true){}` 在
 * `stopLoading() + destroy()` 之后仍在饿别人（新建实例 12,001ms 未返回、预热用例直接卡死，
 * 见 `PERF_LEDGER.md` §12.8）。Android 16 SDK 里也没有 `WebView.createWorkspace`
 * （`android/webkit/` 只有给跨域 iframe 用的 `WebViewRenderProcess`）。
 * Node 档实测：同伴插件 **458ms** 拿到结果，跑飞脚本被 `vm` 的 timeout 自行打断。
 *
 * ## 桥接为什么改成多轮
 *
 * 旧语义是"插件 `return` 一个 `__bridge__` 哨兵，宿主执行后当作工具结果" ——
 * **一次工具调用只能做一次外部动作**。这是 WebView 单程返回值留下的结构，不是 Node 的限制。
 * 现在宿主与子进程走行协议：插件调 `muse.bridge()` → 子进程往 stdout 写一条 request 并
 * **同步阻塞**读 stdin → 宿主查账本执行后写回 reply → 插件拿到真实对象继续算。
 *
 * ## 边界（不粉饰）
 *
 *  - `vm` 上下文里**没有** `require` / `process` / `fs` / `fetch` / `XMLHttpRequest`：
 *    不是"拦住了"，是**不注入就没有**。能力唯一出口是 `muse.bridge()`，由能力账本判定。
 *  - **注入了 `setTimeout` / `setInterval` / `fetch`**，但语义与浏览器不完全一样：
 *    定时器回调要等 `vm` 交回控制权才会跑（纯同步阶段里排的定时器不会立即触发）；
 *    `fetch` 底层是**同步桥接往返**，因此 `network` 能力未授予时它会抛异常，
 *    而不是"绕过沙盒偷偷发请求"。响应对象只实现插件真正会用的那几个成员。
 *  - 一次执行的桥接次数有上限（[MAX_BRIDGE_CALLS]），防止插件与宿主互相打转。
 *  - `vm` 的 timeout 只管**同步**执行；异步 promise 里的忙等靠进程超时被杀。
 */
class NodePluginEngine(private val context: Context) {

    /**
     * @param bridgeHandler 收到一条桥接请求（`{"action":...,"params":{...}}`）时由调用方执行，
     *   返回 [SkillBridge.HandleResult]：`Output` 成功、`Failure` 会让插件那侧抛出异常。
     *   调用方必须在这里做能力判定 —— 引擎本身不知道插件被授予了什么。
     */
    @Suppress("TooGenericExceptionCaught") // 子进程启动/管道/解析失败的统一兜底
    suspend fun callPluginFunction(
        entryCode: String,
        functionName: String,
        argsJson: String,
        timeoutMs: Long = SkillEngine.DEFAULT_TIMEOUT_MS,
        scopeKey: String? = null,
        pluginConfigJson: String? = null,
        bridgeHandler: suspend (String) -> SkillBridge.HandleResult = {
            SkillBridge.HandleResult.Failure("桥接不可用（宿主未接入能力总线）")
        },
    ): SkillEngineResult {
        if (!tryEnterSlot()) {
            // 旧实现靠全局锁顺带限流；池化/子进程化之后那层保护没了，必须显式补回来：
            // 助手一次并行调 5 个插件工具是常规场景，不限并发就是 5 个 Node 进程同时吃内存。
            // 这里选"快速失败"而不是排队：排队会让调用方以为还在跑，超时报错更难归因。
            return SkillEngineResult.Error(
                message = "插件并发已满（同时最多 $MAX_CONCURRENT_PROCESSES 个 Node 执行），请稍后重试；" +
                    "内置 JS 工具与其他插件不受影响，它们不占这个额度",
            )
        }
        val work = File(MuseRuntime.runtimeDir(context), PLUGIN_WORK_DIR)
        val tmp = File(work, "plugin-${UUID.randomUUID()}.js")
        val errFile = File(work, tmp.name + ".err")
        return try {
            if (!MuseRuntime.isReady(context)) {
                // 这个检查必须在 try 里面：放在外面会从 finally 旁边绕过去，
                // 永久吃掉一个并发槽位 —— 运行时没装好的设备上，两次调用就把插件全锁死了。
                SkillEngineResult.Error(
                    message = "内置 Node 运行时未就绪（设置 → 工具与扩展 → 扩展运行时）；" +
                        "Node 档插件暂时无法执行，请安装运行时数据或改用 js/python 档",
                )
            } else {
                work.mkdirs()
                tmp.writeText(buildWrapper(entryCode, functionName, argsJson, scopeKey, pluginConfigJson, timeoutMs))
                runPump(tmp, errFile, timeoutMs, bridgeHandler)
            }
        } catch (e: Exception) {
            // 子进程启动/管道/解析失败的统一兜底：任何异常都得变成可读的 Error，
            // 不能把堆栈甩给模型 —— 与 ProcessSkillEngine 同一口径。
            Logger.w(TAG, "Node 插件执行失败: ${e.message}", e)
            SkillEngineResult.Error(message = e.message ?: "Node 插件执行异常")
        } finally {
            exitSlot()
            runCatching { tmp.delete() }
            runCatching { errFile.delete() }
        }
    }

    /**
     * 抢一个执行槽位。测试可以直接调这两个函数（不需要真的起 Node），
     * 因为"满了要快速失败"本身就是被测行为。
     */
    internal suspend fun tryEnterSlot(): Boolean = slots.tryAcquire()

    internal fun exitSlot() {
        if (slots.availablePermits() < MAX_CONCURRENT_PROCESSES) slots.release()
    }

    private suspend fun runPump(
        script: File,
        errFile: File,
        timeoutMs: Long,
        bridgeHandler: suspend (String) -> SkillBridge.HandleResult,
    ): SkillEngineResult = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(
            listOf(
                MuseRuntime.nodeBinary(context).absolutePath,
                "--max-old-space-size=$MAX_HEAP_MB",
                script.absolutePath,
            ),
        ).apply {
            redirectError(errFile)
            environment().clear()
            environment().putAll(MuseRuntime.buildEnv(context))
        }
        val process = runCatching { builder.start() }
            .getOrElse { return@withContext SkillEngineResult.Error("Node 子进程启动失败: ${it.message}") }
        val outcome = try {
            withTimeoutOrNull(timeoutMs + PROCESS_GRACE_MS) {
                readLoop(
                    BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)),
                    BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8)),
                    bridgeHandler,
                )
            } ?: LoopOutcome(resultLine = null, timedOut = true)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(GRACE_AFTER_KILL_MS, TimeUnit.MILLISECONDS)
            }
        }
        val stderr = runCatching { errFile.readText() }.getOrDefault("")
        when {
            outcome.resultLine != null -> interpret(
                stdout = outcome.resultLine,
                stderr = stderr,
                exitCode = exitCodeOf(process),
                timedOut = false,
            )
            outcome.timedOut -> interpret(stdout = "", stderr = stderr, exitCode = -1, timedOut = true)
            else -> interpret(stdout = "", stderr = stderr, exitCode = exitCodeOf(process), timedOut = false)
        }
    }

    /**
     * 行协议主循环：读一行 → 是 request 就执行并回写，是 result 就收尾。
     *
     * 整个循环跑在 [Dispatchers.IO]（[BufferedReader.readLine] 是阻塞调用），
     * 桥接本身是挂起函数，在同一个 IO 块里直接 await 是安全的。
     */
    private suspend fun readLoop(
        reader: BufferedReader,
        writer: BufferedWriter,
        bridgeHandler: suspend (String) -> SkillBridge.HandleResult,
    ): LoopOutcome {
        var bridgeCalls = 0
        while (true) {
            val line = reader.readLine() ?: return LoopOutcome(resultLine = null, streamEnded = true)
            val request = parseBridgeRequest(line)
            if (request != null) {
                bridgeCalls++
                val reply = if (bridgeCalls > MAX_BRIDGE_CALLS) {
                    replyError(request.id, "单次执行的桥接次数超过上限($MAX_BRIDGE_CALLS)，已中止")
                } else {
                    runBridge(request, bridgeHandler)
                }
                writeLine(writer, reply)
                continue
            }
            if (line.trim().startsWith("{")) return LoopOutcome(resultLine = line.trim())
            Logger.d(TAG, "忽略非协议输出: ${line.take(MAX_STRAY_CHARS)}")
        }
    }

    /**
     * 执行一条桥接请求并把结果包成 reply 行。
     *
     * 被拒（能力未授予、动作非法）时必须走 `ok:false`：JS 侧会把它抛成异常，
     * 插件里 `const a = muse.bridge(...)` 之后紧接着的算式不会拿着一个 error 对象继续跑 ——
     * 静默的"结果是错误对象"比失败更难查。
     */
    private suspend fun runBridge(request: BridgeRequest, bridgeHandler: suspend (String) -> SkillBridge.HandleResult): String {
        val payload = "{\"action\":${request.actionJson},\"params\":${request.paramsJson}}"
        return when (
            val result = runCatching { bridgeHandler(payload) }.getOrElse {
                return replyError(request.id, "桥接执行异常: ${it.message}")
            }
        ) {
            is SkillBridge.HandleResult.Output ->
                "{\"kind\":\"reply\",\"id\":${request.id},\"ok\":true,\"result\":${result.json}}"
            is SkillBridge.HandleResult.Failure -> replyError(request.id, result.message)
            is SkillBridge.HandleResult.NotBridge -> replyError(request.id, "这条请求不是桥接调用")
        }
    }

    private fun replyError(id: Int, message: String): String =
        "{\"kind\":\"reply\",\"id\":$id,\"ok\":false,\"error\":${JsonPrimitive(message)}}"

    private fun exitCodeOf(process: Process): Int = if (process.isAlive) -1 else runCatching { process.exitValue() }.getOrDefault(-1)

    private fun writeLine(writer: BufferedWriter, line: String) {
        writer.write(line)
        writer.write("\n")
        writer.flush()
    }

    /** 一行是不是桥接请求；不是就返回 null（外层据此当成结果行）。 */
    internal fun parseBridgeRequest(line: String): BridgeRequest? {
        val trimmed = line.trim()
        val obj = if (trimmed.startsWith("{")) {
            runCatching { AppJson.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
        } else {
            null
        }
        val action = (obj?.get("action") as? JsonPrimitive)?.contentOrNull
        val isRequest = obj != null && (obj["kind"] as? JsonPrimitive)?.contentOrNull == "bridge" && action != null
        return when {
            !isRequest || action == null -> null
            else -> BridgeRequest(
                id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                actionJson = JsonPrimitive(action).toString(),
                paramsJson = obj["params"]?.toString() ?: "{}",
            )
        }
    }

    /** 一次桥接请求的解析结果。 */
    internal class BridgeRequest(val id: Int, val actionJson: String, val paramsJson: String)

    /** 主循环的产出：拿到结果行 / 流提前结束 / 超时。 */
    internal data class LoopOutcome(
        val resultLine: String? = null,
        val timedOut: Boolean = false,
        val streamEnded: Boolean = false,
    )

    /**
     * 解析结果行协议。
     *
     * 失败原因统一走 [classifyProcessExit]：以前"超时"和"没产出结果"是两句话，
     * 而**环境根本起不来**、**插件被系统杀掉**、**磁盘写满**这三件事都挤在
     * "没有产出结果（exit=N）"里 —— 模型拿到这句话没法决定该重试、该装运行时还是该改插件。
     */
    internal fun interpret(stdout: String, stderr: String, exitCode: Int, timedOut: Boolean): SkillEngineResult {
        val line = stdout.lineSequence().lastOrNull { it.trim().startsWith("{") }?.trim()
        if (line != null) return interpretLine(line, stderr)
        val kind = classifyProcessExit(
            // 有输出就说明进程确实起来了；一行都没输出＝没跑起来（另一类原因）
            everReady = stdout.isNotBlank(),
            exitCode = exitCode,
            outputTail = stderr,
            timedOut = timedOut,
        )
        return SkillEngineResult.Error(
            message = "Node 插件执行失败（${kind.summary}；exit=$exitCode）" + tail(stderr),
            consoleLogs = logsOf(stdout),
        )
    }

    private fun interpretLine(line: String, stderr: String): SkillEngineResult {
        val payload = runCatching { AppJson.parseToJsonElement(line) as? JsonObject }.getOrNull()
        val logs = logsOf(line)
        val ok = (payload?.get("ok") as? JsonPrimitive)?.booleanOrNull == true
        return when {
            payload == null -> SkillEngineResult.Error("Node 插件结果不是合法 JSON" + tail(stderr))
            !ok -> SkillEngineResult.Error(
                message = (payload["error"] as? JsonPrimitive)?.contentOrNull ?: "插件执行失败",
                consoleLogs = logs,
            )
            // 取 contentOrNull：包装脚本里 valueJson 本身就是 JSON 文本；
            // 用 toString() 会把外层引号一起带进来，数字就变成字符串了。
            else -> SkillEngineResult.Success(
                valueJson = (payload["valueJson"] as? JsonPrimitive)?.contentOrNull ?: "null",
                consoleLogs = logs,
            )
        }
    }

    private fun logsOf(jsonLine: String): List<String> {
        val parsed = runCatching { AppJson.parseToJsonElement(jsonLine) as? JsonObject }.getOrNull()
        val array = parsed?.get("logs") as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    private fun tail(text: String): String = if (text.isBlank()) "" else "\n" + text.substring(maxOf(0, text.length - MAX_STDERR_CHARS))

    /**
     * 生成包装脚本。
     *
     * `vm` 上下文自带一套内在对象（JSON/Math/Promise...），所以**只注入 console / host / muse**：
     * 把宿主 realm 的 JSON 之类传进去是经典坑（跨 realm 的 `instanceof` 会莫名其妙失效）。
     * 同理桥接回包在**上下文内**用 `JSON.parse` 还原，插件拿到的对象属于它自己的 realm。
     */
    internal fun buildWrapper(
        entryCode: String,
        functionName: String,
        argsJson: String,
        scopeKey: String?,
        pluginConfigJson: String?,
        timeoutMs: Long,
    ): String = WRAPPER_TEMPLATE
        .replace("__SYNC_TIMEOUT_MS__", timeoutMs.toString())
        .replace("__ENTRY_CODE__", JsonPrimitive(entryCode).toString())
        .replace("__FUNCTION_NAME__", JsonPrimitive(functionName).toString())
        .replace("__ARGS_JSON__", argsJson.ifBlank { "[]" })
        .replace("__PLUGIN_ID__", JsonPrimitive(scopeKey ?: "").toString())
        .replace("__CONFIG_JSON__", pluginConfigJson ?: "null")

    // 不用 private companion：测试要读 MAX_CONCURRENT_PROCESSES 来验证并发闸门，
    // 而且这个上限本来就是对外的行为约定（写死在拒绝文案里）。
    companion object {
        const val TAG = "NodePluginEngine"
        const val PLUGIN_WORK_DIR = "plugin-sandbox"
        const val MAX_HEAP_MB = 192
        const val MAX_STDERR_CHARS = 1_200
        const val MAX_STRAY_CHARS = 160

        /** 同时允许的几个插件 Node 进程（一个 Node 进程几十 MB 起）。 */
        const val MAX_CONCURRENT_PROCESSES = 2

        /** 进程级共享：所有 NodePluginEngine 实例共用同一份额度。 */
        private val slots = java.util.concurrent.Semaphore(MAX_CONCURRENT_PROCESSES)

        /** 单次执行的桥接次数上限：插件与宿主互相打转时的硬闸。 */
        const val MAX_BRIDGE_CALLS = 16

        /** 进程级兜底超时的宽限：`vm` 的同步超时先到，杀进程只是最后手段。 */
        const val PROCESS_GRACE_MS = 3_000L
        const val GRACE_AFTER_KILL_MS = 2_000L

        val WRAPPER_TEMPLATE = """
            |// muse-node-plugin-wrapper (auto-generated)
            |const vm = require('vm');
            |const __fs = require('fs');
            |const __logs = [];
            |const __consoleSink = {};
            |const __fmt = function (a) {
            |  try { return typeof a === 'string' ? a : JSON.stringify(a); } catch (e) { return String(a); }
            |};
            |['log', 'warn', 'error', 'info'].forEach(function (k) {
            |  __consoleSink[k] = function () {
            |    __logs.push('[' + k + '] ' + Array.prototype.map.call(arguments, __fmt).join(' '));
            |  };
            |});
            |const __config = __CONFIG_JSON__;
            |// ── 行协议：request 走 stdout，reply 从 stdin 同步读 ──
            |let __seq = 0;
            |let __inBuf = '';
            |const __emit = function (obj) { process.stdout.write(JSON.stringify(obj) + '\n'); };
            |const __readLineSync = function () {
            |  const buf = Buffer.alloc(65536);
            |  for (;;) {
            |    const nl = __inBuf.indexOf('\n');
            |    if (nl >= 0) { const out = __inBuf.slice(0, nl); __inBuf = __inBuf.slice(nl + 1); return out; }
            |    let n = -1;
            |    try { n = __fs.readSync(0, buf, 0, buf.length, null); } catch (e) {
            |      if (e.code === 'EAGAIN') { continue; }
            |      throw new Error('读取宿主回包失败: ' + e.message);
            |    }
            |    if (n <= 0) throw new Error('宿主没有回话（进程被终止或管道断裂）');
            |    __inBuf += buf.toString('utf8', 0, n);
            |  }
            |};
            |// 返回 **JSON 文本**而不是对象：让上下文自己的 JSON.parse 去还原，
            |// 这样插件拿到的对象属于它自己的 realm，instanceof 才不会翻车。
            |const __bridgeRaw = function (action, paramsJson) {
            |  const id = ++__seq;
            |  __emit({ kind: 'bridge', id: id, action: action, params: JSON.parse(paramsJson || '{}') });
            |  const reply = JSON.parse(__readLineSync());
            |  if (reply.id !== id) throw new Error('桥接响应序号不匹配（宿主串台了）');
            |  if (!reply.ok) throw new Error(reply.error || '桥接调用失败');
            |  return JSON.stringify(reply.result === undefined ? null : reply.result);
            |};
            |// fetch 垫片：仍然走同一条桥接 RPC —— 换 runtime 不能顺带绕过能力授权。
            |// 返回 Promise 只是为了语义像浏览器；底层是同步往返，因此 await 之前请求已经发完。
            |const __fetch = function (input, init) {
            |  const url = typeof input === 'string' ? input : (input && input.url) || '';
            |  const method = ((init && init.method) || 'GET').toUpperCase();
            |  const params = { url: url };
            |  if (init && init.headers) { params.headers = init.headers; }
            |  if (init && init.body != null) { params.body = String(init.body); }
            |  const action = (method === 'GET' || method === 'HEAD') ? 'http_get' : 'http_post';
            |  const res = JSON.parse(__bridgeRaw(action, JSON.stringify(params)));
            |  const bodyText = res.body == null ? '' : String(res.body);
            |  return Promise.resolve({
            |    ok: res.status >= 200 && res.status < 300,
            |    status: res.status,
            |    url: url,
            |    headers: {},
            |    text: function () { return Promise.resolve(bodyText); },
            |    json: function () { return Promise.resolve(JSON.parse(bodyText)); }
            |  });
            |};
            |// 定时器：回调在宿主 event loop 上跑，所以只在"返回 Promise 的插件"里真正会触发；
            |// 同步阶段排的定时器要等 vm 交回控制权之后才跑。延迟一律夹到本次执行预算内。
            |const __capDelay = function (ms) { return Math.min(Math.max(0, Number(ms) || 0), __SYNC_TIMEOUT_MS__); };
            |const __sandbox = {
            |  console: __consoleSink,
            |  fetch: __fetch,
            |  setTimeout: function (fn, ms) { return setTimeout(fn, __capDelay(ms)); },
            |  setInterval: function (fn, ms) { return setInterval(fn, __capDelay(ms)); },
            |  clearTimeout: function (h) { clearTimeout(h); },
            |  clearInterval: function (h) { clearInterval(h); },
            |  URL: URL,
            |  URLSearchParams: URLSearchParams,
            |  host: {
            |    getConfig: function (key) {
            |      var v = __config ? __config[key] : undefined;
            |      return v === undefined ? null : JSON.stringify(v);
            |    },
            |    getPluginId: function () { return __PLUGIN_ID__; }
            |  },
            |  muse: {
            |    bridge: function (action, params) { return JSON.parse(__bridgeRaw(action, JSON.stringify(params || {}))); },
            |    // storage 只是 bridge 的糖：**必须走同一条通道**，否则等于在沙盒里开第二个不受账本管的后门
            |    storage: {
            |      get: function (key) { return JSON.parse(__bridgeRaw('storage_get', JSON.stringify({ key: key }))); },
            |      set: function (key, value) {
            |        return JSON.parse(__bridgeRaw('storage_set', JSON.stringify({ key: key, value: value })));
            |      },
            |      delete: function (key) { return JSON.parse(__bridgeRaw('storage_delete', JSON.stringify({ key: key }))); },
            |      list: function () { return JSON.parse(__bridgeRaw('storage_list', '{}')); }
            |    }
            |  }
            |};
            |vm.createContext(__sandbox);
            |let __value;
            |try {
            |  vm.runInContext(__ENTRY_CODE__, __sandbox, { filename: 'plugin-entry.js', timeout: __SYNC_TIMEOUT_MS__ });
            |  __value = vm.runInContext(
            |    '(function(){ var f = globalThis[' + JSON.stringify(__FUNCTION_NAME__) + '];' +
            |    ' if (typeof f !== "function") { throw new Error("入口里没有该函数: " + ' + JSON.stringify(__FUNCTION_NAME__) + '); }' +
            |    ' return f.apply(undefined, __ARGS_JSON__); })()',
            |    __sandbox,
            |    { filename: 'plugin-invoke.js', timeout: __SYNC_TIMEOUT_MS__ }
            |  );
            |} catch (e) {
            |  __emit({ ok: false, error: (e && e.message) || String(e), logs: __logs });
            |  process.exit(0);
            |}
            |if (__value && typeof __value.then === 'function') {
            |  Promise.resolve(__value).then(
            |    function (v) { __emit({ ok: true, valueJson: JSON.stringify(v === undefined ? null : v), logs: __logs }); },
            |    function (e) { __emit({ ok: false, error: (e && e.message) || String(e), logs: __logs }); }
            |  ).catch(function (e) { __emit({ ok: false, error: 'await failed: ' + ((e && e.message) || String(e)), logs: __logs }); });
            |} else {
            |  __emit({ ok: true, valueJson: JSON.stringify(__value === undefined ? null : __value), logs: __logs });
            |}
            |
        """.trimMargin()
    }
}
