package io.zer0.ai.gemini

import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Phase 9.4 (M10): Google Vertex AI 服务账号鉴权。
 *
 * 实现标准 Google Service Account JWT → Access Token 交换流程:
 *  1. 构造 JWT(header + claims),claims 含 iss/scope/aud/iat/exp
 *  2. 用服务账号私钥(RSA)对 JWT 做 SHA256withRSA 签名
 *  3. POST 到 https://oauth2.googleapis.com/token 拿 access_token
 *  4. 缓存 token(expires_in 默认 3600s),过期前 60s 提前刷新
 *
 * 私钥格式: PEM PKCS#8(服务账号 JSON 文件里的 private_key 字段)
 * ```
 * -----BEGIN PRIVATE KEY-----
 * MIIEvQ...
 * -----END PRIVATE KEY-----
 * ```
 *
 * 线程安全: [cachedToken] / [cachedExpiresAt] 用 synchronized 保护,
 * 多协程并发取 token 不会重复请求(典型: ChatService.completeText + memory 后台任务)。
 *
 * v1.80 修复:
 *  - M-GEM5: exchangeToken 用 try/catch 替代 runCatching,rethrow CancellationException
 *  - M-GEM6: refreshAndGet 改为 singleflight(锁内只检查,HTTP 在锁外)
 *  - M-GEM7: 刷新失败时降级用旧 token;getValidToken 失败抛含原因的异常而非返回 null
 *  - L-GEM1: 复用 io.zer0.common.AppJson 替代自建 Json 实例
 *  - M-VTX1: 构造函数接收外部 OkHttpClient,避免自建 client 导致连接池/线程泄漏
 *  - M-VTX2: 校验 expiresInSec > 0,防止异常响应(0/负数)导致无限刷新
 *  - L-VTX1: parsePemPrivateKey 兼容 PKCS#1(RSA PRIVATE KEY)格式,自动包装为 PKCS#8
 *  - L-VTX2: 改用 java.util.Base64 替代 android.util.Base64(便于 JVM 单元测试)
 *
 * 参考: https://developers.google.com/identity/protocols/oauth2/service-account#jwtset
 * 独立编写(参考 Google 官方文档协议),Apache 2.0。
 */
