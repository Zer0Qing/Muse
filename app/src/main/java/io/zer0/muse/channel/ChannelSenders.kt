package io.zer0.muse.channel

import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * v1.0.92: 渠道发送器 — 把一段文本发送到对应平台(发送侧)。
 *
 * 接收侧(事件长连接 / webhook 接入)与更深的双向能力分步推进。
 */
internal interface ChannelSender {
    /**
     * 发送一段文本;失败时返回带原因的异常。
     *
     * [targetOverride] 用于自动回复等"回发到消息来源"场景(飞书 open_id / QQ openid),
     * 为空时使用 [ChannelConfig.targetId]。
     */
    suspend fun sendText(config: ChannelConfig, text: String, targetOverride: String? = null): Result<Unit>
}

/** 平台 access_token 缓存(有效期约 2 小时;提前 5 分钟视为过期)。 */
private class TokenCache {
    @Volatile private var token: String? = null
    @Volatile private var expiresAt: Long = 0L

    fun get(): String? = token?.takeIf { System.currentTimeMillis() < expiresAt }

    fun put(value: String, expiresInSeconds: Long) {
        token = value
        val safeTtl = (expiresInSeconds - 300L).coerceAtLeast(60L)
        expiresAt = System.currentTimeMillis() + safeTtl * 1000L
    }
}

private val HTTP: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
}

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/** 发送 JSON POST 请求;非 2xx 抛带响应片段的异常(用于 Result 链)。 */
private fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): Result<String> =
    runCatching {
        val builder = Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA))
        headers.forEach { (k, v) -> builder.header(k, v) }
        HTTP.newCall(builder.build()).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
            text
        }
    }

/**
 * 飞书(开放平台自建应用)发送器。
 *
 * 换取 tenant_access_token → `POST /open-apis/im/v1/messages`。
 * receive_id_type 按目标前缀判定:oc_=chat_id / ou_=open_id / on_=union_id。
 */
internal class FeishuChannelSender : ChannelSender {

    private val tokenCache = TokenCache()

    override suspend fun sendText(config: ChannelConfig, text: String, targetOverride: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = tokenCache.get() ?: run {
                    val (value, expires) = fetchToken(config)
                    tokenCache.put(value, expires)
                    value
                }
                val target = targetOverride?.takeIf { it.isNotBlank() } ?: config.targetId
                val idType = when {
                    target.startsWith("oc_") -> "chat_id"
                    target.startsWith("ou_") -> "open_id"
                    target.startsWith("on_") -> "union_id"
                    else -> "chat_id"
                }
                val body = buildJsonObject {
                    put("receive_id", target)
                    put("msg_type", "text")
                    put("content", buildJsonObject { put("text", text) }.toString())
                }.toString()
                postJson(
                    "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=$idType",
                    body,
                    mapOf("Authorization" to "Bearer $token"),
                ).getOrThrow()
            }.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { e ->
                    Logger.w(TAG, "飞书发送失败: ${e.message}")
                    Result.failure(e)
                },
            )
        }

    /** 换取 tenant_access_token,返回 (token, expiresInSeconds)。 */
    private fun fetchToken(config: ChannelConfig): Pair<String, Long> {
        val body = buildJsonObject {
            put("app_id", config.appId)
            put("app_secret", config.appSecret)
        }.toString()
        val resp = postJson(FEISHU_TOKEN_URL, body).getOrThrow()
        val obj = AppJson.parseToJsonElement(resp).jsonObject
        val code = obj["code"]?.jsonPrimitive?.contentOrNull
        if (code != null && code != "0") error("飞书 token 错误: ${resp.take(200)}")
        val token = obj["tenant_access_token"]?.jsonPrimitive?.contentOrNull ?: error("飞书 token 响应缺字段")
        val expires = obj["expire"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 7200L
        return token to expires
    }

    companion object {
        private const val TAG = "FeishuSender"
        private const val FEISHU_TOKEN_URL =
            "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"
    }
}

/**
 * QQ 开放平台机器人发送器。
 *
 * AppID/AppSecret 换 access_token → `POST /v2/groups/{group_openid}/messages`
 * 或 `/v2/users/{openid}/messages`(按 targetType 区分)。
 */
internal class QqChannelSender : ChannelSender {

    private val tokenCache = TokenCache()

    override suspend fun sendText(config: ChannelConfig, text: String, targetOverride: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = tokenCache.get() ?: run {
                    val (value, expires) = fetchToken(config)
                    tokenCache.put(value, expires)
                    value
                }
                val override = targetOverride?.takeIf { it.isNotBlank() }
                val target = override ?: config.targetId
                val url = if (override != null || config.targetType == "c2c") {
                    "https://api.sgroup.qq.com/v2/users/$target/messages"
                } else {
                    "https://api.sgroup.qq.com/v2/groups/$target/messages"
                }
                val body = buildJsonObject {
                    put("content", text)
                    put("msg_type", 0)
                }.toString()
                postJson(
                    url,
                    body,
                    mapOf(
                        "Authorization" to "QQBot $token",
                        "X-Union-Appid" to config.appId,
                    ),
                ).getOrThrow()
            }.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { e ->
                    Logger.w(TAG, "QQ 发送失败: ${e.message}")
                    Result.failure(e)
                },
            )
        }

    /** 换取 access_token,返回 (token, expiresInSeconds)。 */
    private fun fetchToken(config: ChannelConfig): Pair<String, Long> {
        val body = buildJsonObject {
            put("appId", config.appId)
            put("clientSecret", config.appSecret)
        }.toString()
        val resp = postJson(QQ_TOKEN_URL, body).getOrThrow()
        val obj = AppJson.parseToJsonElement(resp).jsonObject
        val token = obj["access_token"]?.jsonPrimitive?.contentOrNull ?: error("QQ token 响应缺字段")
        val expires = obj["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 7200L
        return token to expires
    }

    companion object {
        private const val TAG = "QqSender"
        private const val QQ_TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken"
    }
}

/**
 * 微信 ClawBot(iLink)发送器 — 协议对接专项,占位实现。
 *
 * 背景:2026-03 官方开放的微信个人号 Bot 通道(扫码绑定 + iLink 消息协议);
 * 接入要素与稳定性正在跟进,落地前该渠道返回明确提示而非静默失败。
 */
internal class WeClawChannelSender : ChannelSender {
    override suspend fun sendText(config: ChannelConfig, text: String, targetOverride: String?): Result<Unit> =
        Result.failure(IllegalStateException("微信 ClawBot 通道尚未接入(协议对接中)"))
}
