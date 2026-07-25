package io.zer0.muse.workspace

import android.content.Context
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * P2-7: 工作区目录管理器(简化版 — 不实际运行 proot,仅提供目录与文件管理基础设施)。
 *
 * 工作区根目录: `/data/data/io.zer0.muse/files/workspace/`,所有 AI 工具与 UI 操作
 * 均只能在该根目录内进行读写,严禁通过 `..` 越权访问工作区外的任意文件。
 *
 * 主要能力:
 *  - [listDir]: 列出指定相对路径下的目录与文件
 *  - [readFile]: 读取文本文件(限制 1MB,超出则返回错误)
 *  - [writeFile]: 写入文本文件(限制 10MB,超出则返回错误)
 *  - [delete]: 删除文件或目录(目录递归删除)
 *  - [mkdir]: 创建目录(支持多级)
 *  - [move]: 移动/重命名
 *  - [copy]: 复制(目录递归复制)
 *  - [resolveSafe]: 路径安全检查(私有,所有公共方法都经此校验)
 *
 * 所有 IO 操作均切换到 [Dispatchers.IO] 协程执行,避免阻塞主线程。
 *
 * @param context 应用 Context(用于定位 filesDir)
 */
class WorkspaceManager(private val context: Context) {

    /** 工作区根目录:`/data/data/io.zer0.muse/files/workspace/`。 */
    val rootDir: File = File(context.filesDir, "workspace").apply {
        if (!exists()) mkdirs()
    }

    /**
     * 工作区内的一条目录/文件条目(UI 列表与工具返回均使用此结构)。
     *
     * @param name 显示名(不含路径)
     * @param relativePath 相对工作区根目录的路径(以 "/" 分隔,根目录为空串)
     * @param isDirectory 是否为目录
     * @param size 字节数(目录恒为 0)
     * @param lastModified 最后修改时间戳(毫秒)
     */
    data class WorkspaceEntry(
        val name: String,
        val relativePath: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
    )

