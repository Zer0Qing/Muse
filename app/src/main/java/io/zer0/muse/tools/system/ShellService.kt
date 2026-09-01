package io.zer0.muse.tools.system

import androidx.annotation.Keep
import io.zer0.common.Logger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * P3-3: Shizuku UserService — 以 shell 权限执行命令的服务。
 *
 * 该服务通过 [Shizuku.bindUserService] 在 Shizuku 管理的独立进程中启动,
 * 继承 Shizuku 的 shell (ADB) 权限。主进程通过 AIDL IPC 调用 [execute]。
 *
 * 注意：Shizuku UserService 不是 Android [android.app.Service]。服务类本身必须
 * 实现 [android.os.IBinder]，这里直接继承生成的 AIDL Stub；否则 bindUserService
 * 不会得到可用的 binder，调用方会持续等待连接超时。
 *
 * 生命周期:
 *  - 由 [ShizukuAuthorizer] 在首次执行时绑定(bindUserService)
 *  - 服务在独立进程运行,与主进程隔离,崩溃不影响主进程
 *  - Shizuku 服务停止时,该进程也会被终止
 *
 * 安全:
 *  - 仅响应 [IShellService.execute] 调用,不处理 Intent
 *  - 命令执行无白名单过滤(由调用方 [ShellExecutor] / [ToolPermissionResolver] 负责硬边界校验)
 *  - 服务运行在 Shizuku 进程中,即使被恶意调用也仅能执行 shell 级操作(非 root)
 */
@Keep
class ShellService : IShellService.Stub() {

    companion object {
        private const val TAG = "ShellService"
        /** AIDL 返回值的字段分隔符(null char,shell 输出中不会出现)。 */
        private const val FIELD_SEPARATOR = '\u0000'
        /** 命令执行超时 ms。 */
        private const val TIMEOUT_MS = 10_000L
        /** 防止异常命令把 UserService 内存和 Binder 回包撑爆。 */
        private const val MAX_OUTPUT_BYTES = 2 * 1024 * 1024
    }

    private val streamExecutor = Executors.newCachedThreadPool()

    override fun execute(command: String): String {
        return try {
            Logger.d(TAG, "执行命令: $command")
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            // stdout/stderr 必须在 waitFor 的同时持续排空；否则 uiautomator/screencap
            // 等输出稍大的命令会把管道写满，子进程永远等不到退出而被误判超时。
            val stdout: Future<String> = streamExecutor.submit(Callable { readStream(proc.inputStream) })
            val stderr: Future<String> = streamExecutor.submit(Callable { readStream(proc.errorStream) })
            val finished = proc.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                stdout.cancel(true)
                stderr.cancel(true)
                return "-1${FIELD_SEPARATOR}${FIELD_SEPARATOR}执行超时(${TIMEOUT_MS / 1000}s)"
            }
            val out = stdout.get(1, TimeUnit.SECONDS)
            val err = stderr.get(1, TimeUnit.SECONDS)
            val code = proc.exitValue()
            buildString {
                append(code)
                append(FIELD_SEPARATOR)
                append(out)
                append(FIELD_SEPARATOR)
                append(err)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.e(TAG, "Shell 执行被中断: ${e.message}", e)
            "-1${FIELD_SEPARATOR}${FIELD_SEPARATOR}${e.message ?: "执行被中断"}"
        } catch (e: IOException) {
            Logger.e(TAG, "Shell 执行 IO 异常: ${e.message}", e)
            "-1${FIELD_SEPARATOR}${FIELD_SEPARATOR}${e.message ?: "IO 异常"}"
        } catch (e: SecurityException) {
            Logger.e(TAG, "Shell 执行权限异常: ${e.message}", e)
            "-1${FIELD_SEPARATOR}${FIELD_SEPARATOR}${e.message ?: "执行权限异常"}"
        } catch (e: IllegalArgumentException) {
            Logger.e(TAG, "Shell 执行异常: ${e.message}", e)
            "-1${FIELD_SEPARATOR}${FIELD_SEPARATOR}${e.message ?: "执行异常"}"
        }
    }

    private fun readStream(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var retained = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (retained < MAX_OUTPUT_BYTES) {
                val take = minOf(read, MAX_OUTPUT_BYTES - retained)
                output.write(buffer, 0, take)
                retained += take
            }
        }
        val text = output.toString(Charsets.UTF_8.name())
        return if (retained >= MAX_OUTPUT_BYTES) "$text\n[输出已截断]" else text
    }

    /** Shizuku 解绑/版本替换时调用的保留事务，必须结束 UserService 进程。 */
    override fun destroy() {
        Logger.i(TAG, "Shell UserService destroy")
        streamExecutor.shutdownNow()
        System.exit(0)
    }

    /** 应用侧可选的主动退出事务。 */
    override fun exit() = destroy()
}
