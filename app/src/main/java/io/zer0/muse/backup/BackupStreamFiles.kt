package io.zer0.muse.backup

import io.zer0.common.Logger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

internal object BackupStreamFiles {
    const val START = "binaryFileStart"
    const val CHUNK = "binaryFileChunk"
    const val END = "binaryFileEnd"
    private const val CHUNK_BYTES = 256 * 1024
    private const val MAX_ENCODED_CHUNK = (CHUNK_BYTES + 2) / 3 * 4

    suspend fun write(writer: BufferedWriter, path: String, file: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_BYTES)
        val expectedSize = file.length()
        val modifiedAt = file.lastModified()
        var size = 0L
        file.inputStream().buffered().use { input ->
            writer.writeRecord(START) { put("path", path) }
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                size += read
                val content = Base64.getEncoder().encodeToString(
                    if (read == buffer.size) buffer else buffer.copyOf(read),
                )
                writer.writeRecord(CHUNK) { put("content", content) }
            }
        }
        check(size == expectedSize && file.lastModified() == modifiedAt) { "A file changed during backup" }
        writer.writeRecord(END) {
            put("size", size)
            put("sha256", Base64.getEncoder().encodeToString(digest.digest()))
        }
    }

    private inline fun BufferedWriter.writeRecord(type: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        write(
            buildJsonObject {
                put("type", type)
                fields()
            }.toString(),
        )
        newLine()
    }

    /** Files are published only after their final record matches and the database validates. */
    class Staging(private val cacheDir: File, private val filesDir: File) : Closeable {
        private data class Pending(
            val path: String,
            val file: File,
            val output: FileOutputStream,
            val digest: MessageDigest = MessageDigest.getInstance("SHA-256"),
            var size: Long = 0L,
        )

        private var stagingDirectory: File? = null
        private var pending: Pending? = null
        private val completed = linkedMapOf<String, File>()

        fun read(type: String, record: JsonObject) {
            when (type) {
                START -> start(record.field("path"))
                CHUNK -> append(record.field("content"))
                END -> finish(record.field("size").toLong(), record.field("sha256"))
                else -> requireComplete()
            }
        }

        private fun start(path: String) {
            requireComplete()
            require(BackupBinaryFiles.resolveTarget(filesDir, path) != null && path !in completed) {
                "Invalid or duplicate backup file path"
            }
            val dir = stagingDirectory ?: Files.createTempDirectory(cacheDir.toPath(), "backup-files-")
                .toFile().also { stagingDirectory = it }
            val file = File(dir, completed.size.toString())
            pending = Pending(path, file, FileOutputStream(file))
        }

        private fun append(encoded: String) {
            val target = checkNotNull(pending) { "Backup file data has no opening record" }
            require(encoded.isNotEmpty() && encoded.length <= MAX_ENCODED_CHUNK) { "Invalid backup file chunk size" }
            val data = Base64.getDecoder().decode(encoded)
            require(data.size <= CHUNK_BYTES) { "Invalid backup file chunk size" }
            target.output.write(data)
            target.digest.update(data)
            target.size += data.size
        }

        private fun finish(size: Long, hash: String) {
            val target = checkNotNull(pending) { "Backup file completion has no opening record" }
            check(target.size == size && Base64.getEncoder().encodeToString(target.digest.digest()) == hash) {
                "Incomplete or corrupt backup file"
            }
            target.output.close()
            completed[target.path] = target.file
            pending = null
        }

        fun requireComplete() {
            check(pending == null) { "Backup ended before completing a file" }
        }

        fun publish() {
            requireComplete()
            val added = mutableListOf<File>()
            try {
                completed.forEach { (path, staged) ->
                    val target = requireNotNull(BackupBinaryFiles.resolveTarget(filesDir, path))
                    target.parentFile?.mkdirs()
                    val existed = target.exists()
                    Files.move(
                        staged.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                    if (!existed) added += target
                }
            } catch (error: java.io.IOException) {
                added.forEach { if (!it.delete()) Logger.w("BackupService", "Unable to remove a partially restored file") }
                throw error
            }
        }

        override fun close() {
            try {
                pending?.output?.close()
            } finally {
                if (stagingDirectory?.deleteRecursively() == false) {
                    Logger.w("BackupService", "Unable to remove staged backup files")
                }
            }
        }

        private fun JsonObject.field(name: String): String =
            requireNotNull((get(name) as? JsonPrimitive)?.contentOrNull) { "Missing backup file field: $name" }
    }
}
