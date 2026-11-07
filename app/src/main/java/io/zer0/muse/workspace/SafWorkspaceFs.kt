package io.zer0.muse.workspace

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.zer0.common.Result
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * 挂在系统授权目录上的文件系统。
 *
 * 系统分区存储之后，「相册」「下载」这些目录拿不到 [java.io.File]，
 * 只能拿到一个目录 URI，再用文档句柄逐层访问。安全边界由那次授权本身保证：
 * 所有查找都从授权根开始逐段下钻，不拼绝对路径，也就没有越界可言。
 *
 * 写文件用 `wa` 模式追加；部分提供方不支持时退回「读全量再写回」。
 *
 * @param context 应用 Context，用于取 ContentResolver
 * @param treeUri 用户授权目录的持久化 URI
 */
@Suppress("TooManyFunctions")
class SafWorkspaceFs(private val context: Context, treeUri: Uri) : WorkspaceFs {

    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw WorkspaceIoException("无法打开授权目录")

    /** 逐段下钻到目标文档。 */
    private fun find(relative: String): DocumentFile? {
        val segments = relative.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null
        var current: DocumentFile = root
        for (segment in segments) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    /** 找到目标文档的父目录与末段名字。 */
    private fun findParent(relative: String): Pair<DocumentFile, String>? {
        val segments = relative.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." }) return null
        var current: DocumentFile = root
        for (segment in segments.dropLast(1)) {
            current = current.findFile(segment)?.takeIf { it.isDirectory } ?: return null
        }
        return current to segments.last()
    }

    /** 逐级创建目录，返回最终目录。 */
    private fun ensureDirectory(relative: String): DocumentFile? {
        val segments = relative.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null
        var current: DocumentFile = root
        for (segment in segments) {
            val existing = current.findFile(segment)
            current = when {
                existing == null -> current.createDirectory(segment) ?: return null
                existing.isDirectory -> existing
                else -> return null
            }
        }
        return current
    }

    override suspend fun list(relative: String): List<FsEntry> = withContext(Dispatchers.IO) {
        val dir = find(relative) ?: throw WorkspaceIoException("目录不存在: $relative")
        if (!dir.isDirectory) throw WorkspaceIoException("不是文件夹: $relative")
        val base = relative.trim('/')
        dir.listFiles().map { doc ->
            val name = doc.name ?: "(未命名)"
            FsEntry(
                name = name,
                relativePath = if (base.isEmpty()) name else "$base/$name",
                isDirectory = doc.isDirectory,
                size = if (doc.isFile) doc.length() else 0L,
                lastModified = doc.lastModified(),
            )
        }.sortedWith(compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun exists(relative: String): Boolean = withContext(Dispatchers.IO) {
        if (relative.isBlank()) root.exists() else find(relative)?.exists() == true
    }

    override suspend fun isDirectory(relative: String): Boolean = withContext(Dispatchers.IO) {
        if (relative.isBlank()) root.isDirectory else find(relative)?.isDirectory == true
    }

    override suspend fun size(relative: String): Long = withContext(Dispatchers.IO) {
        find(relative)?.takeIf { it.isFile }?.length() ?: 0L
    }

    override suspend fun lastModified(relative: String): Long = withContext(Dispatchers.IO) {
        find(relative)?.takeIf { it.isFile }?.lastModified() ?: 0L
    }

    override suspend fun readText(relative: String): String = withContext(Dispatchers.IO) {
        val doc = find(relative) ?: throw WorkspaceIoException("文件不存在: $relative")
        if (!doc.isFile) throw WorkspaceIoException("不是文件: $relative")
        workspaceIo("读取") {
            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: throw WorkspaceIoException("无法打开文件: $relative")
        }
    }

    override suspend fun readBytes(relative: String): ByteArray? = withContext(Dispatchers.IO) {
        val doc = find(relative) ?: return@withContext null
        if (!doc.isFile) return@withContext null
        runCatching {
            context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    override suspend fun contentUri(relative: String): android.net.Uri? = withContext(Dispatchers.IO) {
        val doc = find(relative) ?: return@withContext null
        if (!doc.isFile) return@withContext null
        doc.uri
    }

    override suspend fun readHead(relative: String, maxBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
        val doc = find(relative) ?: return@withContext null
        if (!doc.isFile) return@withContext null
        runCatching {
            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                val buf = ByteArray(maxBytes.coerceAtLeast(1))
                var filled = 0
                while (filled < buf.size) {
                    val n = input.read(buf, filled, buf.size - filled)
                    if (n < 0) break
                    filled += n
                }
                if (filled == buf.size) buf else buf.copyOf(filled)
            }
        }.getOrNull()
    }

    override suspend fun readTextRange(relative: String, offsetChars: Int, lengthChars: Int): String = withContext(Dispatchers.IO) {
        // 分页读在授权目录上先整份读进来再切片：这里的文件通常是文档与图片说明，
        // 超过上限的读取由上层拦截，不会把大文件拖进内存。
        val text = readText(relative)
        val start = offsetChars.coerceIn(0, text.length)
        val end = (start + lengthChars.coerceAtLeast(1)).coerceAtMost(text.length)
        text.substring(start, end)
    }

    override suspend fun writeText(relative: String, content: String, append: Boolean): Unit = withContext(Dispatchers.IO) {
        val parentAndName = findParent(relative) ?: throw WorkspaceIoException("路径非法: $relative")
        val (parent, name) = parentAndName
        val existing = parent.findFile(name)
        val target = existing ?: parent.createFile(MIME_BINARY, name)
            ?: throw WorkspaceIoException("无法创建文件: $relative")
        workspaceIo("写入") {
            if (append && existing != null) {
                appendBytes(target, content.toByteArray(Charsets.UTF_8))
            } else {
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                } ?: throw WorkspaceIoException("无法写入: $relative")
            }
        }
    }

    override suspend fun writeStream(relative: String, source: InputStream, maxBytes: Long): Long = withContext(Dispatchers.IO) {
        val parentAndName = findParent(relative) ?: throw WorkspaceIoException("路径非法: $relative")
        val (parent, name) = parentAndName
        val target = parent.findFile(name) ?: parent.createFile(MIME_BINARY, name)
            ?: throw WorkspaceIoException("无法创建文件: $relative")
        var written = 0L
        val result = resultOf {
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                val buffer = ByteArray(64 * 1024)
                var read = source.read(buffer)
                while (read >= 0) {
                    if (read > 0) {
                        written += read
                        if (written > maxBytes) throw WorkspaceIoException("文件过大")
                        out.write(buffer, 0, read)
                    }
                    read = source.read(buffer)
                }
            } ?: throw WorkspaceIoException("无法写入: $relative")
        }
        if (result is Result.Error) {
            runCatching { target.delete() }
            val cause = result.throwable
            throw if (cause is WorkspaceIoException) cause else WorkspaceIoException("写入失败: ${result.message}", cause)
        }
        written
    }

    /** 追加写入：优先用 `wa` 打开，提供方不支持时读全量重写。 */
    private fun appendBytes(target: DocumentFile, bytes: ByteArray) {
        val appended = runCatching {
            context.contentResolver.openOutputStream(target.uri, "wa")?.use { out ->
                out.write(bytes)
                true
            } ?: false
        }.getOrDefault(false)
        if (appended) return
        val old = readBytesSync(target) ?: ByteArray(0)
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
            out.write(old)
            out.write(bytes)
        } ?: throw WorkspaceIoException("无法追加写入")
    }

    private fun readBytesSync(doc: DocumentFile): ByteArray? = runCatching {
        context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
    }.getOrNull()

    /**
     * 删除一个文件或目录。
     *
     * 不能只报一句「删除失败」就完：`DocumentFile.delete()` 内部把异常吞成 false，
     * 而真实原因恰恰是用户能行动的那部分信息。失败后再走一次 `contentResolver.delete`：
     * 要么真的删掉了（上一档返 false 但文件其实可删），要么拿到原始异常，
     * 具体怎么翻成给人看的话见 [safDeleteReason]。
     */
    override suspend fun delete(relative: String): Unit = withContext(Dispatchers.IO) {
        val doc = find(relative) ?: throw WorkspaceIoException("路径不存在: $relative")
        if (doc.delete()) return@withContext
        val retry = runCatching { context.contentResolver.delete(doc.uri, null, null) }
        if (retry.isSuccess) return@withContext
        throw WorkspaceIoException(safDeleteReason(relative, retry.exceptionOrNull()))
    }

    override suspend fun mkdirs(relative: String): Unit = withContext(Dispatchers.IO) {
        ensureDirectory(relative) ?: throw WorkspaceIoException("目录创建失败: $relative")
    }

    override suspend fun move(from: String, to: String): Unit = withContext(Dispatchers.IO) {
        val src = find(from) ?: throw WorkspaceIoException("源路径不存在: $from")
        val fromParent = findParent(from) ?: throw WorkspaceIoException("路径非法: $from")
        val toParentAndName = findParent(to) ?: throw WorkspaceIoException("目标路径非法: $to")
        val (toParent, toName) = toParentAndName
        if (toParent.findFile(toName) != null) throw WorkspaceIoException("目标已存在: $to")
        if (fromParent.first.uri == toParent.uri && src.renameTo(toName)) return@withContext
        copy(from, to)
        if (!src.delete()) throw WorkspaceIoException("移动后清理失败: $from")
    }

    override suspend fun copy(from: String, to: String): Unit = withContext(Dispatchers.IO) {
        val src = find(from) ?: throw WorkspaceIoException("源路径不存在: $from")
        val (parent, name) = findParent(to) ?: throw WorkspaceIoException("目标路径非法: $to")
        if (parent.findFile(name) != null) throw WorkspaceIoException("目标已存在: $to")
        copyDocument(src, parent, name)
    }

    /** 递归复制一个文档到目标目录下。 */
    private fun copyDocument(src: DocumentFile, parent: DocumentFile, name: String) {
        if (src.isDirectory) copyDirectory(src, parent, name) else copyFile(src, parent, name)
    }

    private fun copyDirectory(src: DocumentFile, parent: DocumentFile, name: String) {
        val dir = parent.createDirectory(name) ?: throw WorkspaceIoException("无法创建目录: $name")
        src.listFiles().forEach { child ->
            copyDocument(child, dir, child.name ?: return@forEach)
        }
    }

    private fun copyFile(src: DocumentFile, parent: DocumentFile, name: String) {
        val target = parent.createFile(MIME_BINARY, name) ?: throw WorkspaceIoException("无法创建文件: $name")
        workspaceIo("复制") {
            openInput(src).use { input ->
                openOutput(target, name).use { output -> input.copyTo(output) }
            }
        }
    }

    /** 打开源文档的读取流。 */
    private fun openInput(src: DocumentFile): InputStream =
        context.contentResolver.openInputStream(src.uri) ?: throw WorkspaceIoException("无法读取 ${src.name}")

    /** 打开目标文档的写入流。 */
    private fun openOutput(target: DocumentFile, name: String): OutputStream =
        context.contentResolver.openOutputStream(target.uri, "wt") ?: throw WorkspaceIoException("无法写入 $name")

    override suspend fun walkFiles(relative: String, limit: Int): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        val start = if (relative.isBlank()) {
            root
        } else {
            find(relative) ?: throw WorkspaceIoException("目录不存在: $relative")
        }
        val base = relative.trim('/')
        fun visit(dir: DocumentFile) {
            for (doc in dir.listFiles()) {
                if (out.size >= limit) return
                val name = doc.name ?: continue
                val rel = if (base.isEmpty()) name else "$base/$name"
                if (doc.isDirectory) {
                    if (!name.startsWith(".")) visit(doc)
                } else {
                    out.add(rel)
                }
            }
        }
        visit(start)
        out
    }

