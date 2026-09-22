package io.zer0.muse.tools

import io.zer0.ai.core.OAuthConfig
import io.zer0.muse.auth.OAuthManager
import io.zer0.muse.connector.ConnectorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * v2.0: 连接器工具注册器 — 把已授权的 OAuth 连接器暴露为 LLM 工具。
 *
 *  - [TOOL_LIST_CONNECTORS] 列出已配置连接器及授权状态;
 *  - [TOOL_CALL_CONNECTOR] 以连接器凭据调用外部服务 HTTP API(HIGH,走审批):
 *    access_token 自动附带,过期时经 [OAuthManager.refreshTokenIfNeeded] 自动刷新。
 *
 * 安全约束:目标 url 必须与连接器 token 端点同注册域(取末两段域名近似判定),
 * 防止 access_token 被引导发送到无关主机。
 */
class ConnectorToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val connectorStore: ConnectorStore,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_LIST_CONNECTORS,
                description = "列出已配置的 OAuth 连接器(外部服务授权)及其 id / 名称 / 是否已授权。" +
                    "调用外部服务前先用本工具获取 connector_id。",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) {
            runBlocking {
                val connectors = connectorStore.load()
                if (connectors.isEmpty()) {
                    "尚未配置任何连接器。"
                } else {
                    val lines = mutableListOf<String>()
                    for (c in connectors) {
                        val authorized = OAuthManager.getStoredToken("connector_${c.id}") != null
                        lines += "${c.id} | ${c.name.ifBlank { "-" }} | " +
                            if (authorized) "已授权" else "未授权"
                    }
                    lines.joinToString("\n")
                }
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_CALL_CONNECTOR,
                description = "用已授权的连接器凭据调用外部服务 HTTP API(自动附带 Bearer token,过期自动刷新)。" +
                    "先用 connector_list 查询可用连接器 id;url 必须与连接器服务同域。",
                parameters = mapOf(
                    "connector_id" to "必填,连接器 id(见 connector_list)",
                    "url" to "必填,完整请求 URL(如 https://api.github.com/user)",
                    "method" to "可选,HTTP 方法:GET(默认)/POST/PUT/PATCH/DELETE",
                    "body" to "可选,请求体(JSON 文本,POST/PUT/PATCH 时使用)",
                ),
                required = setOf("connector_id", "url"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            runBlocking { executeCall(args) }
        }
    }

    private suspend fun executeCall(args: Map<String, String>): String = withContext(Dispatchers.IO) {
        val connectorId = args["connector_id"]?.trim().orEmpty()
        val url = args["url"]?.trim().orEmpty()
        val method = args["method"]?.trim()?.uppercase().orEmpty().ifBlank { "GET" }
        val body = args["body"]
        if (connectorId.isBlank()) return@withContext "缺少 connector_id"
        if (url.isBlank()) return@withContext "缺少 url"
        if (method !in ALLOWED_METHODS) return@withContext "不支持的方法: $method"
        val connector = connectorStore.load().firstOrNull { it.id == connectorId }
            ?: return@withContext "连接器不存在: $connectorId"
        // 同域校验:防止 token 被发送到无关主机
        if (!sameSite(url, connector.tokenUrl)) {
            return@withContext "拒绝: url 与连接器服务域不一致" +
                "(${hostOf(url) ?: "?"} 不属于 ${hostOf(connector.tokenUrl) ?: "?"} 站点)"
        }
        val oauthConfig = OAuthConfig(
            clientId = connector.clientId,
            clientSecret = connector.clientSecret.takeIf { it.isNotBlank() },
            authorizeUrl = connector.authorizeUrl,
            tokenUrl = connector.tokenUrl,
            redirectUri = "io.zer0.muse://oauth/callback",
            scope = connector.scope,
        )
        val token = OAuthManager.refreshTokenIfNeeded("connector_${connector.id}", oauthConfig)
            .getOrElse { e ->
                return@withContext "连接器未授权或 token 刷新失败: ${e.message}" +
                    "(请先在 设置-连接器 完成授权)"
            }
        runCatching {
            val builder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
            when (method) {
                "GET" -> builder.get()
                "DELETE" -> builder.delete()
                else -> builder.method(method, (body ?: "").toRequestBody(JSON_MEDIA))
            }
            HTTP.newCall(builder.build()).execute().use { resp ->
                val text = resp.body.string()
                "HTTP ${resp.code}\n${text.take(MAX_RESPONSE_LENGTH)}"
            }
        }.getOrElse { e -> "请求失败: ${e.message}" }
    }

    /** 同注册域判定:取末两段域名比较(github.com / api.github.com → github.com)。 */
    private fun sameSite(a: String, b: String): Boolean {
        val hostA = hostOf(a) ?: return false
        val hostB = hostOf(b) ?: return false
        return registeredDomain(hostA) == registeredDomain(hostB)
    }

    private fun hostOf(url: String): String? = runCatching {
        java.net.URI(url).host?.lowercase()
    }.getOrNull()

    private fun registeredDomain(host: String): String {
        val parts = host.split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }

    companion object {
        /** 列出已配置连接器(只读)。 */
        const val TOOL_LIST_CONNECTORS = "connector_list"

        /** 以连接器凭据调用外部服务 API(审批后执行)。 */
        const val TOOL_CALL_CONNECTOR = "call_connector"

        private const val MAX_RESPONSE_LENGTH = 4_000

        private val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private val HTTP: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
