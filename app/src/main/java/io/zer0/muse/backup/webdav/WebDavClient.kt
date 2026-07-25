package io.zer0.muse.backup.webdav

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.backup.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone

/**
 * Phase 8.9: WebDAV 客户端(基于 OkHttp)。
 *
 * WebDAV 是 HTTP 扩展,通过特定方法(PUT/GET/MKCOL/PROPFIND/DELETE)操作云端文件。
 * 大多数网盘(Nutstore / NextCloud / ownCloud / Box / koofr)支持 WebDAV 协议。
 *
 * 支持操作:
 *  - PUT(上传文件)
 *  - GET(下载文件)
 *  - MKCOL(创建目录)
 *  - PROPFIND(检查存在/列目录)
 *  - DELETE(删除文件)
 *
 * v1.132 扩展:新增 [testConnection] / [upload] (File) / [download] (File) / [list] / [mkdir],
 * 供 CloudBackupPage 直接调用,补齐 rikkahub/kelivo 风格的云备份交互。
 *
 * 认证: HTTP Basic Auth(username:password 编码为 Base64)。
 *
 * @param baseUrl WebDAV 根 URL(如 https://dav.jianguoyun.com/dav/)
 * @param username 用户名
 * @param password 密码(应用专用密码)
 * @param client OkHttpClient(复用 named("chat"))
 */
class WebDavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val client: OkHttpClient,
) {
    /** 构建完整 URL(baseUrl + 相对路径)。 */
    private fun buildUrl(remotePath: String): String {
        val base = baseUrl.trimEnd('/')
        val path = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
        return base + path
    }

    /** 构建 Basic Auth header。 */
    private fun authHeader(): String {
        val credentials = "$username:$password"
        return "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray())
    }

    /**
     * 上传文件(PUT)。
     * @param remotePath 远程路径(如 /muse/backup-2024-01-01.json)
     * @param data 文件字节
     * @param contentType MIME 类型
     * @return true 成功
     */
    fun putFile(remotePath: String, data: ByteArray, contentType: String = "application/json"): Boolean {
        // 确保父目录存在(尝试 MKCOL,失败忽略 — 可能已存在)
        val parentPath = remotePath.substringBeforeLast('/', "")
        if (parentPath.isNotEmpty()) {
            mkcol(parentPath)
        }
        val req = Request.Builder()
            .url(buildUrl(remotePath))
            .header("Authorization", authHeader())
            .put(data.toRequestBody(contentType.toMediaType()))
            .build()
        return execute(req, false) { resp -> resp.isSuccessful }
    }

    /**
     * 下载文件(GET)。
     * @param remotePath 远程路径
     * @return 文件字节;null 失败
     */
    fun getFile(remotePath: String): ByteArray? {
        val req = Request.Builder()
            .url(buildUrl(remotePath))
            .header("Authorization", authHeader())
            .get()
            .build()
        return execute(req, null) { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
    }

    /**
     * 创建目录(MKCOL)。
     * @param remotePath 目录路径
     * @return true 成功(已存在返回 false 但不报错)
     */
    fun mkcol(remotePath: String): Boolean {
        val req = Request.Builder()
            .url(buildUrl(remotePath))
            .header("Authorization", authHeader())
            .method("MKCOL", null)
            .build()
        return execute(req, false) { resp -> resp.isSuccessful || resp.code == 405 }
    }

    /**
     * 检查文件是否存在(PROPFIND Depth: 0)。
     */
    fun exists(remotePath: String): Boolean {
        val req = Request.Builder()
            .url(buildUrl(remotePath))
            .header("Authorization", authHeader())
            .header("Depth", "0")
            .method("PROPFIND", "".toRequestBody())
            .build()
        return execute(req, false) { resp -> resp.isSuccessful }
    }

    /**
     * 删除文件(DELETE)。
     */
    fun delete(remotePath: String): Boolean {
        val req = Request.Builder()
            .url(buildUrl(remotePath))
            .header("Authorization", authHeader())
            .delete()
            .build()
        return execute(req, false) { resp -> resp.isSuccessful }
    }

    // ── v1.132: 协程化 + File 直接读写 API(供 CloudBackupPage 使用) ─────

    /**
     * v1.132: 测试连接是否可用(对根 URL 发 PROPFIND Depth:0)。
     * 走 [Dispatchers.IO] 避免阻塞调用方。
     * @return true 认证通过且服务端响应 2xx;false 网络异常或认证失败
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(buildUrl("/"))
            .header("Authorization", authHeader())
            .header("Depth", "0")
            .method("PROPFIND", "".toRequestBody())
            .build()
        execute(req, false) { resp -> resp.isSuccessful }
    }

    /**
     * v1.132: 上传本地文件到远程路径(以流形式 PUT,避免大文件一次性读入内存)。
     * @param localFile 本地文件
     * @param remotePath 远程路径
     * @return true 成功
     */
    suspend fun upload(localFile: File, remotePath: String): Boolean = withContext(Dispatchers.IO) {
        // 确保父目录存在(忽略已存在错误)
        val parentPath = remotePath.substringBeforeLast('/', "")
        if (parentPath.isNotEmpty()) mkcol(parentPath)
        val req = Request.Builder()
            .url(buildUrl(remotePath))
            .header("Authorization", authHeader())
            .put(localFile.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        execute(req, false) { resp -> resp.isSuccessful }
    }

    /**
     * v1.132: 下载远程文件到本地路径(以流形式写入,避免大文件 OOM)。
     * @param remotePath 远程路径
     * @param localFile 本地目标文件(会被覆盖)
     * @return true 成功
     */
    suspend fun download(remotePath: String, localFile: File): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(buildUrl(remotePath))
            .header("Authorization", authHeader())
            .get()
            .build()
        execute(req, false) { resp ->
            if (!resp.isSuccessful) return@execute false
            val body = resp.body ?: return@execute false
            runCatching {
                localFile.parentFile?.mkdirs()
                localFile.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
                true
            }.getOrElse {
                Logger.w("WebDavClient", "download 写入本地文件失败: ${it.message}")
                false
            }
        }
    }

    /**
     * v1.132: 列出远程目录下的文件(PROPFIND Depth:1,解析 WebDAV multistatus XML)。
     * 仅返回文件项(过滤掉目录本身与子目录),供 [CloudBackupService.listBackups] 使用。
     * @param remotePath 远程目录
     * @return 文件列表;空表示目录为空或请求失败
     */
    suspend fun list(remotePath: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(buildUrl(remotePath))
            .header("Authorization", authHeader())
            .header("Depth", "1")
            .header("Content-Type", "application/xml; charset=utf-8")
            .method(
                "PROPFIND",
                PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()),
            )
            .build()
        execute(req, emptyList()) { resp ->
            if (!resp.isSuccessful) return@execute emptyList()
            val xml = resp.body?.string() ?: return@execute emptyList()
            parseMultistatus(xml, buildUrl(remotePath))
        }
    }

    /**
     * v1.132: 创建目录(MKCOL)的协程版本,语义与 [mkcol] 一致。
     * 已存在(405)视为成功。
     */
    suspend fun mkdir(remotePath: String): Boolean = withContext(Dispatchers.IO) { mkcol(remotePath) }

    // ── 工具方法 ──────────────────────────────────────────────────────

    /** 执行请求并用 transform 提取结果,失败时返回 [defaultValue]。 */
    private fun <T> execute(req: Request, defaultValue: T, transform: (okhttp3.Response) -> T): T {
        return resultOf {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Logger.w("WebDavClient", "${req.method} ${req.url} failed: HTTP ${resp.code}")
                }
                transform(resp)
            }
        }.onError { msg, t ->
            Logger.e("WebDavClient", "WebDAV request error", t)
        }.getOrNull() ?: defaultValue
    }

    /**
     * v1.132: 解析 WebDAV PROPFIND multistatus XML,提取文件名 / 大小 / 修改时间。
     * 用简易正则解析(避免引入 XML 解析依赖);对常见 NextCloud / 坚果云 / koofr 测试通过。
     * 仅返回文件项(过滤掉与请求路径相同的 self 项,以及 resourcetype 为 collection 的子目录)。
     */
    private fun parseMultistatus(xml: String, listUrl: String): List<RemoteFile> {
        val result = mutableListOf<RemoteFile>()
        // 按 <D:href> 或 <d:href> 分块(不同服务大小写不一)
        val responseRegex = Regex("(?is)<(?:D|d):response>(.*?)</(?:D|d):response>")
        val hrefRegex = Regex("(?is)<(?:D|d):href>(.*?)</(?:D|d):href>")
        val collectionRegex = Regex("(?is)<(?:D|d):collection\\s*/>")
        val sizeRegex = Regex("(?is)<(?:D|d):getcontentlength>(.*?)</(?:D|d):getcontentlength>")
        val dateRegex = Regex("(?is)<(?:D|d):getlastmodified>(.*?)</(?:D|d):getlastmodified>")
        val selfPath = java.net.URI(listUrl).path?.trimEnd('/') ?: ""

        for (match in responseRegex.findAll(xml)) {
            val block = match.groupValues[1]
            val href = hrefRegex.find(block)?.groupValues?.get(1)?.trim() ?: continue
            // 跳过目录本身(self 引用)
            val decoded = runCatching { java.net.URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
            if (decoded.trimEnd('/') == selfPath || decoded.trimEnd('/') == "$selfPath/") continue
            // 跳过子目录(collection 标记)
            if (collectionRegex.containsMatchIn(block)) continue
            val name = decoded.substringAfterLast('/').ifBlank { continue }
            val size = sizeRegex.find(block)?.groupValues?.get(1)?.trim()?.toLongOrNull() ?: 0L
            val lastModified = dateRegex.find(block)?.groupValues?.get(1)?.trim()
                ?.let { parseHttpDate(it) } ?: 0L
            result.add(RemoteFile(name = name, path = decoded, size = size, lastModified = lastModified))
        }
        return result
    }

    /** 解析 HTTP 日期(RFC 1123 / RFC 850 / asctime),失败返回 0。 */
    private fun parseHttpDate(text: String): Long {
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy",
        )
        for (fmt in formats) {
            runCatching {
                val sdf = SimpleDateFormat(fmt, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(text)?.time ?: continue
            }
        }
        return 0L
    }

    /** v1.132: PROPFIND 请求体,只请求 displayname / getcontentlength / getlastmodified / resourcetype。 */
    private val PROPFIND_BODY: String = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:propfind xmlns:D="DAV:">
            <D:prop>
                <D:resourcetype/>
                <D:getcontentlength/>
                <D:getlastmodified/>
            </D:prop>
        </D:propfind>
    """.trimIndent()

    companion object
}
