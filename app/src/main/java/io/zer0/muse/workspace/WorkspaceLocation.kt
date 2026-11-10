package io.zer0.muse.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import io.zer0.common.Logger
import java.io.File

/**
 * 工作区**放在哪儿**（以及怎么搬过去）。
 *
 * 起因很具体：默认位置在 `filesDir/workspace`，那是个任何文件管理器都进不去的私有目录 ——
 * 用户想看看自己的工作区里有什么、想用电脑拷走、想拿别的应用处理，全都做不到，
 * 甚至不知道该去哪儿找。
 *
 * 所以这里提供第二条路径：**放到公共下载目录**（`Download/Muse`），任何文件管理器都能看见。
 *
 * ## 为什么不是默认就放下载目录
 *
 * Android 10（API 29）之后，写公共目录需要「所有文件访问」这项特殊权限，
 * 而它必须由用户去系统设置里亲手打开。**不能默认索取**：一个聊天应用一上来就要全盘访问，
 * 正常人的第一反应是拒绝并卸载。
 *
 * 所以流程是：默认留在私有目录 → 用户在界面上主动点「放到下载目录」→ 申请权限 → 迁移 → 生效。
 *
 * ## 迁移的三条底线
 *
 * 1. **先复制，后删除**：复制完整成功才删源目录，中途失败不留半份；
 * 2. **目标非空不覆盖**：`Download/Muse` 里已经有东西时直接放弃并报原因，
 *    绝不合并 —— 合并是最容易丢数据的那种"聪明"；
 * 3. **失败就退回**：任何一步出错都继续用私有目录，用户的工作区不会因此不可用。
 */
object WorkspaceLocation {

    private const val TAG = "WorkspaceLocation"

    /** 私有目录下的工作区目录名（历史路径，不能改）。 */
    const val INTERNAL_DIR_NAME = "workspace"

    /** 公共下载目录下的工作区目录名。 */
    const val DOWNLOAD_DIR_NAME = "Muse"

    /** 是否已选择放到下载目录（偏好，不用 DataStore：读它的时候可能连 Koin 都还没起来）。 */
    private const val PREFS = "workspace_location"
    private const val KEY_PREFER_DOWNLOADS = "prefer_downloads"

    /** 私有工作区。 */
    fun internalRoot(context: Context): File = File(context.applicationContext.filesDir, INTERNAL_DIR_NAME)

    /** 公共下载目录下的工作区：`Download/Muse`。 */
    fun downloadsRoot(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_DIR_NAME)

    /**
     * 是否拿到了「所有文件访问」。
     *
     * API 30+ 用 `isExternalStorageManager`；更老的版本按可写外部存储判断
     * （那时只要 WRITE_EXTERNAL_STORAGE 就够）。
     */
    fun hasBroadStorageAccess(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }.getOrDefault(false)

    /** 用户是否选择了「放到下载目录」。 */
    fun prefersDownloads(context: Context): Boolean = runCatching {
        prefs(context).getBoolean(KEY_PREFER_DOWNLOADS, false)
    }.getOrDefault(false)

    /**
     * 当前实际使用的工作区根目录。
     *
     * 只有「用户选过 + 权限在 + 目录可写」三条同时成立才用下载目录，
     * 否则一律退回私有目录 —— 宁可用户看不到，也不能让工作区打不开。
     */
    fun resolve(context: Context): File {
        val internal = internalRoot(context)
        if (!prefersDownloads(context)) return internal.ensureDir()
        if (!hasBroadStorageAccess(context)) {
            Logger.w(TAG, "选择过下载目录，但「所有文件访问」未开启，暂用私有目录")
            return internal.ensureDir()
        }
        val downloads = downloadsRoot()
        if (!downloads.canWriteInto()) {
            Logger.w(TAG, "下载目录不可写，暂用私有目录: ${downloads.absolutePath}")
            return internal.ensureDir()
        }
        return downloads.ensureDir()
    }

    /** 记下用户的选择（真正迁移由 [migrateToDownloads] 负责）。 */
    fun setPrefersDownloads(context: Context, prefer: Boolean) {
        runCatching {
            prefs(context).edit().putBoolean(KEY_PREFER_DOWNLOADS, prefer).apply()
        }.onFailure { Logger.w(TAG, "写入工作区位置偏好失败: ${it.message}") }
    }

