package io.zer0.muse.data.sticker

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import io.zer0.muse.util.readZipEntryWithLimit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * v1.95: 表情包库仓库 — 基于文件存储(不碰 Room/MuseDb)。
 *
 * 存储结构:
 *  - 图片文件: `filesDir/stickers/<category>/<filename>`
 *  - 清单文件: `filesDir/stickers/manifest.json`(JSON 序列化的 [StickerItem] 列表)
 *
 * 设计要点:
 *  - 导入 zip 时按 zip 内的**文件夹结构**作为分类(如 zip 内 `猫猫/001.png` 归入"猫猫"分类);
 *    无文件夹的图片归入"默认"分类。
 *  - 只接受图片文件(png/jpg/jpeg/gif/webp/bmp),忽略 __MACOSX/.DS_Store 等噪声文件。
 *  - 清单读写用 [Mutex] 保护,IO 在 [Dispatchers.IO]。
 *  - 用 [AppJson] 序列化(已配置 ignoreUnknownKeys,兼容字段演进)。
 *
 * @param appContext 应用 Context(用于 filesDir / contentResolver)
 */
/** 配文长度上限：说明用的字段，不是正文。 */
private const val MAX_CAPTION_CHARS = 200

class StickerLibraryRepository(private val appContext: Context) {

    /** stickers 根目录(`filesDir/stickers`)。 */
    private val rootDir: File get() = File(appContext.filesDir, "stickers").apply { if (!exists()) mkdirs() }

    /** 清单文件(`filesDir/stickers/manifest.json`)。 */
    private val manifestFile: File get() = File(rootDir, "manifest.json")

    /** 清单读写互斥锁(保证并发导入/删除的原子性)。 */
    private val manifestMutex = Mutex()

    /** 支持的图片扩展名(小写)。 */
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    /** 解不出尺寸时的中性回退：不因尺寸挡下用户选中的图（不是常量，所以用小写名）。 */
    private val neutralDimension = 500 to 500

    // v1.117: 导入体积限制,防 ZIP 炸弹 / OOM(对齐 DocumentParser 的限制策略)
    private val MAX_SINGLE_ENTRY_BYTES = 10L * 1024 * 1024 // 单个图片 10MB
    private val MAX_TOTAL_IMPORT_BYTES = 200L * 1024 * 1024 // 累计 200MB
    private val MAX_ENTRY_COUNT = 1000 // 最多 1000 个文件

    // ── 公开 API ──────────────────────────────────────────────────────────

    /** 列出所有分类(按名称排序,空库返回空列表)。 */
    suspend fun listCategories(): List<String> = withContext(Dispatchers.IO) {
        manifestMutex.withLock { readManifest() }.map { it.category }.distinct().sorted()
    }

    /**
     * 列出表情包(可按分类筛选)。
     *
     * @param category 分类筛选;null 或空字符串表示列出全部
     */
    suspend fun listStickers(category: String? = null): List<StickerItem> = withContext(Dispatchers.IO) {
        val all = manifestMutex.withLock { readManifest() }
        val filtered = if (category.isNullOrBlank()) all else all.filter { it.category == category }
        filtered.sortedWith(compareBy({ it.category }, { it.fileName }))
    }

    /**
     * v1.0.53: 从 Uri 自动识别并导入 — 支持 ZIP 压缩包和单张图片。
     *
     * 根据 Uri 的文件扩展名判断类型:
     *  - .zip → 调用 [importZip]
     *  - png/jpg/jpeg/gif/webp/bmp → 调用 [importImage]
     *  - 其他 → 返回错误
     *
     * 这是用户导入的统一入口,管理页应调用此方法而非直接调用 importZip,
     * 以支持单张图片导入和避免 MIME 类型过滤问题。
     *
     * @return 导入数量;失败时 resultOf 返回 Error
     */
    suspend fun importUri(uri: Uri, onProgress: ((phase: String, done: Int, total: Int?) -> Unit)? = null): Result<Int> =
        withContext(Dispatchers.IO) {
            val displayName = queryDisplayName(uri)
            Logger.i("StickerLibraryRepository", "importUri: uri=$uri, displayName=$displayName")
            if (displayName.isNullOrBlank()) {
                // 从某些分享入口进来的 Uri 没有文件名。以前一律按 ZIP 处理，
                // 于是文档形态会被当成"解压失败"；这里先按容器嗅一次再回落。
                Logger.w("StickerLibraryRepository", "importUri: 无法获取文件名,先按文档容器尝试")
                val asDocument = importDocument(uri, "导入的素材包", onProgress)
                return@withContext if (asDocument.isSuccess) {
                    asDocument
                } else {
                    Logger.i("StickerLibraryRepository", "importUri: 非文档容器,回落 ZIP 路径")
                    importZip(uri, onProgress)
                }
            }
            val lowerName = displayName.lowercase()
            when {
                lowerName.endsWith(".zip") -> {
                    Logger.i("StickerLibraryRepository", "importUri: 识别为 ZIP 文件,调用 importZip")
                    importZip(uri, onProgress)
                }
                isImageFile(displayName) -> {
                    Logger.i("StickerLibraryRepository", "importUri: 识别为单张图片,调用 importImage")
                    importImage(uri, displayName, onProgress)
                }
                documentKindOf(displayName) != null -> {
                    Logger.i("StickerLibraryRepository", "importUri: 识别为文档容器,调用 importDocument")
                    importDocument(uri, displayName, onProgress)
                }
                else -> {
                    Logger.w("StickerLibraryRepository", "importUri: 不支持的文件类型: $displayName")
                    Result.Error(
                        "不支持的文件类型: $displayName（支持：ZIP 压缩包、docx/pptx 文档、png/jpg/jpeg/gif/webp/bmp 图片）",
                    )
                }
            }
        }

