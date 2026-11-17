package io.zer0.muse.runtime

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 运行环境的落盘布局与状态判定。
 *
 * 唯一事实源是 `current.json`：只有自检通过才写它。目录存在而指针没写，一律按「未安装」处理 ——
 * 这样下载中断、解压失败、进程被杀都不会留下一个「看起来装好了其实跑不起来」的半成品。
 *
 * ```
 * filesDir/runtime/
 *   ubuntu-node/
 *     current.json          {"revision":"r1","installedBytes":241000000}
 *     r1/                   rootfs 内容
 *     staging/              解压中的临时目录，成功后整体改名
 * ```
 */
class RuntimeStore(private val context: Context) {

    private val root: File get() = File(context.filesDir, "runtime")

    /** 运行时总目录（下载缓存、临时文件也放它下面）。 */
    fun rootDir(): File = root.apply { mkdirs() }

    fun dirFor(id: RuntimeId): File = File(root, id.manifestKey)

    fun installDir(id: RuntimeId, revision: String): File = File(dirFor(id), revision)

    fun stagingDir(id: RuntimeId): File = File(dirFor(id), "staging")

    fun pointerFile(id: RuntimeId): File = File(dirFor(id), "current.json")

    /** 按指针判断当前状态。不探目录内容，避免每次调用都做 IO 遍历。 */
    fun state(id: RuntimeId): RuntimeState {
        val file = pointerFile(id)
        if (!file.isFile) return RuntimeState.NotInstalled
        val pointer = runCatching { AppJson.decodeFromString<Pointer>(file.readText()) }.getOrNull()
            ?: return RuntimeState.Broken("指针文件损坏")
        val dir = installDir(id, pointer.revision)
        if (!dir.isDirectory) return RuntimeState.Broken("安装目录缺失：${pointer.revision}")
        return RuntimeState.Ready(pointer.revision, pointer.installedBytes)
    }

    /** 自检通过后调用：写指针，旧版本目录由 [pruneOld] 清理。 */
    suspend fun markReady(id: RuntimeId, revision: String, installedBytes: Long) {
        AtomicFileStore.writeText(pointerFile(id), AppJson.encodeToString(Pointer(revision, installedBytes)))
    }

    /** 卸载：删指针再删目录。先删指针，避免删目录中途失败留下「有指针没内容」。 */
    suspend fun uninstall(id: RuntimeId) {
        pointerFile(id).delete()
        dirFor(id).deleteRecursively()
    }

    /** 清掉 pointer 之外的旧版本目录，返回释放的字节数。 */
    fun pruneOld(id: RuntimeId, keepRevision: String): Long {
        val base = dirFor(id)
        val keep = setOf(keepRevision, "staging")
        var freed = 0L
        base.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name !in keep) {
                freed += child.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                child.deleteRecursively()
                Logger.i(TAG, "清理旧版本目录: ${id.manifestKey}/${child.name}")
            }
        }
        return freed
    }

    @Serializable
    private data class Pointer(val revision: String, val installedBytes: Long)

    private companion object {
        const val TAG = "RuntimeStore"
    }
}
