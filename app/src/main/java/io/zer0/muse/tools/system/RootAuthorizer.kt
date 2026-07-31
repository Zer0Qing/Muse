package io.zer0.muse.tools.system

import io.zer0.common.Logger
import java.io.File

/**
 * P3-3: Root 授权器 — 检测设备是否已 root,并提供以 root 权限执行命令的能力。
 *
 * 三通道路由中权限最高的通道(无需安装 Shizuku,直接用 su)。
 * 检测方式:
 *  1. 检查 su 二进制是否存在于常见路径
 *  2. 执行 `su -v` 验证 su 可用(弹 root 授权弹窗)
 *
 * 安全:
 *  - 仅检测可用性,不主动提权
 *  - 命令执行由 [ShellExecutor] 统一路由,本类只提供 [execute] 原语
 *  - Root 通道风险最高,仅在 Shizuku 不可用时降级使用
 */
class RootAuthorizer {

    companion object {
        private const val TAG = "RootAuthorizer"

        /** su 二进制的常见安装路径。 */
        private val SU_PATHS = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/system/sbin/su",
            "/system/bin/.ext/.su",
        )
    }

    /**
     * 快速检测 su 二进制是否存在(不弹授权弹窗)。
     * 注意:存在 su 不代表应用已获 root 授权,需 [checkPermission] 进一步验证。
     */
    fun isAvailable(): Boolean = SU_PATHS.any { File(it).exists() }

    /**
     * 验证 root 授权:执行 `su -v`,成功返回说明应用已获 root 授权。
     * @return true 表示 root 可用且已授权
     */
    fun checkPermission(): Boolean {
        if (!isAvailable()) return false
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-v"))
            val exitCode = process.waitFor()
            // su -v 成功返回 0 表示 root 授权有效;部分 ROM 返回非 0 但有输出
            if (exitCode == 0) {
                true
            } else {
                // 读取错误流,判断是否被拒绝(授权弹窗取消)
                val err = process.errorStream.readBytes().toString(Charsets.UTF_8)
                Logger.w(TAG, "su -v 退出码 $exitCode, stderr=$err")
                false
            }
        } catch (e: Exception) {
            // 必要容错:su 执行可能抛异常(权限拒绝/超时),记录日志
            Logger.w(TAG, "root 权限验证失败: ${e.message}")
            false
        }
    }

    /**
     * 以 root 权限执行命令。
     * @param command 要执行的命令字符串
     * @return [RootExecResult] 包含退出码与输出
     */
    fun execute(command: String): RootExecResult {
        if (!checkPermission()) return RootExecResult(-1, "", "root 未授权")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val out = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            val err = process.errorStream.readBytes().toString(Charsets.UTF_8).trim()
            val exitCode = process.waitFor()
            RootExecResult(exitCode, out, err)
        } catch (e: Exception) {
            Logger.e(TAG, "root 执行失败: ${e.message}", e)
            RootExecResult(-1, "", e.message ?: "执行异常")
        }
    }

    /** Root 命令执行结果。 */
    data class RootExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }
}
