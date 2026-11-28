package io.zer0.muse.mcp

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.runtime.MuseRuntime
import io.zer0.muse.runtime.ProotRuntime
import io.zer0.muse.runtime.RuntimeBridge
import io.zer0.muse.runtime.RuntimeId
import io.zer0.muse.runtime.RuntimeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * P1-A: MCP stdio 传输 — 本地子进程（Node 系 MCP server）的 JSON-RPC 行协议。
 *
 * 职责边界：
 *  - 本类只负责「进程 + 行收发」；JSON-RPC 语义（id 匹配、通知分发）由 [McpClient] 处理。
 *  - 进程运行在应用内置 Node 运行时之上（[McpStdioCommand] 解析命令，
 *    [MuseRuntime.buildEnv] 构造环境），工作目录为 `filesDir/muse-runtime/mcp/<serverId>/`。
 *
 * 消息格式：stdio 传输为「一行一条 JSON」（NDJSON）；stdout 中非 JSON 行被忽略。
 *
 * @param onMessage 收到一条 JSON 消息（响应 / 通知 / server 请求均由调用方分流）。
 * @param onExit 子进程退出回调（主动 [stop] 时也会触发，调用方需自行区分）。
 */
class McpStdioTransport(
    private val config: McpServerConfig,
    private val context: Context,
    private val scope: CoroutineScope,
    private val onMessage: (JsonObject) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    private val writeLock = Any()

    fun isAlive(): Boolean = process?.isAlive == true

    /** 启动子进程并挂载 stdout/stderr 处理循环。返回是否启动成功。 */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        val store = RuntimeStore(context)
        val proot = ProotRuntime(context, store)
        val useExternal = RuntimeBridge.isReady(context) && proot.isAvailable()
        val resolved = if (useExternal) {
            McpStdioCommand.resolveForGuest(config.command, context)
        } else {
            McpStdioCommand.resolve(config.command, context)
        }
        val argv = when (resolved) {
            is McpStdioCommand.Result.Ok -> resolved.argv
            is McpStdioCommand.Result.Error -> {
                Logger.w(TAG, "[${config.id}] MCP launch command was rejected")
                return@withContext false
            }
        }
        val environment = config.environment.filterKeys(::isMcpEnvironmentName)
        val workdir = File(MuseRuntime.runtimeDir(context), "mcp/${config.id}").apply { mkdirs() }
        runCatching {
            val pb = if (useExternal) {
                proot.processBuilder(
                    id = RuntimeId.UBUNTU_NODE,
                    argv = argv,
                    binds = listOf("${context.filesDir.absolutePath}:${RuntimeBridge.GUEST_FILES}"),
                    env = environment,
                    workdirGuest = RuntimeBridge.toGuestPath(context, workdir.absolutePath),
                ) ?: return@withContext false
            } else {
                ProcessBuilder(argv).apply {
                    directory(workdir)
                    environment().clear()
                    environment().putAll(MuseRuntime.buildEnv(context))
                    environment().putAll(environment)
                }
            }
            val p = pb.start()
            process = p
            writer = p.outputStream.bufferedWriter()
            mountStdout(p)
            mountStderr(p)
            mountExit(p)
            Logger.i(TAG, "[${config.name}] stdio 进程已启动: ${argv.firstOrNull()}")
            true
        }.getOrElse {
            Logger.w(TAG, "[${config.id}] MCP process start failed (${it.javaClass.simpleName})")
            stop()
            false
        }
    }

    /** stdout 行循环 → JSON 消息分发。 */
    private fun mountStdout(p: Process) {
        scope.launch(Dispatchers.IO) {
            val reader = p.inputStream.bufferedReader()
            try {
                while (isActive) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && trimmed.startsWith("{")) {
                        runCatching { AppJson.parseToJsonElement(trimmed) as? JsonObject }
                            .getOrNull()
                            ?.let(onMessage)
                    }
                }
            } catch (_: Exception) {
                // 进程结束时 readLine 抛出/EOF,静默退出循环
            }
        }
    }

    /** stderr → 日志（限流,避免服务端刷屏）。 */
    private fun mountStderr(p: Process) {
        scope.launch(Dispatchers.IO) {
            var count = 0
            runCatching {
                p.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { ln ->
                        if (ln.isNotBlank() && count < 300) {
                            count++
                            Logger.d(TAG, "[${config.name}] $ln")
                        }
                    }
                }
            }
        }
    }

    /** 进程退出监听（经 [onExit] 回调；主动 stop 由调用方区分）。 */
    private fun mountExit(p: Process) {
        scope.launch(Dispatchers.IO) {
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            // 仅当当前进程仍是本实例时回调（防止旧进程退出影响重启后的新进程）
            if (process === p) {
                onExit(code)
            }
        }
    }

    /** 发送一行 JSON（线程安全；进程不可用时返回 false）。 */
    fun send(json: String): Boolean {
        val w = writer ?: return false
        return runCatching {
            synchronized(writeLock) {
                w.write(json)
                w.write("\n")
                w.flush()
            }
            true
        }.getOrElse {
            Logger.w(TAG, "[${config.name}] stdin 写入失败: ${it.message}")
            false
        }
    }

    /** 停止子进程（幂等）。 */
    fun stop() {
        runCatching { writer?.close() }
        writer = null
        process?.let { p ->
            p.destroy()
            runCatching {
                if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
            }
        }
        process = null
    }

    companion object {
        private const val TAG = "McpStdioTransport"
    }
}
private val MCP_ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

internal fun isMcpEnvironmentName(name: String): Boolean =
    MCP_ENV_NAME.matches(name) && !name.startsWith("PROOT_") && !name.startsWith("LD_") &&
        name != "HOME" && name != "PATH"
