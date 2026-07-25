package io.zer0.muse.data.session

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Session file manager (openhanako session-folders-tool.ts port).
 *
 * Each session can have associated files/images stored in app_internal/session_files/{sessionId}/.
 */
class SessionFileManager(private val context: Context) {

    companion object {
        private const val TAG = "SessionFileManager"
        private const val DIR_NAME = "session_files"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class SessionFile(
        val name: String,
        val path: String,
        val mimeType: String = "",
        val sizeBytes: Long = 0L,
        val addedAt: Long = System.currentTimeMillis(),
    )

    private fun sessionDir(sessionId: String): File {
        return File(context.filesDir, "$DIR_NAME/$sessionId").also { it.mkdirs() }
    }

    /** List files for a session. */
    suspend fun listFiles(sessionId: String): List<SessionFile> = withContext(Dispatchers.IO) {
        val dir = sessionDir(sessionId)
        dir.listFiles()?.map { f ->
            SessionFile(name = f.name, path = f.absolutePath, sizeBytes = f.length(), mimeType = guessMimeType(f.name))
        } ?: emptyList()
    }

    /** 向会话添加文件(从源文件复制)。 */
    suspend fun addFile(sessionId: String, sourceFile: File, mimeType: String = ""): SessionFile? = withContext(Dispatchers.IO) {
        if (!sourceFile.exists()) return@withContext null
        val destDir = sessionDir(sessionId)
        val dest = File(destDir, sourceFile.name)
        sourceFile.copyTo(dest, overwrite = true)
        SessionFile(name = dest.name, path = dest.absolutePath, sizeBytes = dest.length(), mimeType = mimeType.ifBlank { guessMimeType(dest.name) })
    }

    /** Remove a file from a session. */
    suspend fun removeFile(sessionId: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(sessionDir(sessionId), fileName)
        if (file.exists()) file.delete() else false
    }

    /** 清理会话的所有文件。 */
    suspend fun clearSession(sessionId: String) = withContext(Dispatchers.IO) {
        sessionDir(sessionId).deleteRecursively()
    }

    /** 获取会话文件占用的总存储空间。 */
    suspend fun totalSize(): Long = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, DIR_NAME)
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun guessMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }
}
