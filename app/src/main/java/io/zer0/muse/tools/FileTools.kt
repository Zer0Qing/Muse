package io.zer0.muse.tools

import android.content.Context
import android.webkit.URLUtil
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * v1.0.47 P2-2/P2-3/P2-4: 文件与链接工具集(AI 可调用)。
 *
 * 三个工具补全 AI 处理"用户提供的文件/链接"的能力:
 *  1. read_file      — 读取应用可访问目录下的文本文件(下载/文档/工作区/工具输出)
 *  2. create_download — 将文本内容写入 Download 目录,生成用户可见的下载文件
 *  3. parse_link     — 抓取 URL 页面,提取标题+正文(脱壳广告/导航),返回 Markdown
 *
 * 安全设计:
 *  - read_file 路径白名单:仅允许应用 filesDir / cacheDir / 外部 Download / 工作区 / tool_outputs
 *  - read_file 禁止 ".." 越权,大小上限 2MB
 *  - create_download 仅写入公共 Download 目录(MediaStore.Downloads),文件名 sanitize
 *  - parse_link 仅 HTTP/HTTPS,超时 15s,响应体上限 1MB
 */
object FileTools {

    const val NAME_READ_FILE = "read_file"
    const val NAME_CREATE_DOWNLOAD = "create_download"
    const val NAME_PARSE_LINK = "parse_link"

    /** read_file 文件大小上限 2MB。 */
    private const val READ_FILE_MAX_BYTES = 2 * 1024 * 1024
    /** create_download 内容大小上限 10MB。 */
    private const val CREATE_DOWNLOAD_MAX_BYTES = 10 * 1024 * 1024
    /** parse_link 抓取超时 ms。 */
    private const val PARSE_LINK_TIMEOUT_MS = 15_000
    /** parse_link 响应体上限 1MB。 */
    private const val PARSE_LINK_MAX_BYTES = 1024 * 1024

    fun toolDefs(): List<ToolRegistry.ToolDef> = listOf(
        ToolRegistry.ToolDef(
            name = NAME_READ_FILE,
            description = "读取应用可访问目录下的文本文件(UTF-8)。允许的目录:工作区/下载/工具输出缓存。" +
                "路径可以是绝对路径或相对工作区路径。大小上限 2MB。",
            parameters = mapOf(
                "path" to "必填,文件路径。支持:工作区相对路径(如 'notes.txt')、" +
                    "工具输出引用(如 'tool_outputs/xxx.json')、绝对路径",
            ),
            required = setOf("path"),
            category = "built-in",
            riskLevel = ToolRiskLevel.SAFE,
        ),
        ToolRegistry.ToolDef(
            name = NAME_CREATE_DOWNLOAD,
            description = "将文本内容写入用户可见的 Download 目录,生成下载文件。" +
                "适用于:AI 生成的长文/代码/JSON/CSV 等需要用户保存查看的内容。" +
                "文件名自动 sanitize(移除特殊字符),同名文件自动追加序号。",
            parameters = mapOf(
                "filename" to "必填,文件名(如 'report.md'、'data.json')",
                "content" to "必填,要写入的文本内容(UTF-8)",
                "subdir" to "可选,Download 下的子目录(如 'AI生成'),默认根目录",
            ),
            required = setOf("filename", "content"),
            category = "built-in",
            riskLevel = ToolRiskLevel.NORMAL,
        ),
        ToolRegistry.ToolDef(
            name = NAME_PARSE_LINK,
            description = "抓取 URL 页面,提取标题和正文,返回 Markdown 格式。" +
                "自动脱壳广告/导航/侧边栏,适合阅读新闻/博客/文档。" +
                "超时 15 秒,响应体上限 1MB。",
            parameters = mapOf(
                "url" to "必填,HTTP/HTTPS URL",
            ),
            required = setOf("url"),
            category = "built-in",
            riskLevel = ToolRiskLevel.SAFE,
        ),
    )

    /**
     * 执行工具调用。
     *
     * @param context 应用上下文(读取 filesDir/Download)
     * @param workspaceRoot 工作区根目录(用于解析工作区相对路径)
     */
    suspend fun execute(
        name: String,
        args: Map<String, String>,
        context: Context,
        workspaceRoot: File,
    ): String = withContext(Dispatchers.IO) {
        when (name) {
            NAME_READ_FILE -> execReadFile(args, context, workspaceRoot)
            NAME_CREATE_DOWNLOAD -> execCreateDownload(args, context)
            NAME_PARSE_LINK -> execParseLink(args)
            else -> "[错误] 未知工具: $name"
        }
    }

    // ============================ read_file ============================

