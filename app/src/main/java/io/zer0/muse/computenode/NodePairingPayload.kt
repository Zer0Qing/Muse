package io.zer0.muse.computenode

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.SecureKeyCipher
import io.zer0.muse.data.SecureKeyStore
import io.zer0.muse.data.computenode.ComputeNodeMode
import io.zer0.muse.data.computenode.ComputeNodeStatus
import io.zer0.muse.data.computenode.RemoteComputeNodeEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Base64
import java.util.Locale

/**
 * 一行命令扫码配对的载荷。
 *
 * 终端把 [NodePairingPayload] 序列化成 JSON 后做 Base64URL 编码，二维码与粘贴串都使用同一格式。
 * 该类只保存定向连接所需的最小信息；Token 不参与任何日志输出（见 [toString]）。
 */
@Serializable
data class NodePairingPayload(
    val v: Int,
    val host: String,
    val port: Int,
    val token: String,
    val fp: String,
    val name: String = "",
) {

    init {
        require(v == SUPPORTED_VERSION) { "unsupported pairing payload version" }
        require(isValidHost(host)) { "pairing host is invalid" }
        require(port in 1..65535) { "pairing port out of range" }
        require(TOKEN_REGEX.matches(token)) { "pairing token must be 64 hex characters" }
        require(normalizeFingerprintHex(fp) != null) { "pairing fingerprint is invalid" }
        require(name.length <= MAX_NAME_LENGTH) { "pairing name is too long" }
    }

    /** 叶子证书 SHA-256 指纹的规范化 64 位小写十六进制形式；[fp] 在构造时已校验。 */
    fun fingerprintHex(): String = checkNotNull(normalizeFingerprintHex(fp)) {
        "fingerprint was not validated at construction"
    }

    /**
     * 转成可直接落库的节点实体。
     *
     * 扫码配对只用于 DIRECT_TLS 模式；Token 先经 [SecureKeyCipher.encrypt] 加密再落库，
     * 明文只在本次构造过程中停留于内存。
     */
    suspend fun toEntity(
        nodeId: String,
        cipher: SecureKeyCipher = SecureKeyStore,
        now: Long = System.currentTimeMillis(),
    ): RemoteComputeNodeEntity = RemoteComputeNodeEntity(
        nodeId = nodeId,
        name = name.ifBlank { host },
        connectionMode = ComputeNodeMode.DIRECT_TLS.wireValue,
        host = host,
        daemonPort = port,
        authType = AUTH_TYPE_PAIR_TOKEN,
        encryptedNodeToken = cipher.encrypt(token),
        tlsFingerprint = FORMATTED_FINGERPRINT_PREFIX + fingerprintHex(),
        status = ComputeNodeStatus.UNINITIALIZED.wireValue,
        createdAt = now,
        updatedAt = now,
    )

    /** 不允许把 Token 带进日志、崩溃报告或异常消息。 */
    override fun toString(): String = "NodePairingPayload(v=" + v + ", host=" + host + ", port=" + port +
        ", token=***, fp=" + fp + ", name=" + name + ")"

    companion object {
        const val SUPPORTED_VERSION = 1
        const val FORMATTED_FINGERPRINT_PREFIX = "SHA256:"

        private const val MAX_NAME_LENGTH = 120
        private const val AUTH_TYPE_PAIR_TOKEN = "PAIR_TOKEN"
        private val TOKEN_REGEX = Regex("[0-9a-fA-F]{64}")
        private val HEX_REGEX = Regex("[0-9a-fA-F]{64}")

        /**
         * 解析二维码或粘贴串。
         *
         * 正常输入是 JSON 的 Base64URL（可省略补位）；为了便于手工粘贴，也接受原始 JSON 文本。
         * 任何字段缺失、版本不符或指纹格式错误都返回 [NodePairingResult.Failure]，不抛异常。
         */
        fun parse(raw: String): NodePairingResult {
            val trimmed = raw.trim()
            return if (trimmed.isEmpty()) {
                NodePairingResult.Failure("pairing payload is empty")
            } else {
                parseDecoded(trimmed)
            }
        }

        private fun parseDecoded(raw: String): NodePairingResult {
            val jsonText = decodeJsonText(raw)
                ?: return NodePairingResult.Failure("pairing payload is not valid Base64URL JSON")
            val obj = try {
                AppJson.parseToJsonElement(jsonText) as? JsonObject
            } catch (e: SerializationException) {
                // 用户可能粘贴了损坏的二维码文本；解析失败按无效输入处理，不把原文写进日志。
                Logger.w("NodePairingPayload", "pairing payload JSON parsing failed: ${e.message}")
                return NodePairingResult.Failure("pairing payload is not valid JSON")
            } ?: return NodePairingResult.Failure("pairing payload must be a JSON object")

            return validateObject(obj)
        }

        private fun validateObject(obj: JsonObject): NodePairingResult {
            val version = obj.intField("v")
            val host = obj.stringField("host")?.trim().orEmpty()
            val port = obj.intField("port")
            val basicFailure = validateBasicFields(version, host, port)
            if (basicFailure != null) return NodePairingResult.Failure(basicFailure)
            val validVersion = checkNotNull(version)
            val validPort = checkNotNull(port)
            val token = obj.stringField("token")?.trim().orEmpty()
            val fingerprint = normalizeFingerprintHex(obj.stringField("fp")?.trim().orEmpty())
            val credentialsFailure = validateCredentials(token, fingerprint)
            if (credentialsFailure != null) return NodePairingResult.Failure(credentialsFailure)
            val validFingerprint = checkNotNull(fingerprint)
            val name = obj.stringField("name")?.trim().orEmpty()
            if (name.length > MAX_NAME_LENGTH) return NodePairingResult.Failure("pairing name is too long")
            return NodePairingResult.Success(
                NodePairingPayload(
                    v = validVersion,
                    host = host,
                    port = validPort,
                    token = token.lowercase(Locale.US),
                    fp = FORMATTED_FINGERPRINT_PREFIX + validFingerprint,
                    name = name,
                ),
            )
        }

        private fun validateBasicFields(version: Int?, host: String, port: Int?): String? {
            return when {
                version == null -> "pairing version is missing"
                version != SUPPORTED_VERSION -> "unsupported pairing version: $version"
                !isValidHost(host) -> "pairing host is invalid"
                port == null -> "pairing port is missing"
                port !in 1..65535 -> "pairing port out of range"
                else -> null
            }
        }

        private fun validateCredentials(token: String, fingerprint: String?): String? {
            return when {
                !TOKEN_REGEX.matches(token) -> "pairing token must be 64 hex characters"
                fingerprint == null -> "pairing fingerprint must be SHA256:<64 hex>"
                else -> null
            }
        }

        /**
         * 把证书指纹统一成 64 位小写十六进制。
         *
         * 兼容一个带 SHA256 前缀、冒号分段和裸十六进制三种常见输出；其余返回 null。
         */
        fun normalizeFingerprintHex(raw: String): String? {
            var value = raw.trim().replace(" ", "")
            if (value.startsWith(FORMATTED_FINGERPRINT_PREFIX, ignoreCase = true)) {
                value = value.substring(FORMATTED_FINGERPRINT_PREFIX.length)
            }
            value = value.replace(":", "")
            if (!HEX_REGEX.matches(value)) return null
            return value.lowercase(Locale.US)
        }

        private fun decodeJsonText(raw: String): String? {
            if (raw.startsWith("{")) return raw
            // 终端可能把负载放在一行命令的末尾；取最后一个空白分隔的片段即可，不影响纯 Base64URL。
            val candidate = raw.substringAfterLast(' ').trim()
            val compact = candidate.filterNot { it.isWhitespace() }
            if (compact.isEmpty()) return null
            val padding = (4 - compact.length % 4) % 4
            val padded = compact + "=".repeat(padding)
            return runCatching {
                String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
            }.getOrNull()
        }

        private fun JsonObject.stringField(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.intField(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

        private fun isValidHost(host: String): Boolean {
            if (host.isBlank() || host.length > 253) return false
            if (host.contains("://") || host.contains('/')) return false
            return host.none { it.isWhitespace() }
        }
    }
}

/** 配对串解析结果：成功返回规范化载荷，失败只带可展示原因。 */
sealed interface NodePairingResult {
    data class Success(val payload: NodePairingPayload) : NodePairingResult

    data class Failure(val reason: String) : NodePairingResult
}
