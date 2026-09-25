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
                    "${feishuOpenBase(config)}/open-apis/im/v1/messages?receive_id_type=$idType",
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

    /** v2.0.1: 飞书开放平台域名 — 中国版 / 国际版 Lark 二选一。 */
    private fun feishuOpenBase(config: ChannelConfig): String =
        if (config.region == "intl") "https://open.larksuite.com" else "https://open.feishu.cn"

    /** 换取 tenant_access_token,返回 (token, expiresInSeconds)。internal 供凭证检测复用。 */
    internal fun fetchToken(config: ChannelConfig): Pair<String, Long> {
        val body = buildJsonObject {
            put("app_id", config.appId)
            put("app_secret", config.appSecret)
        }.toString()
        val resp = postJson(
            "${feishuOpenBase(config)}/open-apis/auth/v3/tenant_access_token/internal",
            body,
        ).getOrThrow()
        val obj = AppJson.parseToJsonElement(resp).jsonObject
        val code = obj["code"]?.jsonPrimitive?.contentOrNull
        if (code != null && code != "0") error("飞书 token 错误: ${resp.take(200)}")
        val token = obj["tenant_access_token"]?.jsonPrimitive?.contentOrNull ?: error("飞书 token 响应缺字段")
        val expires = obj["expire"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 7200L
        return token to expires
    }

    companion object {
        private const val TAG = "FeishuSender"
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
            val token = tokenCache.get() ?: run {
                val info = QqClient.fetchAccessToken(config.appId, config.appSecret).getOrElse { e ->
                    Logger.w(TAG, "QQ access_token 获取失败: ${e.message}")
                    return@withContext Result.failure<Unit>(e)
                }
                tokenCache.put(info.accessToken, info.expiresInSeconds)
                info.accessToken
            }
            val overrideTarget = targetOverride?.takeIf { it.isNotBlank() }
            val rawTarget = overrideTarget ?: config.targetId
            // v2.0.1: 群目标以 "group:" 前缀标记(来自群消息);否则按配置的 targetType 判定。
            val isGroup = if (overrideTarget != null) {
                overrideTarget.startsWith("group:")
            } else {
                config.targetType == "group"
            }
            val target = rawTarget.removePrefix("group:")
            val url = if (isGroup) {
                "${QqClient.API_BASE}/v2/groups/$target/messages"
            } else {
                "${QqClient.API_BASE}/v2/users/$target/messages"
            }
            val body = buildJsonObject {
                put("content", text)
                put("msg_type", 0)
                // v2.0.1: 被动回复 — 来源消息 ID(60 分钟窗)+ 递增序号(相同 msg_id+seq 会被平台去重)。
                QqMsgIdCache.get(rawTarget)?.let { msgId ->
                    put("msg_id", msgId)
                    put("msg_seq", QqMsgIdCache.nextSeq(rawTarget))
                }
            }.toString()
            runCatching {
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

    companion object {
        private const val TAG = "QqSender"
    }
}

/**
 * v2.0: 微信 ClawBot(iLink)发送器。
 *
 * bot_token 存于 appSecret;回复目标为扫码绑定的用户(targetId=ilink_user_id)。
 * 回发时优先携带来源消息的 context_token(协议要求)。
 */
internal class WeClawChannelSender : ChannelSender {
    override suspend fun sendText(config: ChannelConfig, text: String, targetOverride: String?): Result<Unit> {
        val botToken = config.appSecret.trim()
        if (botToken.isBlank()) {
            return Result.failure(IllegalStateException("ClawBot 未绑定(缺少 bot_token,请先扫码绑定)"))
        }
        val target = targetOverride?.takeIf { it.isNotBlank() } ?: config.targetId
        if (target.isBlank()) {
            return Result.failure(IllegalStateException("缺少接收方 ID(ilink_user_id)"))
        }
        return WeClawClient.sendMessage(
            botToken = botToken,
            toUserId = target,
            text = text,
            contextToken = WeClawContextCache.get(target),
        )
    }
}

/**
 * v2.0: Telegram 发送器。
 *
 * Bot Token 存于 appSecret(@BotFather 提供);发送目标为 chat_id。
 * 回复消息用 sendMessage 直接发到 chat_id,无需额外上下文令牌。
 */
internal class TelegramChannelSender : ChannelSender {
    override suspend fun sendText(config: ChannelConfig, text: String, targetOverride: String?): Result<Unit> {
        val token = config.appSecret.trim()
        if (token.isBlank()) {
            return Result.failure(IllegalStateException("Telegram Bot Token 未配置(请填 App Secret)"))
        }
        val target = targetOverride?.takeIf { it.isNotBlank() } ?: config.targetId
        if (target.isBlank()) {
            return Result.failure(IllegalStateException("缺少 chat_id(发送目标)"))
        }
        return TelegramClient.sendMessage(token, target, text)
    }
}

/**
 * v2.0: 钉钉发送器。
 *
 * 优先用收到消息时缓存的 sessionWebhook 回发(约 2 小时内有效,无需鉴权);
 * 缓存不可用时走 OpenAPI 主动发送(需要 ClientID/ClientSecret 换 access_token)。
 * 目标语义:以 "cid" 开头视为群会话(openConversationId),否则视为单聊用户(senderStaffId)。
 */
internal class DingtalkChannelSender : ChannelSender {

    private val tokenCache = TokenCache()

    override suspend fun sendText(config: ChannelConfig, text: String, targetOverride: String?): Result<Unit> {
        val target = targetOverride?.takeIf { it.isNotBlank() } ?: config.targetId
        if (target.isBlank()) {
            return Result.failure(IllegalStateException("缺少发送目标(会话或用户 id)"))
        }
        // 1. sessionWebhook 优先(临时地址自带会话凭据)
        DingtalkSessionCache.get(target)?.let { webhook ->
            return DingtalkClient.sendViaSessionWebhook(webhook, text)
        }
        // 2. OpenAPI 主动发送
        val appKey = config.appId.trim()
        val appSecret = config.appSecret.trim()
        if (appKey.isBlank() || appSecret.isBlank()) {
            return Result.failure(
                IllegalStateException("钉钉凭据未配置(ClientID/ClientSecret),且会话窗口已过期"),
            )
        }
        val token = tokenCache.get() ?: run {
            val info = DingtalkClient.fetchAccessToken(appKey, appSecret, config.dingtalkApiBase).getOrElse { e ->
                return Result.failure(e)
            }
            tokenCache.put(info.accessToken, info.expireInSeconds)
            info.accessToken
        }
        // v2.0.1: Robot Code 显式配置优先,留空回退 AppKey(企业内部机器人两者通常相同)。
        val robotCode = config.robotCode.trim().ifBlank { appKey }
        return if (target.startsWith("cid")) {
            DingtalkClient.sendToGroup(token, robotCode, target, text, config.dingtalkApiBase)
        } else {
            DingtalkClient.sendToUser(token, robotCode, target, text, config.dingtalkApiBase)
        }
    }
}
