package io.zer0.muse.tools.system

import android.content.Context
import com.topjohnwu.superuser.Shell
import io.zer0.common.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 权限体系重设计: Root 授权器 — libsu 门面（持久 shell + 被动三态授权缓存）。
 *
 * 设计要点（对照旧实现的三类病灶）:
 *  1. **探测 ≠ 请求**：[isAvailable]/[grantState]/[checkPermission] 全部被动、零副作用，
 *     不创建 shell、不触发 root 管理器弹窗。冷启动与向导 RESUMED 自动刷新走这里，
 *     不再「用户没点任何东西 Magisk/KernelSU 弹窗就糊脸」，也不再在主线程阻塞等 su。
 *     显式授权请求由 [io.zer0.muse.automation.executors.RootExecutor.requestRootAccess]
 *     在用户点按钮时发起（fresh `su -c id`，保留拒绝后可重试的语义）。
 *  2. **持久 shell**：[execute] 走 libsu 的 main shell（一条常驻 su 进程），
 *     不再每条命令起一个新 su 进程；执行前校验 shell 确为 root，拒绝时不静默降级到普通 sh。
 *  3. **三态缓存 + 曾授权持久化**：libsu [Shell.isAppGrantedRoot] 给本进程已确认态；
 *     冷启动尚未确认时，凭「曾授权」标记乐观判可用，真实复验推迟到首次执行。
 *
 * 设备预筛保留广探测（su 路径 + /data/adb + which su + 管理器包）：
 * libsu 的 isAppGrantedRoot 仅按 PATH 里的 su 文件判断，KernelSU 系内核拦截 execve、
 * 文件系统未必暴露 su，单靠它会误报「无 root」，故预筛独立于 libsu。
 */
class RootAuthorizer(private val context: Context) {

    /** 被动授权状态（不创建 shell、不弹窗）。 */
    enum class GrantState {
        /** 设备无 root 方案（无 su / 无 /data/adb / 无管理器）。 */
        NoRoot,

        /** 有 root 方案，但本进程从未确认过授权（也未记录曾授权）。 */
        Unknown,

        /** 曾授权（持久化标记），本进程尚未复验；执行时按需复验。 */
        PreviouslyGranted,

        /** 本进程已确认拿到 root。 */
        Granted,

        /** 本进程已确认被拒绝。 */
        Denied,
    }

    companion object {
        private const val TAG = "RootAuthorizer"

        /** libsu main shell 创建/校验超时（秒）：覆盖用户处理 root 管理器弹窗的时间。 */
        private const val SHELL_TIMEOUT_SECONDS = 30L

        private const val PREFS_NAME = "muse_root_state"
        private const val KEY_EVER_GRANTED = "ever_granted"

        /** su 二进制的常见安装路径。 */
        private val SU_PATHS = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/system/sbin/su",
            "/system/bin/.ext/.su",
        )

        /** 主流 root 管理器包名 → 展示名（用于「有方案但管理器未装」的定向指引）。 */
        private val MANAGER_PACKAGES = listOf(
            "com.topjohnwu.magisk" to "Magisk",
            "io.github.huskydg.magisk" to "Kitsune Mask",
            "me.weishu.kernelsu" to "KernelSU",
            "com.rifsxd.ksunext" to "KernelSU Next",
            "com.sukisu.ultra" to "SukiSU Ultra",
            "me.bmax.apatch" to "APatch",
        )

        @Volatile
        private var builderConfigured = false