    companion object {
        /** 通用二进制类型：避免部分提供方按类型自动追加扩展名。 */
        private const val MIME_BINARY = "application/octet-stream"
    }
}

/**
 * 把 SAF 删除失败的原因翻成能行动的一句话。
 *
 * 放在文件级而不是类成员：构造 [SafWorkspaceFs] 需要一个真实 treeUri 与 Context，
 * 把判据绑在那上面只会让人不写测试。此处它是纯函数。
 *
 * `OperationNotImplementedException` 按**类名**比对：它在 API 26 引入，局部单元环上
 * 未必能加载到这个类型，名字比对不需要额外的 mock。
 *
 * 两类异常必须说成两回事：
 *  - Android 11+ 删其他应用拥有的媒体时抛 RecoverableSecurityException（继承
 *    SecurityException）。那不是「不准删」，是「要你再确认一次」；
 *  - OperationNotImplementedException 基本就是只授了读权限的目录。
 */
internal fun safDeleteReason(relative: String, cause: Throwable?): String {
    val name = relative.substringAfterLast('/')
    return when {
        cause is SecurityException ->
            "删除被系统拦下：「$name」属于其他应用，Android 要你再确认一次。请在系统弹窗里批准，或到相册/文件管理器里删除"

        cause?.javaClass?.name == OPERATION_NOT_IMPLEMENTED_CLASS ->
            "删不了「$name」：这个目录只提供了读取能力（挂载时可能只给了读权限）"

        cause == null -> "删除失败：$name"

        else -> "删除失败：$name（${cause.message ?: cause.javaClass.simpleName}）"
    }
}

/** android.content.OperationNotImplementedException 的全限定名。 */
private const val OPERATION_NOT_IMPLEMENTED_CLASS = "android.content.OperationNotImplementedException"
