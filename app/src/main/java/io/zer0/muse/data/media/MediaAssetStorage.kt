package io.zer0.muse.data.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.FileProvider
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.ui.common.SsrfGuard
import io.zer0.muse.util.ShareIntentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/** Locally persisted media file metadata produced by [MediaAssetStorage]. */
data class StoredMediaFile(
    val localPath: String,
    val thumbnailPath: String? = null,
    val remoteUrl: String? = null,
    val mimeType: String,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
)

private fun inferImageMime(bytes: ByteArray, hint: String): String {
    val detected = when {
        hasBytesAt(bytes, 0, 0xFF, 0xD8) -> "image/jpeg"
        hasBytesAt(bytes, 0, 0x89, 0x50, 0x4E, 0x47) -> "image/png"
        hasBytesAt(bytes, 0, 0x47, 0x49, 0x46) -> "image/gif"
        hasBytesAt(bytes, 0, 0x52, 0x49, 0x46, 0x46) &&
            hasBytesAt(bytes, 8, 0x57, 0x45, 0x42, 0x50) -> "image/webp"
        else -> null
    }
    if (detected != null) return detected
    val lower = hint.lowercase()
    return when {
        lower.contains("image/jpeg") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
        lower.contains("image/webp") || lower.endsWith(".webp") -> "image/webp"
        lower.contains("image/gif") || lower.endsWith(".gif") -> "image/gif"
        else -> "image/png"
    }
}

private fun hasBytesAt(bytes: ByteArray, offset: Int, vararg expected: Int): Boolean =
    expected.indices.all { index ->
        offset + index < bytes.size &&
            (bytes[offset + index].toInt() and 0xFF) == expected[index]
    }

/**
 * Local file persistence, thumbnail extraction, system gallery export, and sharing for
 * generated images and videos under `filesDir/media_studio/`.
 */
