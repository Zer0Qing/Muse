package io.zer0.muse.workspace

import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 工作区文件系统的一处 IO 失败。消息面向用户，可原样展示。 */
class WorkspaceIoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 跑一段可能抛异常的 IO 块，失败统一转成 [WorkspaceIoException]。
 *
 * 已经就是 [WorkspaceIoException] 的失败原样上抛，避免消息被套两层。
 *
 * @param op 动作名，用于拼出「读取失败: xxx」这类给用户看的消息
 * @param block 实际 IO 动作
 */
internal inline fun <T> workspaceIo(op: String, block: () -> T): T {
    val result = resultOf(block)
    if (result is Result.Error) {
        val cause = result.throwable
        if (cause is WorkspaceIoException) throw cause
        throw WorkspaceIoException("${op}失败: ${result.message}", cause)
    }
    return result.getOrThrow()
}

/**
 * 工作区里的一条目录/文件条目。
 *
 * 不同来源（本地目录、系统授权目录）统一成这一种形状，界面与工具不必关心底层。
 *
 * @param name 显示名（不含路径）
 * @param relativePath 相对所属挂载根目录的路径（以 "/" 分隔，根目录为空串）
 * @param isDirectory 是否为目录
 * @param size 字节数（目录恒为 0）
 * @param lastModified 最后修改时间戳（毫秒）
 */
data class FsEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

/**
 * 一个挂载背后的文件系统。
 *
 * 抽象出这一层的原因：本地目录能直接拿到 [File]，系统授权目录只能拿到文档句柄，
 * 两套 API 语义差得远。上层只面对这组方法，就不再被来源绑死。
 */
interface WorkspaceFs {
    /** 列目录。不存在或不是目录时抛 [WorkspaceIoException]。 */
    suspend fun list(relative: String): List<FsEntry>

    /** 是否存在。 */
    suspend fun exists(relative: String): Boolean

    /** 是否是目录。 */
    suspend fun isDirectory(relative: String): Boolean

    /** 字节数；不存在或不是文件时返回 0。 */
    suspend fun size(relative: String): Long

    /** 读文本。超出调用方上限由上层判断，这里不设业务上限。 */
    suspend fun readText(relative: String): String

    /** 读原始字节。 */
    suspend fun readBytes(relative: String): ByteArray?

    /** 按字符区间读文本。 */
    suspend fun readTextRange(relative: String, offsetChars: Int, lengthChars: Int): String

    /** 写文本，[append] 为真时追加。父目录不存在时自动创建。 */
    suspend fun writeText(relative: String, content: String, append: Boolean)

    /**
     * 把一段输入流写到文件，返回写入字节数。
     *
     * 导入外部文件走这条路：流式拷贝，不会把整个文件读进内存。
     * 超过 [maxBytes] 时抛异常并中断。
     */
    suspend fun writeStream(relative: String, source: java.io.InputStream, maxBytes: Long): Long

    /** 删除文件或目录。 */
    suspend fun delete(relative: String)

    /** 创建目录（含多级）。 */
    suspend fun mkdirs(relative: String)

    /** 移动或重命名。 */
    suspend fun move(from: String, to: String)

    /** 复制。 */
    suspend fun copy(from: String, to: String)

    /** 递归收集文件（相对路径），最多 [limit] 个，跳过点开头的目录。 */
    suspend fun walkFiles(relative: String, limit: Int): List<String>
}

/**
 * 直接挂在本机目录上的文件系统。
 *
 * 所有路径都先取 canonicalPath 再校验与根目录的前缀关系：`..`、符号链接、
 * 相对路径拼接这些绕过手段在 canonical 之后都会现形。
 *
 * @param root 挂载根目录
 */
class LocalWorkspaceFs(private val root: File) : WorkspaceFs {

