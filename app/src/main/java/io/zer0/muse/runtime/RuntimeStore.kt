package io.zer0.muse.runtime

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

class RuntimeStore(private val context: Context) {

    private val root: File get() = File(context.filesDir, "runtime")

    fun rootDir(): File = root.apply { mkdirs() }

    fun dirFor(id: RuntimeId): File = File(root, id.manifestKey)

    fun installDir(id: RuntimeId, revision: String): File {
        require(isSafeRevision(revision)) { "运行环境版本目录名非法" }
        val pointer = readPointer(id)
        val directoryRevision = when (revision) {
            pointer?.revision -> pointer.directoryRevision
            pointer?.previousRevision -> pointer.previousDirectoryRevision ?: revision
            else -> revision
        }
        return File(dirFor(id), directoryRevision)
    }

    fun stagingDir(id: RuntimeId): File = File(dirFor(id), "staging")

    fun pointerFile(id: RuntimeId): File = File(dirFor(id), "current.json")

    fun currentRevision(id: RuntimeId): String? = readPointer(id)?.revision

    fun previousRevision(id: RuntimeId): String? = readPointer(id)?.previousRevision

    fun operationFile(id: RuntimeId = RuntimeId.UBUNTU_NODE): File = File(dirFor(id), "operation.json")

    fun state(id: RuntimeId): RuntimeState {
        val file = pointerFile(id)
        if (!file.isFile) return RuntimeState.NotInstalled
        val pointer = runCatching { AppJson.decodeFromString<Pointer>(file.readText()) }
            .onFailure { Logger.w(TAG, "运行环境指针解析失败", it) }
            .getOrNull()
            ?: return RuntimeState.Broken("指针文件损坏")
        if (!isSafeRevision(pointer.revision) || !isSafeRevision(pointer.directoryRevision)) {
            return RuntimeState.Broken("指针版本非法")
        }
        return when (val health = health(id, pointer.revision)) {
            is RuntimeHealth.Ready -> RuntimeState.Ready(pointer.revision, pointer.installedBytes)
            is RuntimeHealth.Broken -> RuntimeState.Broken(health.reason)
        }
    }

    fun health(id: RuntimeId, revision: String): RuntimeHealth {
        if (!isSafeRevision(revision)) return RuntimeHealth.Broken(revision, "运行环境版本目录名非法", listOf(revision))
        val pointer = readPointer(id)
        val directoryRevision = when (revision) {
            pointer?.revision -> pointer.directoryRevision
            pointer?.previousRevision -> pointer.previousDirectoryRevision ?: revision
            else -> revision
        }
        return healthAt(id, directoryRevision, revision)
    }

    fun healthAt(id: RuntimeId, directoryRevision: String, revision: String = directoryRevision): RuntimeHealth {
        if (!isSafeRevision(directoryRevision)) {
            return RuntimeHealth.Broken(revision, "运行环境版本目录名非法", listOf(directoryRevision))
        }
        val rootfs = File(dirFor(id), directoryRevision)
        val native = File(context.applicationInfo.nativeLibraryDir)
        return inspectHealth(rootfs, native, revision)
    }

    suspend fun markReady(id: RuntimeId, revision: String, installedBytes: Long, directoryRevision: String = revision) {
        require(isSafeRevision(revision) && isSafeRevision(directoryRevision)) { "运行环境版本目录名非法" }
        val old = readPointer(id)
        val previousRevision = if (old != null && old.revision != revision) old.revision else old?.previousRevision
        val previousDirectory = if (old != null && old.revision != revision) {
            old.directoryRevision
        } else {
            old?.previousDirectoryRevision
        }
        val previousBytes = if (old != null && old.revision != revision) {
            old.installedBytes
        } else {
            old?.previousInstalledBytes
        }
        val pointer = Pointer(
            revision = revision,
            installedBytes = installedBytes,
            directoryRevision = directoryRevision,
            previousRevision = previousRevision,
            previousInstalledBytes = previousBytes,
            previousDirectoryRevision = previousDirectory,
        )
        AtomicFileStore.writeText(pointerFile(id), AppJson.encodeToString(pointer))
    }

