package io.zer0.muse.ui.chat

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 附件类型枚举 — 集中定义，替代散落各处的字符串/MIME 判断。
 *
 * 旧实现现状（无统一枚举）：
 *  - IMAGE:  ChatState.pendingImages / UIMessage.imageBase64List（base64 列表）
 *  - AUDIO:  ASR 录音流程，不挂在消息体上
 *  - FILE:   DocumentPicker 选取后经 DocumentParser 解析为文本
 *  - VIDEO:  本文件新增，直接发给支持视频的 Provider（如 Gemini），或降级为关键帧图片
 */
enum class AttachmentType {
    IMAGE,
    AUDIO,
    FILE,
    VIDEO,
}

/**
 * 视频附件元数据。
 *
 * - [uri] 视频 content uri（SAF 临时授权 uri 仅在当前进程生命周期内有效）
 * - [durationMs] 时长（毫秒）
 * - [width] 视频宽（像素）
 * - [height] 视频高（像素）
 * - [thumbnail] 首帧缩略图 base64（无 data: 前缀），用于消息气泡内预览
 */
data class VideoAttachment(
    val uri: Uri,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val thumbnail: String?,
)

/**
 * 视频附件处理器。
 *
 * 职责：
 *  - 校验视频大小（>50MB 返回失败，提示用户压缩或拒绝）
 *  - 用 [MediaMetadataRetriever] 提取元数据（时长/分辨率）
 *  - 提取首帧缩略图，用于 UI 预览
 *  - 提取关键帧（首帧 + 中间帧 + 末帧）base64 列表，
 *    供不支持视频的 Provider 降级为图片发送
 *
 * 约束：
 *  - 不新增依赖：仅使用 Android 原生 [MediaMetadataRetriever]
 *  - 所有 IO 与解码均切到 [Dispatchers.IO]，避免阻塞主线程
 *  - MediaMetadataRetriever 用完必须 release，否则会导致底层 MediaPlayer 泄漏
 */
class VideoAttachmentHandler {

    companion object {
        private const val TAG = "VideoAttachmentHandler"

        /** 视频大小上限：50MB（避免内存溢出 + 与 Provider 上传限制对齐）。 */
        const val MAX_VIDEO_SIZE_BYTES = 50L * 1024 * 1024

        /** 缩略图 JPEG 压缩质量（0-100）。 */
        private const val THUMBNAIL_JPEG_QUALITY = 80

        /** 缩略图最长边像素（视觉预览不需要原分辨率，控制 base64 体积）。 */
        private const val THUMBNAIL_MAX_EDGE = 720
    }

    /**
     * 处理视频附件：校验大小 + 提取元数据 + 生成首帧缩略图。
     *
     * 流程：
     *  1. 通过 [ContentResolver] 查询文件大小，>50MB 直接返回失败
     *  2. 用 [MediaMetadataRetriever] 提取时长/分辨率
     *  3. getFrameAtTime(0) 取首帧，编码为 base64 JPEG 缩略图
     *
     * @return 成功返回 [VideoAttachment]；失败（文件过大 / 无法读取 / 无元数据）返回 Result.failure
     */
    suspend fun handleVideoAttachment(uri: Uri, context: Context): Result<VideoAttachment> {
        return runCatching {
            withContext(Dispatchers.IO) {
                // 1. 校验文件大小（>50MB 拒绝）
                val size = queryFileSize(uri, context)
                require(size in 1..MAX_VIDEO_SIZE_BYTES) {
                    "视频过大（${formatBytes(size)}），请压缩到 50MB 以内后重试"
                }

                // 2. 提取元数据
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val durationMs = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION,
                    )?.toLongOrNull() ?: 0L
                    val width = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                    )?.toIntOrNull() ?: 0
                    val height = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                    )?.toIntOrNull() ?: 0

                    // 3. 提取首帧缩略图（失败不致命，降级为 null）
                    val thumbnail = runCatching {
                        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?.let { bmp -> encodeBitmapToBase64(bmp) }
                    }.onFailure { e ->
                        Logger.w(TAG, "提取首帧缩略图失败", e)
                    }.getOrNull()

                    VideoAttachment(
                        uri = uri,
                        durationMs = durationMs,
                        width = width,
                        height = height,
                        thumbnail = thumbnail,
                    )
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }.onFailure { e ->
            Logger.e(TAG, "handleVideoAttachment failed", e)
        }
    }

    /**
     * 提取关键帧 base64 列表（首帧 + 中间帧 + 末帧）。
     *
     * 用于不支持视频上传的 Provider：把视频降级为多张图片发送给视觉模型。
     *
     * @param frameCount 期望的关键帧数量（默认 3：首/中/末），实际帧数可能少于该值
     * @return base64 字符串列表（无 data: 前缀），失败时返回空列表
     */
    suspend fun extractKeyFrames(
        uri: Uri,
        context: Context,
        frameCount: Int = 3,
    ): List<String> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) return@withContext emptyList()

            // 时间戳单位为微秒（us），durationMs 为毫秒，需 *1000
            // 末帧留 50ms 余量避免越界（部分编码器尾部帧不可达）
            val timestamps = buildList {
                add(0L)
                if (frameCount >= 2) add(durationMs * 1000L / 2)
                if (frameCount >= 3) add((durationMs - 50).coerceAtLeast(0) * 1000L)
            }.distinct()

            timestamps.mapNotNull { tsUs ->
                runCatching {
                    retriever.getFrameAtTime(tsUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?.let { bmp -> encodeBitmapToBase64(bmp) }
                }.onFailure { e ->
                    Logger.w(TAG, "提取关键帧失败 ts=$tsUs", e)
                }.getOrNull()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "extractKeyFrames failed", e)
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 获取首帧 base64 缩略图（无 data: 前缀）。
     *
     * 失败时返回 null（调用方应做兜底，例如显示占位图）。
     */
    suspend fun getVideoThumbnail(uri: Uri, context: Context): String? {
        return withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let { bmp -> encodeBitmapToBase64(bmp) }
            } catch (e: Exception) {
                Logger.w(TAG, "getVideoThumbnail failed", e)
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    /**
     * 通过 ContentResolver 查询视频文件字节数。
     *
     * 用 openAssetFileDescriptor 拿 length，兼容 content:// 与 file:// uri。
     * 失败返回 -1（调用方据此跳过大小校验或拒绝）。
     */
    private fun queryFileSize(uri: Uri, context: Context): Long {
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length
            } ?: -1L
        }.getOrDefault(-1L)
    }

    /** 把 Bitmap 编码为 base64（JPEG，无 data: 前缀），按 [THUMBNAIL_MAX_EDGE] 等比缩放。 */
    private fun encodeBitmapToBase64(bmp: android.graphics.Bitmap): String {
        val scaled = scaleIfNeeded(bmp)
        val baos = ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, baos)
        if (scaled !== bmp) scaled.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    /** 等比缩放至最长边不超过 [THUMBNAIL_MAX_EDGE]，避免缩略图过大导致 base64 体积爆炸。 */
    private fun scaleIfNeeded(bmp: android.graphics.Bitmap): android.graphics.Bitmap {
        val w = bmp.width
        val h = bmp.height
        val longest = maxOf(w, h)
        if (longest <= THUMBNAIL_MAX_EDGE) return bmp
        val scale = THUMBNAIL_MAX_EDGE.toFloat() / longest
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return android.graphics.Bitmap.createScaledBitmap(bmp, newW, newH, true)
    }

    /** 格式化字节数为可读字符串。 */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
