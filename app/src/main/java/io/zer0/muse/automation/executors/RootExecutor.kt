package io.zer0.muse.automation.executors

import android.content.Context
import io.zer0.common.Logger
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
 *
 * H-SEC-1: 所有参数经过白名单校验,防止 prompt injection 导致任意命令注入。
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

    /** H-SEC-1: 包名白名单校验,拒绝包含 shell 元字符的参数。 */
    private fun validatePackageName(name: String): Boolean =
        Regex("^[a-zA-Z][a-zA-Z0-9_.]*\$").matches(name)

    /** H-SEC-1: 路径白名单校验,拒绝路径穿越和 shell 注入字符。 */
    private fun validatePath(path: String): Boolean =
        Regex("^[/a-zA-Z0-9_.\\-]+$").matches(path)

    /**
     * Resolve an APK only from directories where this app stages APK files.
     * Canonicalization also prevents symlinks from escaping those directories.
     */
    private fun resolveAllowedApkPath(path: String): String? {
        if (!path.startsWith('/') || !validatePath(path)) return null
        if (path.split('/').any { it == "." || it == ".." }) return null
        if (!path.endsWith(".apk", ignoreCase = true)) return null
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (!validatePath(candidate.path)) return null
        val allowedRoots = buildList {
            add(context.cacheDir)
            add(context.filesDir)
            context.getExternalFilesDir(null)?.let(::add)
            // Standard adb/installer staging directory; root pm can read it directly.
            add(File("/data/local/tmp"))
        }.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        return candidate.path.takeIf { candidate.isWithinAny(allowedRoots) }
    }

    private fun File.isWithinAny(roots: List<File>): Boolean =
        roots.any { root ->
            path == root.path || path.startsWith(root.path + File.separator)
        }

    /** 静默安装 APK(root 下 pm install 不需要用户确认)。 */
    suspend fun installApk(apkPath: String): Boolean {
        val canonicalPath = resolveAllowedApkPath(apkPath)
        if (canonicalPath == null) {
            Logger.w(TAG, "installApk 拒绝: 非法或不允许的 APK 路径")
            return false
        }
        val result = exec("pm install -r $canonicalPath")
        return result.isSuccess && result.getOrDefault("").contains("Success", ignoreCase = true)
    }

    /** 卸载 App(root 下 pm uninstall)。 */
    suspend fun uninstallApp(packageName: String): Boolean {
        if (!validatePackageName(packageName)) {
            Logger.w(TAG, "uninstallApp 拒绝: 非法包名 $packageName")
            return false
        }
        return exec("pm uninstall $packageName").isSuccess
    }

    /** 授予运行时权限(root 下 pm grant)。 */
    suspend fun grantPermission(packageName: String, permission: String): Boolean {
        if (!validatePackageName(packageName)) {
            Logger.w(TAG, "grantPermission 拒绝: 非法包名 $packageName")
            return false
        }
        // permission 格式如 android.permission.SEND_SMS,只允许字母/数字/点/下划线
        if (!Regex("^[a-zA-Z][a-zA-Z0-9_.]*\$").matches(permission)) {
            Logger.w(TAG, "grantPermission 拒绝: 非法权限名 $permission")
            return false
        }
        return exec("pm grant $packageName $permission").isSuccess
    }

    /** 强制停止 App。 */
    suspend fun forceStop(packageName: String): Boolean {
        if (!validatePackageName(packageName)) {
            Logger.w(TAG, "forceStop 拒绝: 非法包名 $packageName")
            return false
        }
        return exec("am force-stop $packageName").isSuccess
    }

    /** 读取其他 App 私有目录文件(需 root)。 */
    suspend fun readAppFile(appPackage: String, relativePath: String): String? {
        if (!validatePackageName(appPackage)) {
            Logger.w(TAG, "readAppFile 拒绝: 非法包名 $appPackage")
            return null
        }
        // relativePath 不允许绝对路径或路径穿越
        if (relativePath.startsWith("/") || relativePath.contains("..")) {
            Logger.w(TAG, "readAppFile 拒绝: 非法相对路径 $relativePath")
            return null
        }
        if (!validatePath(relativePath)) {
            Logger.w(TAG, "readAppFile 拒绝: 路径包含非法字符 $relativePath")
            return null
        }
        val path = "/data/data/$appPackage/$relativePath"
        val result = exec("cat $path")
        return if (result.isSuccess) result.getOrDefault("") else null
    }

    companion object {
        private const val TAG = "RootExec"
    }
}