    suspend fun rollback(id: RuntimeId, revision: String? = null): Result<Unit> {
        val current = readPointer(id)
            ?: return Result.failure(IllegalStateException("没有可回滚的运行环境指针"))
        val targetRevision = revision ?: current.previousRevision
            ?: return Result.failure(IllegalStateException("没有可用的旧版本"))
        val invalidTarget = targetRevision == current.revision || !isSafeRevision(targetRevision)
        if (invalidTarget) return Result.failure(IllegalArgumentException("回滚版本无效"))
        val targetDirectoryRevision = if (targetRevision == current.previousRevision) {
            current.previousDirectoryRevision ?: targetRevision
        } else {
            targetRevision
        }
        return when (val health = healthAt(id, targetDirectoryRevision, targetRevision)) {
            is RuntimeHealth.Broken -> Result.failure(IllegalStateException("旧版本不可回滚：${health.reason}"))
            is RuntimeHealth.Ready -> {
                val bytes = if (targetRevision == current.previousRevision) {
                    current.previousInstalledBytes ?: directoryBytes(File(dirFor(id), targetDirectoryRevision))
                } else {
                    directoryBytes(File(dirFor(id), targetDirectoryRevision))
                }
                val pointer = Pointer(
                    revision = health.revision,
                    installedBytes = bytes,
                    directoryRevision = targetDirectoryRevision,
                    previousRevision = current.revision,
                    previousInstalledBytes = current.installedBytes,
                    previousDirectoryRevision = current.directoryRevision,
                )
                AtomicFileStore.writeText(pointerFile(id), AppJson.encodeToString(pointer))
                Result.success(Unit)
            }
        }
    }

    suspend fun uninstall(id: RuntimeId) = withContext(Dispatchers.IO) {
        pointerFile(id).delete()
        dirFor(id).deleteRecursively()
        val archivePrefix = "${id.manifestKey}-"
        root.listFiles()?.filter { file ->
            file.isFile && file.name.startsWith(archivePrefix) &&
                (file.name.endsWith(".tar.gz") || file.name.endsWith(".tar.gz.part") || file.name.endsWith(".tar.gz.part.meta"))
        }?.forEach { file ->
            if (!file.delete()) Logger.w(TAG, "无法清理运行环境临时归档: ${file.name}")
        }
    }

    fun pruneOld(id: RuntimeId, keepRevision: String, previousRevision: String? = null): Long {
        val base = dirFor(id)
        val pointer = readPointer(id)
        val retainedCurrentDirectory = pointer?.directoryRevision ?: keepRevision
        val retainedPreviousDirectory = previousRevision ?: pointer?.previousDirectoryRevision
        val keep = listOfNotNull(retainedCurrentDirectory, retainedPreviousDirectory, "staging").toSet()
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

    private fun readPointer(id: RuntimeId): Pointer? {
        val file = pointerFile(id)
        if (!file.isFile) return null
        return runCatching { AppJson.decodeFromString<Pointer>(file.readText()) }
            .onFailure { Logger.w(TAG, "运行环境指针解析失败", it) }
            .getOrNull()
            ?.takeIf {
                isSafeRevision(it.revision) && isSafeRevision(it.directoryRevision) &&
                    (it.previousRevision == null || isSafeRevision(it.previousRevision)) &&
                    (it.previousDirectoryRevision == null || isSafeRevision(it.previousDirectoryRevision))
            }
    }

    private fun directoryBytes(directory: File): Long = directory.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    @Serializable
    private data class Pointer(
        val revision: String,
        val installedBytes: Long,
        val directoryRevision: String = revision,
        val previousRevision: String? = null,
        val previousInstalledBytes: Long? = null,
        val previousDirectoryRevision: String? = previousRevision,
    )

    companion object {
        private const val TAG = "RuntimeStore"
        private val REVISION_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
        private val ROOTFS_PATHS = listOf(
            "usr/local/bin/node",
            "usr/local/lib/node_modules/npm/bin/npm-cli.js",
            "usr/bin",
            "tmp",
        )
        private val NATIVE_PATHS = listOf(
            "libmuse_proot.so",
            "libmuse_proot_loader.so",
            "libtalloc.so",
            "libandroid-shmem.so",
        )

        fun isSafeRevision(revision: String): Boolean = REVISION_PATTERN.matches(revision) && ".." !in revision

        fun inspectHealth(rootfs: File, nativeLibraryDir: File, revision: String): RuntimeHealth {
            if (!rootfs.isDirectory) {
                return RuntimeHealth.Broken(revision, "安装目录缺失：$revision", listOf(rootfs.path))
            }
            val missingRootfs = ROOTFS_PATHS
                .map { File(rootfs, it) }
                .filterNot(File::exists)
                .map { "rootfs/${it.relativeTo(rootfs).invariantSeparatorsPath}" }
            val missingNative = NATIVE_PATHS
                .map { File(nativeLibraryDir, it) }
                .filterNot(File::isFile)
                .map { "native/${it.name}" }
            val missing = missingRootfs + missingNative
            if (missing.isNotEmpty()) {
                return RuntimeHealth.Broken(revision, "运行环境关键文件缺失", missing)
            }
            return RuntimeHealth.Ready(revision, ROOTFS_PATHS + NATIVE_PATHS)
        }
    }
}