    /**
     * 列出 [relativePath] 下的所有条目(目录在前,文件在后,各自按名称升序)。
     *
     * @param relativePath 相对工作区根目录的路径,空串或 "/" 表示根目录
     * @return 操作结果:[ListResult] 包含条目列表或错误信息
     */
    suspend fun listDir(relativePath: String): ListResult = withContext(Dispatchers.IO) {
        val dir = resolveSafe(relativePath, allowRoot = true, mustExist = true, mustBeDirectory = true)
            ?: return@withContext ListResult.Error("非法路径或目录不存在: $relativePath")
        val children = dir.listFiles()?.toList() ?: emptyList()
        val entries = children.map { file ->
            WorkspaceEntry(
                name = file.name,
                relativePath = formatRelativePath(file),
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0L,
                lastModified = file.lastModified(),
            )
        }.sortedWith(compareByDescending<WorkspaceEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        ListResult.Success(entries)
    }

    /**
     * 读取文本文件内容(UTF-8)。
     *
     * 限制:文件大小不得超过 [MAX_READ_BYTES](1MB),超出则返回错误。
     *
     * @param relativePath 相对工作区根目录的文件路径
     * @return 操作结果:[ReadResult] 包含文件内容或错误信息
     */
    suspend fun readFile(relativePath: String): ReadResult = withContext(Dispatchers.IO) {
        val file = resolveSafe(relativePath, allowRoot = false, mustExist = true, mustBeDirectory = false)
            ?: return@withContext ReadResult.Error("非法路径或文件不存在: $relativePath")
        val size = file.length()
        if (size > MAX_READ_BYTES) {
            return@withContext ReadResult.Error("文件过大($size 字节),读取上限为 $MAX_READ_BYTES 字节")
        }
        try {
            val content = file.readText(Charsets.UTF_8)
            ReadResult.Success(content)
        } catch (e: Exception) {
            Logger.w(TAG, "readFile 失败: ${e.message}")
            ReadResult.Error("读取失败: ${e.message}")
        }
    }

    /**
     * 写入文本文件(UTF-8,覆盖写入)。
     *
     * 限制:写入字节数不得超过 [MAX_WRITE_BYTES](10MB),超出则返回错误。
     * 若父目录不存在会自动创建(支持多级)。
     *
     * @param relativePath 相对工作区根目录的文件路径
     * @param content 文本内容
     * @return 操作结果:[OpResult] 表示成功或失败原因
     */
    suspend fun writeFile(relativePath: String, content: String): OpResult = withContext(Dispatchers.IO) {
        if (content.toByteArray(Charsets.UTF_8).size.toLong() > MAX_WRITE_BYTES) {
            return@withContext OpResult.Error("内容过大,写入上限为 $MAX_WRITE_BYTES 字节")
        }
        val file = resolveSafe(relativePath, allowRoot = false, mustExist = false, mustBeDirectory = false)
            ?: return@withContext OpResult.Error("非法路径: $relativePath")
        try {
            file.parentFile?.takeIf { !it.exists() }?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            OpResult.Success
        } catch (e: Exception) {
            Logger.w(TAG, "writeFile 失败: ${e.message}")
            OpResult.Error("写入失败: ${e.message}")
        }
    }

    /**
     * 删除文件或目录(目录递归删除)。
     *
     * @param relativePath 相对工作区根目录的路径
     * @return 操作结果:[OpResult] 表示成功或失败原因
     */
    suspend fun delete(relativePath: String): OpResult = withContext(Dispatchers.IO) {
        val file = resolveSafe(relativePath, allowRoot = false, mustExist = true, mustBeDirectory = false)
            ?: return@withContext OpResult.Error("非法路径或不存在: $relativePath")
        try {
            val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
            if (ok) OpResult.Success else OpResult.Error("删除失败(可能存在子项或权限不足)")
        } catch (e: Exception) {
            Logger.w(TAG, "delete 失败: ${e.message}")
            OpResult.Error("删除失败: ${e.message}")
        }
    }

    /**
     * 创建目录(支持多级,已存在视为成功)。
     *
     * @param relativePath 相对工作区根目录的目录路径
     * @return 操作结果:[OpResult] 表示成功或失败原因
     */
    suspend fun mkdir(relativePath: String): OpResult = withContext(Dispatchers.IO) {
        val file = resolveSafe(relativePath, allowRoot = false, mustExist = false, mustBeDirectory = true)
            ?: return@withContext OpResult.Error("非法路径: $relativePath")
        try {
            if (file.exists()) {
                if (file.isDirectory) OpResult.Success
                else OpResult.Error("目标已存在且不是目录: $relativePath")
            } else {
                if (file.mkdirs()) OpResult.Success else OpResult.Error("目录创建失败")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "mkdir 失败: ${e.message}")
            OpResult.Error("目录创建失败: ${e.message}")
        }
    }

    /**
     * 移动/重命名文件或目录。
     *
     * @param from 源路径(相对工作区根目录)
     * @param to 目标路径(相对工作区根目录)
     * @return 操作结果:[OpResult] 表示成功或失败原因
     */
    suspend fun move(from: String, to: String): OpResult = withContext(Dispatchers.IO) {
        val src = resolveSafe(from, allowRoot = false, mustExist = true, mustBeDirectory = false)
            ?: return@withContext OpResult.Error("源路径非法或不存在: $from")
        val dst = resolveSafe(to, allowRoot = false, mustExist = false, mustBeDirectory = false)
            ?: return@withContext OpResult.Error("目标路径非法: $to")
        if (dst.exists()) return@withContext OpResult.Error("目标已存在: $to")
        try {
            dst.parentFile?.takeIf { !it.exists() }?.mkdirs()
            if (src.renameTo(dst)) OpResult.Success else OpResult.Error("移动失败(可能跨挂载点,请使用 copy)")
        } catch (e: Exception) {
            Logger.w(TAG, "move 失败: ${e.message}")
            OpResult.Error("移动失败: ${e.message}")
        }
    }

    /**
     * 复制文件或目录(目录递归复制)。
     *
     * @param from 源路径(相对工作区根目录)
     * @param to 目标路径(相对工作区根目录)
     * @return 操作结果:[OpResult] 表示成功或失败原因
     */
    suspend fun copy(from: String, to: String): OpResult = withContext(Dispatchers.IO) {
        val src = resolveSafe(from, allowRoot = false, mustExist = true, mustBeDirectory = false)
            ?: return@withContext OpResult.Error("源路径非法或不存在: $from")
        val dst = resolveSafe(to, allowRoot = false, mustExist = false, mustBeDirectory = false)
            ?: return@withContext OpResult.Error("目标路径非法: $to")
        if (dst.exists()) return@withContext OpResult.Error("目标已存在: $to")
        try {
            dst.parentFile?.takeIf { !it.exists() }?.mkdirs()
            if (src.isDirectory) {
                src.copyRecursively(dst, overwrite = false)
                OpResult.Success
            } else {
                src.copyTo(dst, overwrite = false)
                OpResult.Success
            }
        } catch (e: Exception) {
            Logger.w(TAG, "copy 失败: ${e.message}")
            OpResult.Error("复制失败: ${e.message}")
        }
    }

    // ── 内部工具方法 ──────────────────────────────────────────────────────────

    /**
     * 路径安全校验与解析(核心安全边界)。
     *
     * 校验步骤:
     *  1. 规范化输入路径(去除前后空白),空串或 "/" 视为根目录
     *  2. 拼接到 rootDir 后取 canonicalFile,解析所有 `.`/`..`/符号链接
     *  3. 严格校验 canonicalPath 必须以 rootDir.canonicalPath 为前缀(禁止越权)
     *  4. 校验存在性/目录性(根据参数)
     *
     * 任何一步失败均返回 null,调用方据此返回错误信息。
     *
     * @param path 相对工作区根目录的路径
     * @param allowRoot 是否允许根目录本身(列出根目录时为 true,读写文件时为 false)
     * @param mustExist 是否必须存在
     * @param mustBeDirectory 是否必须为目录(若为 true,则存在的文件不算目录会失败)
     * @return 解析后的 [File],失败返回 null
     */
    private fun resolveSafe(
        path: String,
        allowRoot: Boolean,
        mustExist: Boolean,
        mustBeDirectory: Boolean,
    ): File? {
        val trimmed = path.trim().trim('/')
        if (trimmed.isEmpty()) {
            if (!allowRoot) return null
            return if (mustBeDirectory) rootDir else null
        }
        // 拒绝任何 ".." 段(双保险:即便 canonicalFile 能解析,也先在路径段层面拒绝)
        val segments = trimmed.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == ".." }) return null
        val candidate = File(rootDir, trimmed)
        // canonicalFile 解析所有 `.`/符号链接,得到绝对真实路径
        val canonical = try {
            candidate.canonicalFile
        } catch (e: Exception) {
            Logger.w(TAG, "canonicalFile 解析失败: ${e.message}(path=$path)")
            return null
        }
        val rootCanonical = rootDir.canonicalPath
        // 严格前缀校验:canonicalPath 必须等于 root 或以 "$root/" 开头
        val canonicalPath = canonical.canonicalPath
        if (canonicalPath != rootCanonical && !canonicalPath.startsWith("$rootCanonical/")) {
            Logger.w(TAG, "越权访问拒绝: $path -> $canonicalPath(根: $rootCanonical)")
            return null
        }
        if (mustExist && !canonical.exists()) return null
        if (mustBeDirectory && canonical.exists() && !canonical.isDirectory) return null
        return canonical
    }

    /**
     * 把绝对 [file] 转换为相对工作区根目录的路径(用于 [WorkspaceEntry.relativePath])。
     */
    private fun formatRelativePath(file: File): String {
        val rootPath = rootDir.canonicalPath
        val filePath = file.canonicalPath
        if (filePath == rootPath) return ""
        return filePath.removePrefix("$rootPath/").replace(File.separatorChar, '/')
    }

    // ── 结果类型(便于 UI/工具统一处理)──────────────────────────────────────

    /** listDir 结果。 */
    sealed class ListResult {
        data class Success(val entries: List<WorkspaceEntry>) : ListResult()
        data class Error(val message: String) : ListResult()
    }

    /** readFile 结果。 */
    sealed class ReadResult {
        data class Success(val content: String) : ReadResult()
        data class Error(val message: String) : ReadResult()
    }

    /** 通用操作(write/delete/mkdir/move/copy)结果。 */
    sealed class OpResult {
        object Success : OpResult()
        data class Error(val message: String) : OpResult()
    }

    companion object {
        private const val TAG = "WorkspaceManager"

        /** 读取文件大小上限:1MB(防止 AI 读取超大文件造成内存压力)。 */
        const val MAX_READ_BYTES: Long = 1L * 1024 * 1024

        /** 写入文件大小上限:10MB(防止 AI 写入超大内容造成磁盘压力)。 */
        const val MAX_WRITE_BYTES: Long = 10L * 1024 * 1024
    }
}