        private val builderLock = Any()
    }

    init {
        ensureBuilder()
    }

    /** 配置 libsu 全局 main shell builder（幂等，进程内一次）。 */
    private fun ensureBuilder() {
        if (builderConfigured) return
        synchronized(builderLock) {
            if (builderConfigured) return
            runCatching {
                Shell.setDefaultBuilder(
                    Shell.Builder.create()
                        .setContext(context.applicationContext)
                        .setTimeout(SHELL_TIMEOUT_SECONDS),
                )
                builderConfigured = true
            }.onFailure { Logger.w(TAG, "配置 libsu builder 失败: ${it.message}") }
        }
    }

    /**
     * 快速检测设备是否"可能已 root"(不弹授权弹窗)。
     *
     * KernelSU 系(含 Next/SukiSU)在 GKI/LKM 模式下不在文件系统暴露 su 二进制 ——
     * 内核 sucompat 直接拦截 execve，File.exists() 查不到；追加 /data/adb(root 方案数据目录)
     * 与已安装管理器作为预筛信号。存在这些都不代表已授权，授权以 [grantState] 为准。
     */
    fun isAvailable(): Boolean = SU_PATHS.any { File(it).exists() } ||
        File("/data/adb").exists() ||
        whichSu() ||
        installedManager() != null

    /** 已安装的 root 管理器展示名；无则 null。 */
    fun installedManager(): String? = MANAGER_PACKAGES.firstOrNull { (pkg, _) -> isPackageInstalled(pkg) }?.second

    fun isManagerInstalled(): Boolean = installedManager() != null

    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
    }.isSuccess

    private fun whichSu(): Boolean = runCatching {
        val process = ProcessBuilder("which", "su").redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(2, TimeUnit.SECONDS)
        out.isNotBlank()
    }.getOrDefault(false)

    /**
     * 被动授权状态：读 libsu 本进程缓存 + 曾授权持久化标记，**不创建 shell、不弹窗**。
     * 供向导/启动刷新使用，开销极小（File.exists + 包查询 + 读 prefs）。
     */
    fun grantState(): GrantState {
        // 本进程已确认拿到 root 优先（libsu 非阻塞返回）。
        if (Shell.isAppGrantedRoot() == true) return GrantState.Granted
        // 必须先判设备有无 root 方案：libsu 的 false 仅表示「PATH 无 su 文件」，
        // 完全无 root 的零售机也会返回 false；若先映射成 Denied 会让向导误报
        // 「检测到 root 方案但无管理器」。无方案一律 NoRoot。
        if (!isAvailable()) return GrantState.NoRoot
        // 有 root 方案但 libsu 确认非 root（su 被拒回落非 root shell）→ Denied。
        if (Shell.isAppGrantedRoot() == false) return GrantState.Denied
        return if (everGranted()) GrantState.PreviouslyGranted else GrantState.Unknown
    }

    /**
     * 是否可当作已授权（被动、零副作用）。
     * 已确认授权或曾授权（乐观，执行时复验）均返回 true；向导据此显示，不再阻塞主线程等 su。
     */
    fun checkPermission(): Boolean = when (grantState()) {
        GrantState.Granted, GrantState.PreviouslyGranted -> true
        else -> false
    }

    /** 记录「曾授权」（显式请求成功后调用），供下次冷启动被动判可用。 */
    fun markEverGranted() {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_EVER_GRANTED, true).apply()
        }.onFailure { Logger.w(TAG, "写入曾授权标记失败: ${it.message}") }
    }

    private fun everGranted(): Boolean = runCatching {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_EVER_GRANTED, false)
    }.getOrDefault(false)

    /**
     * 以 root 权限执行命令（libsu 持久 main shell）。
     *
     * 执行是主动使用：main shell 按需创建（已授权则不弹窗）。创建后校验确为 root，
     * 被拒绝时返回失败而非静默降级到普通 sh（libsu 拒绝会回落非 root shell，必须显式拦）。
     */
    fun execute(command: String): RootExecResult {
        ensureBuilder()
        return try {
            val shell = Shell.getShell()
            if (!shell.isRoot()) {
                Logger.w(TAG, "root shell 不可用(未授权或被拒绝),拒绝执行")
                return RootExecResult(-1, "", "root 未授权")
            }
            val result = Shell.cmd(command).exec()
            RootExecResult(
                exitCode = result.code,
                stdout = result.out.joinToString("\n"),
                stderr = result.err.joinToString("\n"),
            )
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
