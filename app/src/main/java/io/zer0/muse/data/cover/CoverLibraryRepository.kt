package io.zer0.muse.data.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/**
 * 封面条目数据模型。
 *
 * @param id UUID(唯一标识符)
 * @param fileName 磁盘文件名,如 "cover_abc123.jpg"
 * @param source 来源: "local"(相册导入) / "generated"(AI 生成) / "imported"
 * @param width 像素宽(生图时记录;导入时为压缩后宽度)
 * @param height 像素高
 * @param addedAt 入库时间戳
 */
@Serializable
data class CoverItem(
    val id: String,
    val fileName: String,
    val source: String,
    val width: Int = 0,
    val height: Int = 0,
    val addedAt: Long,
)

/**
 * v1.0.53: 封面图库仓库(对标 Beautify 封面工作流的封面库)。
 *
 * 存储结构:
 *  - 图片文件: `filesDir/covers/<fileName>`(统一 JPEG,最长边 ≤ [MAX_DIMENSION])
 *  - 清单文件: `filesDir/covers/manifest.json`(JSON 序列化的 [CoverItem] 列表)
 *
 * 设计要点:
 *  - 清单读写用 [Mutex] 保护,IO 在 [Dispatchers.IO]
 *  - 导入/生成统一压缩:最长边缩到 [MAX_DIMENSION](1600),JPEG 质量 85
 *  - 超大图先 inSampleSize 降采样防 OOM
 *  - manifest 原子写(temp + rename),防写中途崩溃损坏清单
 *
 * @param appContext 应用 Context(用于 filesDir)
 */
class CoverLibraryRepository(private val appContext: Context) {

    /** covers 根目录(`filesDir/covers`)。 */
    private val rootDir: File get() = File(appContext.filesDir, "covers").apply { if (!exists()) mkdirs() }

    /** 清单文件(`filesDir/covers/manifest.json`)。 */
    private val manifestFile: File get() = File(rootDir, "manifest.json")

    /** 清单读写互斥锁(保证并发导入/删除/生成的原子性)。 */
    private val manifestMutex = Mutex()

    companion object {
        private const val TAG = "CoverLibraryRepository"

        /** 封面最长边上限(像素)。 */
        const val MAX_DIMENSION = 1600

        /** JPEG 压缩质量。 */
        const val JPEG_QUALITY = 85

        /** 解码时的目标采样边(超过此尺寸先 inSampleSize)。 */
        private const val SAMPLE_THRESHOLD = 4096
    }

    /** 列出全部封面,按时间倒序。 */
    suspend fun listCovers(): List<CoverItem> = withContext(Dispatchers.IO) {
        manifestMutex.withLock { readManifest() }.sortedByDescending { it.addedAt }
    }

    /**
     * 从 SAF Uri 导入一张图片为封面(压缩到最长边 [MAX_DIMENSION],JPEG)。
     *
     * @param uri 内容 Uri(相册/文件选择器)
     * @return 导入的 [CoverItem];失败时返回 Error
     */
    suspend fun importFromUri(uri: Uri): Result<CoverItem> = withContext(Dispatchers.IO) {
        resultOf {
            val bitmap = decodeSampledBitmap(uri)
                ?: error("无法解码所选图片")
            val (w, h) = bitmap.width to bitmap.height
            val fileName = "cover_${UUID.randomUUID().toString().take(8)}.jpg"
            val targetFile = File(rootDir, fileName)
            targetFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (!bitmap.isRecycled) bitmap.recycle()

            val item = CoverItem(
                id = UUID.randomUUID().toString(),
                fileName = fileName,
                source = "local",
                width = w,
                height = h,
                addedAt = System.currentTimeMillis(),
            )
            manifestMutex.withLock {
                val current = readManifest().toMutableList()
                current.add(item)
                writeManifest(current)
            }
            item
        }.onError { msg, t ->
            Logger.w(TAG, "导入封面失败: $msg", t)
        }
    }