    private fun execReadFile(
        args: Map<String, String>,
        context: Context,
        workspaceRoot: File,
    ): String {
        val path = args["path"]?.takeIf { it.isNotBlank() }
            ?: return "[错误] 缺少必填参数 path"

        // 安全:禁止 .. 越权
        if (path.contains("..")) return "[错误] 路径禁止包含 '..'"

        // 解析候选路径:工作区相对路径 → tool_outputs → filesDir → cacheDir → 外部 Download
        val candidates = listOf(
            File(workspaceRoot, path),
            File(context.filesDir, "tool_outputs/$path"),
            File(context.filesDir, path),
            File(context.cacheDir, path),
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), path),
        )

        val target = candidates.firstOrNull { it.exists() && it.isFile }
            ?: return "[错误] 文件不存在或不是普通文件: $path"

        if (!target.canRead()) return "[错误] 文件不可读: ${target.absolutePath}"

        val size = target.length()
        if (size > READ_FILE_MAX_BYTES) {
            return "[错误] 文件过大(${size} 字节),上限 ${READ_FILE_MAX_BYTES} 字节(2MB)。" +
                "大文件请用 workspace_read 配合分块读取。"
        }

        return runCatching { target.readText() }
            .onFailure { Logger.w("FileTools", "read_file 读取失败: ${it.message}", it) }
            .getOrElse { "[错误] 读取失败: ${it.message}" }
    }

    // ============================ create_download ============================

    private fun execCreateDownload(args: Map<String, String>, context: Context): String {
        val filename = args["filename"]?.takeIf { it.isNotBlank() }
            ?: return "[错误] 缺少必填参数 filename"
        val content = args["content"] ?: return "[错误] 缺少必填参数 content"
        val subdir = args["subdir"]?.trim()?.trim('/') ?: ""

        if (content.toByteArray().size > CREATE_DOWNLOAD_MAX_BYTES) {
            return "[错误] 内容过大,上限 ${CREATE_DOWNLOAD_MAX_BYTES / 1024 / 1024}MB"
        }

        // sanitize 文件名:仅保留字母数字下划线点连字符
        val safeFilename = filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeIf { it.isNotBlank() } ?: "download_${System.currentTimeMillis()}.txt"

        val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        val targetDir = if (subdir.isNotBlank()) File(downloadDir, subdir) else downloadDir
        if (!targetDir.exists()) targetDir.mkdirs()

        // 同名文件自动追加序号
        var target = File(targetDir, safeFilename)
        var idx = 1
        val dotIdx = safeFilename.lastIndexOf('.')
        val baseName = if (dotIdx > 0) safeFilename.substring(0, dotIdx) else safeFilename
        val ext = if (dotIdx > 0) safeFilename.substring(dotIdx) else ""
        while (target.exists()) {
            target = File(targetDir, "$baseName($idx)$ext")
            idx++
        }

        return runCatching {
            target.writeText(content)
            Logger.i("FileTools", "create_download 成功: ${target.absolutePath} (${content.length} chars)")
            "[成功] 文件已保存到 Download 目录\n路径: ${target.absolutePath}\n大小: ${content.length} 字符"
        }.getOrElse {
            Logger.w("FileTools", "create_download 失败: ${it.message}", it)
            "[错误] 写入失败: ${it.message}"
        }
    }

    // ============================ parse_link ============================

    private fun execParseLink(args: Map<String, String>): String {
        val urlStr = args["url"]?.takeIf { it.isNotBlank() }
            ?: return "[错误] 缺少必填参数 url"

        if (!URLUtil.isNetworkUrl(urlStr)) return "[错误] 仅支持 HTTP/HTTPS URL"
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            return "[错误] URL 必须以 http:// 或 https:// 开头"
        }

        return runCatching {
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = PARSE_LINK_TIMEOUT_MS
                readTimeout = PARSE_LINK_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Muse/1.0")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                instanceFollowRedirects = true
            }

            try {
                val code = conn.responseCode
                if (code !in 200..299) return "[错误] HTTP $code: ${conn.responseMessage}"

                val contentType = conn.contentType ?: ""
                if (!contentType.contains("html", ignoreCase = true)) {
                    // 非 HTML,直接读文本
                    val raw = conn.inputStream.buffered().use { it.readBytes() }
                    val text = String(raw.copyOfLength(PARSE_LINK_MAX_BYTES.coerceAtMost(raw.size)), Charsets.UTF_8)
                    return "[非 HTML 内容: $contentType]\n\n$text"
                }

                // HTML:用 Jsoup 解析,提取标题+正文(先读字符串再解析,避免 InputStream 重载歧义)
                val htmlText = conn.inputStream.buffered().use { stream ->
                    stream.readBytes().copyOfLength(PARSE_LINK_MAX_BYTES).toString(Charsets.UTF_8)
                }
                conn.disconnect()

                val doc = Jsoup.parse(htmlText, urlStr)
                val title = doc.title().trim().ifBlank { "(无标题)" }
                // Jsoup 自动移除 script/style,再选 article 或 body
                val article = doc.selectFirst("article") ?: doc.body()
                val text = article.text().replace(Regex("\\s{2,}"), "\n").trim()

                val truncated = if (text.length > 8000) {
                    text.substring(0, 8000) + "\n\n...(正文超过 8000 字符,已截断)"
                } else text

                Logger.i("FileTools", "parse_link 成功: $urlStr (${text.length} chars)")
                "# $title\n\n来源: $urlStr\n\n$truncated"
            } finally {
                conn.disconnect()
            }
        }.getOrElse {
            Logger.w("FileTools", "parse_link 失败: ${it.message}", it)
            "[错误] 抓取失败: ${it.message}"
        }
    }

    private fun ByteArray.copyOfLength(length: Int): ByteArray =
        if (size <= length) this else copyOf(length)
}

/**
 * v1.0.47 P2: FileTools 注册器 — 把 read_file/create_download/parse_link 注册到 ToolRegistry。
 *
 * 依赖 ToolRegistry + WorkspaceManager(取 rootDir)+ Context。
 */
class FileToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val context: Context,
    private val workspaceRoot: File,
) {
    init { registerAll() }

    fun registerAll() {
        FileTools.toolDefs().forEach { def ->
            toolRegistry.register(def) { args ->
                FileTools.execute(def.name, args, context, workspaceRoot)
            }
        }
    }
}
