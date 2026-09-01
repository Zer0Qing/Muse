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
class StickerLibraryRepository(private val appContext: Context) {

    /** stickers 根目录(`filesDir/stickers`)。 */
    private val rootDir: File get() = File(appContext.filesDir, "stickers").apply { if (!exists()) mkdirs() }

    /** 清单文件(`filesDir/stickers/manifest.json`)。 */
    private val manifestFile: File get() = File(rootDir, "manifest.json")

    /** 清单读写互斥锁(保证并发导入/删除的原子性)。 */
    private val manifestMutex = Mutex()

    /** 支持的图片扩展名(小写)。 */
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    // v1.117: 导入体积限制,防 ZIP 炸弹 / OOM(对齐 DocumentParser 的限制策略)
    private val MAX_SINGLE_ENTRY_BYTES = 10L * 1024 * 1024      // 单个图片 10MB
    private val MAX_TOTAL_IMPORT_BYTES = 200L * 1024 * 1024     // 累计 200MB
    private val MAX_ENTRY_COUNT = 1000                          // 最多 1000 个文件

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
    suspend fun importUri(
        uri: Uri,
        onProgress: ((phase: String, done: Int, total: Int?) -> Unit)? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri)
        Logger.i("StickerLibraryRepository", "importUri: uri=$uri, displayName=$displayName")
        if (displayName.isNullOrBlank()) {
            Logger.w("StickerLibraryRepository", "importUri: 无法获取文件名,尝试按 ZIP 处理")
            return@withContext importZip(uri, onProgress)
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
            else -> {
                Logger.w("StickerLibraryRepository", "importUri: 不支持的文件类型: $displayName")
                Result.Error("不支持的文件类型: $displayName(仅支持 ZIP 压缩包或 png/jpg/jpeg/gif/webp/bmp 图片)")
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
            Logger.i("StickerLibraryRepository", "importImage: 图片已写入 ${targetFile.path} (${fileSize} bytes)")

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
                } else null
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
    suspend fun importZip(
        uri: Uri,
        onProgress: ((phase: String, done: Int, total: Int?) -> Unit)? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
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
                                    if (entryCount % 100 == 0) Logger.d("StickerLibraryRepository", "importZip: 已跳过 $entryCount 个噪声条目(示例: $rawName)")
                                    zis.closeEntry()
                                    entry = zis.nextEntry
                                    continue
                                }
                                // 解析分类与文件名
                                val (category, fileName) = parseCategoryAndName(rawName)
                                if (fileName != null && isImageFile(fileName)) {
                                    if (entryCount % 100 == 0) Logger.d("StickerLibraryRepository", "importZip: 已发现 $entryCount 个条目,当前图片: $rawName")
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
                                    if (entryCount % 100 == 0) Logger.d("StickerLibraryRepository", "importZip: 已跳过 $entryCount 个条目(当前非图片: $rawName)")
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
                // staging → 正式目录 + 写清单(锁内完成,失败抛异常走 finally 清理)
                manifestMutex.withLock {
                    val current = readManifest().toMutableList()
                    for ((idx, pe) in pendingEntries.withIndex()) {
                        // v1.0.54: 写入阶段进度(总数已知)
                        if (idx % 10 == 0) {
                            onProgress?.invoke("正在写入", idx, pendingEntries.size)
                        }
                        // 文件名冲突时附加短 uuid 后缀,避免覆盖
                        val targetDir = File(rootDir, pe.category).apply { mkdirs() }
                        var finalName = pe.fileName
                        var targetFile = File(targetDir, finalName)
                        // v1.113: Zip Slip 防护 — 确保解压目标路径在 rootDir 内
                        val canonicalTarget = targetFile.canonicalPath
                        val canonicalRoot = rootDir.canonicalPath
                        if (!canonicalTarget.startsWith(canonicalRoot + File.separator)) {
                            Logger.w("StickerLibraryRepository", "跳过路径穿越条目: ${pe.category}/${pe.fileName} -> $canonicalTarget")
                            continue
                        }
                        if (targetFile.exists()) {
                            val dotIdx = pe.fileName.lastIndexOf('.')
                            val base = if (dotIdx > 0) pe.fileName.substring(0, dotIdx) else pe.fileName
                            val ext = if (dotIdx > 0) pe.fileName.substring(dotIdx) else ""
                            finalName = "${base}_${UUID.randomUUID().toString().take(6)}$ext"
                            targetFile = File(targetDir, finalName)
                        }
                        // staging 文件移入正式目录(同分区 rename 原子且不复制)
                        if (!pe.stagingFile.renameTo(targetFile)) {
                            // rename 失败(罕见),回退复制
                            pe.stagingFile.copyTo(targetFile, overwrite = false)
                        }
                        val item = StickerItem(
                            id = UUID.randomUUID().toString(),
                            category = pe.category,
                            fileName = finalName,
                            relativePath = "stickers/${pe.category}/$finalName",
                            addedAt = now,
                        )
                        current.add(item)
                        imported.add(item)
                    }
                    writeManifest(current)
                }
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
            return "默认" to null  // 返回 null fileName 会被调用方跳过
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
)
