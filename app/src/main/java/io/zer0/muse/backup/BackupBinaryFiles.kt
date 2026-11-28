package io.zer0.muse.backup

import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import java.io.File
import java.util.Base64

/**
 * v4.1: 目录型文件存储(笔记附件 / 头像私有副本)的备份与恢复。
 *
 * 背景:此前的 [BackupService.backupFileStorePaths] 只覆盖单文件白名单,
 * `note_attachments/`(NoteAttachmentStore,二进制与文本混存)与 `avatar/`
 * (AvatarStorage 的私有副本)完全没进备份 —— 换机恢复后 QuickNoteEntity.attachmentsJson
 * 与 AssistantEntity.avatarImageUrl 全部指向不存在的文件。
 *
 * 设计约束:
 *  - 备份键是**相对 filesDir 的路径**(如 `note_attachments/img_x.png`),值是 Base64 文本;
 *  - 只允许 [allowedPrefixes] 里的目录,恢复时必须重新校验,禁止写到白名单外;
 *  - 单文件 ≤ 8MB、每个目录合计 ≤ 16MB,超限跳过并打日志(不静默截断);
 *  - 读失败跳过并记日志(不让整体备份失败);写失败向上传播(触发恢复点回滚)。
 */
internal object BackupBinaryFiles {

    /** 允许备份的目录前缀(相对 filesDir),恢复时按同一份清单校验。 */
    val allowedPrefixes: List<String> = listOf(
        "note_attachments/",
        // 助手头像实际保存到复数目录；保留旧路径以导入历史备份。
        "avatar/",
        "avatars/",
        // 消息/群聊/会话附件和用户维护的封面、贴纸均位于 app 私有 filesDir。
        "muse_images/",
        "session_files/",
        "covers/",
        "stickers/",
        "voice_messages/",
        "tool_outputs/",
        "workspace/",
        "channel_conversations/",
        // 2026-11-01: 卡片数据改为按卡片分片（CardDataStore），单文件 card_data.json 只留作迁移源。
        // 不加这一条的话，分片之后卡片数据就不在备份范围里 —— 恢复后所有 show_card 内容丢失。
        "card_data/",
        // 2026-11-01: 渠道收件箱改为按条目分片（ChannelInbox），单文件 channel_inbox.json 只留作迁移源。
        // 不加这一条的话，恢复后待派发事件与近期入站历史全部丢失。
        "channel_inbox/",
        // Media Studio 生成的图片/视频本体（`filesDir/media_studio/`）。
        // 只带行不带文件的话，换机后记录还在但一张图都打不开。
        // 单文件受 MAX_FILE_BYTES(8MB) 与目录合计 MAX_DIR_BYTES(16MB) 约束，
        // 超限文件会被跳过并打 W 日志：MediaGenAssetEntity.remoteUrl 保留了原始远程地址，
        // 行还在就能重新取回，所以宁可备份不完整也不把备份撑爆。
        "media_studio/",
    )

    /** 单文件上限:8MB。 */
    const val MAX_FILE_BYTES: Long = 8L * 1024 * 1024

    /** 每个白名单目录合计上限:16MB。 */
    const val MAX_DIR_BYTES: Long = 16L * 1024 * 1024

    private const val TAG = "BackupBinaryFiles"

    /** 导出用的一个待写条目:相对路径 + 原始字节。 */
    data class Entry(val path: String, val bytes: ByteArray) {
        // ByteArray 是引用类型,data class 默认的 equals 语义会误导;显式关掉。
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** 跳过统计。 */
    data class SkipStats(val skippedFiles: Int = 0, val skippedBytes: Long = 0L)

    /** 导出快照:按路径排序的条目 + 跳过统计。 */
    data class Snapshot(val items: List<Entry>, val skipped: SkipStats = SkipStats()) {
        /** 单 JSON 导出用:路径 → Base64。 */
        fun asBase64Map(): Map<String, String> = items.associate { it.path to Base64.getEncoder().encodeToString(it.bytes) }
    }

    /** 恢复结果。 */
    data class RestoreResult(
        val writtenFiles: Int = 0,
        val skippedFiles: Int = 0,
        val skippedBytes: Long = 0L,
    )