    /**
     * v1.0.53: 导入单张图片到"默认"分类。
     *
     * @param uri 图片 Uri
     * @param displayName 文件名(用于确定扩展名)
     * @return 导入数量(1 或 0);失败时 resultOf 返回 Error
     */
    suspend fun importImage(
        uri: Uri,
        displayName: String,
        onProgress: ((phase: String, done: Int, total: Int?) -> Unit)? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
        resultOf {
            Logger.i("StickerLibraryRepository", "importImage: 开始导入单张图片, uri=$uri, name=$displayName")
            val now = System.currentTimeMillis()
            val category = "默认"
            val targetDir = File(rootDir, category).apply { mkdirs() }

            // 文件名冲突时附加短 uuid 后缀
            var finalName = displayName
            var targetFile = File(targetDir, finalName)
            if (targetFile.exists()) {
                val dotIdx = displayName.lastIndexOf('.')
                val base = if (dotIdx > 0) displayName.substring(0, dotIdx) else displayName
                val ext = if (dotIdx > 0) displayName.substring(dotIdx) else ""
                finalName = "${base}_${UUID.randomUUID().toString().take(6)}$ext"
                targetFile = File(targetDir, finalName)
                Logger.i("StickerLibraryRepository", "importImage: 文件名冲突,重命名为 $finalName")
            }

            // 读取图片并写入目标文件
            appContext.contentResolver.openInputStream(uri).use { input ->
                if (input == null) error("无法打开所选图片")
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val fileSize = targetFile.length()
            Logger.i("StickerLibraryRepository", "importImage: 图片已写入 ${targetFile.path} ($fileSize bytes)")

            // 写清单
            val item = StickerItem(
                id = UUID.randomUUID().toString(),
                category = category,
                fileName = finalName,
                relativePath = "stickers/$category/$finalName",
                addedAt = now,
            )
            manifestMutex.withLock {
                val current = readManifest().toMutableList()
                current.add(item)
                writeManifest(current)
            }
            Logger.i("StickerLibraryRepository", "importImage: 导入成功, id=${item.id}")
            1
        }.onError { msg, t ->
            Logger.w("StickerLibraryRepository", "importImage 失败: $msg", t)
        }
    }

    /**
     * v1.0.53: 查询 Uri 的显示文件名(通过 ContentResolver OPENABLE_COLUMNS)。
     *
     * 用于在导入前判断文件类型(ZIP 还是图片)。返回 null 表示无法获取文件名
     * (常见于某些文件管理器不返回 DISPLAY_NAME 列)。
     */
    private fun queryDisplayName(uri: Uri): String? {
        return try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        } catch (t: Throwable) {
            if (t is kotlin.coroutines.cancellation.CancellationException) throw t
            Logger.w("StickerLibraryRepository", "queryDisplayName 失败: ${t.message}")
            null
        }
    }

