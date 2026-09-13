package io.zer0.muse.data

import io.zer0.common.Logger
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 运行时小型文件的原子写入和损坏隔离工具。
 *
 * 写入先落到目标文件同目录的临时文件，并在替换前同步文件内容；
 * 替换失败时不会直接覆盖正式文件。适用于会话快照、索引和注册表等
 * 可恢复状态，不替代 SAF 导出流或大文件下载器。
 */
object AtomicFileStore {

    private const val TAG = "AtomicFileStore"

    /** 原子写入 UTF-8 文本，不在目标文件上做直接覆盖写。 */
    fun writeText(target: File, text: String) {
        writeBytes(target, text.toByteArray(Charsets.UTF_8))
    }

    /** 原子写入字节，临时文件和目标文件必须位于同一目录。 */
    fun writeBytes(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: error("atomic file target must have a parent")
        check(parent.exists() || parent.mkdirs()) { "无法创建原子文件目录: ${parent.absolutePath}" }
        val temp = File(parent, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            moveIntoPlace(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * 将无法解析的文件移出正式路径，保留原始内容供诊断或人工恢复。
     * @return 隔离后的文件；目标不存在时返回 null。
     */
    @Suppress("TooGenericExceptionCaught")
    fun quarantine(file: File, reason: String): File? {
        if (!file.exists()) return null
        val suffix = reason.replace(Regex("[^A-Za-z0-9._-]"), "_").take(32).ifEmpty { "invalid" }
        val quarantined = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}-$suffix")
        return try {
            if (file.renameTo(quarantined)) {
                quarantined
            } else {
                // 某些 Android/Robolectric 文件系统不支持 renameTo；同目录复制后删除正式文件。
                file.copyTo(quarantined, overwrite = false)
                check(file.delete()) { "无法删除待隔离文件: ${file.absolutePath}" }
                quarantined
            }
        } catch (error: Exception) {
            Logger.w(TAG, "隔离损坏文件失败: ${file.name}", error)
            null
        }
    }

    private fun moveIntoPlace(temp: File, target: File) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (unsupported: AtomicMoveNotSupportedException) {
            // 某些 Android 文件系统不支持 ATOMIC_MOVE；仍使用同目录替换，
            // 绝不退回到直接 writeText，避免目标文件留下半写内容。
            Logger.w(TAG, "文件系统不支持原子移动，使用同目录替换: ${target.name}", unsupported)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
