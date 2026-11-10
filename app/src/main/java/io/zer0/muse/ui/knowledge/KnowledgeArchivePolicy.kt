package io.zer0.muse.ui.knowledge

/** 可导入的压缩包格式及其文档来源 scheme。 */
internal enum class ArchiveFormat(val extension: String, val sourceScheme: String) {
    ZIP("zip", "zip"),
    SEVEN_Z("7z", "7z"),
    RAR("rar", "rar"),
    ;

    companion object {
        fun fromFileName(name: String): ArchiveFormat? {
            val extension = KnowledgeArchivePolicy.extensionOf(name)
            return entries.firstOrNull { it.extension == extension }
        }
    }
}

/** 压缩包内文档条目类型。 */
internal enum class ArchiveEntryKind { TEXT, PARSED, SKIP }

/**
 * 压缩包导入策略 — 白名单分类、junk 过滤、上限与临时文件命名。
 *
 * v2.3.1: 追加单文件导入的压缩包拒绝判定([isArchiveFile]),供主知识库页与知识库管理页共用;
 * 需要 ContentResolver 的二进制嗅探见 ImportGuards.kt。
 *
 * 纯逻辑(无 Android 依赖),独立成文件便于单测;解压/落盘/索引流程
 * 见 KnowledgeArchiveImporter.kt。
 */
internal object KnowledgeArchivePolicy {
    /** 单次导入最多处理的条目数。 */
    const val MAX_ENTRIES = 500

    /** 单条目解压上限(字节,解析类落盘用);超限条目按失败处理。 */
    const val MAX_ENTRY_BYTES = 50L * (1L shl 20)

    /** 单次导入累计消耗上限(字节口径,文本条目按字符近似)。 */
    const val MAX_TOTAL_BYTES = 300L * (1L shl 20)

    /** 需要 seekable reader 的源 archive 临时副本上限。 */
    const val MAX_ARCHIVE_BYTES = 300L * (1L shl 20)

    /** 7z / RAR 解码字典的内存上限。 */
    const val MAX_DECODER_MEMORY_KIB = 128 * 1024
    const val MAX_DECODER_MEMORY_BYTES = 128L * 1024 * 1024

    /** 文档正文预览上限,索引仍使用完整内容。 */
    const val MAX_PREVIEW_CHARS = 500_000

    /** 文本条目读取字符上限(约合 chars*2≈bytes 口径);超限截断并加标记。 */
    const val MAX_TEXT_CHARS = 10_000_000

    /** 文本类白名单(md/markdown/txt/csv/json/log)。 */
    private val TEXT_EXTS = setOf("md", "markdown", "txt", "csv", "json", "log")

    /** 解析类白名单(pdf/docx/doc/epub/pptx)。 */
    private val PARSED_EXTS = setOf("pdf", "docx", "doc", "epub", "pptx")

    /** 不支持作为知识库 bundle 导入的其他压缩格式。 */
    private val UNSUPPORTED_ARCHIVE_EXTS = setOf("tar", "gz", "tgz", "bz2", "xz")

    /** 该文件名是否为不应被当文本导入的、不支持的压缩格式。 */
    fun isArchiveFile(name: String): Boolean = extensionOf(name) in UNSUPPORTED_ARCHIVE_EXTS

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** 目录 / 隐藏文件 / macOS 资源叉等不应视为文档的条目。 */
    fun isJunk(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        val base = normalized.substringAfterLast('/')
        val dirOrBlank = normalized.isBlank() || normalized.endsWith('/')
        val macMeta = normalized.startsWith("__MACOSX/", ignoreCase = true)
        val hidden = base.isEmpty() || base.startsWith('.')
        return dirOrBlank || macMeta || hidden
    }

    fun classify(name: String): ArchiveEntryKind {
        val ext = extensionOf(name)
        return when (ext) {
            in TEXT_EXTS -> ArchiveEntryKind.TEXT
            in PARSED_EXTS -> ArchiveEntryKind.PARSED
            else -> ArchiveEntryKind.SKIP
        }
    }

    /** 文本条目的 doc.fileType(与单文件导入口径一致)。 */
    fun textFileType(name: String): String {
        val ext = extensionOf(name)
        return when (ext) {
            "md", "markdown" -> "md"
            "csv" -> "csv"
            "json" -> "json"
            else -> "txt"
        }
    }

    /** 解析类条目的 doc.fileType。 */
    fun parsedFileType(name: String): String {
        val ext = extensionOf(name)
        return when (ext) {
            "pdf" -> "pdf"
            "docx", "doc" -> "docx"
            "epub" -> "epub"
            "pptx" -> "pptx"
            else -> "txt"
        }
    }

    /** 临时落盘文件名:保留扩展名供解析器识别,剔除路径与非法字符,并限长。 */
    fun sanitizeTempName(entryName: String): String {
        val base = entryName.replace('\\', '/').substringAfterLast('/')
        val dot = base.lastIndexOf('.')
        val rawStem = if (dot > 0) base.substring(0, dot) else base
        val stem = rawStem.take(48).replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
        val ext = if (dot > 0) base.substring(dot + 1).take(12) else ""
        return if (ext.isEmpty()) "entry_$stem" else "entry_$stem.$ext"
    }
}

/**
 * 这个 uri 指向的是不是压缩包。
 *
 * 导入分流要用：压缩包自带一套完整资料，直接解压成新库、不先问「放到哪个库」；
 * 普通文件才需要问。文件名优先取 `DISPLAY_NAME`（比 `lastPathSegment` 可靠），
 * 取不到再退回 uri 末段 —— 两处都取不到就当作「不是压缩包」，让用户走正常那条路。
 */
internal fun isArchiveUri(context: android.content.Context, uri: android.net.Uri): Boolean {
    val displayName = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
    val name = displayName ?: uri.lastPathSegment.orEmpty()
    return ArchiveFormat.fromFileName(name.lowercase()) != null
}
