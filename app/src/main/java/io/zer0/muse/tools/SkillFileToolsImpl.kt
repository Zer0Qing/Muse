package io.zer0.muse.tools

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Build
import android.provider.MediaStore
import io.zer0.muse.R
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlinx.coroutines.withTimeoutOrNull

/**
 * P1-3b 拆域：Skill 文件/公共目录工具实现（从 SkillExecutor.kt 迁移）。
 * 由 SkillExecutor 委托调用。
 */
class SkillFileToolsImpl(private val context: Context, private val client: OkHttpClient) {

    /** 读取应用沙盒内文件(限定 filesDir / cacheDir 子路径)。 */
    fun execReadFile(args: Map<String, String>): String {
        val path = args["path"] ?: return context.getString(R.string.skill_missing_param_path)
        val file = resolveSandboxFile(path) ?: return context.getString(R.string.skill_path_violation, path)
        if (!file.exists()) return context.getString(R.string.skill_file_not_found, path)
        if (file.length() > 1_000_000) return context.getString(R.string.skill_file_too_large, file.length())
        // v1.52: 二进制文件检测 — 读取前 1024 字节,若含 NUL 字节或 UTF-8 替换字符占比过高则判定为二进制
        // v1.52 修订: 空文件直接返回空串;使用实际读入字节数判断,避免 read 未读满导致尾部 NUL 误判
        // L-SE12: 设计权衡 — 这里探测后又调 readText/readLines 重新读全文,存在重复 IO。
        // 复用 headBytes 需处理 offset/length/charset 三种读取模式的拼接,复杂度收益不划算
        // (1MB 上限下二次读取成本可接受)。保持当前实现,后续若支持大文件再改为流式探测+读取。
        if (file.length() == 0L) return ""
        val readLen = minOf(1024, file.length().toInt())
        val headBytes = ByteArray(readLen)
        val actualRead = file.inputStream().use { it.read(headBytes) }
        if (actualRead > 0) {
            val probe = if (actualRead < readLen) headBytes.copyOf(actualRead) else headBytes
            if (probe.any { it == 0.toByte() }) {
                return context.getString(R.string.skill_binary_file_nul, path)
            }
            val decoded = String(probe, Charsets.UTF_8)
            val replacementCount = decoded.count { it == '\uFFFD' }
            if (replacementCount.toDouble() / probe.size > 0.05) {
                return context.getString(R.string.skill_binary_file_utf8, path)
            }
        }
        // offset/length: 分段读取(起始行号 + 读取行数,默认 0=全部)
        val offset = args["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val length = args["length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        // encoding: 默认 utf-8(当前仅支持 utf-8/utf-16,其它回退 utf-8)
        val encoding = args["encoding"]?.takeIf { it.isNotBlank() } ?: "utf-8"
        val charset = when (encoding.lowercase()) {
            "utf-16", "utf-16le" -> Charsets.UTF_16
            "utf-16be" -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        if (offset > 0 || length > 0) {
            val lines = file.readLines(charset)
            val from = offset.coerceAtMost(lines.size)
            val to = if (length > 0) (from + length).coerceAtMost(lines.size) else lines.size
            return lines.subList(from, to).joinToString("\n")
        }
        return file.readText(charset)
    }

    /** 写入应用沙盒内文件。 */
    fun execWriteFile(args: Map<String, String>): String {
        val path = args["path"] ?: return context.getString(R.string.skill_missing_param_path)
        val content = args["content"] ?: return context.getString(R.string.skill_missing_param_content)
        val append = args["append"]?.toBoolean() ?: false
        // create_dirs: 默认 true,自动创建父目录
        val createDirs = args["create_dirs"]?.toBoolean() ?: true
        val file = resolveSandboxFile(path) ?: return context.getString(R.string.skill_path_violation, path)
        if (createDirs) {
            file.parentFile?.mkdirs()
        } else {
            // 不自动建目录时,父目录不存在则报错
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                return context.getString(R.string.skill_parent_dir_not_exist, parent.absolutePath)
            }
        }
        val isOverwrite = file.exists() && !append
        if (append) file.appendText(content) else file.writeText(content)
        // v1.28: 返回绝对路径,让 MessageBubble 的 extractFilePaths 能匹配并渲染附件芯片
        val timestamp = System.currentTimeMillis()
        // v1.47: 返回内容预览(前 200 字符),让调用方能核对写入结果,而非只看到字节数盲信工具
        val previewLimit = 200
        val preview = if (content.length <= previewLimit) content else
            content.take(previewLimit) + context.getString(R.string.skill_write_preview_truncated, content.length)
        return context.getString(
            R.string.skill_write_result,
            file.length(),
            file.absolutePath,
            if (isOverwrite) context.getString(R.string.skill_yes) else context.getString(R.string.skill_no),
            timestamp,
            preview,
        )
    }

    /**
     * SSRF 防护:校验 URL 是否指向公网地址。
     *
     * 拒绝以下地址:
     *  - 主机名为 localhost
     *  - 回环地址 127.0.0.0/8、::1
     *  - 私网 10.0.0.0/8、172.16.0.0/12、192.168.0.0/16、IPv6 fc00::/7
     *  - 链路本地 169.254.0.0/16
     *  - 未指定地址、组播地址
     *
     * 同时用 [java.net.InetAddress.getAllByName] 解析 DNS 后二次校验 IP,
     * 防止 DNS rebinding 攻击(域名解析得到的实际 IP 仍指向内网)。
     *
     * @return true 表示安全(可继续请求);false 表示指向内网,调用方应拒绝
     */
    fun validatePublicUrl(url: String): Boolean {
        val uri = try {
            java.net.URI(url)
        } catch (e: Exception) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        if (host == "localhost") return false
        // 解析 DNS 后二次校验 IP(防 DNS rebinding):只要任一解析结果指向内网就拒绝
        val addresses = try {
            java.net.InetAddress.getAllByName(host)
        } catch (e: Exception) {
            return false
        }
        return addresses.all { addr ->
            // IPv4 私网/回环/链路本地等由 InetAddress 内置方法覆盖
            if (addr.isLoopbackAddress || addr.isAnyLocalAddress ||
                addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
                addr.isMulticastAddress
            ) {
                return@all false
            }
            // IPv6 私网 fc00::/7(InetAddress.isSiteLocalAddress 对 IPv6 返回 false,需手动判断)
            if (addr is java.net.Inet6Address) {
                val bytes = addr.address
                // fc00::/7 的前 7 位是 1111110,即首字节范围 0xfc..0xfd
                if ((bytes[0].toInt() and 0xFE) == 0xFC) return@all false
            }
            true
        }
    }

    /** HTTP GET 请求。失败时(404/超时/连接失败)降级到搜索摘要;401/403 等业务错误不降级。 */
    fun resolveSandboxFile(path: String): File? {
        val filesDir = context.filesDir.canonicalPath
        val cacheDir = context.cacheDir.canonicalPath
        val target = File(context.filesDir, path).canonicalFile
        val targetPath = target.canonicalPath
        return if (targetPath.startsWith(filesDir) || targetPath.startsWith(cacheDir)) target else null
    }

    // H-SE1: 改用 resultOf{}(正确重抛 CancellationException)
    fun execListDir(args: Map<String, String>): String {
        val path = args["path"] ?: return context.getString(R.string.skill_missing_param_path)
        val dir = resolveSandboxFile(path) ?: return context.getString(R.string.skill_path_violation, path)
        if (!dir.exists()) return context.getString(R.string.skill_dir_not_exist, path)
        if (!dir.isDirectory) return context.getString(R.string.skill_not_dir, path)
        val files = dir.listFiles()
        if (files.isNullOrEmpty()) return "empty"
        return files.sortedBy { it.name }.joinToString("\n") { f ->
            val prefix = if (f.isDirectory) "[D]" else "[F]"
            val size = if (f.isFile) " (${f.length()}B)" else ""
            "$prefix ${f.name}$size"
        }
    }

    /** delete_file — 删除文件或空目录(沙盒内)。支持单个 path 或批量 paths。 */
    fun execDeleteFile(args: Map<String, String>): String {
        // v1.47: 支持 paths 批量删除(逗号或换行分隔),兼容旧的单 path 参数
        val paths = args["paths"]?.takeIf { it.isNotBlank() }
            ?.split(",", "\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOfNotNull(args["path"]?.takeIf { it.isNotBlank() })
        if (paths.isEmpty()) return context.getString(R.string.skill_missing_param_path_or_paths)
        val results = paths.map { p ->
            val file = resolveSandboxFile(p) ?: return@map context.getString(R.string.skill_delete_path_violation, p)
            if (!file.exists()) return@map context.getString(R.string.skill_delete_not_exist, p)
            val ok = file.delete()
            if (ok) context.getString(R.string.skill_delete_success, p) else context.getString(R.string.skill_delete_failed, p)
        }
        val okCount = results.count { it.startsWith("[成功]") }
        return context.getString(R.string.skill_batch_delete_result, okCount, paths.size) + results.joinToString("\n")
    }

    /** file_exists — 判断文件是否存在(沙盒内)。 */
    fun execFileExists(args: Map<String, String>): String {
        val path = args["path"] ?: return context.getString(R.string.skill_missing_param_path)
        val file = resolveSandboxFile(path) ?: return context.getString(R.string.skill_path_violation, path)
        return if (file.exists()) "exists" else "not_exists"
    }

    // ── 公共目录与文件传输 ──────────────────────────────────────────────

    /** file_download — 从 URL 下载文件到应用沙盒(限定 filesDir 下)。 */
    suspend fun execFileDownload(args: Map<String, String>): String {
        val url = args["url"] ?: return "error: missing url"
        // H-SE4: 校验 url scheme,防止 SSRF/本地文件读取(file:// 协议)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return context.getString(R.string.skill_url_invalid_scheme_err)
        }
        val path = args["path"] ?: return "error: missing path"
        // H-SE3: 改用 resolveSandboxFile 校验路径,防止路径穿越(如 path="../../databases/main.db")
        val file = resolveSandboxFile(path) ?: return context.getString(R.string.skill_path_violation_err)
        // M-SE8: timeout 限制在 1..300 秒,防止无上限阻塞
        val timeoutSec = args["timeout"]?.toIntOrNull()?.coerceIn(1, 300) ?: 60

        file.parentFile?.mkdirs()

        return withTimeoutOrNull(timeoutSec * 1000L) {
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withTimeoutOrNull "error: HTTP ${response.code}"

                    response.body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    context.getString(R.string.skill_download_success, file.absolutePath, file.length())
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                context.getString(R.string.skill_download_failed, e.message ?: "")
            }
        } ?: context.getString(R.string.skill_download_timeout, timeoutSec)
    }

