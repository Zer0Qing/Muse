package io.zer0.muse.connector

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import io.zer0.muse.data.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * v1.0.92: OAuth 连接器配置 — 通用 OAuth2 服务接入。
 *
 * 连接流程复用 [io.zer0.muse.auth.OAuthManager](授权码 + PKCE),
 * 回调统一走 `io.zer0.muse://oauth/callback`;token 由 OAuthManager 的
 * 加密凭证存储按 `connector_{id}` 隔离保存。
 */
@Serializable
data class ConnectorConfig(
    val id: String,
    val name: String = "",
    /** 授权端点(如 https://github.com/login/oauth/authorize)。 */
    val authorizeUrl: String = "",
    /** token 交换端点(如 https://github.com/login/oauth/access_token)。 */
    val tokenUrl: String = "",
    /** 空格分隔的 scope。 */
    val scope: String = "",
    val clientId: String = "",
    /** 应用密钥(公共客户端可留空);存储时加密。 */
    val clientSecret: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 连接器配置存储 — filesDir/connector_configs.json(原子写),
 * clientSecret 经 [SecureKeyStore] 加密后落盘。
 */
class ConnectorStore(context: Context) {

    private val file = File(context.filesDir, "connector_configs.json")

    @Serializable
    private data class Payload(val connectors: List<ConnectorConfig> = emptyList())

    suspend fun load(): List<ConnectorConfig> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            AppJson.decodeFromString(Payload.serializer(), file.readText()).connectors.map { decrypt(it) }
        }.onFailure { e ->
            Logger.w(TAG, "连接器配置读取失败: ${e.message}")
        }.getOrDefault(emptyList())
    }

    /**
     * v2.0: 区分“配置文件损坏”与“尚未配置”的读取 —
     * 返回 null 表示文件存在但解析/解密失败(损坏),UI 应提示而不是显示空列表。
     */
    suspend fun loadOrNull(): List<ConnectorConfig>? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            AppJson.decodeFromString(Payload.serializer(), file.readText()).connectors.map { decrypt(it) }
        }.onFailure { e ->
            Logger.w(TAG, "连接器配置读取失败: ${e.message}")
        }.getOrNull()
    }

    suspend fun save(connectors: List<ConnectorConfig>) = withContext(Dispatchers.IO) {
        val payload = Payload(connectors.map { encrypt(it) })
        AtomicFileStore.writeText(file, AppJson.encodeToString(Payload.serializer(), payload))
    }

    private suspend fun encrypt(config: ConnectorConfig): ConnectorConfig =
        config.copy(
            clientSecret = if (config.clientSecret.isBlank()) "" else SecureKeyStore.encrypt(config.clientSecret),
        )

    private suspend fun decrypt(config: ConnectorConfig): ConnectorConfig =
        config.copy(
            clientSecret = if (config.clientSecret.isBlank()) "" else SecureKeyStore.decrypt(config.clientSecret),
        )

    companion object {
        private const val TAG = "ConnectorStore"
    }
}
