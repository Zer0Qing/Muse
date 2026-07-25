package io.zer0.muse.data.assistant

import android.content.Context
import android.net.Uri
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * v0.25: 助手头像存储 — 把用户选的图片复制到 App 内部存储,返回绝对路径。
 *
 * 为什么不直接存 content:// URI:
 *  - SAF 返回的 URI 在 App 重启后可能失效(尤其未调用 takePersistableUriPermission)
 *  - 复制到内部存储后:① 持久化 ② 不需权限 ③ 备份恢复时图片跟着走
 *
 * 文件位置:`filesDir/avatars/assistant_<id>_<timestamp>.<ext>`
 */
object AvatarStorage {

    private const val TAG = "AvatarStorage"
    private const val DIR = "avatars"

    // H-AV1: 头像大小上限(10MB),超过拒绝写入,避免大图导致磁盘/内存压力
    private const val MAX_AVATAR_BYTES = 10L * 1024 * 1024

    /**
     * 把 [sourceUri] 指向的图片复制到内部存储,返回绝对路径。失败返回 null。
     *
     * H-AV1: 改为 suspend 函数,内部 `withContext(Dispatchers.IO)`,避免主线程磁盘 IO。
     * M-AV1: 显式捕获 OOM 和 IO 异常,原 `runCatching { ... }.getOrElse` 会吞掉
     * CancellationException(协程被取消时不应被静默捕获)和 OOM(JVM 不稳定状态)。
     */
    suspend fun copyToInternal(context: Context, sourceUri: Uri, assistantId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, DIR).apply { mkdirs() }
                val ext = guessExtension(context, sourceUri)
                val target = File(dir, "assistant_${assistantId}_${System.currentTimeMillis()}.$ext")

                // M-AV1: 边复制边累计大小,超过上限立即中止,避免一次性读入大图导致 OOM
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(8 * 1024)
                        var read: Int
                        var total = 0L
                        while (input.read(buf).also { read = it } > 0) {
                            total += read
                            if (total > MAX_AVATAR_BYTES) {
                                runCatching { target.delete() }
                                Logger.w(TAG, "头像超过 ${MAX_AVATAR_BYTES / 1024 / 1024}MB 上限,已拒绝")
                                return@withContext null
                            }
                            output.write(buf, 0, read)
                        }
                    }
                } ?: run {
                    Logger.w(TAG, "openInputStream 返回 null: $sourceUri")
                    return@withContext null
                }

                // 清理同 assistant 的旧头像(只保留最新的)
                dir.listFiles { f -> f.name.startsWith("assistant_${assistantId}_") }
                    ?.filter { it.absolutePath != target.absolutePath }
                    ?.forEach { runCatching { it.delete() } }
                Logger.i(TAG, "saved avatar → ${target.absolutePath}")
                target.absolutePath
            } catch (e: OutOfMemoryError) {
                // M-AV1: 大图复制时若 OOM,记录并返回 null,不让 App 崩溃
                Logger.e(TAG, "copy avatar OOM", e)
                null
            } catch (e: IOException) {
                Logger.e(TAG, "copy avatar IO failed", e)
                null
            } catch (e: SecurityException) {
                Logger.e(TAG, "copy avatar denied", e)
                null
            }
        }

    private fun guessExtension(context: Context, uri: Uri): String {
        val mime = context.contentResolver.getType(uri) ?: ""
        return when {
            mime.contains("png", ignoreCase = true) -> "png"
            mime.contains("webp", ignoreCase = true) -> "webp"
            mime.contains("gif", ignoreCase = true) -> "gif"
            mime.contains("jpeg", ignoreCase = true) || mime.contains("jpg", ignoreCase = true) -> "jpg"
            else -> "jpg"
        }
    }
}