    /** read_public_file — 通过 SAF 读取用户分享/打开方式传入的文件 URI。 */
    suspend fun execReadPublicFile(args: Map<String, String>): String {
        val uriStr = args["uri"] ?: return "error: missing uri"
        val encoding = args["encoding"]?.takeIf { it.isNotBlank() } ?: "utf-8"
        // L-SE14: 单独校验 charset,给出明确提示而非笼统的"读取失败"
        val cs = try {
            java.nio.charset.Charset.forName(encoding)
        } catch (e: java.nio.charset.UnsupportedCharsetException) {
            return context.getString(R.string.skill_unsupported_encoding, encoding)
        } catch (e: java.nio.charset.IllegalCharsetNameException) {
            return context.getString(R.string.skill_illegal_encoding, encoding)
        }

        return try {
            val uri = Uri.parse(uriStr)
            // 审计修复 (1.3): 只允许 content:// scheme。原实现接受 file://,
            // LLM 可读应用私有目录任意文件(数据库、shared_prefs 等)。
            // read_public_file 语义是 SAF 分享文件,content:// 是标准通道。
            val scheme = uri.scheme?.lowercase()
            if (scheme != "content") {
                return "error: unsupported uri scheme '$scheme', only content:// is allowed"
            }
            val input = context.contentResolver.openInputStream(uri)
                ?: return "error: cannot open uri"

            // M-SE6: 流式读取到 1MB 即停止,避免大文件 readText() 导致 OOM
            val limit = 1_000_000
            input.use { stream ->
                val reader = stream.bufferedReader(cs)
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var total = 0
                var truncated = false
                while (total < limit) {
                    val n = reader.read(buf, 0, minOf(buf.size, limit - total))
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    total += n
                }
                // 若还能继续读,说明文件超过 1MB
                if (reader.read() >= 0) truncated = true
                if (truncated) {
                    sb.append("\n... (已截断到 ${limit} 字符)")
                }
                sb.toString()
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            context.getString(R.string.skill_read_failed, e.message ?: "")
        }
    }

    /** save_to_downloads — 保存文本或本地文件到 Download 目录(Android 10+ 用 MediaStore)。 */
    suspend fun execSaveToDownloads(args: Map<String, String>): String {
        val filename = args["filename"] ?: return "error: missing filename"
        val mimeType = args["mime_type"] ?: "text/plain"
        // v1.47: 支持 file_path 参数 — 直接从沙盒读取文件转存到 Download,无需先 read_file 再写文本
        val filePath = args["file_path"]?.takeIf { it.isNotBlank() }
        val content = args["content"]

        // content 与 file_path 二选一
        if (content == null && filePath == null) {
            return context.getString(R.string.skill_missing_content_or_filepath)
        }

        // 如果传了 file_path,读取本地文件字节(支持二进制,不强制文本)
        val srcFile = filePath?.let { resolveSandboxFile(it) }
        if (filePath != null) {
            if (srcFile == null) return context.getString(R.string.skill_path_violation_err)
            if (!srcFile.exists()) return context.getString(R.string.skill_source_file_not_exist, filePath)
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 用 MediaStore
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return "error: cannot create file in Downloads"

                resolver.openOutputStream(uri)?.use { output ->
                    if (srcFile != null) {
                        srcFile.inputStream().use { it.copyTo(output) }
                    } else {
                        // v1.74: 逻辑保证 filePath==null 时 content 非空(顶部已校验二选一),用 ?: "" 防御性降级
                        output.write((content ?: "").toByteArray())
                    }
                } ?: return "error: cannot open output stream"

                if (srcFile != null) {
                    context.getString(R.string.skill_saved_to_download_with_size, filename, srcFile.length())
                } else {
                    context.getString(R.string.skill_saved_to_download, filename)
                }
            } else {
                // Android 9 以下直接写公共 Download 目录
                // M-SE9: API < 29 需检查 WRITE_EXTERNAL_STORAGE 权限
                val hasWritePerm = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!hasWritePerm) {
                    return context.getString(R.string.skill_write_perm_needed)
                }
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadDir, filename)
                if (srcFile != null) {
                    srcFile.copyTo(file, overwrite = true)
                } else {
                    // v1.74: 逻辑保证 filePath==null 时 content 非空(顶部已校验二选一),用 ?: "" 防御性降级
                    file.writeText(content ?: "")
                }
                context.getString(R.string.skill_saved_to_path, file.absolutePath)
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            context.getString(R.string.skill_save_failed, e.message ?: "")
        }
    }

    /** list_public_files — 列出指定公共目录的文件(MediaStore 查询)。 */
    suspend fun execListPublicFiles(args: Map<String, String>): String {
        val directory = args["directory"] ?: "Downloads"
        val limit = args["limit"]?.toIntOrNull() ?: 50

        return try {
            // v1.29: directory="all" 时遍历所有公共目录并合并结果
            if (directory.equals("all", ignoreCase = true)) {
                val allDirs = listOf("Downloads", "Documents", "Pictures", "Music", "Movies")
                val merged = StringBuilder()
                allDirs.forEachIndexed { idx, dir ->
                    merged.append("== $dir ==\n")
                    merged.append(queryPublicDir(dir, limit))
                    if (idx < allDirs.size - 1) merged.append("\n\n")
                }
                return merged.toString().trimEnd()
            }
            queryPublicDir(directory, limit)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            context.getString(R.string.skill_query_failed, e.message ?: "")
        }
    }

    /** 查询单个公共目录的文件列表(MediaStore)。v1.47: 输出含 content:// URI,可直接喂给 read_public_file。 */
    fun queryPublicDir(directory: String, limit: Int): String {
        val collection = when (directory.lowercase()) {
            "downloads" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Files.getContentUri("external")
            "documents" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Files.getContentUri("external")
            else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }
        // v1.47: 多查 _ID 列,用于拼出 content:// URI,让 list → read 能力对称
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        // M-SE7: 用 selectionArgs 参数化绑定 directory,避免 SQL 注入;
        // 转义 LIKE 通配符(% _ \)并加 ESCAPE '\' 子句
        val escapedDir = directory.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
        } else null
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("%$escapedDir%")
        } else null
        // v1.109 修复: LIMIT 加上限 200,防止 LLM 传超大值导致资源耗尽
        val safeLimit = limit.coerceIn(1, 200)
        val cursor = context.contentResolver.query(
            collection, projection, selection, selectionArgs,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC LIMIT $safeLimit",
        ) ?: return context.getString(R.string.skill_query_failed_short)
        return cursor.use {
            val results = mutableListOf<String>()
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val name = it.getString(1) ?: "?"
                val size = it.getLong(2)
                val uri = android.content.ContentUris.withAppendedId(collection, id)
                // 格式: 文件名 (大小B) | uri=content://...
                results.add("$name (${size}B) | uri=$uri")
            }
            if (results.isEmpty()) context.getString(R.string.skill_dir_empty) else results.joinToString("\n")
        }
    }

}
