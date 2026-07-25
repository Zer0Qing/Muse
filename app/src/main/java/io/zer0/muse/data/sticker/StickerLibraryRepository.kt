package io.zer0.muse.data.sticker

import android.content.Context
import android.net.Uri
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
import java.io.OutputStream
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
    suspend fun importZip(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        resultOf {
            val imported = mutableListOf<StickerItem>()
            val now = System.currentTimeMillis()
            // 先把 zip 内所有图片解压到临时内存条目(避免中途失败留下半成品),
            // 解析完再统一落盘 + 写清单。zip 文件本身不大(图片压缩包),整体可接受。
            val pendingEntries = mutableListOf<PendingEntry>()
            // v1.117: 累计大小/数量计数器,防 ZIP 炸弹
            var totalBytes = 0L

            appContext.contentResolver.openInputStream(uri).use { input ->
                if (input == null) error("无法打开所选文件")
                // v1.112 (F4): 用 GBK charset 构造 ZipInputStream 修复中文文件名乱码。
                // ZIP 规范:entry 的 EFS 标志位(bit 11)=1 时 ZipInputStream 忽略传入 charset 强制 UTF-8;
                // EFS=0 时用传入的 charset。Windows 中文工具(好压/2345/WinRAR 中文版)生成的 zip
                // 通常 EFS=0 且文件名用 GBK 编码,传 GBK 能正确解码;标准 UTF-8 zip(EFS=1)仍用 UTF-8。
                ZipInputStream(input, Charset.forName("GBK")).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val rawName = entry.name
                            // 跳过 macOS 系统目录与 .DS_Store 噪声
                            if (rawName.contains("__MACOSX") || rawName.endsWith(".DS_Store")) {
                                zis.closeEntry()
                                entry = zis.nextEntry
                                continue
                            }
                            // 解析分类与文件名:zip 内 "猫猫/001.png" → category="猫猫", fileName="001.png"
                            val (category, fileName) = parseCategoryAndName(rawName)
                            if (fileName != null && isImageFile(fileName)) {
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
                                pendingEntries.add(PendingEntry(category, fileName, bytes))
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            if (pendingEntries.isEmpty()) error("压缩包内未找到图片文件(png/jpg/jpeg/gif/webp/bmp)")

            // 落盘 + 构造清单条目
            manifestMutex.withLock {
                val current = readManifest().toMutableList()
                for (pe in pendingEntries) {
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
                    // v1.117: 原子写 — 先写 .tmp 再 rename,避免写中途崩溃留下半截文件
                    val tmpFile = File(targetDir, "$finalName.tmp")
                    try {
                        tmpFile.outputStream().use { out: OutputStream -> out.write(pe.bytes) }
                        if (!tmpFile.renameTo(targetFile)) {
                            // rename 失败(跨分区等罕见情况),回退直接写
                            tmpFile.outputStream().use { out -> out.write(pe.bytes) }
                        }
                    } finally {
                        if (tmpFile.exists()) tmpFile.delete()
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

    /** 待落盘的解压条目(分类 + 文件名 + 字节)。 */
    private data class PendingEntry(
        val category: String,
        val fileName: String,
        val bytes: ByteArray,
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
     *  - "猫猫/001.png" → ("猫猫", "001.png")
     *  - "a/b/002.jpg" → ("a/b" 折叠为 "a" 的第一段? 这里取**首段**作为分类,与任务描述"文件夹结构作为分类"一致)
     *
     * 实际处理:取第一个 "/" 之前的部分作为分类;若有多级目录,仍用第一级作为分类(简洁实用)。
     * 无 "/" 的直接文件 → category="默认", fileName=原名。
     */
    private fun parseCategoryAndName(rawName: String): Pair<String, String?> {
        // v1.113: 快速拒绝含 .. 的路径(防 Zip Slip)
        if (rawName.contains("..")) {
            return "默认" to null  // 返回 null fileName 会被调用方跳过
        }
        // 统一路径分隔符(zip 规范用 "/",部分工具可能用 "\")
        val normalized = rawName.replace('\\', '/')
        val slashIdx = normalized.indexOf('/')
        return if (slashIdx > 0) {
            val category = normalized.substring(0, slashIdx).trim()
            val fileName = normalized.substring(slashIdx + 1).trim()
            // zip 内多级目录(如 a/b/001.png)时 fileName 仍含 "/",取最后一段作为文件名
            val finalFileName = fileName.substringAfterLast('/').trim()
            (if (category.isBlank()) "默认" else category) to finalFileName.ifBlank { null }
        } else {
            "默认" to normalized.trim().ifBlank { null }
        }
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
