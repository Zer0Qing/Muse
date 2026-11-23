package io.zer0.muse.runtime

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.tools.TOOL_OUTPUTS_DIR
import io.zer0.muse.tools.ToolOutputCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object PythonRuntime {
    private const val TAG = "PythonRuntime"
    private const val PYTHON_BIN = "/usr/bin/python3"
    private const val OUTPUT_DRAIN_TIMEOUT_MS = 30_000L

    fun runtimeDir(context: Context): File = File(context.filesDir, "runtime/python")

    fun homeDir(context: Context): File = File(runtimeDir(context), "home")

    fun isReady(context: Context): Boolean {
        if (!RuntimeBridge.isReady(context)) return false
        val store = RuntimeStore(context)
        val state = store.state(RuntimeId.UBUNTU_NODE) as? RuntimeState.Ready ?: return false
        return File(store.installDir(RuntimeId.UBUNTU_NODE, state.revision), PYTHON_BIN.removePrefix("/")).isFile
    }

    suspend fun ensureData(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isReady(context)) {
            return@withContext Result.failure(IllegalStateException("已安装的 Ubuntu 运行环境没有 Python 3"))
        }
        val probe = try {
            execPython(
                context = context,
                args = listOf("-c", "import ssl, sqlite3, json; print('python-ready')"),
                timeoutMs = 30_000L,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Logger.w(TAG, "Ubuntu Python 自检启动失败: ${error.message}", error)
            return@withContext Result.failure(error)
        }
        if (probe.exitCode == 0 && probe.stdout.contains("python-ready")) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    "Ubuntu Python 自检失败(exit=${probe.exitCode}): ${(probe.stderr.ifBlank { probe.stdout }).take(300)}",
                ),
            )
        }
    }

    fun buildEnv(context: Context, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val home = homeDir(context).apply { mkdirs() }
        val tmp = File(runtimeDir(context), "tmp").apply { mkdirs() }
        val pipCache = File(runtimeDir(context), "pip-cache").apply { mkdirs() }
        return buildMap {
            put("HOME", RuntimeBridge.toGuestPath(context, home.absolutePath))
            put("TMPDIR", RuntimeBridge.toGuestPath(context, tmp.absolutePath))
            put("PYTHONDONTWRITEBYTECODE", "1")
            put("PYTHONUNBUFFERED", "1")
            put("PYTHONIOENCODING", "utf-8")
            put("MUSE_PYTHON_BIN", PYTHON_BIN)
            put("SSL_CERT_FILE", "/etc/ssl/certs/ca-certificates.crt")
            put("PIP_BREAK_SYSTEM_PACKAGES", "1")
            put("PIP_CACHE_DIR", RuntimeBridge.toGuestPath(context, pipCache.absolutePath))
            extra.forEach { (key, value) -> put(key, RuntimeBridge.toGuestPath(context, value)) }
        }
    }

    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
        val stdoutFile: File? = null,
        val stderrFile: File? = null,
        val captureError: String? = null,
    ) {
        companion object {
            fun failure(message: String): ExecResult = ExecResult(-1, "", message)
        }
    }

    suspend fun execPython(
        context: Context,
        args: List<String>,
        timeoutMs: Long = 30_000L,
        workdir: File? = homeDir(context),
        env: Map<String, String> = emptyMap(),
    ): ExecResult = withContext(Dispatchers.IO) {
        if (!isReady(context)) return@withContext ExecResult.failure("Ubuntu Python 3 未就绪")
        val store = RuntimeStore(context)
        val hostWorkdir = (workdir ?: homeDir(context)).apply { mkdirs() }
        val guestWorkdir = RuntimeBridge.toGuestPath(context, hostWorkdir.absolutePath)
        val builder = ProotRuntime(context, store).processBuilder(
            id = RuntimeId.UBUNTU_NODE,
            argv = listOf(PYTHON_BIN) + args.map { RuntimeBridge.toGuestPath(context, it) },
            binds = listOf("${context.filesDir.absolutePath}:${RuntimeBridge.GUEST_FILES}"),
            env = buildEnv(context, env),
            workdirGuest = guestWorkdir,
        ) ?: return@withContext ExecResult.failure("运行环境未安装或 proot 不可用")

        val process = builder.start()
        val outputDirectory = File(context.filesDir, TOOL_OUTPUTS_DIR)
        val outCapture = ToolOutputCapture(outputDirectory, "python_stdout")
        val errCapture = ToolOutputCapture(outputDirectory, "python_stderr")
        val captureFailure = AtomicReference<Throwable?>(null)
        val stdoutThread = outputReaderThread("muse-proot-python-stdout", process.inputStream, outCapture, captureFailure)
        val stderrThread = outputReaderThread("muse-proot-python-stderr", process.errorStream, errCapture, captureFailure)
        stdoutThread.start()
        stderrThread.start()

        val finished = process.waitFor(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(3, TimeUnit.SECONDS)
        }
        stdoutThread.join(OUTPUT_DRAIN_TIMEOUT_MS)
        stderrThread.join(OUTPUT_DRAIN_TIMEOUT_MS)
        if (stdoutThread.isAlive) {
            runCatching { process.inputStream.close() }
            stdoutThread.join(1_000L)
            captureFailure.compareAndSet(null, IOException("stdout 输出流未能完整排空"))
        }
        if (stderrThread.isAlive) {
            runCatching { process.errorStream.close() }
            stderrThread.join(1_000L)
            captureFailure.compareAndSet(null, IOException("stderr 输出流未能完整排空"))
        }

        val outputFailure = captureFailure.get()
        if (outputFailure != null) {
            return@withContext ExecResult(
                exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
                stdout = finishPartial(outCapture, "stdout"),
                stderr = finishPartial(errCapture, "stderr"),
                timedOut = !finished,
                stdoutFile = outCapture.savedFile,
                stderrFile = errCapture.savedFile,
                captureError = outputFailure.message ?: outputFailure.javaClass.simpleName,
            )
        }

        ExecResult(
            exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
            stdout = outCapture.finish(header = "", emptyMessage = ""),
            stderr = errCapture.finish(header = "", emptyMessage = ""),
            timedOut = !finished,
            stdoutFile = outCapture.savedFile,
            stderrFile = errCapture.savedFile,
        )
    }

    private fun finishPartial(capture: ToolOutputCapture, streamName: String): String = runCatching {
        capture.finish(header = "[$streamName 输出未能完整接收]", emptyMessage = "")
    }.getOrElse {
        capture.close()
        capture.savedFile?.let { "[$streamName 部分输出文件: ${it.absolutePath}]" }.orEmpty()
    }

    private fun outputReaderThread(
        threadName: String,
        input: InputStream,
        capture: ToolOutputCapture,
        failure: AtomicReference<Throwable?>,
    ): Thread = Thread({
        try {
            InputStreamReader(input, Charsets.UTF_8).buffered().use { reader ->
                val buffer = CharArray(8_192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    capture.append(String(buffer, 0, count))
                }
            }
        } catch (error: Throwable) {
            failure.compareAndSet(null, error)
        }
    }, threadName).apply {
        isDaemon = true
    }
}
