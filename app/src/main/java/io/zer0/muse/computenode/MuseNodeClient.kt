package io.zer0.muse.computenode

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.SecureKeyCipher
import io.zer0.muse.data.SecureKeyStore
import io.zer0.muse.data.computenode.ComputeNodeMode
import io.zer0.muse.data.computenode.RemoteComputeNodeEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * muse-node 控制面客户端。
 *
 * 所有请求都走项目已有的 OkHttp 分发器与连接池：
 * - SSH_TUNNEL：先向 [SshTunnelManager] 要本地转发端口，再访问 http://127.0.0.1:port；
 * - DIRECT_TLS：强制校验证书链第一片叶子证书的 SHA-256 指纹，指纹不符直接断开；
 * - 除 /ping 外全部自动附带 Authorization: Bearer 头。
 *
 * 服务端错误统一映射为 [MuseNodeException]，业务字段保持协议原始值，便于调用方按
 * AUTH_REQUIRED / APPROVAL_REQUIRED 等错误码分流。
 */
class MuseNodeClient(
    private val baseClient: OkHttpClient,
    private val tunnelManager: SshTunnelManager?,
    private val cipher: SecureKeyCipher = SecureKeyStore,
) {

    private var endpointOverride: NodeEndpointOverride? = null
    private val tlsClients = ConcurrentHashMap<String, OkHttpClient>()
    private val noProxyClient: OkHttpClient by lazy {
        baseClient.newBuilder().proxy(Proxy.NO_PROXY).build()
    }

    /** 单测专用：固定端点，跳过 SSH 隧道与 Keystore 解密。 */
    internal constructor(
        baseClient: OkHttpClient,
        endpointOverride: NodeEndpointOverride,
        cipher: SecureKeyCipher = SecureKeyStore,
    ) : this(baseClient, null, cipher) {
        this.endpointOverride = endpointOverride
    }

    /** GET /health。 */
    suspend fun health(node: RemoteComputeNodeEntity): NodeHealth {
        val endpoint = resolveEndpoint(node)
        val json = executeJson(endpoint, HttpMethod.GET, listOf("health"))
        if (json["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw errorFromJson(json, 200)
        }
        return decodeOrInvalid("health", NodeHealth.serializer(), json)
    }

    /**
     * POST /exec 后返回一个可收集输出帧的会话。
     *
     * 真正的命令输出走 WS /exec/{runId}；[ExecSession.events] 是冷流，首次收集时才建立 WS。
     */
    suspend fun startExec(node: RemoteComputeNodeEntity, request: ExecRequest): ExecSession {
        val endpoint = resolveEndpoint(node)
        val payload = ExecStartPayload(
            runId = request.runId,
            kind = request.kind.wireValue,
            command = request.command,
            code = request.code,
            cwd = request.cwd,
            timeoutSec = request.timeoutSec,
        )
        val json = executeJson(
            endpoint = endpoint,
            method = HttpMethod.POST,
            pathSegments = listOf("exec"),
            body = AppJson.encodeToString(ExecStartPayload.serializer(), payload),
        )
        if (json["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw errorFromJson(json, 200)
        }
        val runId = json["runId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: request.runId
        val socketRequest = authedRequest(endpoint)
            .url(webSocketUrl(endpoint.baseUrl, listOf("exec", runId)))
            .build()
        return ExecSession(webSocketClientFor(endpoint), socketRequest, runId)
    }

    /**
     * 启动命令并直接返回输出帧流；适合工具层透传。
     *
     * 收集结束或取消时自动关闭 WebSocket；需要主动取消远端进程时使用 [startExec] 返回的
     * [ExecSession.cancel]。
     */
    suspend fun exec(node: RemoteComputeNodeEntity, request: ExecRequest): Flow<ExecEvent> {
        val session = startExec(node, request)
        return session.events.onCompletion { session.close() }
    }

    /** POST /env/ensure。apt 且未授权系统级安装时返回 [EnvEnsureResult.ApprovalRequired]。 */
    suspend fun envEnsure(node: RemoteComputeNodeEntity, request: EnvEnsureRequest): EnvEnsureResult {
        val endpoint = resolveEndpoint(node)
        val payload = EnvEnsurePayload(
            ecosystem = request.ecosystem.wireValue,
            packages = request.packages,
            allowSystem = request.allowSystem,
        )
        val json = executeJson(
            endpoint = endpoint,
            method = HttpMethod.POST,
            pathSegments = listOf("env", "ensure"),
            body = AppJson.encodeToString(EnvEnsurePayload.serializer(), payload),
        )
        val code = json.stringValue("code")
        val message = json.stringValue("message")
        if (code == "APPROVAL_REQUIRED") {
            return EnvEnsureResult.ApprovalRequired(message.ifBlank { "需要用户审批系统级安装" })
        }
        val ok = json["ok"]?.jsonPrimitive?.booleanOrNull == true
        val exitCode = json["exitCode"]?.jsonPrimitive?.intOrNull ?: 0
        val outputTail = json.stringValue("outputTail")
        return if (ok) {
            EnvEnsureResult.Installed(
                ecosystem = request.ecosystem,
                packages = request.packages,
                exitCode = exitCode,
                outputTail = outputTail,
            )
        } else {
            EnvEnsureResult.Failed(
                code = code.ifBlank { "INSTALL_FAILED" },
                message = message.ifBlank { "依赖安装失败" },
                exitCode = exitCode,
                outputTail = outputTail,
            )
        }
    }

    /** GET /files。路径由服务端限制在 ~/muse-workspace 内。 */
    suspend fun readFile(node: RemoteComputeNodeEntity, path: String): ByteArray {
        val endpoint = resolveEndpoint(node)
        return executeRaw(
            endpoint = endpoint,
            method = HttpMethod.GET,
            pathSegments = listOf("files"),
            query = mapOf("path" to path),
        )
    }

    suspend fun readTextFile(node: RemoteComputeNodeEntity, path: String): String = String(readFile(node, path), Charsets.UTF_8)

    /** PUT /files。单次请求限制 10MB，与服务端一致。 */
    suspend fun writeFile(node: RemoteComputeNodeEntity, path: String, bytes: ByteArray): FileWriteReceipt {
        require(bytes.size <= MAX_FILE_BYTES) { "file exceeds the 10MB node limit" }
        val endpoint = resolveEndpoint(node)
        val json = executeJson(
            endpoint = endpoint,
            method = HttpMethod.PUT,
            pathSegments = listOf("files"),
            query = mapOf("path" to path),
            bodyBytes = bytes,
            contentType = OCTET_STREAM,
        )
        return FileWriteReceipt(
            path = json.stringValue("path").ifBlank { path },
            bytes = json["bytes"]?.jsonPrimitive?.longOrNull ?: bytes.size.toLong(),
        )
    }

    suspend fun writeTextFile(node: RemoteComputeNodeEntity, path: String, text: String): FileWriteReceipt =
        writeFile(node, path, text.toByteArray(Charsets.UTF_8))

    /** POST /watchers/upsert。 */
    suspend fun upsertWatcher(node: RemoteComputeNodeEntity, spec: WatcherSpec): WatcherReceipt {
        spec.validate()
        val endpoint = resolveEndpoint(node)
        val payload = WatcherUpsertPayload(
            watcherId = spec.watcherId,
            intervalMinutes = spec.intervalMinutes,
            script = spec.script,
            kind = spec.kind.wireValue,
            webhookUrl = spec.webhookUrl,
        )
        val json = executeJson(
            endpoint = endpoint,
            method = HttpMethod.POST,
            pathSegments = listOf("watchers", "upsert"),
            body = AppJson.encodeToString(WatcherUpsertPayload.serializer(), payload),
        )
        return WatcherReceipt(
            watcherId = json.stringValue("watcherId").ifBlank { spec.watcherId },
            intervalMinutes = json["intervalMinutes"]?.jsonPrimitive?.intOrNull ?: spec.intervalMinutes,
        )
    }

    /** DELETE /watchers/{id}。服务端对不存在的 watcher 也返回成功，保持幂等。 */
    suspend fun deleteWatcher(node: RemoteComputeNodeEntity, watcherId: String): Boolean {
        require(WATCHER_ID_REGEX.matches(watcherId)) { "invalid watcherId" }
        val endpoint = resolveEndpoint(node)
        val json = executeJson(
            endpoint = endpoint,
            method = HttpMethod.DELETE,
            pathSegments = listOf("watchers", watcherId),
        )
        return json["deleted"]?.jsonPrimitive?.booleanOrNull == true
    }

    /** GET /outbox?since=timestamp。 */
    suspend fun listOutbox(node: RemoteComputeNodeEntity, since: Long = 0L): OutboxPage {
        val endpoint = resolveEndpoint(node)
        val json = executeJson(
            endpoint = endpoint,
            method = HttpMethod.GET,
            pathSegments = listOf("outbox"),
            query = mapOf("since" to since.toString()),
        )
        return decodeOrInvalid("outbox", OutboxPage.serializer(), json)
    }

    /** POST /outbox/ack，返回服务端确认清理的条数。 */
    suspend fun ackOutbox(node: RemoteComputeNodeEntity, eventIds: List<String>): Int {
        if (eventIds.isEmpty()) return 0
        val endpoint = resolveEndpoint(node)
        val payload = OutboxAckPayload(eventIds = eventIds)
        val json = executeJson(
            endpoint = endpoint,
            method = HttpMethod.POST,
            pathSegments = listOf("outbox", "ack"),
            body = AppJson.encodeToString(OutboxAckPayload.serializer(), payload),
        )
        return json["acked"]?.jsonPrimitive?.intOrNull ?: 0
    }

    /** POST /browser/start + WS /browser/{sid}。 */
    suspend fun openBrowser(
        node: RemoteComputeNodeEntity,
        url: String = "about:blank",
        width: Int = 720,
        height: Int = 1280,
    ): BrowserSession {
        val endpoint = resolveEndpoint(node)
        val payload = BrowserStartPayload(url = url, width = width, height = height)
        val json = executeJson(
            endpoint = endpoint,
            method = HttpMethod.POST,
            pathSegments = listOf("browser", "start"),
            body = AppJson.encodeToString(BrowserStartPayload.serializer(), payload),
        )
        val sid = json.stringValue("sid")
        if (sid.isBlank()) {
            throw MuseNodeException(MuseNodeError.INVALID_RESPONSE, message = "browser session id is missing")
        }
        val info = BrowserSessionInfo(
            sid = sid,
            url = json.stringValue("url"),
            title = json.stringValue("title"),
            width = json["width"]?.jsonPrimitive?.intOrNull ?: width,
            height = json["height"]?.jsonPrimitive?.intOrNull ?: height,
        )
        val socketRequest = authedRequest(endpoint)
            .url(webSocketUrl(endpoint.baseUrl, listOf("browser", sid)))
            .build()
        return BrowserSession(webSocketClientFor(endpoint), socketRequest, info)
    }

    private suspend fun resolveEndpoint(node: RemoteComputeNodeEntity): ResolvedEndpoint {
        val override = endpointOverride
        if (override != null) {
            return ResolvedEndpoint(
                baseUrl = override.baseUrl.trimEnd('/'),
                bearerToken = override.bearerToken,
                pinnedFingerprintHex = override.pinnedFingerprintHex,
            )
        }
        val token = cipher.decryptOrNull(node.encryptedNodeToken)?.takeIf { it.isNotBlank() }
            ?: nodeFailure(MuseNodeError.AUTH_REQUIRED, "node token is missing or cannot be decrypted")
        val mode = ComputeNodeMode.fromWire(node.connectionMode)
            ?: nodeFailure(MuseNodeError.NODE_NOT_CONFIGURED, "unsupported node connection mode")
        return when (mode) {
            ComputeNodeMode.SSH_TUNNEL -> {
                val manager = tunnelManager
                    ?: nodeFailure(MuseNodeError.TUNNEL_UNAVAILABLE, "SSH tunnel manager is not available")
                val localPort = try {
                    manager.awaitLocalPort(node)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SshBootstrapException) {
                    Logger.w(TAG, "tunnel unavailable for node " + node.nodeId + ": " + e.javaClass.simpleName)
                    nodeFailure(MuseNodeError.TUNNEL_UNAVAILABLE, "SSH tunnel is not available", e)
                }
                ResolvedEndpoint(
                    baseUrl = "http://127.0.0.1:" + localPort,
                    bearerToken = token,
                    pinnedFingerprintHex = null,
                )
            }
            ComputeNodeMode.DIRECT_TLS -> {
                if (node.host.isBlank() || node.daemonPort !in 1..65535) {
                    throw MuseNodeException(
                        error = MuseNodeError.NODE_NOT_CONFIGURED,
                        message = "direct TLS node address is incomplete",
                    )
                }
                val fingerprint = NodePairingPayload.normalizeFingerprintHex(node.tlsFingerprint)
                    ?: throw MuseNodeException(
                        error = MuseNodeError.TLS_FINGERPRINT_MISMATCH,
                        message = "node certificate fingerprint is invalid",
                    )
                ResolvedEndpoint(
                    baseUrl = "https://" + node.host + ":" + node.daemonPort,
                    bearerToken = token,
                    pinnedFingerprintHex = fingerprint,
                )
            }
        }
    }

    private fun authedRequest(endpoint: ResolvedEndpoint): Request.Builder =
        Request.Builder().header("Authorization", "Bearer " + endpoint.bearerToken)

    private fun clientFor(endpoint: ResolvedEndpoint): OkHttpClient {
        val fingerprint = endpoint.pinnedFingerprintHex
        if (fingerprint == null) return noProxyClient
        return tlsClients.getOrPut(fingerprint) { buildPinnedClient(fingerprint) }
    }

    /** WebSocket 可能长时间没有输出帧；关闭读超时，避免 /exec 长任务被 5 分钟静默切断。 */
    private fun webSocketClientFor(endpoint: ResolvedEndpoint): OkHttpClient =
        clientFor(endpoint).newBuilder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()

    private fun buildPinnedClient(fingerprintHex: String): OkHttpClient {
        val expectedHex = NodePairingPayload.normalizeFingerprintHex(fingerprintHex)
            ?: throw MuseNodeException(
                error = MuseNodeError.TLS_FINGERPRINT_MISMATCH,
                message = "invalid pinned certificate fingerprint",
            )
        val trustManager = FingerprintTrustManager(expectedHex)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        return baseClient.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // 自签证书的 CN 通常不是连接地址；身份由精确的叶子证书指纹钉扎，而不是主机名。
            .hostnameVerifier { _, _ -> true }
            .proxy(Proxy.NO_PROXY)
            .build()
    }

    private suspend fun executeJson(
        endpoint: ResolvedEndpoint,
        method: HttpMethod,
        pathSegments: List<String>,
        query: Map<String, String> = emptyMap(),
        body: String? = null,
        bodyBytes: ByteArray? = null,
        contentType: MediaType = JSON_MEDIA_TYPE,
    ): JsonObject {
        val payloadBytes = bodyBytes ?: body?.toByteArray(Charsets.UTF_8)
        val bytes = executeRaw(endpoint, method, pathSegments, query, payloadBytes, contentType)
        return parseJsonObject(String(bytes, Charsets.UTF_8))
    }

    private suspend fun executeRaw(
        endpoint: ResolvedEndpoint,
        method: HttpMethod,
        pathSegments: List<String>,
        query: Map<String, String> = emptyMap(),
        bodyBytes: ByteArray? = null,
        contentType: MediaType = JSON_MEDIA_TYPE,
    ): ByteArray = withContext(Dispatchers.IO) {
        val urlBuilder = endpoint.baseUrl.toHttpUrl().newBuilder()
        pathSegments.forEach(urlBuilder::addPathSegment)
        query.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        val requestBuilder = authedRequest(endpoint).url(urlBuilder.build())
        when (method) {
            HttpMethod.GET -> requestBuilder.get()
            HttpMethod.POST -> requestBuilder.post((bodyBytes ?: ByteArray(0)).toRequestBody(contentType))
            HttpMethod.PUT -> requestBuilder.put((bodyBytes ?: ByteArray(0)).toRequestBody(contentType))
            HttpMethod.DELETE -> requestBuilder.delete()
        }
        val response = try {
            executeCall(clientFor(endpoint), requestBuilder.build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw networkException(e, endpoint)
        }
        try {
            response.use { resp ->
                val bytes = resp.body.bytes()
                if (!resp.isSuccessful) {
                    throw responseException(resp.code, String(bytes, Charsets.UTF_8))
                }
                bytes
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // 读取响应体也可能因连接中断失败，与建连失败同样收敛为网络错误。
            throw networkException(e, endpoint)
        }
    }

    private suspend fun executeCall(client: OkHttpClient, request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private fun networkException(e: IOException, endpoint: ResolvedEndpoint): MuseNodeException {
        val sslFailure = generateSequence<Throwable>(e) { it.cause }.any {
            it is SSLException || it is CertificateException
        }
        return if (endpoint.pinnedFingerprintHex != null && sslFailure) {
            MuseNodeException(
                error = MuseNodeError.TLS_FINGERPRINT_MISMATCH,
                message = "TLS certificate fingerprint verification failed",
                cause = e,
            )
        } else {
            MuseNodeException(
                error = MuseNodeError.NETWORK,
                message = "network error: " + e.javaClass.simpleName,
                cause = e,
            )
        }
    }

    private fun responseException(status: Int, text: String): MuseNodeException {
        val obj = try {
            if (text.isBlank()) null else AppJson.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            Logger.w(TAG, "error response is not JSON: " + e.javaClass.simpleName)
            null
        }
        val serverCode = obj.stringValue("code")
        val message = obj.stringValue("message").ifBlank { "HTTP " + status }
        return MuseNodeException(
            error = mapError(serverCode, status),
            serverCode = serverCode,
            message = message,
            httpStatus = status,
        )
    }

    private fun errorFromJson(json: JsonObject, status: Int): MuseNodeException {
        val serverCode = json.stringValue("code")
        return MuseNodeException(
            error = mapError(serverCode, status),
            serverCode = serverCode,
            message = json.stringValue("message").ifBlank { "node request failed" },
            httpStatus = status,
        )
    }

    private fun parseJsonObject(text: String): JsonObject {
        if (text.isBlank()) {
            return invalidResponse("server returned an empty response")
        }
        val element = try {
            AppJson.parseToJsonElement(text)
        } catch (e: SerializationException) {
            Logger.w(TAG, "server response cannot be parsed: " + e.javaClass.simpleName)
            return invalidResponse("server response is malformed", e)
        }
        return element as? JsonObject ?: invalidResponse("server response is not a JSON object")
    }

    private fun nodeFailure(error: MuseNodeError, message: String, cause: Throwable? = null): Nothing =
        throw MuseNodeException(error = error, message = message, cause = cause)

    private fun invalidResponse(message: String, cause: Throwable? = null): Nothing =
        nodeFailure(MuseNodeError.INVALID_RESPONSE, message, cause)

    private fun <T> decodeOrInvalid(label: String, serializer: kotlinx.serialization.KSerializer<T>, json: JsonObject): T = try {
        AppJson.decodeFromString(serializer, json.toString())
    } catch (e: SerializationException) {
        Logger.w(TAG, label + " response cannot be parsed: " + e.javaClass.simpleName)
        throw MuseNodeException(
            error = MuseNodeError.INVALID_RESPONSE,
            message = label + " response is malformed",
            cause = e,
        )
    }

    private fun webSocketUrl(baseUrl: String, segments: List<String>): HttpUrl {
        val builder = baseUrl.toHttpUrl().newBuilder()
        segments.forEach(builder::addPathSegment)
        return builder.build()
    }

    private fun mapError(serverCode: String, status: Int): MuseNodeError = when (serverCode) {
        "AUTH_REQUIRED" -> MuseNodeError.AUTH_REQUIRED
        "APPROVAL_REQUIRED" -> MuseNodeError.APPROVAL_REQUIRED
        "FILE_TOO_LARGE" -> MuseNodeError.FILE_TOO_LARGE
        "NOT_FOUND", "FILE_NOT_FOUND", "RUN_NOT_FOUND" -> MuseNodeError.NOT_FOUND
        "RUN_EXISTS" -> MuseNodeError.CONFLICT
        else -> when {
            status == 401 || status == 403 -> MuseNodeError.AUTH_REQUIRED
            status == 404 -> MuseNodeError.NOT_FOUND
            status == 409 -> MuseNodeError.CONFLICT
            status == 413 -> MuseNodeError.FILE_TOO_LARGE
            status in 400..499 -> MuseNodeError.BAD_REQUEST
            status >= 500 -> MuseNodeError.INTERNAL_ERROR
            else -> MuseNodeError.INVALID_RESPONSE
        }
    }

    private data class ResolvedEndpoint(
        val baseUrl: String,
        val bearerToken: String,
        val pinnedFingerprintHex: String?,
    )

    private enum class HttpMethod { GET, POST, PUT, DELETE }

    private companion object {
        private const val TAG = "MuseNodeClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val WATCHER_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
        private const val MAX_FILE_BYTES = 10 * 1024 * 1024
    }
}

/** 单测或本地调试时固定端点；生产路径永远按节点连接方式解析。 */
internal data class NodeEndpointOverride(
    val baseUrl: String,
    val bearerToken: String,
    val pinnedFingerprintHex: String? = null,
)

/** 节点控制面的结构化错误码。 */
enum class MuseNodeError {
    AUTH_REQUIRED,
    APPROVAL_REQUIRED,
    BAD_REQUEST,
    NOT_FOUND,
    CONFLICT,
    FILE_TOO_LARGE,
    INTERNAL_ERROR,
    NETWORK,
    TLS_FINGERPRINT_MISMATCH,
    TUNNEL_UNAVAILABLE,
    NODE_NOT_CONFIGURED,
    INVALID_RESPONSE,
}

/** 控制面调用失败；serverCode 保留服务端原始错误码，便于 UI 精确分流。 */
class MuseNodeException(
    val error: MuseNodeError,
    val serverCode: String = "",
    message: String,
    val httpStatus: Int = 0,
    cause: Throwable? = null,
) : Exception(message, cause) {
    override fun toString(): String = "MuseNodeException(error=" + error + ", serverCode=" + serverCode +
        ", httpStatus=" + httpStatus + ", message=" + message + ")"
}

/** GET /health 的响应。 */
@Serializable
data class NodeHealth(
    val nodeVersion: String = "",
    val pythonVersion: String = "",
    val cpuPercent: Double = 0.0,
    val memUsedMb: Double = 0.0,
    val memTotalMb: Long = 0,
    val diskFreeMb: Long = 0,
    val packs: List<String> = emptyList(),
    val runningJobs: Int = 0,
    val outboxCount: Int = 0,
)

/** 命令类型，与服务端 /exec 的 kind 字段一致。 */
enum class ExecKind(val wireValue: String) {
    SHELL("shell"),
    PYTHON("python"),
    NODE("node"),
}

/** 一次 /exec 启动请求。 */
data class ExecRequest(
    val kind: ExecKind,
    val command: String = "",
    val code: String = "",
    val cwd: String = "~/muse-workspace",
    val timeoutSec: Int = 120,
    val runId: String = UUID.randomUUID().toString(),
) {
    init {
        require(RUN_ID_REGEX.matches(runId)) { "runId contains unsupported characters" }
        require(timeoutSec in 1..3600) { "timeoutSec out of range" }
        require(
            when (kind) {
                ExecKind.SHELL -> command.isNotBlank()
                ExecKind.PYTHON, ExecKind.NODE -> code.isNotBlank() || command.isNotBlank()
            },
        ) { "command or code is required" }
    }

    private companion object {
        private val RUN_ID_REGEX = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

/** /exec 输出帧。 */
sealed interface ExecEvent {
    data class Output(val stream: ExecStream, val text: String, val timestampMs: Long) : ExecEvent

    data class Exit(val exitCode: Int, val timestampMs: Long) : ExecEvent
}

enum class ExecStream { STDOUT, STDERR }

/** 一次 exec 会话：event 流 + 取消入口。 */
class ExecSession internal constructor(
    private val client: OkHttpClient,
    private val socketRequest: Request,
    val runId: String,
) : AutoCloseable {

    private val socketRef = AtomicReference<WebSocket?>(null)
    private val finished = AtomicBoolean(false)

    val events: Flow<ExecEvent> = callbackFlow {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketRef.set(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = ExecEventParser.parse(text) ?: return
                trySend(event)
                if (event is ExecEvent.Exit) {
                    finished.set(true)
                    close()
                    webSocket.close(1000, "done")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socketRef.compareAndSet(webSocket, null)
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socketRef.compareAndSet(webSocket, null)
                close(
                    MuseNodeException(
                        error = MuseNodeError.NETWORK,
                        message = "exec websocket failed: " + t.javaClass.simpleName,
                        cause = t,
                    ),
                )
            }
        }
        val socket = client.newWebSocket(socketRequest, listener)
        awaitClose {
            socketRef.compareAndSet(socket, null)
            if (!finished.get()) socket.cancel()
        }
    }

    /** 发送取消帧；服务端会先发一帧 stderr 说明再结束进程。 */
    fun cancel() {
        socketRef.get()?.send(CANCEL_FRAME)
    }

    override fun close() {
        finished.set(true)
        socketRef.get()?.close(1000, "client closed")
    }

    private companion object {
        private const val CANCEL_FRAME = "{\"type\":\"cancel\"}"
    }
}

internal object ExecEventParser {
    private const val TAG = "ExecEventParser"

    fun parse(text: String): ExecEvent? {
        val obj = try {
            AppJson.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            Logger.w(TAG, "exec frame is not JSON: " + e.javaClass.simpleName)
            null
        } ?: return null
        val timestamp = obj["ts"]?.jsonPrimitive?.longOrNull ?: 0L
        return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "stdout" -> ExecEvent.Output(ExecStream.STDOUT, obj.stringValue("text"), timestamp)
            "stderr" -> ExecEvent.Output(ExecStream.STDERR, obj.stringValue("text"), timestamp)
            "exit" -> ExecEvent.Exit(obj["exitCode"]?.jsonPrimitive?.intOrNull ?: -1, timestamp)
            else -> {
                Logger.w(TAG, "unknown exec frame type")
                null
            }
        }
    }
}

/** /env/ensure 的生态类型。 */
enum class EnvEcosystem(val wireValue: String) {
    PIP("pip"),
    NPM("npm"),
    APT("apt"),
}

data class EnvEnsureRequest(
    val ecosystem: EnvEcosystem,
    val packages: List<String>,
    val allowSystem: Boolean = false,
) {
    init {
        require(packages.isNotEmpty()) { "packages must not be empty" }
        require(packages.all { it.isNotBlank() }) { "package names must not be blank" }
    }
}

/** /env/ensure 的三态结果。 */
sealed interface EnvEnsureResult {
    data class Installed(
        val ecosystem: EnvEcosystem,
        val packages: List<String>,
        val exitCode: Int,
        val outputTail: String,
    ) : EnvEnsureResult

    data class ApprovalRequired(val message: String) : EnvEnsureResult

    data class Failed(
        val code: String,
        val message: String,
        val exitCode: Int,
        val outputTail: String,
    ) : EnvEnsureResult
}

data class FileWriteReceipt(val path: String, val bytes: Long)

enum class WatcherKind(val wireValue: String) {
    PYTHON("python"),
    NODE("node"),
}

data class WatcherSpec(
    val watcherId: String,
    val intervalMinutes: Int,
    val script: String,
    val kind: WatcherKind,
    val webhookUrl: String = "",
) {
    internal fun validate() {
        require(WATCHER_ID_REGEX.matches(watcherId)) { "watcherId contains unsupported characters" }
        require(intervalMinutes in 1..10080) { "intervalMinutes out of range" }
        require(script.isNotBlank()) { "watcher script must not be blank" }
        require(webhookUrl.isBlank() || webhookUrl.startsWith("http://") || webhookUrl.startsWith("https://")) {
            "webhookUrl must be http/https"
        }
    }

    private companion object {
        private val WATCHER_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

data class WatcherReceipt(val watcherId: String, val intervalMinutes: Int)

/** outbox 中的一条离机事件；未知字段由 AppJson 忽略。 */
@Serializable
data class OutboxEvent(
    val eventId: String = "",
    val createdAt: Long = 0,
    val type: String = "",
    val runId: String = "",
    val watcherId: String = "",
    val status: String = "",
    val exitCode: Int = 0,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val output: String = "",
    val outputTail: String = "",
    val fingerprint: String = "",
)

@Serializable
data class OutboxPage(
    val events: List<OutboxEvent> = emptyList(),
    val count: Int = 0,
    val serverTime: Long = 0,
)

@Serializable
data class BrowserSessionInfo(
    val sid: String,
    val url: String = "",
    val title: String = "",
    val width: Int = 720,
    val height: Int = 1280,
)

/** /browser WS 的一帧。frame 帧携带 JPEG Base64，其余控制帧只带文本字段。 */
sealed interface BrowserFrame {
    data class Frame(
        val url: String,
        val title: String,
        val jpegBase64: String,
        val width: Int,
        val height: Int,
    ) : BrowserFrame

    data class Closed(val reason: String) : BrowserFrame

    data class Ack(val action: String) : BrowserFrame

    data class Error(val code: String, val message: String) : BrowserFrame
}

/** 一个浏览器观战会话：帧流 + 点按/输入/导航/交回。 */
class BrowserSession internal constructor(
    private val client: OkHttpClient,
    private val socketRequest: Request,
    val info: BrowserSessionInfo,
) : AutoCloseable {

    val sid: String get() = info.sid

    private val socketRef = AtomicReference<WebSocket?>(null)
    private val finished = AtomicBoolean(false)

    val frames: Flow<BrowserFrame> = callbackFlow {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketRef.set(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = BrowserFrameParser.parse(text) ?: return
                trySend(frame)
                if (frame is BrowserFrame.Closed) {
                    finished.set(true)
                    close()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socketRef.compareAndSet(webSocket, null)
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socketRef.compareAndSet(webSocket, null)
                close(
                    MuseNodeException(
                        error = MuseNodeError.NETWORK,
                        message = "browser websocket failed: " + t.javaClass.simpleName,
                        cause = t,
                    ),
                )
            }
        }
        val socket = client.newWebSocket(socketRequest, listener)
        awaitClose {
            socketRef.compareAndSet(socket, null)
            if (!finished.get()) socket.cancel()
        }
    }

    fun tap(x: Float, y: Float) {
        send(
            buildJsonObject {
                put("type", "tap")
                put("x", x)
                put("y", y)
            },
        )
    }

    fun type(text: String) {
        send(
            buildJsonObject {
                put("type", "text")
                put("value", text)
            },
        )
    }

    fun navigate(url: String) {
        send(
            buildJsonObject {
                put("type", "navigate")
                put("url", url)
            },
        )
    }

    fun handoffDone() {
        send(buildJsonObject { put("type", "handoff_done") })
    }

    private fun send(payload: JsonObject) {
        val socket = socketRef.get()
        if (socket == null || !socket.send(payload.toString())) {
            // 用户可能在帧流尚未开始收集前点击；丢弃时留下日志，避免表现为无响应。
            Logger.w(TAG, "browser command dropped: websocket is not open")
        }
    }

    override fun close() {
        finished.set(true)
        socketRef.get()?.close(1000, "client closed")
    }

    private companion object {
        private const val TAG = "BrowserSession"
    }
}

internal object BrowserFrameParser {
    private const val TAG = "BrowserFrameParser"

    fun parse(text: String): BrowserFrame? {
        val obj = try {
            AppJson.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            Logger.w(TAG, "browser frame is not JSON: " + e.javaClass.simpleName)
            null
        } ?: return null
        return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "frame" -> BrowserFrame.Frame(
                url = obj.stringValue("url"),
                title = obj.stringValue("title"),
                jpegBase64 = obj.stringValue("jpegBase64"),
                width = obj["width"]?.jsonPrimitive?.intOrNull ?: 0,
                height = obj["height"]?.jsonPrimitive?.intOrNull ?: 0,
            )
            "closed" -> BrowserFrame.Closed(obj.stringValue("reason"))
            "ack" -> BrowserFrame.Ack(obj.stringValue("action"))
            "error" -> BrowserFrame.Error(obj.stringValue("code"), obj.stringValue("message"))
            else -> {
                Logger.w(TAG, "unknown browser frame type")
                null
            }
        }
    }
}

@Serializable
private data class ExecStartPayload(
    val runId: String,
    val kind: String,
    val command: String,
    val code: String,
    val cwd: String,
    val timeoutSec: Int,
)

@Serializable
private data class EnvEnsurePayload(
    val ecosystem: String,
    val packages: List<String>,
    val allowSystem: Boolean,
)

@Serializable
private data class WatcherUpsertPayload(
    val watcherId: String,
    val intervalMinutes: Int,
    val script: String,
    val kind: String,
    val webhookUrl: String,
)

@Serializable
private data class OutboxAckPayload(val eventIds: List<String>)

@Serializable
private data class BrowserStartPayload(val url: String, val width: Int, val height: Int)

private fun JsonObject?.stringValue(key: String): String = (this?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

private class FingerprintTrustManager(private val expectedHex: String) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not accepted")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("server certificate chain is empty")
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).toHex()
        val matches = MessageDigest.isEqual(
            actual.toByteArray(Charsets.US_ASCII),
            expectedHex.toByteArray(Charsets.US_ASCII),
        )
        if (!matches) throw CertificateException("server certificate fingerprint mismatch")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun ByteArray.toHex(): String {
    val builder = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        builder.append(HEX_CHARS[value ushr 4]).append(HEX_CHARS[value and 0x0F])
    }
    return builder.toString()
}

private const val HEX_CHARS = "0123456789abcdef"