    /**
     * 从 Uri 读取 zip 并解压到 stickers 目录,按 zip 内文件夹结构自动分类。
     *
     * 解压规则:
     *  - zip 内 `猫猫/001.png` 归入"猫猫"分类
     *  - zip 内无文件夹的图片(如 `001.png`)归入"默认"分类
     *  - 只接受图片文件(png/jpg/jpeg/gif/webp/bmp),忽略其他文件(__MACOSX/.DS_Store 等)
     *  - 文件名含中文时:先按 UTF-8 尝试,出现替换字符(乱码)再按 GBK 重试
     *
     * @return 导入数量;失败时 resultOf 返回 Error
     */
    suspend fun importZip(uri: Uri, onProgress: ((phase: String, done: Int, total: Int?) -> Unit)? = null): Result<Int> =
        withContext(Dispatchers.IO) {
            resultOf {
                Logger.i("StickerLibraryRepository", "importZip: 开始导入, uri=$uri")
                val imported = mutableListOf<StickerItem>()
                val now = System.currentTimeMillis()
                // v1.0.52: 改为 staging 流式落盘 — 每读一个条目立即写临时目录,
                // 内存只保留单张图字节(≤10MB),避免大包把所有图片 ByteArray 攒在内存导致 OOM。
                // 全部成功后原子移入正式目录 + 写清单;任一步失败清理 staging,不留半成品。
                val stagingDir = File(rootDir, ".staging_${UUID.randomUUID().toString().take(8)}")
                stagingDir.mkdirs()
                // 每个 staging 条目:分类 + 最终文件名 + staging 文件名
                val pendingEntries = mutableListOf<PendingEntry>()
                // v1.117: 累计大小/数量计数器,防 ZIP 炸弹
                var totalBytes = 0L

                try {
                    val input = appContext.contentResolver.openInputStream(uri)
                    if (input == null) {
                        Logger.w("StickerLibraryRepository", "importZip: ContentResolver 返回 null InputStream")
                        error("无法打开所选文件")
                    }
                    Logger.i("StickerLibraryRepository", "importZip: InputStream 已打开,开始读取 ZIP 条目")
                    input.use { stream ->
                        // v1.112 (F4): 用 GBK charset 构造 ZipInputStream 修复中文文件名乱码。
                        // ZIP 规范:entry 的 EFS 标志位(bit 11)=1 时 ZipInputStream 忽略传入 charset 强制 UTF-8;
                        // EFS=0 时用传入的 charset。Windows 中文工具(好压/2345/WinRAR 中文版)生成的 zip
                        // 通常 EFS=0 且文件名用 GBK 编码,传 GBK 能正确解码;标准 UTF-8 zip(EFS=1)仍用 UTF-8。
                        ZipInputStream(stream, Charset.forName("GBK")).use { zis ->
                            var entry: ZipEntry? = zis.nextEntry
                            var entryCount = 0
                            while (entry != null) {
                                entryCount++
                                // v1.0.54: 解压阶段进度回调(总数未知,先计数)
                                if (entryCount % 10 == 0) {
                                    onProgress?.invoke("正在解压", entryCount, null)
                                }
                                if (!entry.isDirectory) {
                                    val rawName = entry.name
                                    // 跳过 macOS 系统目录与 .DS_Store 噪声
                                    if (rawName.contains("__MACOSX") || rawName.endsWith(".DS_Store")) {
                                        if (entryCount % 100 == 0) {
                                            Logger.d(
                                                "StickerLibraryRepository",
                                                "importZip: 已跳过 $entryCount 个噪声条目(示例: $rawName)",
                                            )
                                        }
                                        zis.closeEntry()
                                        entry = zis.nextEntry
                                        continue
                                    }
                                    // 解析分类与文件名
                                    val (category, fileName) = parseCategoryAndName(rawName)
                                    if (fileName != null && isImageFile(fileName)) {
                                        if (entryCount % 100 == 0) {
                                            Logger.d(
                                                "StickerLibraryRepository",
                                                "importZip: 已发现 $entryCount 个条目,当前图片: $rawName",
                                            )
                                        }
                                        // v1.117: 数量限制
                                        if (pendingEntries.size >= MAX_ENTRY_COUNT) {
                                            error("压缩包内图片数量超过限制 $MAX_ENTRY_COUNT,已中止导入")
                                        }
                                        // v1.117: 带大小限制的读取,防 ZIP 炸弹单条目 OOM
                                        val bytes = readZipEntryWithLimit(zis, MAX_SINGLE_ENTRY_BYTES, rawName)
                                        totalBytes += bytes.size
                                        if (totalBytes > MAX_TOTAL_IMPORT_BYTES) {
                                            error("压缩包累计体积超过限制 ${MAX_TOTAL_IMPORT_BYTES / 1024 / 1024}MB,已中止导入")
                                        }
                                        // 立即写 staging 临时文件,bytes 可被 GC,内存峰值 = 单张图
                                        val stagingFile = File(stagingDir, "${pendingEntries.size}_$fileName")
                                        stagingFile.outputStream().use { it.write(bytes) }
                                        pendingEntries.add(PendingEntry(category, fileName, stagingFile))
                                    } else {
                                        if (entryCount % 100 == 0) {
                                            Logger.d(
                                                "StickerLibraryRepository",
                                                "importZip: 已跳过 $entryCount 个条目(当前非图片: $rawName)",
                                            )
                                        }
                                    }
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                            Logger.i("StickerLibraryRepository", "importZip: ZIP 读取完成,共 $entryCount 个条目,其中 ${pendingEntries.size} 个图片")
                        }
                    }

                    if (pendingEntries.isEmpty()) error("压缩包内未找到图片文件(png/jpg/jpeg/gif/webp/bmp)")

                    Logger.i("StickerLibraryRepository", "importZip: 开始写入正式目录和清单")
                    imported.addAll(commitStaged(pendingEntries, now, onProgress))
                    Logger.i("StickerLibraryRepository", "importZip: 导入完成,共 ${imported.size} 张图片")
                } finally {
                    // 无论成败,清理 staging 目录(成功时已空,失败时含半成品)
                    resultOf { stagingDir.deleteRecursively() }
                        .onError { msg, t -> Logger.w("StickerLibraryRepository", "清理 staging 目录失败: $msg", t) }
                }
                imported.size
            }.onError { msg, t ->
                Logger.w("StickerLibraryRepository", "importZip 失败: $msg", t)
            }
        }

    /**
     * staging → 正式目录 + 写清单（压缩包与文档导入共用一份）。
     *
     * 抽出来的原因不是省事，是**两处各写一份就会漂移**：文件名冲突规则、
     * 路径穿越防护、清单落盘顺序，任何一处漏改都会表现成"某个入口导入的图会覆盖别人"。
     */
    private suspend fun commitStaged(
        pending: List<PendingEntry>,
        now: Long,
        onProgress: ((phase: String, done: Int, total: Int?) -> Unit)?,
    ): List<StickerItem> = manifestMutex.withLock {
        val current = readManifest().toMutableList()
        val imported = mutableListOf<StickerItem>()
        for ((idx, pe) in pending.withIndex()) {
            if (idx % 10 == 0) onProgress?.invoke("正在写入", idx, pending.size)
            val targetDir = File(rootDir, pe.category).apply { mkdirs() }
            val targetFile = uniqueTargetFile(targetDir, pe.fileName)
            // Zip Slip / 路径穿越防护：目标必须仍在 stickers 根内
            if (!targetFile.canonicalPath.startsWith(rootDir.canonicalPath + File.separator)) {
                Logger.w("StickerLibraryRepository", "跳过越界条目: ${pe.category}/${pe.fileName}")
                continue
            }
            if (!pe.stagingFile.renameTo(targetFile)) {
                pe.stagingFile.copyTo(targetFile, overwrite = false)
            }
            val item = StickerItem(
                id = UUID.randomUUID().toString(),
                category = pe.category,
                fileName = targetFile.name,
                relativePath = "stickers/${pe.category}/${targetFile.name}",
                addedAt = now,
                caption = pe.caption?.takeIf { it.isNotBlank() },
            )
            current.add(item)
            imported.add(item)
        }
        writeManifest(current)
        imported
    }

    /** 同名时附加短 uuid 后缀，绝不覆盖已有文件。 */
    private fun uniqueTargetFile(dir: File, wantedName: String): File {
        val first = File(dir, wantedName)
        if (!first.exists()) return first
        val dot = wantedName.lastIndexOf('.')
        val base = if (dot > 0) wantedName.substring(0, dot) else wantedName
        val ext = if (dot > 0) wantedName.substring(dot) else ""
        return File(dir, base + "_" + UUID.randomUUID().toString().take(6) + ext)
    }

    /**
     * 从 Office 文档（docx / pptx）导入表情（表情包重做 P2）。
     *
     * 市面上传的素材包多数是 PDF + DOCX 这一对而不是压缩包：微信里传 zip 手机端打不开，
     * 而 docx 里每张图都能长按另存、正文还带着每张的中文描述。这里把它变成"选中文件→直接进库"。
     *
     * 分类取正文第一段（样本里就是包名，如"呆猫八条"），取不到用文件名。
     * 图旁描述作为**配文**保留 —— 那是唯一能说明"这张图什么意思"的信息。
     */
    suspend fun importDocument(
        uri: Uri,
        displayName: String,
        onProgress: ((phase: String, done: Int, total: Int?) -> Unit)? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
        resultOf {
            val container = appContext.contentResolver.openInputStream(uri)?.use { stream ->
                StickerContainerReader.read(stream)
            } ?: error("无法打开所选文件（不是有效的 docx/pptx？）")
            val scanned = StickerDocumentScanner.scan(container.kind, container.entries)
            val category = safeCategoryName(scanned.title ?: displayName.substringBeforeLast('.'))
            val ordered = scanned.candidates.mapNotNull { candidate ->
                container.entries[candidate.mediaPath]?.let { candidate to it }
            }
            if (ordered.isEmpty()) error("文档里没有找到图片")
            val usable = StickerCandidateFilters.filter(
                items = ordered,
                widthOf = { entry -> dimensionsOf(entry.second).first },
                heightOf = { entry -> dimensionsOf(entry.second).second },
                bytesOf = { entry -> entry.second },
            )
            if (usable.isEmpty()) error("文档里的图片都不像表情（尺寸或重复规则挡下）")
            onProgress?.invoke("正在解压", 0, usable.size)
            val stagingDir = File(rootDir, ".staging_" + UUID.randomUUID().toString().take(8))
            stagingDir.mkdirs()
            try {
                val pending = usable.mapIndexed { index, (candidate, bytes) ->
                    val file = File(stagingDir, index.toString() + "_" + safeFileName(candidate.mediaPath))
                    file.outputStream().use { it.write(bytes) }
                    PendingEntry(category, safeFileName(candidate.mediaPath), file, candidate.caption)
                }
                val imported = commitStaged(pending, System.currentTimeMillis(), onProgress)
                Logger.i("StickerLibraryRepository", "importDocument: 导入 ${imported.size} 张，分类=$category")
                imported.size
            } finally {
                resultOf { stagingDir.deleteRecursively() }
                    .onError { msg, t -> Logger.w("StickerLibraryRepository", "清理 staging 失败: $msg", t) }
            }
        }.onError { msg, t -> Logger.w("StickerLibraryRepository", "importDocument 失败: $msg", t) }
    }

    /** 读文件开头若干字节（用来嗅探类型）。 */
    private fun File.headBytes(count: Int): ByteArray = runCatching {
        java.io.FileInputStream(this).use { input ->
            val buffer = ByteArray(count)
            val read = input.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        }
    }.getOrDefault(ByteArray(0))

    /** 媒体路径 → 只留文件名。 */
    private fun safeFileName(mediaPath: String): String = mediaPath.substringAfterLast('/')

    /** 分类名当目录名用，必须去掉路径分隔符与控制字符；空了回落"文档导入"。 */
    private fun safeCategoryName(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("""[/\\:*?"<>|\x00-\x1F]"""), "_")
            .trim('.', ' ')
            .take(40)
        return cleaned.ifBlank { "文档导入" }
    }

    /**
     * 图片尺寸；解不出来回退成"中性方图"。
     *
     * 解不开不是丢弃的理由：把用户选的图悄悄扔掉，比多导一张更难解释。
     * 真有多余的，导入向导里能看见并勾掉（P3）。
     */
    private fun dimensionsOf(bytes: ByteArray): Pair<Int, Int> = runCatching {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            neutralDimension
        }
    }.getOrDefault(neutralDimension)

