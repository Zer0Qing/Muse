package io.zer0.muse.data.quicknote

import android.content.Context
import android.net.Uri
import io.zer0.common.Logger
import java.io.File
import java.util.UUID

/**
 * v2.6.7: 快速记录的附件图片存储。
 *
 * ## 为什么单开一个目录，不复用小手机相册的 mini_album_import
 *
 * 相册导入目录会被「小手机相册」页整个列出（listImported 扫目录）。
 * 笔记附件若也写进那里，用户往笔记贴一张图，相册里就凭空多一张 —— 两个功能的
 * 数据边界会互相污染。所以各用各的目录。
 *
 * ## 为什么存文件而不是只存 URI
 *
 * 系统相册的 content URI 只有短暂读权限（且受 SAF 生命周期约束），
 * 笔记要能长期回看附件，必须把字节复制进应用私有目录。
 *
 * ## 文件命名
 *
 * `img_<uuid>.<ext>` —— 不携带笔记 id。笔记与附件的关系存在
 * QuickNoteEntity.attachmentsJson（路径数组）里，删除笔记时按清单回收文件。
 */
class NoteAttachmentStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /**
     * 把选中的图片复制进私有目录，返回可长期读取的 file:// 路径字符串。
     *
     * 失败（无读权限、空文件、IO 异常）返回 null —— 调用方据此提示用户，
     * 不写入一条指向不存在文件的附件记录。
     */
    fun importOne(uri: Uri): String? = runCatching {
        val ext = guessExtension(uri)
        val target = File(dir, "img_${UUID.randomUUID()}$ext")
        val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
        input.use { stream ->
            target.outputStream().use { output -> stream.copyTo(output) }
        }
        // 0 字节说明读取被拒或源为空，不能当成导入成功
        if (target.length() <= 0L) {
            target.delete()
            return@runCatching null
        }
        Uri.fromFile(target).toString()
    }.onFailure { t ->
        Logger.w(TAG, "笔记附件导入失败: ${t.message}")
    }.getOrNull()

    /**
     * 删除一个附件文件。
     *
     * 安全边界：只允许删本目录下的文件。attachmentsJson 理论上可被外部导入数据
     * 污染，不校验就会变成任意文件删除。
     */
    fun delete(uriString: String): Boolean = runCatching {
        val path = Uri.parse(uriString).path ?: return@runCatching false
        val file = File(path)
        if (file.parentFile?.absolutePath != dir.absolutePath) {
            Logger.w(TAG, "拒绝删除笔记目录之外的附件: ${file.name}")
            return@runCatching false
        }
        file.delete()
    }.onFailure { t ->
        Logger.w(TAG, "删除笔记附件失败: ${t.message}")
    }.getOrDefault(false)

    /** 按清单回收文件（笔记被永久删除 / 回收站清空时调用）。 */
    fun deleteAll(uriStrings: List<String>) {
        uriStrings.forEach { delete(it) }
    }

    private fun guessExtension(uri: Uri): String {
        val name = uri.lastPathSegment.orEmpty()
        val dot = name.lastIndexOf('.')
        if (dot > 0 && dot < name.length - 1) {
            val ext = name.substring(dot + 1).lowercase()
            if (ext in ALLOWED_EXTENSIONS) return ".$ext"
        }
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
        return when {
            type.contains("png", ignoreCase = true) -> ".png"
            type.contains("gif", ignoreCase = true) -> ".gif"
            type.contains("webp", ignoreCase = true) -> ".webp"
            else -> ".jpg"
        }
    }

    /** 校验某个路径是否确实是本存储管理的附件。 */
    fun isManaged(uriString: String): Boolean = uriString.contains("/$DIR_NAME/")

    companion object {
        private const val TAG = "NoteAttachmentStore"
        private const val DIR_NAME = "note_attachments"
        private val ALLOWED_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")
    }
}