class VertexAiAuthToken(
    private val serviceAccountEmail: String,
    privateKeyPem: String,
    /**
     * v1.80 (M-VTX1): 外部传入的 OkHttpClient(推荐用 ProviderHttpSupport.sharedHttpClient),
     * 避免每个 VertexAiAuthToken 实例自建 client 导致连接池/线程泄漏。
     * 调用方可在 Provider 销毁时统一关闭 client。
     */
    private val client: OkHttpClient,
    private val scope: String = "https://www.googleapis.com/auth/cloud-platform",
) {
    /** 解析后的 RSA 私钥(java.security.PrivateKey)。 */
    private val privateKey: java.security.PrivateKey = parsePemPrivateKey(privateKeyPem)

    /** 缓存的 access token。null 表示未取/已过期。 */
    @Volatile
    private var cachedToken: String? = null

    /** 缓存 token 的过期时间戳(毫秒)。 */
    @Volatile
    private var cachedExpiresAt: Long = 0L

    /**
     * 取有效的 access token。若缓存有效直接返回,否则同步刷新。
     * 在协程里调用([withContext] 切到 IO)。
     *
     * M-GEM7: 刷新失败且旧 token 完全过期时抛含原因的异常,不再返回 null。
     */
    suspend fun getValidToken(): String {
        val cached = cachedToken
        val now = System.currentTimeMillis()
        // 提前 60s 刷新,避免边界请求拿到过期 token
        if (cached != null && now < cachedExpiresAt - 60_000L) {
            return cached
        }
        return refreshAndGet()
    }

    /**
     * 同步刷新 token。
     *
     * M-GEM6: singleflight 模式 — synchronized 锁内只检查是否已被其他协程刷新,
     * HTTP 调用放在锁外执行,避免持锁等网络导致其他协程阻塞。
     * 多协程并发时可能发出重复请求,但不会死锁,且成功后锁内写回缓存统一可见。
     */
    private suspend fun refreshAndGet(): String = withContext(Dispatchers.IO) {
        // 锁内:双重检查,等待锁的协程拿到锁后可能已被前一个协程刷新
        synchronized(this@VertexAiAuthToken) {
            val now = System.currentTimeMillis()
            cachedToken?.takeIf { now < cachedExpiresAt - 60_000L }?.let { return@withContext it }
        }
        // 锁外:构造 JWT 并做 HTTP 交换(允许并发,避免持锁等网络)
        val jwt = buildSignedJwt()
        val token = exchangeToken(jwt)
        if (token != null) {
            // M-VTX2: 校验 expiresInSec > 0,异常响应(0/负数)降级用默认 3600s,避免无限刷新
            val effectiveExpiresIn = if (token.expiresInSec > 0L) token.expiresInSec else 3600L
            val now = System.currentTimeMillis()
            synchronized(this@VertexAiAuthToken) {
                cachedToken = token.accessToken
                cachedExpiresAt = now + effectiveExpiresIn * 1000L
            }
            Logger.d(TAG, "Vertex AI token 刷新成功,有效期 ${effectiveExpiresIn}s")
            return@withContext token.accessToken
        }
        // M-GEM7: 刷新失败,若旧 token 未完全过期,降级用旧 token
        val stale = cachedToken
        val now = System.currentTimeMillis()
        if (stale != null && now < cachedExpiresAt) {
            Logger.w(TAG, "Token 刷新失败,降级用旧 token(剩余 ${cachedExpiresAt - now}ms)")
            return@withContext stale
        }
        // M-GEM7: 无可用 token,抛含原因的异常
        throw RuntimeException("Vertex AI token 刷新失败且无可用旧 token")
    }

    /**
     * 构造并签名 JWT。
     * - Header: {"alg":"RS256","typ":"JWT"}
     * - Claims: iss/scope/aud/iat/exp(1 小时有效期)
     */
    private fun buildSignedJwt(): String {
        val now = System.currentTimeMillis() / 1000L  // 秒级
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val claims = buildString {
            append('{')
            append("\"iss\":\"").append(serviceAccountEmail).append("\",")
            append("\"scope\":\"").append(scope).append("\",")
            append("\"aud\":\"").append(TOKEN_ENDPOINT).append("\",")
            append("\"iat\":").append(now).append(',')
            append("\"exp\":").append(now + 3600L)
            append('}')
        }
        val headerB64 = base64UrlEncode(header.toByteArray(Charsets.UTF_8))
        val claimsB64 = base64UrlEncode(claims.toByteArray(Charsets.UTF_8))
        val signingInput = "$headerB64.$claimsB64"

        // SHA256withRSA 签名
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(signingInput.toByteArray(Charsets.UTF_8))
        val signatureBytes = signature.sign()
        val signatureB64 = base64UrlEncode(signatureBytes)

        return "$signingInput.$signatureB64"
    }

    /**
     * 用 JWT 换 access_token。
     * POST application/x-www-form-urlencoded: grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion={jwt}
     *
     * M-GEM5: 用 try/catch 替代 runCatching,专门 rethrow CancellationException,
     * 避免吞掉协程取消信号。
     */
    private fun exchangeToken(jwt: String): TokenResponse? {
        val formBody = buildString {
            append("grant_type=").append(java.net.URLEncoder.encode(JWT_GRANT_TYPE, "UTF-8"))
            append("&assertion=").append(java.net.URLEncoder.encode(jwt, "UTF-8"))
        }
        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(formBody.toRequestBody(X_WWW_FORM_URLENCODED))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) {
                    Logger.w(TAG, "Token 交换失败 HTTP ${resp.code}: $body")
                    return@use null
                }
                AppJson.decodeFromString<TokenResponse>(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Token 交换异常: ${e.message}")
            null
        }
    }

    /**
     * PEM 私钥 → java.security.PrivateKey。
     *
     * v1.80 (L-VTX1): 兼容 PKCS#1(`BEGIN RSA PRIVATE KEY`)格式。
     *  PKCS#1 仅含 RSA 私钥的 ASN.1 序列,KeyFactory 需要 PKCS8EncodedKeySpec,
     *  此处检测到 PKCS#1 header 时用标准 RSA PrivateKeyInfo 包装层包裹后再解析。
     *  包装层结构(ASN.1 DER):
     *    SEQUENCE {
     *      INTEGER 0                          -- 版本
     *      SEQUENCE { OID rsaEncryption, NULL } -- 算法标识符
     *      OCTET STRING <原 PKCS#1 字节>        -- privateKey
     *    }
     *  其中前缀 22 字节是固定的(RSA + PKCS#8 头),最后 1 字节是 OCTET STRING 长度标签。
     *
     * v1.80 (L-VTX2): 改用 java.util.Base64 替代 android.util.Base64。
     */
    private fun parsePemPrivateKey(pem: String): java.security.PrivateKey {
        val isPkcs1 = pem.contains("-----BEGIN RSA PRIVATE KEY-----")
        // 去掉 PEM 头尾和换行,得到 base64 字符串
        val base64 = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keyBytes = Base64.getMimeDecoder().decode(base64)
        val pkcs8Bytes = if (isPkcs1) wrapPkcs1ToPkcs8(keyBytes) else keyBytes
        val spec = PKCS8EncodedKeySpec(pkcs8Bytes)
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    /**
     * v1.80 (L-VTX1): 把 PKCS#1 RSA 私钥字节包装为 PKCS#8 PrivateKeyInfo。
     * 共 26 字节固定头:SEQUENCE(4) + version(3) + AlgorithmIdentifier{rsaEncryption,NULL}(13)
     * + OCTET STRING tag/length(4),最后追加原 PKCS#1 字节。
     */
    private fun wrapPkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
        // RSA Encryption OID + NULL params 的 AlgorithmIdentifier,前面带 SEQUENCE 与 version
        // 完整 26 字节前缀:
        //   30 82 xx xx                 SEQUENCE (总长,后填)
        //   02 01 00                    INTEGER 0 (版本)
        //   30 0d 06 09 2a 86 48 86 f7 0d 01 01 01 00  SEQUENCE { rsaEncryption, NULL }
        //   04 82 xx xx                 OCTET STRING (包裹 PKCS#1)
        val prefix = byteArrayOf(
            0x30.toByte(), 0x82.toByte(), 0x00, 0x00,                           // SEQUENCE,长度后填
            0x02, 0x01, 0x00,                                                   // version = 0
            0x30, 0x0d,                                                         // SEQUENCE(AlgorithmIdentifier)
            0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, // OID rsaEncryption
            0x05, 0x00,                                                         // NULL
            0x04, 0x82.toByte(), 0x00, 0x00,                                    // OCTET STRING,长度后填
        )
        val totalLen = prefix.size + pkcs1.size
        // SEQUENCE 长度(totalLen - 4,占 2 字节大端)
        val seqLen = totalLen - 4
        prefix[2] = ((seqLen shr 8) and 0xFF).toByte()
        prefix[3] = (seqLen and 0xFF).toByte()
        // OCTET STRING 长度(pkcs1.size,占 2 字节大端,位置在 prefix 末尾倒数第 2/1 字节)
        prefix[prefix.size - 2] = ((pkcs1.size shr 8) and 0xFF).toByte()
        prefix[prefix.size - 1] = (pkcs1.size and 0xFF).toByte()
        return prefix + pkcs1
    }

    /** JWT 专用 Base64URL 编码(无 padding)。v1.80 (L-VTX2): 改用 java.util.Base64。 */
    private fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    @Serializable
    private data class TokenResponse(
        @kotlinx.serialization.SerialName("access_token") val accessToken: String = "",
        @kotlinx.serialization.SerialName("expires_in") val expiresInSec: Long = 3600L,
        @kotlinx.serialization.SerialName("token_type") val tokenType: String = "Bearer",
    )

    private companion object {
        const val TAG = "VertexAiAuthToken"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        const val JWT_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer"
        val X_WWW_FORM_URLENCODED = "application/x-www-form-urlencoded".toMediaType()
    }
}