    /**
     * 把相对路径解析成真实文件，并做越界校验。
     *
     * @param relative 相对根目录的路径
     * @param allowRoot 是否允许指向根目录本身
     * @return 解析后的文件；越界或解析失败返回 null
     */
    private fun resolve(relative: String, allowRoot: Boolean): File? {
        val segments = relative.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == ".." }) return null
        if (segments.isEmpty()) return if (allowRoot) root else null
        val candidate = File(root, segments.joinToString("/"))
        val canonical = resultOf { candidate.canonicalFile }
            .onError { message, _ -> Logger.w(TAG, "canonicalFile 解析失败: $message") }
            .getOrNull() ?: return null
        val rootCanonical = root.canonicalPath
        val canonicalPath = canonical.canonicalPath
        val inside = canonicalPath == rootCanonical ||
            canonicalPath.startsWith(rootCanonical + File.separator)
        return if (inside) canonical else null
    }

    private fun require(relative: String, allowRoot: Boolean = false): File =
        resolve(relative, allowRoot) ?: throw WorkspaceIoException("非法路径或越界访问: $relative")

    override suspend fun list(relative: String): List<FsEntry> = withContext(Dispatchers.IO) {
        val dir = require(relative, allowRoot = true)
        if (!dir.isDirectory) throw WorkspaceIoException("不是文件夹: $relative")
        val rootCanonical = root.canonicalPath
        (dir.listFiles()?.toList() ?: emptyList()).map { file ->
            FsEntry(
                name = file.name,
                relativePath = file.canonicalPath
                    .removePrefix(rootCanonical + File.separator)
                    .replace(File.separatorChar, '/'),
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0L,
                lastModified = file.lastModified(),
            )
        }.sortedWith(compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun exists(relative: String): Boolean = withContext(Dispatchers.IO) {
        resolve(relative, allowRoot = true)?.exists() == true
    }

    override suspend fun isDirectory(relative: String): Boolean = withContext(Dispatchers.IO) {
        resolve(relative, allowRoot = true)?.isDirectory == true
    }

    override suspend fun size(relative: String): Long = withContext(Dispatchers.IO) {
        val file = resolve(relative, allowRoot = false) ?: return@withContext 0L
        if (file.isFile) file.length() else 0L
    }

    override suspend fun readText(relative: String): String = withContext(Dispatchers.IO) {
        val file = require(relative)
        if (!file.isFile) throw WorkspaceIoException("不是文件: $relative")
        workspaceIo("读取") { file.readText(Charsets.UTF_8) }
    }

    override suspend fun readBytes(relative: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = resolve(relative, allowRoot = false) ?: return@withContext null
        if (!file.isFile) return@withContext null
        runCatching { file.readBytes() }.getOrNull()
    }

    override suspend fun readTextRange(relative: String, offsetChars: Int, lengthChars: Int): String = withContext(Dispatchers.IO) {
        val file = require(relative)
        if (!file.isFile) throw WorkspaceIoException("不是文件: $relative")
        workspaceIo("读取") {
            io.zer0.muse.tools.ToolTextFilePager.readPage(
                file = file,
                path = relative,
                charset = Charsets.UTF_8,
                requestedOffset = offsetChars,
                requestedLength = lengthChars,
                continuationTool = "workspace_read",
            )
        }
    }

    override suspend fun writeText(relative: String, content: String, append: Boolean): Unit = withContext(Dispatchers.IO) {
        val file = require(relative)
        workspaceIo("写入") {
            file.parentFile?.takeIf { !it.exists() }?.mkdirs()
            if (append) file.appendText(content, Charsets.UTF_8) else file.writeText(content, Charsets.UTF_8)
        }
    }

    override suspend fun writeStream(relative: String, source: java.io.InputStream, maxBytes: Long): Long = withContext(Dispatchers.IO) {
        val file = require(relative)
        file.parentFile?.takeIf { !it.exists() }?.mkdirs()
        var written = 0L
        val result = resultOf {
            file.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var read = source.read(buffer)
                while (read >= 0) {
                    if (read > 0) {
                        written += read
                        if (written > maxBytes) throw WorkspaceIoException("文件过大")
                        output.write(buffer, 0, read)
                    }
                    read = source.read(buffer)
                }
            }
        }
        if (result is Result.Error) {
            resultOf { file.delete() }
            val cause = result.throwable
            throw if (cause is WorkspaceIoException) cause else WorkspaceIoException("写入失败: ${result.message}", cause)
        }
        written
    }

    override suspend fun delete(relative: String): Unit = withContext(Dispatchers.IO) {
        val file = require(relative, allowRoot = false)
        if (!file.exists()) throw WorkspaceIoException("路径不存在: $relative")
        workspaceIo("删除") {
            val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
            if (!ok) throw WorkspaceIoException("删除失败（可能存在子项或权限不足）")
        }
    }

    override suspend fun mkdirs(relative: String): Unit = withContext(Dispatchers.IO) {
        val dir = require(relative)
        if (dir.exists()) {
            if (!dir.isDirectory) throw WorkspaceIoException("目标已存在且不是文件夹: $relative")
            return@withContext
        }
        if (!dir.mkdirs()) throw WorkspaceIoException("目录创建失败: $relative")
    }

    override suspend fun move(from: String, to: String): Unit = withContext(Dispatchers.IO) {
        val src = require(from)
        val dst = require(to)
        if (!src.exists()) throw WorkspaceIoException("源路径不存在: $from")
        if (dst.exists()) throw WorkspaceIoException("目标已存在: $to")
        dst.parentFile?.takeIf { !it.exists() }?.mkdirs()
        if (!src.renameTo(dst)) throw WorkspaceIoException("移动失败")
    }

    override suspend fun copy(from: String, to: String): Unit = withContext(Dispatchers.IO) {
        val src = require(from)
        val dst = require(to)
        if (!src.exists()) throw WorkspaceIoException("源路径不存在: $from")
        if (dst.exists()) throw WorkspaceIoException("目标已存在: $to")
        dst.parentFile?.takeIf { !it.exists() }?.mkdirs()
        workspaceIo("复制") {
            if (src.isDirectory) src.copyRecursively(dst, overwrite = false) else src.copyTo(dst, overwrite = false)
        }
    }

    override suspend fun walkFiles(relative: String, limit: Int): List<String> = withContext(Dispatchers.IO) {
        val base = require(relative, allowRoot = true)
        val out = mutableListOf<String>()
        val rootCanonical = root.canonicalPath
        fun visit(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (out.size >= limit) return
                if (child.isDirectory) {
                    if (!child.name.startsWith(".")) visit(child)
                } else {
                    out.add(
                        child.canonicalPath
                            .removePrefix(rootCanonical + File.separator)
                            .replace(File.separatorChar, '/'),
                    )
                }
            }
        }
        visit(base)
        out
    }

    companion object {
        private const val TAG = "LocalWorkspaceFs"
    }
}