    /** 路由新形态（表情包重做 P2）。 */
    private fun documentKindOf(name: String): StickerSourceKind? = when (name.lowercase().substringAfterLast('.', "")) {
        "docx" -> StickerSourceKind.DOCX
        "pptx" -> StickerSourceKind.PPTX
        else -> null
    }

    /**
     * 从相册一次添加多张图片（表情包重做 P4）。
     *
     * 以前只有"导入压缩包"和"导入单张"，用户手机里现成的图要一张张选；
     * 批量选择是更常见的做法，也是一次性把一批表情包搬进来的唯一省事路径。
     *
     * 非图片项被跳过而不是中止整批：相册多选里偶尔混进截图外的文件，
     * 那不该让用户重来一遍。返回值里带上跳过的数量，界面能说清。
     */
    suspend fun importImages(
        uris: List<Uri>,
        category: String? = null,
        onProgress: ((phase: String, done: Int, total: Int?) -> Unit)? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
        resultOf {
            val target = category?.takeIf { it.isNotBlank() }?.let(::safeCategoryName) ?: "默认"
            val stagingDir = File(rootDir, ".staging_" + UUID.randomUUID().toString().take(8))
            stagingDir.mkdirs()
            var skipped = 0
            try {
                val pending = mutableListOf<PendingEntry>()
                uris.forEachIndexed { index, uri ->
                    onProgress?.invoke("正在读取", index, uris.size)
                    val given = queryDisplayName(uri)?.takeIf { isImageFile(it) }
                    val staged = File(stagingDir, index.toString() + "_in")
                    val copied = resultOf {
                        appContext.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input).copyTo(staged.outputStream())
                        }
                        staged.length() > 0
                    }.getOrNull() == true
                    if (!copied) {
                        staged.delete()
                        skipped++
                    } else {
                        // 名字不一定拿得到（某些分享入口的 Uri 没有 DISPLAY_NAME），
                        // 更不能凭空编一个 .png —— 那样任意文件都会以"图片"的身份进库，
                        // 用户看到的是表情位上一个打不开的碎图。字节说了算。
                        val head = staged.headBytes(12)
                        val extension = StickerImportFormat.imageExtension(head)
                        if (extension == null) {
                            staged.delete()
                            skipped++
                        } else {
                            // 不再改名：PendingEntry 本来就分开记"临时文件"和"最终文件名"，
                            // 多一次 renameTo 只是多一个会静默失败的环节（它就是失败了）
                            val wanted = given?.let { safeFileName(it) }
                                ?: ("图片_" + (index + 1) + "." + extension)
                            pending.add(PendingEntry(target, wanted, staged))
                        }
                    }
                }
                if (pending.isEmpty()) error("所选文件里没有可用图片（跳过 $skipped 个）")
                val imported = commitStaged(pending, System.currentTimeMillis(), onProgress)
                Logger.i("StickerLibraryRepository", "importImages: 导入 ${imported.size} 张到 $target，跳过 $skipped")
                imported.size
            } finally {
                resultOf { stagingDir.deleteRecursively() }
                    .onError { msg, t -> Logger.w("StickerLibraryRepository", "清理 staging 失败: $msg", t) }
            }
        }.onError { msg, t -> Logger.w("StickerLibraryRepository", "importImages 失败: $msg", t) }
    }

    /**
     * 改表情包（分类）名字。
     *
     * 与目标同名时**直接拒绝而不是自动合并**：合并意味着把两批图混在一起，
     * 用户想改个名却把两个包搅了，那是比失败更难收拾的结果。
     * 真要合并，可以逐张"挪到别的包"。
     */
    suspend fun renameCategory(from: String, to: String): Result<Int> = withContext(Dispatchers.IO) {
        resultOf {
            val target = safeCategoryName(to)
            if (target == from) error("名字没变")
            manifestMutex.withLock {
                val current = readManifest().toMutableList()
                if (current.any { it.category == target }) error("已经有一个叫「$target」的表情包，换个名字或逐张挪过去")
                val moving = current.filter { it.category == from }
                if (moving.isEmpty()) error("没有找到「$from」")
                val now = System.currentTimeMillis()
                moving.forEach { item ->
                    val dir = File(rootDir, target).apply { mkdirs() }
                    val file = uniqueTargetFile(dir, item.fileName)
                    val source = File(appContext.filesDir, item.relativePath)
                    if (!source.renameTo(file)) source.copyTo(file, overwrite = false)
                    current[current.indexOf(item)] = item.copy(
                        category = target,
                        fileName = file.name,
                        relativePath = "stickers/$target/${file.name}",
                        addedAt = now,
                    )
                }
                writeManifest(current)
                resultOf {
                    val oldDir = File(rootDir, from)
                    if (oldDir.isDirectory && oldDir.listFiles()?.isEmpty() == true) oldDir.delete()
                }
                moving.size
            }
        }.onError { msg, t -> Logger.w("StickerLibraryRepository", "renameCategory 失败: $msg", t) }
    }

    /** 把一张表情挪到另一个包（不存在就新建这个包）。 */
    suspend fun moveSticker(id: String, toCategory: String): Boolean = withContext(Dispatchers.IO) {
        manifestMutex.withLock {
            val current = readManifest().toMutableList()
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) return@withLock false
            val item = current[index]
            val target = safeCategoryName(toCategory)
            if (item.category == target) return@withLock false
            val dir = File(rootDir, target).apply { mkdirs() }
            val file = uniqueTargetFile(dir, item.fileName)
            val source = File(appContext.filesDir, item.relativePath)
            val moved = resultOf { source.renameTo(file) || run { source.copyTo(file, overwrite = false); true } }
                .getOrNull() == true
            if (!moved) return@withLock false
            current[index] = item.copy(
                category = target,
                fileName = file.name,
                relativePath = "stickers/$target/${file.name}",
            )
            writeManifest(current)
            resultOf {
                val oldDir = File(rootDir, item.category)
                if (oldDir.isDirectory && oldDir.listFiles()?.isEmpty() == true) oldDir.delete()
            }
            true
        }
    }

    /**
     * 改配文。
     *
     * 上限 200 字：配文是给"这张图什么意思"用的说明，不是正文；
     * 允许清空（null），因为导入时自动带上的描述有时只是噪声。
     */
    suspend fun setCaption(id: String, caption: String?): Boolean = withContext(Dispatchers.IO) {
        manifestMutex.withLock {
            val current = readManifest().toMutableList()
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) return@withLock false
            current[index] = current[index].copy(caption = caption?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_CAPTION_CHARS))
            writeManifest(current)
            true
        }
    }

    /**
     * 删除单个表情包(删物理文件 + 从清单移除)。
     * @return true 表示删除成功;false 表示未找到对应条目
     */
    suspend fun deleteSticker(id: String): Boolean = withContext(Dispatchers.IO) {
        manifestMutex.withLock {
            val current = readManifest().toMutableList()
            val target = current.firstOrNull { it.id == id } ?: return@withLock false
            // 删物理文件(失败仅记日志,不阻断清单更新)
            resultOf {
                val file = File(appContext.filesDir, target.relativePath)
                if (file.exists()) file.delete()
            }.onError { msg, t -> Logger.w("StickerLibraryRepository", "删除表情包文件失败: $msg", t) }
            current.remove(target)
            writeManifest(current)
            true
        }
    }

    /**
     * v1.112 (F1-F2): 批量删除表情包(删物理文件 + 从清单移除)。
     *
     * @param ids 要删除的表情包 id 列表
     * @return 实际删除数量
     */
    suspend fun deleteStickers(ids: Set<String>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        manifestMutex.withLock {
            val current = readManifest().toMutableList()
            val toRemove = current.filter { it.id in ids }
            if (toRemove.isEmpty()) return@withLock 0
            // 逐个删物理文件(失败仅记日志)
            for (item in toRemove) {
                resultOf {
                    val file = File(appContext.filesDir, item.relativePath)
                    if (file.exists()) file.delete()
                }.onError { msg, t -> Logger.w("StickerLibraryRepository", "批量删除表情包文件失败: $msg", t) }
            }
            current.removeAll(toRemove)
            writeManifest(current)
            toRemove.size
        }
    }

    /**
     * v1.112 (F1-F2): 删除指定分类下的所有表情包。
     *
     * @param category 分类名
     * @return 实际删除数量
     */
    suspend fun deleteCategory(category: String): Int = withContext(Dispatchers.IO) {
        manifestMutex.withLock {
            val current = readManifest().toMutableList()
            val toRemove = current.filter { it.category == category }
            if (toRemove.isEmpty()) return@withContext 0
            for (item in toRemove) {
                resultOf {
                    val file = File(appContext.filesDir, item.relativePath)
                    if (file.exists()) file.delete()
                }.onError { msg, t -> Logger.w("StickerLibraryRepository", "删除分类表情包文件失败: $msg", t) }
            }
            // 删除空分类目录
            resultOf {
                val dir = File(rootDir, category)
                if (dir.exists() && dir.isDirectory && dir.listFiles()?.isEmpty() == true) dir.delete()
            }
            current.removeAll(toRemove)
            writeManifest(current)
            toRemove.size
        }
    }

    /**
     * v1.112 (F1-F2): 清空所有表情包。
     *
     * @return 实际删除数量
     */
    suspend fun clearAll(): Int = withContext(Dispatchers.IO) {
        manifestMutex.withLock {
            val current = readManifest()
            if (current.isEmpty()) return@withContext 0
            // 删除整个 stickers 目录(递归)
            resultOf {
                if (rootDir.exists()) rootDir.deleteRecursively()
            }.onError { msg, t -> Logger.w("StickerLibraryRepository", "清空表情包目录失败: $msg", t) }
            writeManifest(emptyList())
            current.size
        }
    }

    /** 根据 id 获取表情包文件(文件不存在返回 null)。 */
    suspend fun getStickerFile(id: String): File? = withContext(Dispatchers.IO) {
        val item = manifestMutex.withLock { readManifest() }.firstOrNull { it.id == id } ?: return@withContext null
        val file = File(appContext.filesDir, item.relativePath)
        if (file.exists()) file else null
    }

    /**
     * 根据 relativePath 获取文件(供工具调用直接读路径,不校验是否在清单中)。
     * 调用方需自行确保路径合法。
     */
    fun getStickerFileByPath(relativePath: String): File = File(appContext.filesDir, relativePath)

    // ── v2.x: 标记链路支持(消息内 [[sticker:分类名]] 解析用) ─────────────────

    /** 快照内存缓存(写操作经 [writeManifest] 自动失效)。 */
    @Volatile
    private var snapshotCache: StickerSnapshot? = null

    /** 读取分类快照(带内存缓存;首次读盘后常驻,写操作时自动失效)。 */
    suspend fun snapshot(): StickerSnapshot = withContext(Dispatchers.IO) {
        snapshotCache?.let { return@withContext it }
        manifestMutex.withLock {
            snapshotCache?.let { return@withLock it }
            val items = readManifest()
            val snap =
                StickerSnapshot(
                    categories = items.map { it.category }.distinct().sorted(),
                    byCategory = items.groupBy { it.category },
                )
            snapshotCache = snap
            snap
        }
    }

    /** 分类摘要(分类名 → 张数),供 system prompt 生成"可用分类"清单。 */
    suspend fun categorySummary(): List<Pair<String, Int>> {
        val snap = snapshot()
        return snap.categories.map { it to (snap.byCategory[it]?.size ?: 0) }
    }

    /**
     * 容错解析分类名(模型输出的分类名可能不精确)。
     *
     * 匹配顺序:精确 → 忽略大小写与空白 → 编辑距离 ≤ 1 → 唯一包含。
     * 全部失败返回 null(调用方按"忽略该标记"处理)。
     */
    suspend fun resolveCategory(raw: String): String? = matchStickerCategory(snapshot().categories, raw)

    /**
     * 种子随机选取:同一 [seed] 稳定返回同一张(重进会话/重组不跳变)。
     * 非 suspend(基于已读快照,渲染层先 [snapshot] 再调用)。
     */
    fun pickSticker(snap: StickerSnapshot, category: String, seed: Long): StickerItem? {
        val list = snap.byCategory[category] ?: return null
        if (list.isEmpty()) return null
        val idx = Math.floorMod(seed, list.size.toLong()).toInt()
        return list[idx]
    }

    // ── 内部辅助 ─────────────────────────────────────────────────────────

    /**
     * 待落盘的解压条目(分类 + 文件名 + staging 临时文件)。
     *
     * v1.0.52: 由字节数组改为 staging 文件引用 — 图片内容边读边落盘,
     * 内存不累积,避免大表情包包 OOM。
     */
    private data class PendingEntry(
        val category: String,
        val fileName: String,
        val stagingFile: File,
        /** 配文（文档导入才有；zip 路径为 null）。 */
        val caption: String? = null,
    )

    /** 读取清单文件(调用方需持锁)。清单不存在或解析失败返回空列表。 */
    private fun readManifest(): List<StickerItem> {
        if (!manifestFile.exists()) return emptyList()
        return resultOf {
            AppJson.decodeFromString(ListSerializer(StickerItem.serializer()), manifestFile.readText())
        }.onError { msg, t ->
            Logger.w("StickerLibraryRepository", "清单解析失败,回退空列表: $msg", t)
        }.getOrNull() ?: emptyList()
    }

    /** 写入清单文件(调用方需持锁)。v1.117: 原子写(temp+rename)避免写中途崩溃损坏清单。 */
    private fun writeManifest(items: List<StickerItem>) {
        // v2.x: 清单变更 → 分类快照缓存失效(标记链路读取)
        snapshotCache = null
        if (!rootDir.exists()) rootDir.mkdirs()
        val json = AppJson.encodeToString(ListSerializer(StickerItem.serializer()), items)
        // 原子写:先写 .tmp 再 rename,避免 writeText 中途崩溃留下半截 JSON 导致全部元数据丢失
        val tmpFile = File(rootDir, "manifest.json.tmp")
        try {
            tmpFile.writeText(json)
            if (!tmpFile.renameTo(manifestFile)) {
                // rename 失败的罕见情况(同分区一般不会失败),回退直接写
                manifestFile.writeText(json)
            }
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
    }

    /**
     * 从 zip entry 名解析分类与文件名。
     *
     * 分类规则(取路径中最后一个目录名作为分类):
     *  - "表情包/开心/001.png" → ("开心", "001.png")  顶层包装目录被跳过
     *  - "开心/001.png" → ("开心", "001.png")
     *  - "猫猫/日常/001.png" → ("日常", "001.png")
     *  - "001.png" → ("默认", "001.png") 根目录文件归"默认"
     */
    private fun parseCategoryAndName(rawName: String): Pair<String, String?> {
        // v1.113: 快速拒绝含 .. 的路径(防 Zip Slip)
        if (rawName.contains("..")) {
            return "默认" to null // 返回 null fileName 会被调用方跳过
        }
        // 统一路径分隔符(zip 规范用 "/",部分工具可能用 "\\")
        val normalized = rawName.replace('\\', '/')
        // 按 / 分段,过滤空段(如开头/结尾的斜杠)
        val parts = normalized.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return "默认" to null
        val fileName = parts.last().trim()
        if (parts.size == 1) {
            // 根目录直接文件 → 默认分类
            return "默认" to fileName.ifBlank { null }
        }
        // 取最后一个目录名作为分类:跳过顶层包装目录(如 "表情包/"),
        // 分类名是语义化标签(开心/难过/生气…),模型据此判断发哪类表情包
        val category = parts[parts.size - 2].trim()
        return (if (category.isBlank()) "默认" else category) to fileName.ifBlank { null }
    }

    /** 判断文件名是否为支持的图片类型(扩展名不区分大小写)。 */
    private fun isImageFile(fileName: String): Boolean {
        val dotIdx = fileName.lastIndexOf('.')
        if (dotIdx < 0) return false
        val ext = fileName.substring(dotIdx + 1).lowercase()
        return ext in imageExtensions
    }
}

