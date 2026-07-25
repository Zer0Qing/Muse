package io.zer0.muse.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import io.zer0.muse.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * 保存图片到应用私有图片目录,并在 Android Q+ 自动插入系统相册。
 *
 * 优势:
 *  - 不依赖 WRITE_EXTERNAL_STORAGE 权限,全版本可用。
 *  - Android Q+ 通过 MediaStore 写入 Pictures/Muse/,相册可见。
 *  - Android P 及以下写入应用外部私有目录,并通过 MediaScanner 让相册可见。
 *
 * @param uri 图片 URI(http/https/data URI/本地 file 均可)
 * @param displayName 保存后的显示文件名(不含扩展名)
 * @return 保存后的本地文件路径或 MediaStore URI 字符串
 */
suspend fun saveImageToGallery(
    context: Context,
    uri: String,
    displayName: String = "muse_${System.currentTimeMillis()}",
): String = withContext(Dispatchers.IO) {
    val bitmap = loadBitmapFromUri(context, uri)
        ?: throw IllegalStateException(context.getString(R.string.image_save_decode_failed))

    try {
        val fileName = "$displayName.png"

        // 1. 先写入应用外部私有目录(Pictures/Muse/),作为稳定缓存
        val appPicturesDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "Muse",
        ).apply { mkdirs() }
        val cacheFile = File(appPicturesDir, fileName)
        FileOutputStream(cacheFile).use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        }

        // 2. Android Q+ 同时插入 MediaStore 使相册可见
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Muse")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            var imageUri: Uri? = null
            try {
                imageUri = context.contentResolver.insert(collection, contentValues)
                    ?: throw IllegalStateException(context.getString(R.string.image_save_mediastore_insert_failed))
                context.contentResolver.openOutputStream(imageUri)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                } ?: throw IllegalStateException(context.getString(R.string.image_save_open_stream_failed))
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(imageUri, contentValues, null, null)
                return@withContext imageUri.toString()
            } catch (e: Throwable) {
                imageUri?.let { context.contentResolver.delete(it, null, null) }
                throw e
            }
        } else {
            // Android P 及以下: 扫描到相册
            suspendCancellableCoroutine { continuation ->
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(cacheFile.absolutePath),
                    arrayOf("image/png"),
                ) { path, _ -> continuation.resume(path) }
            }
            return@withContext cacheFile.absolutePath
        }
    } finally {
        // 写入完成后释放 Bitmap 内存,避免 OOM(特别是手动 createBitmap 创建的 Bitmap)
        bitmap.recycle()
    }
}

private suspend fun loadBitmapFromUri(context: Context, uri: String): Bitmap? = withContext(Dispatchers.IO) {
    when {
        uri.startsWith("data:image") -> {
            val base64 = uri.substringAfter("base64,", "")
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            // 降采样:先用 inJustDecodeBounds 获取尺寸,再按 inSampleSize 解码,避免高分辨率图片 OOM
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                // 计算 inSampleSize 使最大边长 <= 2048px(保存到相册不需要原始分辨率)
                val maxDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
                var sampleSize = 1
                while (maxDim / sampleSize > 2048) sampleSize *= 2
                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            }
        }
        uri.startsWith("http") -> {
            val request = ImageRequest.Builder(context)
                .data(uri)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request)
            (result as? SuccessResult)?.drawable?.let { drawable ->
                val width = drawable.intrinsicWidth
                val height = drawable.intrinsicHeight
                // 尺寸限制:超过 2048px 时按比例缩小 Canvas 绘制尺寸,避免 OOM(保存到相册不需要原始分辨率)
                val maxDim = maxOf(width, height)
                val scale = if (maxDim > 2048) 2048f / maxDim else 1f
                val targetWidth = (width * scale).toInt().coerceAtLeast(1)
                val targetHeight = (height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                if (scale < 1f) canvas.scale(scale, scale)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(canvas)
                bitmap
            }
        }
        else -> {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                // 降采样:先用 inJustDecodeBounds 获取尺寸,再重新打开流用 inSampleSize 解码,避免高分辨率图片 OOM
                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, boundsOptions)
                if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                    null
                } else {
                    // 计算 inSampleSize 使最大边长 <= 2048px(保存到相册不需要原始分辨率)
                    val maxDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
                    var sampleSize = 1
                    while (maxDim / sampleSize > 2048) sampleSize *= 2
                    // 第一次解码消耗了流,需要重新打开 InputStream 用 inSampleSize 解码
                    context.contentResolver.openInputStream(Uri.parse(uri))?.use { decodeInput ->
                        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        BitmapFactory.decodeStream(decodeInput, null, decodeOptions)
                    }
                }
            }
        }
    }
}
