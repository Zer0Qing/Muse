package io.zer0.muse.automation.executors

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.automation.core.PermissionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

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

    /** 检查设备是否存在 su 二进制或内核 root 方案(不一定有权限执行)。 */
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
            // v2.0: KernelSU 系(GKI/LKM)不暴露 su 文件,sucompat 由内核拦截;
            // /data/adb 是 root 方案的数据目录,作为预筛信号。
            paths.any { File(it).exists() } || File("/data/adb").exists() || runCatching {
                ProcessBuilder("which", "su").start().inputStream.bufferedReader().readText().isNotBlank()
            }.getOrDefault(false)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 显式请求 Root 授权:执行一次 `su -c id`,root 管理器(Magisk/KernelSU 等)会据此弹出授权对话框。
     *
     * 只有存在 su 二进制时才值得请求,否则直接返回 [RootRequestFailure.NO_SU_BINARY],不阻塞 UI;
     * 授权对话框可能长时间无人处理,等待超过 [timeoutMs] 即销毁子进程并返回
     * [RootRequestFailure.TIMEOUT]。本方法只发起授权,不静默提权,也不缓存结果 ——
     * 授权状态始终以 [isAvailable] 的真实探测为准。
     *
     * @return 是否拿到 uid=0,以及失败时的可读原因
     */
    suspend fun requestRootAccess(
        timeoutMs: Long = ROOT_REQUEST_TIMEOUT_MS,
    ): RootRequestResult = withContext(Dispatchers.IO) {
        if (!isRooted()) {
            Logger.i(TAG, "root 授权请求跳过: 设备无 su 二进制")
            return@withContext RootRequestResult.failed(RootRequestFailure.NO_SU_BINARY)
        }
        val process = try {
            ProcessBuilder(rootProbeArgs(shellPrefix))
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            Logger.w(TAG, "root 授权请求无法启动: ${e.message}")
            return@withContext RootRequestResult.failed(classifyRootProbeException(e))
        }
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                // root 管理器弹窗一直未被处理:杀掉等待中的 su,避免后续请求排队、设置页卡死。
                runCatching { process.destroyForcibly() }
                Logger.w(TAG, "root 授权请求超时(${timeoutMs}ms)")
                return@withContext rootProbeTimeout()
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val result = evaluateRootProbe(process.exitValue(), output)
            Logger.i(TAG, "root 授权请求结束: granted=${result.granted} failure=${result.failure}")
            result
        } catch (e: Exception) {
            runCatching { process.destroyForcibly() }
            Logger.w(TAG, "root 授权请求异常: ${e.message}")
            RootRequestResult.failed(classifyRootProbeException(e))
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

    // ── Root-specific capabilities ──────────────────────────────────────

    /**
     * Read an Android settings value by name.
     * Supports global/secure/system namespace via prefix: `global:name`, `secure:name`, `system:name`.
     * Without prefix defaults to `secure:`.
     */
    suspend fun settingsGet(name: String): String? {
        // v2.0.1: 支持 "namespace:name"(如 secure:location_mode);无前缀默认 secure。
        val idx = name.indexOf(':')
        val nsPart = if (idx >= 0) name.substring(0, idx) else "secure"
        val keyPart = if (idx >= 0) name.substring(idx + 1) else name
        val safeNs = validateSettingsNamespace(nsPart)
        val safeName = validateSettingsName(keyPart)
        if (safeNs == null || safeName == null) {
            Logger.w(TAG, "settingsGet 拒绝: 非法设置名 $name")
            return null
        }
        val result = exec("settings get $safeNs$safeName")
        return if (result.isSuccess) result.getOrDefault(null) else null
    }

    /**
     * Write an Android settings value.
     * Format: `namespace:name` (e.g., `global:airplane_mode_on`).
     * Requires root. Falls back to `secure:` namespace if none specified.
     */
    suspend fun settingsPut(namespace: String, name: String, value: String): Boolean {
        val safeNs = validateSettingsNamespace(namespace)
        val safeName = validateSettingsName(name)
        if (safeNs == null || safeName == null) {
            Logger.w(TAG, "settingsPut 拒绝: 非法参数 ns=$namespace name=$name")
            return false
        }
        // v2.0.1: 只保留可打印 ASCII,并用 escapeDoubleQuoted 转义,防 root shell 注入。
        val safeValue = value.filter { it.code in 32..126 }
        return exec("settings put $safeNs$safeName \"${escapeDoubleQuoted(safeValue)}\"").isSuccess
    }

    /**
     * Launch an activity via `am start` with optional extras.
     * Extras format: `key=value` pairs separated by `|`, e.g. `title=Hello|count=5`.
     */
    suspend fun amStart(packageName: String, className: String? = null, extras: String? = null): Boolean {
        if (!validatePackageName(packageName)) {
            Logger.w(TAG, "amStart 拒绝: 非法包名 $packageName")
            return false
        }
        // v2.0.1: 组件名与 extras 必须先白名单校验,再拼进 root shell 命令,防命令注入。
        val safeClass = className?.trim()?.takeIf { it.isNotBlank() }?.let { cls ->
            val normalized = if (cls.startsWith(".")) "$packageName$cls" else cls
            if (!COMPONENT_NAME_REGEX.matches(normalized)) {
                Logger.w(TAG, "amStart rejected: invalid component name")
                return false
            }
            normalized
        }
        val safeExtras = extras?.takeIf { it.isNotBlank() }?.let { e ->
            e.split("|").mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val key = pair.substring(0, eq).trim()
                val rawValue = pair.substring(eq + 1)
                if (key.isBlank() || rawValue.isBlank() || !EXTRA_KEY_REGEX.matches(key)) return@mapNotNull null
                "--es $key \"${escapeDoubleQuoted(rawValue)}\""
            }.joinToString(" ").takeIf { it.isNotEmpty() }
        }
        val cmd = buildString {
            append("am start -n $packageName")
            if (safeClass != null) append("/$safeClass")
            if (safeExtras != null) append(" $safeExtras")
        }
        return exec(cmd).isSuccess
    }

    /**
     * List installed packages, optionally filtered by a substring match on package name.
     */
    suspend fun listPackages(filter: String? = null): List<String> {
        // v2.0.1: 改用 pm list packages 原生过滤参数;先白名单校验,防 root shell 注入。
        val safeFilter = filter?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!PACKAGE_FILTER_REGEX.matches(it)) {
                Logger.w(TAG, "listPackages rejected: invalid filter")
                return emptyList()
            }
            it
        }
        val cmd = "pm list packages" + (safeFilter?.let { " $it" } ?: "")
        val result = exec(cmd)
        if (!result.isSuccess) return emptyList()
        return result.getOrDefault("").lineSequence()
            .mapNotNull { line -> Regex("package:(.+)").find(line)?.groupValues?.get(1) }
            .distinct()
            .sorted()
            .toList()
    }

    /**
     * Tail the device logcat (last [lines] lines).
     * Output is capped at [maxChars] to prevent context explosion.
     */
    suspend fun logcatTail(lines: Int = 100, maxChars: Int = 10_000): String {
        val cmd = "logcat -d -t $lines"
        val result = exec(cmd)
        if (!result.isSuccess) return "logcat failed: ${result.getOrDefault("")}" 
        val output = result.getOrDefault("")
        return if (output.length > maxChars) output.take(maxChars) + "\n... (truncated)" else output
    }

    /**
     * Inject raw text into the focused input field via `su input text`.
     * Safer than shell inputText because su context has different restrictions.
     */
    suspend fun inputInject(text: String): Boolean {
        if (text.isBlank()) return false
        // v2.0.1: 双引号字符串内必须转义 \\ \" $ ` 与换行,否则 root shell 可被注入任意命令。
        val result = exec("input text \"${escapeDoubleQuoted(text)}\"")
        if (result.isSuccess) return true
        // Fallback: write to clipboard then paste
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("auto", text))
            exec("input keyevent 279").isSuccess
        } catch (e: Exception) {
            Logger.w(TAG, "inputInject fallback failed: ${e.message}")
            false
        }
    }

    /** H-SEC: escape a value embedded inside a double-quoted shell string. */
    private fun escapeDoubleQuoted(value: String): String = buildString(value.length + 8) {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append('\\').append('$')
                '`' -> append('\\').append('`')
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
    }

    /** H-SEC: Only allow alphanumeric + underscore for settings keys. */
    private fun validateSettingsName(name: String): String? =
        name.takeIf { it.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")) }

    /** H-SEC: Only allow global/secure/system namespaces. */
    private fun validateSettingsNamespace(ns: String): String? =
        when (ns.lowercase()) {
            "global" -> "global "
            "system" -> "system "
            "secure", "" -> "secure "
            else -> null
        }

    companion object {
        private const val TAG = "RootExec"

        /** Root 授权请求等待上限:需覆盖用户手动点 root 管理器弹窗的时间,超时即放弃。 */
        private const val ROOT_REQUEST_TIMEOUT_MS = 30_000L

        /** H-SEC: 组件名白名单(允许 $ 用于内部类)。 */
        private val COMPONENT_NAME_REGEX = Regex("^[A-Za-z0-9_.$]+$")

        /** H-SEC: am start extras 的 key 白名单。 */
        private val EXTRA_KEY_REGEX = Regex("^[A-Za-z0-9_.-]{1,64}$")

        /** H-SEC: pm list packages 过滤词白名单。 */
        private val PACKAGE_FILTER_REGEX = Regex("^[A-Za-z0-9_.-]{1,64}$")
    }
}
