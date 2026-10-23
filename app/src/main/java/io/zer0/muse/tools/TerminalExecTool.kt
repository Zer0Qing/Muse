package io.zer0.muse.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * v2.x 终端一期:terminal_exec 工具 — 在应用沙盒内执行单条 shell 命令。
 *
 * 与 [ShellSandboxTool](execute_shell:只读白名单) 和 device_shell(Shizuku/Root 设备级操控)
 * 互补:本工具是 **App 自身权限** 的终端,工作目录锁定应用工作区,用于文件处理、脚本、
 * 诊断等沙盒内任务。HIGH 风险,走既有审批链。
 */
object TerminalExecTool {
    const val NAME = "terminal_exec"
    private const val TAG = "TerminalExec"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun toolDef() = ToolRegistry.ToolDef(
        name = NAME,
        description = "在应用沙盒内执行单条 shell 命令(工作目录为应用工作区,App 自身权限)。" +
            "适合文件处理/脚本/诊断;需要点击、启停应用等设备级操控时改用 device_shell(需 Shizuku/Root 授权)。" +
            "返回退出码与完整输出;长输出会保存到可分段读取的应用文件;超时命令会被终止。",
        parameters = mapOf(
            "command" to "必填。单条 shell 命令,如 ls -la / cat notes.txt / mkdir -p out",
            "timeout_ms" to "可选。超时毫秒,默认 15000,范围 1000-60000",
        ),
        required = setOf("command"),
        category = "built-in",
        parameterTypes = mapOf("timeout_ms" to "integer"),
        riskLevel = ToolRiskLevel.HIGH,
    )

    suspend fun execute(args: Map<String, String>, workspaceDir: File, outputDirectory: File): String {
        val command = args["command"]?.trim().orEmpty()
        if (command.isEmpty()) return "Error: command is required."
        val timeoutMs = (args["timeout_ms"]?.toIntOrNull() ?: 15_000).coerceIn(1_000, 60_000)
        return withContext(Dispatchers.IO) {
            val capture = ToolOutputCapture(outputDirectory, "terminal_exec")
            val process = runCatching {
                ProcessBuilder("/system/bin/sh", "-c", command)
                    .directory(workspaceDir)
                    .redirectErrorStream(true)
                    .start()
            }.getOrElse {
                capture.close()
                return@withContext "启动失败: ${it.message ?: "未知错误"}"
            }

            val readerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val readerJob = scope.launch {
                try {
                    val buffer = CharArray(4096)
                    process.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val n = reader.read(buffer)
                            if (n < 0) break
                            capture.append(String(buffer, 0, n))
                        }
                    }
                } catch (error: Throwable) {
                    readerFailure.set(error)
                }
            }

            val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                readerJob.join()
                readerFailure.get()?.let { error ->
                    capture.close()
                    return@withContext "命令超时，且完整输出无法保存: ${error.message ?: "存储失败"}"
                }
                return@withContext capture.finish(
                    header = "命令超时(${timeoutMs / 1000}s)已终止",
                    emptyMessage = "(无输出)",
                )
            }
            readerJob.join()
            readerFailure.get()?.let { error ->
                capture.close()
                return@withContext "命令结束，但完整输出无法保存: ${error.message ?: "存储失败"}"
            }
            val exit = process.exitValue()
            capture.finish(header = "exit=$exit", emptyMessage = "(无输出)")
        }
    }
}
