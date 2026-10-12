package io.zer0.muse.web

import io.ktor.http.HttpStatusCode
import io.zer0.muse.channel.ChannelConfig
import io.zer0.muse.channel.ChannelInbox
import io.zer0.muse.channel.ChannelPlatform
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal sealed interface FeishuDispatch {
    data class Rejected(val status: HttpStatusCode, val message: String) : FeishuDispatch
    data class Challenge(val value: String) : FeishuDispatch
    data class Inbound(
        val source: ChannelInbox.Source,
        val text: String?,
        val rawPayload: String,
    ) : FeishuDispatch
    data object Acknowledge : FeishuDispatch
}

internal sealed interface QqDispatch {
    data class Rejected(val status: HttpStatusCode, val message: String) : QqDispatch
    data class Challenge(val plainToken: String, val signature: String) : QqDispatch
    data class Inbound(
        val source: ChannelInbox.Source,
        val text: String?,
        val rawPayload: String,
    ) : QqDispatch
}

internal fun resolveFeishuPayload(payload: JsonObject, configs: List<ChannelConfig>): FeishuDispatch {
    return if ("encrypt" in payload) {
        FeishuDispatch.Rejected(HttpStatusCode.NotImplemented, "encrypted callbacks are unsupported")
    } else {
        resolveAuthenticatedFeishuPayload(payload, configs)
    }
}

private fun resolveAuthenticatedFeishuPayload(payload: JsonObject, configs: List<ChannelConfig>): FeishuDispatch {
    val header = payload["header"] as? JsonObject
    val token = (header?.get("token") as? JsonPrimitive)?.contentOrNull
        ?: (payload["token"] as? JsonPrimitive)?.contentOrNull
    val config = WebhookAuthPolicy.selectFeishuConfig(configs, token)
    val challenge = (payload["challenge"] as? JsonPrimitive)?.contentOrNull
    return when {
        config == null -> FeishuDispatch.Rejected(HttpStatusCode.Unauthorized, "unauthorized")
        !challenge.isNullOrBlank() -> FeishuDispatch.Challenge(challenge)
        payload["event"] !is JsonObject -> FeishuDispatch.Acknowledge
        else -> resolveFeishuEvent(payload, header, config, payload["event"] as JsonObject)
    }
}

private fun resolveFeishuEvent(payload: JsonObject, header: JsonObject?, config: ChannelConfig, event: JsonObject): FeishuDispatch {
    val eventId = sequenceOf(
        (header?.get("event_id") as? JsonPrimitive)?.contentOrNull,
        (payload["event_id"] as? JsonPrimitive)?.contentOrNull,
        (payload["uuid"] as? JsonPrimitive)?.contentOrNull,
    ).filterNotNull().firstOrNull().orEmpty()
    val createTime = sequenceOf(
        (header?.get("create_time") as? JsonPrimitive)?.contentOrNull,
        (payload["ts"] as? JsonPrimitive)?.contentOrNull,
    ).filterNotNull().firstOrNull()
    val sender = event["sender"] as? JsonObject
    val senderType = sender?.get("sender_type")?.jsonPrimitive?.contentOrNull
    val from = sender?.get("sender_id")?.jsonObject?.get("open_id")?.jsonPrimitive?.contentOrNull.orEmpty()
    val message = event["message"] as? JsonObject
    // v2.6.0 (AUD-CHANNEL-F8): 与长连接路径统一解析 —— 原实现直接取 content
    // 原始串({"text":"你好"})当正文,模型收到 JSON 并可能复读。
    val text = io.zer0.muse.channel.FeishuReceiver.parseFeishuMessageText(
        content = message?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty(),
        messageType = message?.get("message_type")?.jsonPrimitive?.contentOrNull.orEmpty(),
    )
    val validMetadata = eventId.isNotBlank() && WebhookAuthPolicy.isFreshFeishuEvent(createTime)
    val senderIsUser = senderType == null || senderType == "user"
    return when {
        !validMetadata -> FeishuDispatch.Rejected(HttpStatusCode.Forbidden, "invalid event metadata")
        !senderIsUser -> FeishuDispatch.Acknowledge
        else -> FeishuDispatch.Inbound(
            source = ChannelInbox.Source(ChannelPlatform.FEISHU.name, from, config.id, eventId),
            text = text,
            rawPayload = WebhookAuthPolicy.sanitizeFeishuPayloadForStorage(payload),
        )
    }
}

internal fun resolveQqPayload(
    config: ChannelConfig,
    payload: JsonObject,
    body: String,
    signature: String?,
    timestamp: String?,
): QqDispatch {
    return if (payload["op"]?.jsonPrimitive?.contentOrNull == "13") {
        resolveQqChallenge(config, payload)
    } else {
        resolveQqEvent(config, payload, body, signature, timestamp)
    }
}

private fun resolveQqChallenge(config: ChannelConfig, payload: JsonObject): QqDispatch {
    val challenge = payload["d"]?.jsonObject
    val plainToken = challenge?.get("plain_token")?.jsonPrimitive?.contentOrNull.orEmpty()
    val eventTimestamp = challenge?.get("event_ts")?.jsonPrimitive?.contentOrNull.orEmpty()
    return if (WebhookAuthPolicy.isValidQqChallenge(plainToken, eventTimestamp, System.currentTimeMillis() / 1000)) {
        QqDispatch.Challenge(
            plainToken,
            WebhookSignatures.signChallenge(config.appSecret, eventTimestamp, plainToken),
        )
    } else {
        QqDispatch.Rejected(HttpStatusCode.BadRequest, "invalid challenge")
    }
}

private fun resolveQqEvent(config: ChannelConfig, payload: JsonObject, body: String, signature: String?, timestamp: String?): QqDispatch {
    val rejection = when {
        signature.isNullOrBlank() || timestamp.isNullOrBlank() ->
            QqDispatch.Rejected(HttpStatusCode.Unauthorized, "missing signature")
        !WebhookAuthPolicy.verifyQq(config, timestamp, body, signature) ->
            QqDispatch.Rejected(HttpStatusCode.Forbidden, "forbidden")
        else -> null
    }
    return if (rejection != null) rejection else buildQqInbound(config, payload, body)
}

private fun buildQqInbound(config: ChannelConfig, payload: JsonObject, body: String): QqDispatch {
    val data = payload["d"] as? JsonObject
    val from = data?.get("author")?.jsonObject?.get("user_openid")?.jsonPrimitive?.contentOrNull
        ?: data?.get("group_openid")?.jsonPrimitive?.contentOrNull
    val text = data?.get("content")?.jsonPrimitive?.contentOrNull
    val eventId = sequenceOf(
        payload["id"]?.jsonPrimitive?.contentOrNull,
        payload["event_id"]?.jsonPrimitive?.contentOrNull,
        data?.get("id")?.jsonPrimitive?.contentOrNull,
    ).filterNotNull().firstOrNull().orEmpty()
    return if (eventId.isBlank()) {
        QqDispatch.Rejected(HttpStatusCode.BadRequest, "invalid event metadata")
    } else {
        QqDispatch.Inbound(
            source = ChannelInbox.Source(ChannelPlatform.QQ.name, from.orEmpty(), config.id, eventId),
            text = text,
            rawPayload = body,
        )
    }
}