    /**
     * 路径校验:白名单前缀 + 拒绝任何可能穿越目录的写法。
     *
     * 明确拒绝:
     *  - 绝对路径(以 `/` 或 `\` 开头)
     *  - 反斜杠(备份键统一用 `/`;反斜杠在 Windows 上是分隔符,语义歧义)
     *  - 路径中的 `..` 段
     *  - 空段 / `.` 段(如 `note_attachments//x`、`note_attachments/./x`)
     *  - NUL 字符
     *  - 只有目录前缀、没有文件名的键
     */
    fun isAllowedPath(path: String): Boolean {
        val prefix = prefixOf(path)
        return (prefix != null || isAllowedRootBackground(path)) &&
            path.length > (prefix?.length ?: 0) &&
            !path.startsWith("/") &&
            !path.startsWith("\\") &&
            !path.contains('\\') &&
            !path.contains('\u0000') &&
            path.split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    /** 返回该路径所属的白名单前缀;不在白名单内返回 null。 */
    fun prefixOf(path: String): String? = allowedPrefixes.firstOrNull { path.startsWith(it) }

    fun listForStreaming(filesDir: File): List<Pair<String, File>> {
        val root = filesDir.canonicalFile
        val files = mutableListOf<Pair<String, File>>()
        for (prefix in allowedPrefixes) {
            val directory = File(root, prefix.removeSuffix("/"))
            if (!directory.isDirectory || directory.canonicalFile != directory) continue
            directory.walkTopDown()
                .onEnter { it.canonicalFile == it }
                .onFail { _, error -> throw error }
                .filter { it.isFile && it.canonicalFile == it }
                .forEach { file ->
                    val path = file.relativeTo(root).invariantSeparatorsPath
                    require(isAllowedPath(path)) { "Invalid backup file path" }
                    files += path to file
                }
        }
        root.listFiles()?.forEach { file ->
            if (file.isFile && file.canonicalFile == file && isAllowedRootBackground(file.name)) {
                files += file.name to file
            }
        }
        return files.sortedBy { it.first }
    }

    private fun isAllowedRootBackground(path: String): Boolean = path.matches(Regex("chat_background(?:_dark)?(?:_[0-9]+)?\\.jpg")) ||
        path.matches(Regex("chat_bg_(?:session|assistant)_[A-Za-z0-9_-]+(?:_[0-9]+)?\\.jpg"))

    private fun quotaPrefix(path: String): String? = prefixOf(path) ?: if (isAllowedRootBackground(path)) "root_backgrounds/" else null

    /**
     * 把备份键解析成 filesDir 下的目标文件。
     *
     * 双重校验:先过 [isAllowedPath] 的语法白名单,再用 canonicalFile 确认父目录
     * 确实落在 filesDir 之内(防软链接把 `avatar/x` 指向外部目录)。
     * @return 合法目标文件;非法返回 null(调用方必须跳过,不得写盘)。
     */
    fun resolveTarget(filesDir: File, path: String): File? = if (!isAllowedPath(path)) {
        null
    } else {
        val root = runCatching { filesDir.canonicalFile }.getOrNull()
        val target = File(filesDir, path)
        val parent = runCatching { target.canonicalFile }.getOrNull()?.parentFile
        target.takeIf { root != null && parent != null && isInside(root, parent) }
    }

    /** 把本应用 filesDir 下的绝对引用迁移到当前设备的私有目录。 */
    fun rebasePrivateFileReference(value: String, filesDir: File, packageName: String): String {
        val fileUriPrefix = "file://".takeIf { value.startsWith(it) }.orEmpty()
        val path = value.removePrefix(fileUriPrefix)
        val escapedPackageName = Regex.escape(packageName)
        val appFilesPrefix = Regex("^/data/(?:user/\\d+/$escapedPackageName|data/$escapedPackageName)/files/")
        val match = appFilesPrefix.find(path) ?: return value
        val relative = path.substring(match.range.last + 1)
        if (!isAllowedPath(relative)) return value
        val target = resolveTarget(filesDir, relative) ?: return value
        return fileUriPrefix + target.absolutePath
    }

    /** 父目录是否落在 root 之内(含 root 本身)。 */
    private fun isInside(root: File, parent: File): Boolean = parent.path == root.path || parent.path.startsWith(root.path + File.separator)

    /** 单文件或目录合计是否超过上限。 */
    fun exceedsLimit(size: Long, usedBytes: Long): Boolean = size > MAX_FILE_BYTES || usedBytes + size > MAX_DIR_BYTES

    /**
     * 读取白名单目录下的文件(原始字节)。
     *
     * 单个文件读取失败、超限都只跳过并累计计数,最后统一打一条 W 日志 ——
     * 用户必须能看出"这次备份不完整",而不是以为备份是完整的。
     * 读取在 meta 写出之前完成,因此 meta 声明的条数与实际写出的条数一致
     * (NDJSON 导入侧的 B-34 对账依赖这一点)。
     */
    fun read(filesDir: File): Snapshot {
        val entries = mutableListOf<Entry>()
        val dirBytes = mutableMapOf<String, Long>()
        var skippedFiles = 0
        var skippedBytes = 0L
        collectFiles(filesDir).forEach { (relative, file) ->
            val prefix = quotaPrefix(relative)
            val size = file.length()
            if (prefix == null || !isAllowedPath(relative) || exceedsLimit(size, dirBytes[prefix] ?: 0L)) {
                skippedFiles++
                skippedBytes += size
                return@forEach
            }
            val bytes = runCatching { file.readBytes() }
                .onFailure { Logger.w(TAG, "读取一个白名单目录文件失败,已跳过", it) }
                .getOrNull()
            val used = dirBytes[prefix] ?: 0L
            if (bytes == null || exceedsLimit(bytes.size.toLong(), used)) {
                skippedFiles++
                skippedBytes += size
                return@forEach
            }
            entries += Entry(relative, bytes)
            dirBytes[prefix] = used + bytes.size
        }
        val stats = SkipStats(skippedFiles, skippedBytes)
        logSkipped("导出", stats)
        return Snapshot(entries, stats)
    }

    /**
     * 把备份里的目录文件写回 filesDir。
     *
     * 跳过(不阻断):白名单外路径、Base64 非法、超出容量上限。
     * 传播(阻断):[write] 抛出的任何异常 —— 文件型存储与三个 Room 库共享恢复账本,
     * 写失败必须能触发 recovery point 回滚,不能静默留下半个附件目录。
     */
    suspend fun restore(
        files: Map<String, String>,
        filesDir: File,
        write: suspend (File, ByteArray) -> Unit = { target, bytes ->
            target.parentFile?.mkdirs()
            AtomicFileStore.writeBytes(target, bytes)
        },
    ): RestoreResult {
        var writtenFiles = 0
        var skippedFiles = 0
        var skippedBytes = 0L
        val dirBytes = mutableMapOf<String, Long>()
        files.toSortedMap().forEach { (path, encoded) ->
            val prefix = quotaPrefix(path)
            val target = if (prefix == null) null else resolveTarget(filesDir, path)
            if (prefix == null || target == null) {
                skippedFiles++
                skippedBytes += base64SizeHint(encoded)
                Logger.w(TAG, "拒绝恢复白名单外的文件路径")
                return@forEach
            }
            val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
            if (bytes == null) {
                skippedFiles++
                skippedBytes += base64SizeHint(encoded)
                Logger.w(TAG, "一个目录文件的 Base64 内容非法,已跳过")
                return@forEach
            }
            val used = dirBytes[prefix] ?: 0L
            if (exceedsLimit(bytes.size.toLong(), used)) {
                skippedFiles++
                skippedBytes += bytes.size
                return@forEach
            }
            // fail-fast:写失败不捕获,交由上层恢复账本回滚。
            write(target, bytes)
            dirBytes[prefix] = used + bytes.size
            writtenFiles++
        }
        val stats = SkipStats(skippedFiles, skippedBytes)
        logSkipped("恢复", stats)
        return RestoreResult(writtenFiles = writtenFiles, skippedFiles = stats.skippedFiles, skippedBytes = stats.skippedBytes)
    }

    /**
     * 导入侧缓冲区:在把 NDJSON 的 `binaryFile` 行留在内存之前先做白名单与容量裁剪。
     *
     * 恶意/损坏备份可以声明任意多、任意大的条目;不在这里拦截就会先 OOM 再谈校验。
     * 写盘时 [restore] 会再校验一遍(纵深防御)。
     */
    class Buffer {
        private val files = linkedMapOf<String, String>()
        private val dirBytes = mutableMapOf<String, Long>()
        private var skippedFiles = 0
        private var skippedBytes = 0L

        val stats: SkipStats get() = SkipStats(skippedFiles, skippedBytes)

        fun offer(path: String, encoded: String) {
            val prefix = quotaPrefix(path)
            if (prefix == null || !isAllowedPath(path)) {
                skippedFiles++
                skippedBytes += base64SizeHint(encoded)
                Logger.w(TAG, "备份中一个目录文件路径不在白名单内,已跳过")
                return
            }
            val estimated = base64SizeHint(encoded)
            if (exceedsLimit(estimated, dirBytes[prefix] ?: 0L)) {
                skippedFiles++
                skippedBytes += estimated
                return
            }
            files[path] = encoded
            dirBytes[prefix] = (dirBytes[prefix] ?: 0L) + estimated
        }

        fun toMap(): Map<String, String> = files
    }
}

/** 目录递归深度上限(两个存储实际都是扁平的,限制深度只为防御异常目录结构)。 */
private const val MAX_DEPTH = 4

private const val BINARY_TAG = "BackupBinaryFiles"

/** 估算 Base64 文本对应的原始字节数(仅用于跳过统计与容量预判,不参与解码校验)。 */
private fun base64SizeHint(encoded: String): Long {
    val padding = when {
        encoded.endsWith("==") -> 2
        encoded.endsWith("=") -> 1
        else -> 0
    }
    return (encoded.length.toLong() / 4 * 3) - padding
}

/** 收集白名单目录下的全部普通文件(相对 filesDir 的路径 → File),按路径排序保证导出稳定。 */
private fun collectFiles(filesDir: File): List<Pair<String, File>> {
    val out = mutableListOf<Pair<String, File>>()
    val root = runCatching { filesDir.canonicalFile }.getOrNull() ?: return emptyList()
    BackupBinaryFiles.allowedPrefixes.forEach { prefix ->
        val directory = File(filesDir, prefix.removeSuffix("/"))
        val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull() ?: return@forEach
        if (!canonicalDirectory.isDirectory || !canonicalDirectory.path.startsWith(root.path + File.separator)) return@forEach
        collectInto(root, canonicalDirectory, canonicalDirectory, prefix, out, 0)
    }
    root.listFiles()?.filter { it.isFile && BackupBinaryFiles.isAllowedPath(it.name) }
        ?.forEach { file ->
            val canonical = runCatching { file.canonicalFile }.getOrNull()
            if (canonical != null && canonical.parentFile == root) out += file.name to canonical
        }
    return out.sortedBy { it.first }
}

private fun collectInto(root: File, allowedDirectory: File, dir: File, prefix: String, out: MutableList<Pair<String, File>>, depth: Int) {
    if (depth > MAX_DEPTH) return
    val children = dir.listFiles()?.sortedBy { it.name } ?: return
    children.forEach { child ->
        val relative = prefix + child.name
        val canonical = runCatching { child.canonicalFile }.getOrNull() ?: return@forEach
        if (!canonical.path.startsWith(allowedDirectory.path + File.separator)) return@forEach
        if (!canonical.path.startsWith(root.path + File.separator)) return@forEach
        when {
            canonical.isDirectory -> collectInto(root, allowedDirectory, canonical, "$relative/", out, depth + 1)
            canonical.isFile -> out += relative to canonical
        }
    }
}

private fun logSkipped(action: String, stats: BackupBinaryFiles.SkipStats) {
    if (stats.skippedFiles == 0) return
    Logger.w(
        BINARY_TAG,
        "$action 跳过 ${stats.skippedFiles} 个目录文件(共 ${stats.skippedBytes} 字节):" +
            "超出单文件 ${BackupBinaryFiles.MAX_FILE_BYTES / 1024 / 1024}MB / " +
            "目录合计 ${BackupBinaryFiles.MAX_DIR_BYTES / 1024 / 1024}MB 上限、路径不合法或读取失败,备份内容不完整",
    )
}