class MediaAssetStorage(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    private val rootDir: File get() = File(context.filesDir, ROOT_FOLDER).apply { mkdirs() }
    private val imagesDir: File get() = File(rootDir, "images").apply { mkdirs() }
    private val videosDir: File get() = File(rootDir, "videos").apply { mkdirs() }
    private val thumbsDir: File get() = File(rootDir, "thumbs").apply { mkdirs() }
    private val refsDir: File get() = File(rootDir, "refs").apply { mkdirs() }

    /** Persist a generated image from `data:`, `file://`/path, or `http(s)://` URL. */
    suspend fun persistImage(rawSource: String, assetId: String): StoredMediaFile? = withContext(Dispatchers.IO) {
        val remoteUrl = rawSource.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        val bytes = decodeOrDownloadBytes(rawSource, MAX_IMAGE_BYTES) ?: return@withContext null
        val mime = inferImageMime(bytes, rawSource)
        val ext = extensionForMime(mime, defaultExt = "png")
        val target = File(imagesDir, "$assetId.$ext")
        runCatching {
            target.writeBytes(bytes)
        }.onFailure { e ->
            Logger.w(TAG, "persistImage write failed id=$assetId: ${e.message}", e)
            return@withContext null
        }
        val bounds = decodeImageBounds(target)
        StoredMediaFile(
            localPath = target.absolutePath,
            remoteUrl = remoteUrl,
            mimeType = mime,
            width = bounds.first,
            height = bounds.second,
        )
    }

    /** Persist a generated video from `http(s)://`, `data:`, or `file://` and extract a first-frame thumbnail. */
    suspend fun persistVideo(rawSource: String, assetId: String): StoredMediaFile? = withContext(Dispatchers.IO) {
        val remoteUrl = rawSource.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        val target = File(videosDir, "$assetId.mp4")
        val written = writeVideoToTarget(rawSource, target)
        if (!written) return@withContext null

        val meta = extractVideoMetadata(target, assetId)
        StoredMediaFile(
            localPath = target.absolutePath,
            thumbnailPath = meta.thumbnailPath,
            remoteUrl = remoteUrl,
            mimeType = "video/mp4",
            width = meta.width,
            height = meta.height,
            durationMs = meta.durationMs,
        )
    }

    /** Cache a reference image under `refs/` so retry/history stays independent of temporary URIs. */
    suspend fun persistReferenceImage(rawSource: String, taskId: String): String? = withContext(Dispatchers.IO) {
        val trimmed = rawSource.trim()
        if (trimmed.isEmpty()) return@withContext null
        val existingFile = File(trimmed.removePrefix("file://"))
        if (trimmed.startsWith("/") || trimmed.startsWith("file://")) {
            if (existingFile.exists() && isInsideRoot(existingFile)) return@withContext existingFile.absolutePath
        }
        val bytes = decodeOrDownloadBytes(trimmed, MAX_REF_IMAGE_BYTES) ?: return@withContext null
        val mime = inferImageMime(bytes, trimmed)
        val ext = extensionForMime(mime, defaultExt = "jpg")
        val target = File(refsDir, "ref_$taskId.$ext")
        runCatching { target.writeBytes(bytes) }.getOrNull() ?: return@withContext null
        target.absolutePath
    }

    /** Read a cached reference file path back into a data URI for provider submission. */
    fun readFileAsDataUri(localPath: String): String? {
        val file = File(localPath.removePrefix("file://"))
        if (!file.exists()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val mime = inferImageMime(bytes, file.name)
        return "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** Save an image or video asset into the device system gallery via MediaStore. */
    suspend fun saveToSystemGallery(localFile: File, isVideo: Boolean): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            require(localFile.exists()) { "Local media file missing: ${localFile.name}" }
            val ext = localFile.extension.ifBlank { if (isVideo) "mp4" else "png" }
            val mime = if (isVideo) "video/$ext" else inferImageMime(localFile.readBytes(), localFile.name)
            val displayName = "muse_${System.currentTimeMillis()}.$ext"
            val relativeRoot = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (isVideo) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeRoot/Muse")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                var inserted: Uri? = null
                try {
                    inserted = resolver.insert(collection, values)
                        ?: error("MediaStore insert returned null")
                    resolver.openOutputStream(inserted)?.use { out ->
                        localFile.inputStream().use { input -> input.copyTo(out) }
                    } ?: error("MediaStore openOutputStream returned null")
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(inserted, values, null, null)
                    inserted
                } catch (t: Exception) {
                    inserted?.let { resolver.delete(it, null, null) }
                    throw t
                }
            } else {
                val baseDir = Environment.getExternalStoragePublicDirectory(relativeRoot)
                val museDir = File(baseDir, "Muse").apply { mkdirs() }
                val dest = File(museDir, displayName)
                localFile.copyTo(dest, overwrite = true)
                suspendCancellableCoroutine { cont ->
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(dest.absolutePath),
                        arrayOf(mime),
                    ) { _, scannedUri ->
                        cont.resume(scannedUri ?: Uri.fromFile(dest))
                    }
                }
            }
        }
    }

    /** Share a local file through Android FileProvider chooser. */
    fun shareLocalFile(uiContext: Context, localFile: File, mimeType: String): Boolean {
        if (!localFile.exists()) return false
        return runCatching {
            val uri = FileProvider.getUriForFile(
                uiContext,
                "${uiContext.packageName}.fileprovider",
                localFile,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ShareIntentHelper.startChooserSafely(
                uiContext,
                intent,
                uiContext.getString(R.string.common_share),
            )
            true
        }.onFailure { e ->
            Logger.w(TAG, "shareLocalFile failed: ${e.message}", e)
        }.getOrDefault(false)
    }

    /** Extract a local first-frame JPEG thumbnail for any file/content video URI into `thumbsDir`. */
    suspend fun getOrExtractVideoThumbnail(videoUri: String, cacheKey: String): String? = withContext(Dispatchers.IO) {
        val outFile = File(thumbsDir, "vthumb_$cacheKey.jpg")
        if (outFile.exists() && outFile.length() > 0L) return@withContext outFile.absolutePath
        val retriever = MediaMetadataRetriever()
        try {
            when {
                videoUri.startsWith("content://") -> retriever.setDataSource(context, Uri.parse(videoUri))
                videoUri.startsWith("http://", true) || videoUri.startsWith("https://", true) ->
                    retriever.setDataSource(videoUri, emptyMap())
                else -> {
                    val path = videoUri.removePrefix("file://")
                    if (!File(path).exists()) return@withContext null
                    retriever.setDataSource(path)
                }
            }
            val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@withContext null
            try {
                FileOutputStream(outFile).use { fos ->
                    frame.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, fos)
                }
                outFile.absolutePath
            } finally {
                frame.recycle()
            }
        } catch (e: RuntimeException) {
            Logger.w(TAG, "getOrExtractVideoThumbnail failed: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Delete files owned by [asset] if they reside under `filesDir/media_studio`. */
    fun deleteFilesFor(asset: MediaGenAssetEntity) {
        deleteIfOwned(asset.localPath)
        asset.thumbnailPath?.let(::deleteIfOwned)
    }

    private fun deleteIfOwned(path: String) {
        val file = File(path)
        if (file.exists() && isInsideRoot(file)) {
            runCatching { file.delete() }
        }
    }

    private fun isInsideRoot(file: File): Boolean = runCatching {
        val rootPath = rootDir.canonicalPath
        file.canonicalPath.startsWith(rootPath + File.separator)
    }.getOrDefault(false)

    private fun writeVideoToTarget(rawSource: String, target: File): Boolean {
        val trimmed = rawSource.trim()
        return when {
            trimmed.startsWith("data:") -> {
                val b64 = trimmed.substringAfter("base64,", "").replace("\n", "").replace("\r", "")
                if (b64.isBlank()) return false
                runCatching {
                    target.writeBytes(Base64.decode(b64, Base64.DEFAULT))
                    true
                }.getOrDefault(false)
            }
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> {
                downloadToFile(trimmed, target, MAX_VIDEO_BYTES)
            }
            else -> {
                val src = File(trimmed.removePrefix("file://"))
                if (!src.exists()) return false
                if (src.canonicalPath == target.canonicalPath) return true
                runCatching {
                    src.inputStream().use { input ->
                        FileOutputStream(target).use { out -> input.copyTo(out) }
                    }
                    true
                }.getOrDefault(false)
            }
        }
    }

    private fun decodeOrDownloadBytes(rawSource: String, maxBytes: Long): ByteArray? {
        val trimmed = rawSource.trim()
        return when {
            trimmed.startsWith("data:") -> {
                val b64 = trimmed.substringAfter("base64,", "").replace("\n", "").replace("\r", "")
                if (b64.isBlank()) return null
                runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                    ?.takeIf { it.size <= maxBytes }
            }
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> {
                downloadBytes(trimmed, maxBytes)
            }
            trimmed.startsWith("content://") -> {
                runCatching {
                    context.contentResolver.openInputStream(Uri.parse(trimmed))?.use { it.readBytes() }
                }.getOrNull()?.takeIf { it.size <= maxBytes }
            }
            else -> {
                val file = File(trimmed.removePrefix("file://"))
                if (!file.exists() || file.length() > maxBytes) return null
                runCatching { file.readBytes() }.getOrNull()
            }
        }
    }

    private fun downloadBytes(url: String, maxBytes: Long): ByteArray? {
        if (SsrfGuard.isBlocked(url)) {
            Logger.w(TAG, "downloadBytes blocked SSRF url")
            return null
        }
        val req = Request.Builder().url(url).build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body ?: return@use null
                val len = body.contentLength()
                if (len > maxBytes) return@use null
                body.byteStream().use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > maxBytes) return@use null
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            }
        }.onFailure { e ->
            Logger.w(TAG, "downloadBytes failed: ${e.message}")
        }.getOrNull()
    }

    private fun downloadToFile(url: String, target: File, maxBytes: Long): Boolean {
        if (SsrfGuard.isBlocked(url)) {
            Logger.w(TAG, "downloadToFile blocked SSRF url")
            return false
        }
        val req = Request.Builder().url(url).build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val body = resp.body ?: return@use false
                if (body.contentLength() > maxBytes) return@use false
                body.byteStream().use { input ->
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > maxBytes) {
                                runCatching { target.delete() }
                                return@use false
                            }
                            out.write(buf, 0, n)
                        }
                    }
                }
                target.length() > 0L
            }
        }.onFailure { e ->
            Logger.w(TAG, "downloadToFile failed: ${e.message}")
            runCatching { target.delete() }
        }.getOrDefault(false)
    }

    private data class VideoMeta(
        val thumbnailPath: String?,
        val width: Int,
        val height: Int,
        val durationMs: Long,
    )

    private fun extractVideoMetadata(videoFile: File, assetId: String): VideoMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val thumbPath = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { bmp ->
                val thumbFile = File(thumbsDir, "$assetId.jpg")
                try {
                    FileOutputStream(thumbFile).use { fos ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, fos)
                    }
                    thumbFile.absolutePath
                } finally {
                    bmp.recycle()
                }
            }
            VideoMeta(thumbPath, width, height, durationMs)
        } catch (e: RuntimeException) {
            Logger.w(TAG, "extractVideoMetadata failed id=$assetId: ${e.message}")
            VideoMeta(null, 0, 0, 0L)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeImageBounds(file: File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth.coerceAtLeast(0) to opts.outHeight.coerceAtLeast(0)
    }

    private fun extensionForMime(mime: String, defaultExt: String): String = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/png" -> "png"
        "video/mp4" -> "mp4"
        else -> defaultExt
    }

    companion object {
        private const val TAG = "MediaAssetStorage"
        private const val ROOT_FOLDER = "media_studio"
        private const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
        private const val MAX_REF_IMAGE_BYTES = 12L * 1024 * 1024
        private const val MAX_VIDEO_BYTES = 256L * 1024 * 1024
        private const val THUMB_JPEG_QUALITY = 85
    }
}
