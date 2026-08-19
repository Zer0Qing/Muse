@file:Suppress("ReturnCount")

package io.zer0.muse.data

import android.content.Context
import android.net.Uri
import io.zer0.common.Logger
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 个人头像的私有存储边界。
 *
 * Photo Picker 返回的 content URI 可能只有短时授权，不能直接写入 DataStore
 * 作为长期资源。所有可读 URI 在这里复制到 filesDir/avatar 后再持久化路径。
 */
object AvatarStorage {

    private const val MAX_AVATAR_BYTES = 10L * 1024 * 1024

    /**
     * 把头像资源复制到应用私有目录并返回可长期使用的路径。
     *
     * Photo Picker 的 content URI 可能只有临时读取权限；外部 file URI
     * 也可能在应用重启后失效，因此两者都必须复制到 filesDir/avatar。
     */
    suspend fun persist(context: Context, uri: String?): String? {
        if (uri.isNullOrBlank()) return uri
        if (uri.startsWith("android.resource://") || uri.startsWith("data:image/")) {
            return uri
        }

        val normalized = uri.removePrefix("file://")
        if (normalized.startsWith("/")) {
            val sourceFile = File(normalized)
            if (!sourceFile.exists() || !sourceFile.isFile) {
                Logger.w("AvatarStorage", "头像文件不存在: $normalized")
                return null
            }
            if (isPrivateAvatarFile(context, sourceFile)) {
                return sourceFile.absolutePath
            }
            return copyFile(context, sourceFile)
        }
        return copyContentUri(context, Uri.parse(uri))
    }

    /** 把本地头像路径转换成 Coil 可直接加载的模型。 */
    fun imageModel(uri: String): Any =
        when {
            uri.startsWith("/") -> File(uri)
            uri.startsWith("file://") -> Uri.parse(uri).path?.let(::File) ?: uri
            else -> uri
        }

    private fun isPrivateAvatarFile(context: Context, file: File): Boolean =
        try {
            val avatarDir = File(context.filesDir, "avatar").canonicalFile
            file.canonicalFile.parentFile == avatarDir
        } catch (e: IOException) {
            Logger.w("AvatarStorage", "无法判断头像文件是否属于私有目录", e)
            false
        }

    private suspend fun copyFile(context: Context, sourceFile: File): String? =
        withContext(Dispatchers.IO) {
            try {
                sourceFile.inputStream().use { input ->
                    copyInputStream(context, input, sourceFile.extension)
                }
            } catch (e: IOException) {
                Logger.e("AvatarStorage", "头像文件复制失败", e)
                null
            } catch (e: SecurityException) {
                Logger.e("AvatarStorage", "头像文件读取权限失败", e)
                null
            }
        }

    private suspend fun copyContentUri(context: Context, sourceUri: Uri): String? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            try {
                val input = resolver.openInputStream(sourceUri)
                if (input == null) {
                    Logger.w("AvatarStorage", "无法读取头像 URI: $sourceUri")
                    return@withContext null
                }
                val extension = when (resolver.getType(sourceUri)?.lowercase()) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    else -> "jpg"
                }
                input.use { copyInputStream(context, it, extension) }
            } catch (e: IOException) {
                Logger.e("AvatarStorage", "头像复制失败", e)
                null
            } catch (e: SecurityException) {
                Logger.e("AvatarStorage", "头像读取权限失败", e)
                null
            }
        }

    private fun copyInputStream(context: Context, input: InputStream, extension: String): String? {
        val dir = File(context.filesDir, "avatar")
        if (!dir.exists() && !dir.mkdirs()) {
            Logger.e("AvatarStorage", "无法创建头像目录: ${dir.absolutePath}")
            return null
        }
        val normalizedExtension = when (extension.lowercase()) {
            "png", "webp", "gif" -> extension.lowercase()
            else -> "jpg"
        }
        val target = File(dir, "avatar_${UUID.randomUUID()}.$normalizedExtension")
        val temp = File(dir, "${target.name}.tmp")
        try {
            val copied = temp.outputStream().use { output -> copyStream(input, output) }
            if (!copied) {
                temp.delete()
                Logger.w("AvatarStorage", "头像超过 ${MAX_AVATAR_BYTES / 1024 / 1024}MB 上限,已拒绝")
                return null
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                Logger.e("AvatarStorage", "头像文件提交失败: ${target.absolutePath}")
                return null
            }
            return target.absolutePath
        } catch (e: IOException) {
            temp.delete()
            Logger.e("AvatarStorage", "头像写入失败", e)
            return null
        } catch (e: SecurityException) {
            temp.delete()
            Logger.e("AvatarStorage", "头像目录写入权限失败", e)
            return null
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream): Boolean {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return true
            total += read
            if (total > MAX_AVATAR_BYTES) return false
            output.write(buffer, 0, read)
        }
    }
}
