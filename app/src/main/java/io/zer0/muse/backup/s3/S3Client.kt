package io.zer0.muse.backup.s3

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
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 8.9: S3 兼容存储客户端(自实现 AWS Signature V4)。
 *
 * 支持 S3 兼容服务(MinIO / R2 / Backblaze B2 / AWS S3),通过 OkHttp 直接发请求,
 * 不依赖 AWS SDK(0 APK 体积,符合 gap analysis M19 "自实现签名" 要求)。
 *
 * 支持操作:
 *  - PUT object(上传文件)
 *  - GET object(下载文件)
 *  - HEAD object(检查存在)
 *
 * v1.132 扩展:新增 [testConnection] / [upload] (File) / [download] (File) / [list] / [delete],
 * 供 CloudBackupPage 直接调用,补齐 rikkahub/kelivo 风格的云备份交互。
 *
 * 签名算法: AWS Signature Version 4
 *  - CanonicalRequest = method + uri + query + headers + signed_headers + payload_hash
 *  - StringToSign = algorithm + timestamp + scope + SHA256(CanonicalRequest)
 *  - SigningKey = HMAC-SHA256 链(SecretKey → date → region → service → "aws4_request")
 *  - Signature = HMAC-SHA256(SigningKey, StringToSign)
 *  - Authorization: AWS4-HMAC-SHA256 Credential=AK/DATE/REGION/SERVICE/aws4_request, SignedHeaders=..., Signature=...
 *
 * @param endpoint S3 端点(如 https://s3.amazonaws.com 或 https://minio.example.com)
 * @param region 区域(如 us-east-1)
 * @param accessKey Access Key ID
 * @param secretKey Secret Access Key
 * @param client OkHttpClient(复用 named("chat"))
 */
class S3Client(
    private val endpoint: String,
    private val region: String,
    private val accessKey: String,
    private val secretKey: String,
    private val client: OkHttpClient,
) {
    /**
     * 上传对象(PUT)。
     * @param bucket bucket 名
     * @param key 对象 key(如 "muse/backup-2024-01-01.json")
     * @param data 文件字节
     * @param contentType MIME 类型(默认 application/json)
     * @return true 成功;false 失败
     */
    fun putObject(bucket: String, key: String, data: ByteArray, contentType: String = "application/json"): Boolean {
        val url = "$endpoint/$bucket/$key"
        val payloadHash = sha256Hex(data)
        val req = buildSignedRequest("PUT", url, bucket, key, payloadHash, data, contentType)
        return execute(req, false) { resp -> resp.isSuccessful }
    }

    /**
     * 下载对象(GET)。
     * @param bucket bucket 名
     * @param key 对象 key
     * @return 文件字节数组;null 失败或不存在
     */
    fun getObject(bucket: String, key: String): ByteArray? {
        val url = "$endpoint/$bucket/$key"
        val payloadHash = sha256Hex("".toByteArray())
        val req = buildSignedRequest("GET", url, bucket, key, payloadHash, null, null)
        return execute(req, null) { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
    }

    /**
     * 检查对象是否存在(HEAD)。
     */
    fun headObject(bucket: String, key: String): Boolean {
        val url = "$endpoint/$bucket/$key"
        val payloadHash = sha256Hex("".toByteArray())
        val req = buildSignedRequest("HEAD", url, bucket, key, payloadHash, null, null)
        return execute(req, false) { resp -> resp.isSuccessful }
    }

    // ── v1.132: 协程化 + File 直接读写 + list + delete API ──────────────

    /**
     * v1.132: 测试连接是否可用(对 bucket 根发 GET ?max-keys=1)。
     * 走 [Dispatchers.IO] 避免阻塞调用方。
     * @param bucket bucket 名;为空时使用本客户端默认 bucket(由调用方传入)
     * @return true 认证通过且 bucket 可访问;false 网络异常或认证失败
     */
    suspend fun testConnection(bucket: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$endpoint/$bucket?max-keys=1"
        val payloadHash = sha256Hex("".toByteArray())
        val req = buildSignedRequest("GET", url, bucket, "", payloadHash, null, null)
        execute(req, false) { resp -> resp.isSuccessful }
    }

    /**
     * v1.132: 上传本地文件到 S3 对象(以流形式 PUT,避免大文件一次性读入内存)。
     * 注意:流式上传时无法预计算 SHA256,使用 x-amz-content-sha256=UNSIGNED-PAYLOAD
     * (AWS 官方支持的签名降级,所有 S3 兼容服务均支持)。
     * @param localFile 本地文件
     * @param bucket bucket 名
     * @param key 对象 key
     * @return true 成功
     */
    suspend fun upload(localFile: File, bucket: String, key: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$endpoint/$bucket/$key"
        val req = buildSignedRequest(
            method = "PUT",
            url = url,
            bucket = bucket,
            key = key,
            payloadHash = UNSIGNED_PAYLOAD,
            body = null,
            contentType = "application/octet-stream",
            bodyFile = localFile,
        )
        execute(req, false) { resp -> resp.isSuccessful }
    }

    /**
     * v1.132: 下载 S3 对象到本地文件(以流形式写入,避免大文件 OOM)。
     * @param bucket bucket 名
     * @param key 对象 key
     * @param localFile 本地目标文件(会被覆盖)
     * @return true 成功
     */
    suspend fun download(bucket: String, key: String, localFile: File): Boolean = withContext(Dispatchers.IO) {
        val url = "$endpoint/$bucket/$key"
        val payloadHash = sha256Hex("".toByteArray())
        val req = buildSignedRequest("GET", url, bucket, key, payloadHash, null, null)
        execute(req, false) { resp ->
            if (!resp.isSuccessful) return@execute false
            val body = resp.body ?: return@execute false
            runCatching {
                localFile.parentFile?.mkdirs()
                localFile.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
                true
            }.getOrElse {
                Logger.w("S3Client", "download 写入本地文件失败: ${it.message}")
                false
            }
        }
    }

    /**
     * v1.132: 列出 bucket 下指定前缀的对象(GET ?list-type=2&prefix=...)。
     * 解析 ListBucketResult XML,提取 key / size / lastModified。
     * @param bucket bucket 名
     * @param prefix 对象 key 前缀(如 "muse/")
     * @return 对象列表;空表示无对象或请求失败
     */
    suspend fun list(bucket: String, prefix: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val encodedPrefix = uriEncode(prefix, encodeSlash = true)
        val url = "$endpoint/$bucket?list-type=2&prefix=$encodedPrefix"
        val payloadHash = sha256Hex("".toByteArray())
        val req = buildSignedRequest("GET", url, bucket, "", payloadHash, null, null)
        execute(req, emptyList()) { resp ->
            if (!resp.isSuccessful) return@execute emptyList()
            val xml = resp.body?.string() ?: return@execute emptyList()
            parseListBucketResult(xml)
        }
    }

    /**
     * v1.132: 删除对象(DELETE)。
     * @param bucket bucket 名
     * @param key 对象 key
     * @return true 成功
     */
    suspend fun delete(bucket: String, key: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$endpoint/$bucket/$key"
        val payloadHash = sha256Hex("".toByteArray())
        val req = buildSignedRequest("DELETE", url, bucket, key, payloadHash, null, null)
        execute(req, false) { resp -> resp.isSuccessful }
    }

    /** 构建带 AWS SigV4 签名的 Request。 */
    private fun buildSignedRequest(
        method: String,
        url: String,
        bucket: String,
        key: String,
        payloadHash: String,
        body: ByteArray?,
        contentType: String?,
        bodyFile: File? = null,
    ): Request {
        val now = Date()
        val amzDate = formatAmzDate(now)
        val dateStamp = amzDate.substring(0, 8)
        val uri = java.net.URI(url)
        val host = uri.host
        // path + query 拆分(签名规范要求 canonical uri = path,canonical query = query)
        val rawPath = uri.rawPath ?: "/$bucket/$key"
        val canonicalPath = if (rawPath.isBlank()) "/" else rawPath
        val canonicalQuery = uri.rawQuery ?: ""

        val headers = mutableMapOf(
            "host" to host,
            "x-amz-date" to amzDate,
            "x-amz-content-sha256" to payloadHash,
        )
        if (contentType != null) headers["content-type"] = contentType

        val canonicalRequest = buildCanonicalRequest(method, canonicalPath, canonicalQuery, headers, payloadHash)
        val stringToSign = buildStringToSign(amzDate, dateStamp, canonicalRequest)
        val signingKey = deriveSigningKey(dateStamp)
        val signature = hmacSha256Hex(signingKey, stringToSign)

        val signedHeaders = headers.keys.sorted().joinToString(";")
        val authorization = "AWS4-HMAC-SHA256 " +
            "Credential=$accessKey/$dateStamp/$region/s3/aws4_request, " +
            "SignedHeaders=$signedHeaders, " +
            "Signature=$signature"

        val reqBuilder = Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .header("x-amz-date", amzDate)
            .header("x-amz-content-sha256", payloadHash)
        if (contentType != null) reqBuilder.header("content-type", contentType)
        when (method) {
            "GET" -> reqBuilder.get()
            "HEAD" -> reqBuilder.head()
            "DELETE" -> reqBuilder.delete()
            "PUT" -> {
                if (bodyFile != null) {
                    reqBuilder.put(bodyFile.asRequestBody("application/octet-stream".toMediaType()))
                } else {
                    reqBuilder.put((body ?: ByteArray(0)).toRequestBody(contentType?.toMediaType()))
                }
            }
        }
        return reqBuilder.build()
    }

    /** 构建 CanonicalRequest(含 query string,v1.132 修复 list/max-keys 等带参数请求签名)。 */
    private fun buildCanonicalRequest(
        method: String,
        path: String,
        query: String,
        headers: Map<String, String>,
        payloadHash: String,
    ): String {
        val canonicalUri = uriEncode(path, encodeSlash = false)
        // canonical query: 按 key 排序,key/value 都 URI 编码,用 & 连接
        val canonicalQuery = if (query.isBlank()) {
            ""
        } else {
            query.split('&')
                .map { pair ->
                    val idx = pair.indexOf('=')
                    val k = if (idx >= 0) pair.substring(0, idx) else pair
                    val v = if (idx >= 0) pair.substring(idx + 1) else ""
                    uriEncode(k, encodeSlash = true) + "=" + uriEncode(v, encodeSlash = true)
                }
                .sorted()
                .joinToString("&")
        }
        val canonicalHeaders = headers.entries.sortedBy { it.key }
            .joinToString("") { "${it.key.lowercase()}:${it.value.trim()}\n" }
        val signedHeaders = headers.keys.sorted().joinToString(";")
        return "$method\n$canonicalUri\n$canonicalQuery\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
    }

    /** 构建 StringToSign。 */
    private fun buildStringToSign(amzDate: String, dateStamp: String, canonicalRequest: String): String {
        val scope = "$dateStamp/$region/s3/aws4_request"
        val canonicalHash = sha256Hex(canonicalRequest.toByteArray())
        return "AWS4-HMAC-SHA256\n$amzDate\n$scope\n$canonicalHash"
    }

    /** 派生签名密钥: HMAC 链(SecretKey → date → region → service → "aws4_request")。 */
    private fun deriveSigningKey(dateStamp: String): ByteArray {
        val kSecret = ("AWS4$secretKey").toByteArray(Charsets.UTF_8)
        val kDate = hmacSha256(kSecret, dateStamp.toByteArray(Charsets.UTF_8))
        val kRegion = hmacSha256(kDate, region.toByteArray(Charsets.UTF_8))
        val kService = hmacSha256(kRegion, "s3".toByteArray(Charsets.UTF_8))
        return hmacSha256(kService, "aws4_request".toByteArray(Charsets.UTF_8))
    }

    /** 执行请求并用 transform 提取结果,失败时返回 [defaultValue]。 */
    private fun <T> execute(req: Request, defaultValue: T, transform: (okhttp3.Response) -> T): T {
        return resultOf {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Logger.w("S3Client", "${req.method} ${req.url} failed: HTTP ${resp.code}")
                }
                transform(resp)
            }
        }.onError { msg, t ->
            Logger.e("S3Client", "S3 request error", t)
        }.getOrNull() ?: defaultValue
    }

    /**
     * v1.132: 解析 S3 ListBucketResult XML,提取 key / size / lastModified。
     * 用简易正则解析(避免引入 XML 解析依赖);对 AWS S3 / MinIO / R2 测试通过。
     */
    private fun parseListBucketResult(xml: String): List<RemoteFile> {
        val result = mutableListOf<RemoteFile>()
        val contentsRegex = Regex("(?is)<Contents>(.*?)</Contents>")
        val keyRegex = Regex("(?is)<Key>(.*?)</Key>")
        val sizeRegex = Regex("(?is)<Size>(.*?)</Size>")
        val dateRegex = Regex("(?is)<LastModified>(.*?)</LastModified>")
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val isoFmtNoMs = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        for (match in contentsRegex.findAll(xml)) {
            val block = match.groupValues[1]
            val key = keyRegex.find(block)?.groupValues?.get(1)?.trim() ?: continue
            val name = key.substringAfterLast('/')
            val size = sizeRegex.find(block)?.groupValues?.get(1)?.trim()?.toLongOrNull() ?: 0L
            val lastModified = dateRegex.find(block)?.groupValues?.get(1)?.trim()?.let { text ->
                runCatching { isoFmt.parse(text)?.time }.recoverCatching { isoFmtNoMs.parse(text)?.time }.getOrNull() ?: 0L
            } ?: 0L
            result.add(RemoteFile(name = name, path = key, size = size, lastModified = lastModified))
        }
        return result
    }

    // ── 加密工具 ──────────────────────────────────────────────────────────

    private fun sha256Hex(data: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String {
        return hmacSha256(key, data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** URI 编码(RFC 3986)。 */
    private fun uriEncode(input: String, encodeSlash: Boolean = true): String {
        val sb = StringBuilder()
        for (c in input) {
            when {
                c == '/' && !encodeSlash -> sb.append(c)
                c.isLetterOrDigit() || c in "-_.~" -> sb.append(c)
                else -> sb.append("%").append(String.format("%02X", c.code))
            }
        }
        return sb.toString()
    }

    /** 格式化为 AWS 日期格式(yyyyMMdd'T'HHmmss'Z')。 */
    private fun formatAmzDate(date: Date): String {
        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(date)
    }

    companion object {
        /** v1.132: 流式 PUT 用的 unsigned-payload 标识(AWS SigV4 官方支持的降级签名)。 */
        private const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
    }
}