    /**
     * 把生图结果(本地文件路径)登记为封面。
     *
     * 生图产物通常已是合格尺寸,这里仍做一次压缩兜底(防超长边),并复制进 covers 目录。
     *
     * @param file 生图产物的本地文件
     * @param width 原始像素宽(生图返回的元数据)
     * @param height 原始像素高
     * @return 登记后的 [CoverItem];失败时返回 Error
     */
    suspend fun registerGenerated(file: File, width: Int, height: Int): Result<CoverItem> = withContext(Dispatchers.IO) {
        resultOf {
            val fileName = "cover_${UUID.randomUUID().toString().take(8)}.jpg"
            val targetFile = File(rootDir, fileName)
            // 复制(若超长边则压缩;否则直接复制字节)
            if (maxOf(width, height) <= MAX_DIMENSION) {
                file.inputStream().use { input -> targetFile.outputStream().use { output -> input.copyTo(output) } }
            } else {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    ?: error("无法解码生图结果")
                targetFile.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                if (!bitmap.isRecycled) bitmap.recycle()
            }

            val item = CoverItem(
                id = UUID.randomUUID().toString(),
                fileName = fileName,
                source = "generated",
                width = width,
                height = height,
                addedAt = System.currentTimeMillis(),
            )
            manifestMutex.withLock {
                val current = readManifest().toMutableList()
                current.add(item)
                writeManifest(current)
            }
            item
        }.onError { msg, t ->
            Logger.w(TAG, "登记生成封面失败: $msg", t)
        }
    }

    /** 删除封面(物理文件 + 从清单移除)。 */
    suspend fun deleteCover(id: String): Boolean = withContext(Dispatchers.IO) {
        manifestMutex.withLock {
            val current = readManifest().toMutableList()
            val target = current.firstOrNull { it.id == id } ?: return@withLock false
            resultOf {
                val file = File(rootDir, target.fileName)
                if (file.exists()) file.delete()
            }.onError { msg, t -> Logger.w(TAG, "删除封面文件失败: $msg", t) }
            current.remove(target)
            writeManifest(current)
            true
        }
    }

    /** 取封面文件(不存在返回 null)。 */
    fun getCoverFile(item: CoverItem): File = File(rootDir, item.fileName)

    /** 按 id 查条目(不存在返回 null)。 */
    suspend fun getById(id: String): CoverItem? = withContext(Dispatchers.IO) {
        manifestMutex.withLock { readManifest() }.firstOrNull { it.id == id }
    }

    // ── 内部实现 ─────────────────────────────────────────────────────────

    /**
     * 解码并压缩图片:先 inSampleSize 粗降(>4096 时),再精确缩放到最长边 1600。
     */
    private fun decodeSampledBitmap(uri: Uri): Bitmap? {
        // 第一次解码:只读尺寸
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 计算采样率:最长边 > SAMPLE_THRESHOLD 时按比例粗降
        var sampleSize = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sampleSize * 2) >= SAMPLE_THRESHOLD) sampleSize *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        val full = appContext.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, opts)
        } ?: return null

        // 精确缩放:最长边 > MAX_DIMENSION 时缩
        val w = full.width
        val h = full.height
        if (maxOf(w, h) <= MAX_DIMENSION) return full

        val scale = MAX_DIMENSION.toFloat() / maxOf(w, h)
        val scaled = Bitmap.createScaledBitmap(
            full,
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== full && !full.isRecycled) full.recycle()
        return scaled
    }

    /** 读取清单文件(调用方需持锁)。清单不存在或解析失败返回空列表。 */
    private fun readManifest(): List<CoverItem> {
        if (!manifestFile.exists()) return emptyList()
        return resultOf {
            AppJson.decodeFromString(ListSerializer(CoverItem.serializer()), manifestFile.readText())
        }.onError { msg, t ->
            Logger.w(TAG, "封面清单解析失败,回退空列表: $msg", t)
        }.getOrNull() ?: emptyList()
    }

    /** 写入清单文件(调用方需持锁)。原子写(temp+rename)。 */
    private fun writeManifest(items: List<CoverItem>) {
        if (!rootDir.exists()) rootDir.mkdirs()
        val json = AppJson.encodeToString(ListSerializer(CoverItem.serializer()), items)
        val tmpFile = File(rootDir, "manifest.json.tmp")
        try {
            tmpFile.writeText(json)
            if (!tmpFile.renameTo(manifestFile)) {
                manifestFile.writeText(json)
            }
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
    }
}
