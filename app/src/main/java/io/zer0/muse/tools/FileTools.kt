package io.zer0.muse.tools

import android.content.Context
import android.webkit.URLUtil
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
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
            // v1.0.75 fix (工具审查 01): description 与实现对齐 — 实现拒绝绝对路径,
            // 原描述声称"支持绝对路径"导致模型首次调用必失败(用户反馈"反复试错"根因)。
            description = "读取应用可访问目录下的文本文件(UTF-8,上限 2MB)。" +
                "仅支持相对路径,支持三种: 工作区相对路径(如 'notes.txt')、" +
                "工具输出引用(如 'tool_outputs/xxx.json')、应用私有目录相对路径。" +
                "绝对路径会被拒绝,请勿传 '/storage/emulated/0/...' 形式。",
            parameters = mapOf(
                "path" to "必填,文件相对路径。支持:工作区相对路径(如 'notes.txt')、" +
                    "工具输出引用(如 'tool_outputs/xxx.json')、应用私有目录相对路径。" +
                    "绝对路径会被拒绝。",
            ),
            required = setOf("path"),
            category = "built-in",
            riskLevel = ToolRiskLevel.SAFE,
        ),
        ToolRegistry.ToolDef(
            name = NAME_CREATE_DOWNLOAD,
            description = "将文本内容写入用户可见的 Download 目录,生成下载文件。" +
                "适用于:AI 生成的长文/代码/JSON/CSV 等需要用户保存查看的内容。" +
                "与 save_to_downloads 等价(二选一);本工具支持 subdir 子目录。" +
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
            // v1.0.75 fix (工具审查 01): 与 web_fetch/http_get 互斥声明
            description = "抓取 URL 页面,提取标题和正文,返回 Markdown 格式(含标题)。" +
                "自动脱壳广告/导航/侧边栏,适合阅读新闻/博客/文档。" +
                "超时 15 秒,响应体上限 1MB。" +
                "想要纯文本用 web_fetch;想要原始响应用 http_get。",
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

        // 审计修复 (1.2): 拒绝绝对路径 — File(workspaceRoot, absolutePath) 会直接返回
        // 绝对路径本身(Java 忽略 parent),绕开下方候选目录白名单,可读应用私有任意文件。
        val trimmedPath = path.trim()
        val isAbsolute = trimmedPath.startsWith("/") || trimmedPath.startsWith("\\") ||
            Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(trimmedPath)
        if (isAbsolute) {
            return "[错误] 不支持绝对路径,请使用工作区/私有目录相对路径: $path"
        }

        // 解析候选路径:工作区相对路径 → tool_outputs → filesDir → cacheDir → 外部 Download
        val candidates = listOf(
            File(workspaceRoot, trimmedPath),
            File(context.filesDir, "tool_outputs/$trimmedPath"),
            File(context.filesDir, trimmedPath),
            File(context.cacheDir, trimmedPath),
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), trimmedPath),
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

        // 审计修复 (4.6): SSRF 防护 — 解析 URL 后拒绝回环(localhost/127.x/::1)、
        // 私网(10.x/172.16-31.x/192.168.x)、链路本地(169.254.x/fe80::)及保留地址,
        // 防止 parse_link 抓取本机/内网服务。域名经 InetAddress 解析后逐一检查。
        val parsedUrl = URL(urlStr)
        if (isBlockedSsrHost(parsedUrl.host)) {
            return "[错误] 目标地址属于回环/内网/保留地址,已拒绝抓取"
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

    /**
     * 审计修复 (4.6): SSRF 主机校验 — 返回 true 表示该主机属于回环/私网/链路本地/保留地址,应拒绝抓取。
     *
     * 判断方式:字面量 IP 与主机名统一交给 [InetAddress.getAllByName] 解析(字面量不触发 DNS),
     * 再用地址分类标志判断;IPv4 的 127/8、10/8、172.16/12、192.168/16、169.254/16 分别被
     * isLoopbackAddress / isSiteLocalAddress / isLinkLocalAddress 覆盖,IPv6 的 ::1、fe80:: 同理。
     * 解析失败时保守放行(后续连接本身会失败并返回错误,不影响主流程)。
     */
    private fun isBlockedSsrHost(host: String?): Boolean {
        val h = host?.trim()?.trimEnd('.')?.lowercase() ?: return false
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost")) return true
        return runCatching {
            InetAddress.getAllByName(h).any { addr ->
                addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
                    addr.isSiteLocalAddress || addr.isMulticastAddress
            }
        }.getOrDefault(false)
    }
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