/**
 * 表情包条目数据模型。
 *
 * @param id UUID(唯一标识符)
 * @param category 分类(从压缩包文件夹名获取,无文件夹则用"默认")
 * @param fileName 文件名
 * @param relativePath 相对路径,格式 "stickers/<category>/<fileName>"
 * @param addedAt 导入时间戳
 */
@Serializable
data class StickerItem(
    val id: String,
    val category: String,
    val fileName: String,
    val relativePath: String,
    val addedAt: Long,
    /**
     * 配文（表情包重做 P2）。
     *
     * 从 docx/pdf 素材导入时，图旁边那句中文描述是有用的信息（"小猫歪头"），
     * 留下来才能搜、才能在发送时做候选。
     * 默认 null：清单是 JSON 存盘的历史数据，老条目没这个字段也必须能读。
     */
    val caption: String? = null,
)

/**
 * v2.x: 表情包库分类快照(标记链路读取用)。
 *
 * @param categories 所有分类名(排序)
 * @param byCategory 分类 → 条目列表
 */
data class StickerSnapshot(
    val categories: List<String>,
    val byCategory: Map<String, List<StickerItem>>,
) {
    val isEmpty: Boolean get() = categories.isEmpty()
}

/**
 * v2.x: 分类名容错匹配 —— 模型输出的分类名与库中分类的模糊对齐。
 *
 * 匹配顺序:精确 → 归一化(大小写/空白) → 编辑距离 ≤ 1(仅短名) → 唯一包含。
 * 全部失败返回 null(调用方按"忽略该标记"处理,宁可少发不乱发)。
 */