    /** 结果：给界面说人话用。 */
    sealed interface Migration {
        /** 已经在那儿了，什么都不用做。 */
        data class AlreadyThere(val path: String) : Migration

        /** 搬过去了。 */
        data class Moved(val from: String, val to: String, val files: Int) : Migration

        /** 没搬，附原因。 */
        data class Failed(val reason: String) : Migration
    }

    /**
     * 把私有工作区搬到 `Download/Muse`。
     *
     * 调用前应当先拿到「所有文件访问」，否则一定失败（这里会自己再判一次）。
     */
    fun migrateToDownloads(context: Context): Migration {
        val source = internalRoot(context)
        val target = downloadsRoot()
        return when {
            !hasBroadStorageAccess(context) ->
                Migration.Failed("需要先开启「所有文件访问」权限，否则系统不允许写入下载目录")
            !target.canWriteInto() -> Migration.Failed("下载目录不可写：${target.absolutePath}")
            // 不合并：合并是最容易丢数据的"聪明"做法
            target.exists() && target.listFiles()?.isNotEmpty() == true ->
                Migration.Failed("下载目录里已经有 $DOWNLOAD_DIR_NAME 且不是空的，请先清空或改名后再试")
            source.listFiles().isNullOrEmpty() -> {
                target.ensureDir()
                setPrefersDownloads(context, true)
                Migration.Moved(source.absolutePath, target.absolutePath, 0)
            }
            else -> moveTree(context, source, target)
        }
    }

    /** 真正搬：先复制，完整成功之后才删源目录；失败时清掉目标并退回私有目录。 */
    private fun moveTree(context: Context, source: File, target: File): Migration = runCatching {
        target.ensureDir()
        val copied = copyTree(source, target)
        if (!source.deleteRecursively()) {
            Logger.w(TAG, "源目录未完全删除，保留原样: ${source.absolutePath}")
        }
        setPrefersDownloads(context, true)
        Migration.Moved(source.absolutePath, target.absolutePath, copied) as Migration
    }.getOrElse { error ->
        Logger.e(TAG, "迁移工作区失败: ${error.message}", error)
        // 失败时把目标清掉，避免留下半份让人误会"已经搬好了"
        runCatching { if (target.listFiles().isNullOrEmpty()) target.delete() }
        setPrefersDownloads(context, false)
        Migration.Failed(error.message ?: error.javaClass.simpleName)
    }

    /** 搬回私有目录（下载目录不可用时的退路）。 */
    fun migrateBackInternal(context: Context): Migration {
        val source = downloadsRoot()
        val target = internalRoot(context)
        setPrefersDownloads(context, false)
        if (!source.exists() || source.listFiles().isNullOrEmpty()) {
            target.ensureDir()
            return Migration.AlreadyThere(target.absolutePath)
        }
        return runCatching {
            target.ensureDir()
            val copied = copyTree(source, target)
            source.deleteRecursively()
            Migration.Moved(source.absolutePath, target.absolutePath, copied)
        }.getOrElse { error ->
            Migration.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** 打开系统设置里的「所有文件访问」授权页。 */
    fun requestBroadStorageAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }.getOrNull()
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun File.ensureDir(): File = apply { if (!exists()) runCatching { mkdirs() } }

    /** 目录存在、是目录、并且真的能写（`canWrite` 在部分机型上会骗人，所以试着建个临时文件）。 */
    private fun File.canWriteInto(): Boolean = runCatching {
        if (exists() && !isDirectory) return false
        if (!exists() && !mkdirs()) return false
        val probe = File(this, ".write_probe")
        val ok = probe.createNewFile()
        if (ok) probe.delete()
        ok || probe.exists().not() && canWrite()
    }.getOrDefault(false)

    /** 递归复制，返回复制的文件数。符号链接不跟（避免把外部目录整个拖进来）。 */
    private fun copyTree(source: File, target: File): Int {
        var count = 0
        source.listFiles()?.forEach { child ->
            val destination = File(target, child.name)
            if (child.isDirectory) {
                destination.mkdirs()
                count += copyTree(child, destination)
            } else {
                destination.parentFile?.mkdirs()
                child.copyTo(destination, overwrite = false)
                count++
            }
        }
        return count
    }
}
