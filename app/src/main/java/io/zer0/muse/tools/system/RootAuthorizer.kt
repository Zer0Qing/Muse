package io.zer0.muse.tools.system

import io.zer0.common.Logger
import java.io.File
import java.util.concurrent.TimeUnit

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
 *  - 本类只提供 [execute] 原语;通道选择由调用方(automation/executors/ShellExecutor)决定
 *  - Root 通道风险最高,仅在 Shizuku 不可用时降级使用
 */
class RootAuthorizer {

    companion object {
        private const val TAG = "RootAuthorizer"

        /** su 探测超时:root 管理器弹窗没人点时不阻塞 UI。 */
        private const val SU_PROBE_TIMEOUT_MS = 8_000L

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
     * 快速检测设备是否"可能已 root"(不弹授权弹窗)。
     *
     * v2.0: KernelSU 系(含 Next/SukiSU 等分支)在 GKI/LKM 模式下不会在文件系统暴露 su
     * 二进制 —— 内核层 sucompat 直接拦截 App 对 "su" 的执行请求,所以 File.exists() 查不到。
     * 这里追加 /data/adb(root 方案的数据目录,普通设备不存在)作为预筛;
     * 注意:存在 su / 存在 /data/adb 都不代表已授权,需 [checkPermission] 实际探测。
     */
    fun isAvailable(): Boolean = SU_PATHS.any { File(it).exists() } || File("/data/adb").exists()

    /**
     * 验证 root 授权:执行 `su -c id`,输出包含 uid=0 才算已授权。
     *
     * v2.0: 探测从 `su -v` 改为 `su -c id` —— KernelSU 等内核 root 方案不一定支持 `-v`,
     * 旧探测会误判"无 root";`-c id` 是 Magisk/KernelSU/APatch 通用口径。
     *
     * @return true 表示 root 可用且已授权
     */
    fun checkPermission(): Boolean {
        if (!isAvailable()) return false
        return try {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(SU_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                Logger.w(TAG, "su 探测超时(${SU_PROBE_TIMEOUT_MS}ms),按未授权处理")
                return false
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val granted = process.exitValue() == 0 && output.contains("uid=0")
            if (!granted) {
                Logger.w(TAG, "su 探测未获得 uid=0: exit=${process.exitValue()}, out=${output.take(160)}")
            }
            granted
        } catch (e: Exception) {
            // 必要容错:su 执行可能抛异常(权限拒绝/文件缺失/超时),记录日志
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