internal fun matchStickerCategory(candidates: List<String>, raw: String): String? {
    val name = raw.trim()
    if (name.isEmpty() || candidates.isEmpty()) return null
    // 1. 精确
    candidates.firstOrNull { it == name }?.let { return it }
    // 2. 归一化(忽略大小写与空白)
    val norm = name.lowercase().filterNot { it.isWhitespace() }
    candidates.firstOrNull { it.lowercase().filterNot { c -> c.isWhitespace() } == norm }?.let { return it }
    // 3. 编辑距离 ≤ 1(仅短名且等长 —— 等长限制防"开心"误配"不开心"这类插入型差异)
    if (name.length <= 8) {
        candidates.firstOrNull {
            it.length == name.length && levenshteinDistance(it, name) <= 1
        }?.let { return it }
    }
    // 4. 唯一包含(仅 name 包含 candidate 方向:模型把分类名写长了一点;
    //    反向(candidate 包含 name)禁用 —— "开心" 误配 "不开心" 这类反义风险)
    val contained = candidates.filter { name.contains(it, ignoreCase = true) }
    if (contained.size == 1) return contained[0]
    return null
}

/** 标准 Levenshtein 距离(短字符串 DP)。 */
internal fun levenshteinDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    val curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
        }
        curr.copyInto(prev)
    }
    return prev[b.length]
}
