package io.zer0.muse.tools.system

import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.zer0.common.Logger
import java.io.IOException

/**
 * P3-3: Shizuku UserService — 以 shell 权限执行命令的服务。
 *
 * 该服务通过 [Shizuku.bindUserService] 在 Shizuku 管理的独立进程中启动,
 * 继承 Shizuku 的 shell (ADB) 权限。主进程通过 AIDL IPC 调用 [execute]。
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
class ShellService : Service() {

    companion object {
        private const val TAG = "ShellService"
        /** AIDL 返回值的字段分隔符(null char,shell 输出中不会出现)。 */
        private const val FIELD_SEPARATOR = '\u0000'
        /** 命令执行超时 ms。 */
        private const val TIMEOUT_MS = 10_000L
    }

    private val binder = object : IShellService.Stub() {
        override fun execute(command: String): String {
            return try {
                Logger.d(TAG, "执行命令: $command")
                val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                // 审计修复 (4.3): 先 waitFor 带超时、再读输出 — 原实现先 proc.inputStream.readText()
                // 再 waitFor(),且先读 stdout 后读 stderr,输出量大时 stderr 管道写满会阻塞进程,
                // 形成互等死锁;readText 也会让 10s 超时形同虚设。进程退出后再顺序读两个流,
                // 管道已关闭不会阻塞,超时则销毁进程返回超时错误。
                val finished = proc.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    return "-1${FIELD_SEPARATOR}${FIELD_SEPARATOR}执行超时(${TIMEOUT_MS / 1000}s)"
                }
                val out = proc.inputStream.bufferedReader().readText()
                val err = proc.errorStream.bufferedReader().readText()
                val code = proc.exitValue()
                buildString {
                    append(code)
                    append(FIELD_SEPARATOR)
                    append(out)
                    append(FIELD_SEPARATOR)
                    append(err)
                }
            } catch (e: IOException) {
                Logger.e(TAG, "Shell 执行 IO 异常: ${e.message}", e)
                "-1${FIELD_SEPARATOR}${FIELD_SEPARATOR}${e.message ?: "IO 异常"}"
            } catch (e: Exception) {
                Logger.e(TAG, "Shell 执行异常: ${e.message}", e)
                "-1${FIELD_SEPARATOR}${FIELD_SEPARATOR}${e.message ?: "执行异常"}"
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
