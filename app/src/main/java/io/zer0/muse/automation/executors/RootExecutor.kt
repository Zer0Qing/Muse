package io.zer0.muse.automation.executors

import android.content.Context
import io.zer0.muse.automation.core.PermissionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Root 执行器 —— 第三层 UI 自动化。
 *
 * 继承 [ShellExecutor],仅把命令前缀从 `sh` 换成 `su`。
 * 额外能力:
 * - 访问其他 App 的 /data/data 目录
 * - 修改系统文件、iptables、kill 任意进程
 * - 静默安装/卸载、授予运行时权限
 *
 * 检测: 运行 `su -c id`,检查输出是否含 "uid=0"。
 * 无 root 的设备所有命令静默失败。
 */
class RootExecutor(
    context: Context,
) : ShellExecutor(context) {

    override val level = PermissionLevel.ROOT

    override val shellPrefix: List<String> = listOf("su")

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (!isRooted()) return@withContext false
        val result = exec("id")
        result.isSuccess && result.getOrDefault("").contains("uid=0")
    }

    /** 检查设备是否存在 su 二进制(不一定有权限执行)。 */
    private fun isRooted(): Boolean {
        return try {
            val paths = arrayOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/system/sd/xbin/su",
                "/system/bin/failsafe/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/data/local/su",
                "/su/bin/su",
            )
            paths.any { File(it).exists() } || runCatching {
                ProcessBuilder("which", "su").start().inputStream.bufferedReader().readText().isNotBlank()
            }.getOrDefault(false)
        } catch (e: Exception) {
            false
        }
    }

    /** 静默安装 APK(root 下 pm install 不需要用户确认)。 */
    suspend fun installApk(apkPath: String): Boolean {
        val result = exec("pm install -r $apkPath")
        return result.isSuccess && result.getOrDefault("").contains("Success", ignoreCase = true)
    }

    /** 卸载 App(root 下 pm uninstall)。 */
    suspend fun uninstallApp(packageName: String): Boolean =
        exec("pm uninstall $packageName").isSuccess

    /** 授予运行时权限(root 下 pm grant)。 */
    suspend fun grantPermission(packageName: String, permission: String): Boolean =
        exec("pm grant $packageName $permission").isSuccess

    /** 强制停止 App。 */
    suspend fun forceStop(packageName: String): Boolean =
        exec("am force-stop $packageName").isSuccess

    /** 读取其他 App 私有目录文件(需 root)。 */
    suspend fun readAppFile(appPackage: String, relativePath: String): String? {
        val path = "/data/data/$appPackage/$relativePath"
        val result = exec("cat $path")
        return if (result.isSuccess) result.getOrDefault("") else null
    }

    companion object {
        private const val TAG = "RootExec"
    }
}
