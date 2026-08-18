package io.zer0.muse.mcp

import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 飞书 tenant_access_token(internal) 换取客户端。
 *
 * App Secret 只进入请求体,不写日志、不写聊天消息；返回的 TAT 由调用方保存到
 * 已加密的 MCP token 存储中。网络失败返回 null,由 MCP 客户端转为连接失败/重连状态。
 */
internal class McpFeishuTenantTokenClient(
    private val httpClient: OkHttpClient = defaultClient,
    private val endpoint: String = TENANT_ACCESS_TOKEN_ENDPOINT,
) {

    /**
     * 使用飞书应用身份换取 tenant_access_token。
     *
     * @param appId 飞书应用 App ID
     * @param appSecret 飞书应用 App Secret
     * @param now 当前时间,用于单元测试和计算过期时间
     */
    suspend fun fetch(
        appId: String,
        appSecret: String,
        now: Long = System.currentTimeMillis(),
    ): McpTokenInfo? = withContext(Dispatchers.IO) {
        if (appId.isBlank() || appSecret.isBlank()) return@withContext null

        val body = buildJsonObject {
            put("app_id", appId)
            put("app_secret", appSecret)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                if (!response.isSuccessful) {
                    Logger.w(TAG, "飞书 TAT 获取失败: HTTP ${response.code}")
                    return@withContext null
                }
                parseTokenResponse(responseBody, now)
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Logger.w(TAG, "飞书 TAT 获取异常: ${e.message}")
            null
        }
    }

    internal fun parseTokenResponse(body: String, now: Long): McpTokenInfo? {
        val json = runCatching {
            AppJson.decodeFromString(JsonObject.serializer(), body)
        }.getOrNull() ?: return null
        val token = json["tenant_access_token"]
            ?.jsonPrimitive
            ?.contentOrNull
            .orEmpty()
        if (token.isBlank()) {
            val code = json["code"]?.jsonPrimitive?.contentOrNull
            val message = json["msg"]?.jsonPrimitive?.contentOrNull
            Logger.w(TAG, "飞书 TAT 响应缺少 token: code=$code, msg=$message")
            return null
        }
        val expiresInSeconds = json["expire"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toLongOrNull()
            ?: 0L
        return McpTokenInfo(
            accessToken = token,
            expiresAt = if (expiresInSeconds > 0L) {
                now + expiresInSeconds * 1000L
            } else {
                0L
            },
        )
    }

    private companion object {
        const val TAG = "McpFeishuTenantToken"
        const val TENANT_ACCESS_TOKEN_ENDPOINT =
            "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
