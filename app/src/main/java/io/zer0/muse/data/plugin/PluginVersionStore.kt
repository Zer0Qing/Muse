package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.serialization.Serializable
import java.io.File

/** 一个已保留历史版本的元数据；用于回滚前的再校验与 UI 展示。 */
@Serializable
data class RetainedVersionRecord(
    val version: String,
    val publisherId: String,
    val publisherKeyFingerprint: String,
    /** 保留时的内容摘要；回滚前重算比对，防止历史副本被替换。 */
    val contentSha256: String,
    val retainedAtEpochMs: Long,
)

/** 已保留的历史版本：元数据 + 可重新校验的包目录。 */
data class RetainedPluginVersion(
    val pluginId: String,
    val record: RetainedVersionRecord,
    val directory: File,
) {
    val version: String get() = record.version
}

/**
 * 插件历史版本库。
 *
 * 只保存**已通过验签与内容校验**的历史包副本，供用户主动回滚；它不参与执行，
 * 也不出现在插件列表里。回滚时调用方必须重新走一遍签名与内容校验，本类不做信任判断。
 *
 * 目录布局（刻意放在插件目录之外，避免被插件的文件遍历逻辑当成包内文件）：
 * ```
 * <root>/<pluginId>/<version>/meta.json   元数据
 * <root>/<pluginId>/<version>/files/...   包内文件副本
 * ```
 */
class PluginVersionStore(
    private val root: File,
    private val maxRetained: Int = DEFAULT_MAX_RETAINED,
) {

    /** 保留一个已安装版本的副本；同版本重复保留会覆盖旧副本。 */
    fun retain(
        pluginId: String,
        version: String,
        sourceDirectory: File,
        record: RetainedVersionRecord,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Result<Unit> = runCatching {
        requireValidId(pluginId)
        requireValidVersion(version)
        require(sourceDirectory.isDirectory) { "源插件目录不存在" }
        require(record.version == version) { "元数据版本与保留版本不一致" }
        require(record.contentSha256.length == 64) { "内容摘要格式非法" }

        root.mkdirs()
        val target = versionDirectory(pluginId, version)
        val staging = File(pluginIdDirectory(pluginId), ".staging_${version}_${System.nanoTime()}")
        staging.deleteRecursively()
        val filesTarget = File(staging, FILES_DIR)
        require(filesTarget.mkdirs()) { "无法创建版本副本目录" }
        copyPackageFiles(sourceDirectory, filesTarget)
        AtomicFileStore.writeText(
            File(staging, META_FILE),
            AppJson.encodeToString(RetainedVersionRecord.serializer(), record.copy(retainedAtEpochMs = nowEpochMs)),
        )

        // 同版本已存在时先移除旧副本，再整体改名就位；改名失败不会留下半份副本。
        target.deleteRecursively()
        if (!staging.renameTo(target)) {
            staging.copyRecursively(target, overwrite = true)
            staging.deleteRecursively()
        }
        prune(pluginId)
        Logger.i(TAG, "已保留插件历史版本: $pluginId@$version")
    }

    /** 已保留版本，按版本号从新到旧。 */
    fun list(pluginId: String): List<RetainedPluginVersion> {
        val dir = pluginIdDirectory(pluginId)
        val versions = dir.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { readRetained(pluginId, it) }
        return versions.sortedWith(
            compareByDescending<RetainedPluginVersion> { PluginVersion.parse(it.version) ?: PluginVersion(0, 0, 0) }
                .thenByDescending { it.record.retainedAtEpochMs },
        )
    }

    fun find(pluginId: String, version: String): RetainedPluginVersion? =
        list(pluginId).firstOrNull { it.version == version }

    fun delete(pluginId: String, version: String) {
        runCatching {
            requireValidId(pluginId)
            requireValidVersion(version)
            versionDirectory(pluginId, version).deleteRecursively()
        }.onFailure { Logger.w(TAG, "删除历史版本失败: $pluginId@$version", it) }
    }

    fun deleteAll(pluginId: String) {
        runCatching { pluginIdDirectory(pluginId).deleteRecursively() }
            .onFailure { Logger.w(TAG, "删除插件历史版本失败: $pluginId", it) }
    }

    /** 只保留最新的 [maxRetained] 个版本，超出部分按旧到新删除。 */
    fun prune(pluginId: String) {
        val retained = list(pluginId)
        retained.drop(maxRetained).forEach { stale ->
            Logger.i(TAG, "清理过期历史版本: ${stale.pluginId}@${stale.version}")
            stale.directory.deleteRecursively()
        }
    }

    private fun readRetained(pluginId: String, directory: File): RetainedPluginVersion? = runCatching {
        val metaFile = File(directory, META_FILE)
        if (!metaFile.isFile) return@runCatching null
        val record = AppJson.decodeFromString(RetainedVersionRecord.serializer(), metaFile.readText())
        if (record.version != directory.name) return@runCatching null
        val files = File(directory, FILES_DIR)
        if (!files.isDirectory) return@runCatching null
        RetainedPluginVersion(pluginId, record, files)
    }.getOrElse { error ->
        Logger.w(TAG, "历史版本记录损坏，跳过: ${directory.name}", error)
        null
    }

    private fun copyPackageFiles(source: File, target: File) {
        source.listFiles().orEmpty().forEach { entry ->
            val destination = File(target, entry.name)
            if (entry.isDirectory) {
                destination.mkdirs()
                copyPackageFiles(entry, destination)
            } else {
                entry.copyTo(destination, overwrite = true)
            }
        }
    }

    private fun pluginIdDirectory(pluginId: String): File = File(root, pluginId)

    private fun versionDirectory(pluginId: String, version: String): File =
        File(pluginIdDirectory(pluginId), version)

    private fun requireValidId(pluginId: String) {
        require(ID_REGEX.matches(pluginId)) { "插件 id 非法: $pluginId" }
    }

    private fun requireValidVersion(version: String) {
        require(PluginVersion.parse(version) != null) { "版本号非法: $version" }
    }

    companion object {
        private const val TAG = "PluginVersionStore"

        /** 默认保留最近 3 个版本，够回滚一次且不会无界增长。 */
        const val DEFAULT_MAX_RETAINED = 3

        private const val META_FILE = "meta.json"
        private const val FILES_DIR = "files"
        private val ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]*$")
    }
}
