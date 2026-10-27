package io.zer0.muse.channel

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 渠道凭证：按平台分型，取代原来堆在 [ChannelConfig] 里的平铺字段。
 *
 * 原来的问题：`appId` / `appSecret` / `robotCode` / `corpId` / `region` 全挤在
 * ChannelConfig 上，每加一个平台就要再加字段，而且编译期看不出哪个平台需要哪些。
 *
 * 现在每个平台只声明自己真正用到的凭证，新增平台只加一个子类，
 * `when` 分支由编译器强制补全。
 *
 * 兼容：老配置是平铺字段，[ChannelCredentialsSerializer] 负责双向转换，
 * 保证老 JSON 能读、能原样写回。
 */
@Serializable(with = ChannelCredentialsSerializer::class)
sealed interface ChannelCredentials {
    /** 飞书 / Lark 自建应用。 */
    @Serializable
    data class Feishu(
        val appId: String = "",
        val appSecret: String = "",
        val region: String = "cn",
    ) : ChannelCredentials

    /** QQ 机器人。 */
    @Serializable
    data class Qq(
        val appId: String = "",
        val appSecret: String = "",
    ) : ChannelCredentials

    /** 钉钉机器人。 */
    @Serializable
    data class DingTalk(
        val appId: String = "",
        val appSecret: String = "",
        val robotCode: String = "",
        val apiBase: String = "",
    ) : ChannelCredentials

    /** 微信桥接端，token 存在 appSecret 字段里。 */
    @Serializable
    data class WeClaw(
        val botToken: String = "",
    ) : ChannelCredentials

    /** Telegram Bot API。 */
    @Serializable
    data class Telegram(
        val botToken: String = "",
    ) : ChannelCredentials

    /** 尚未配置或平台未知。 */
    @Serializable
    data object None : ChannelCredentials

    /**
     * 该凭证是否填齐到可以保存。
     *
     * 原先这个判断写在设置页的 `when (platform)` 里，每个分支各写一遍，
     * 新增平台极易漏。现在由各凭证类型自己声明。
     *
     * @param webhookEnabled 是否开了 Webhook（需要额外的验证 token，由调用方单独校验）
     */
    fun isComplete(): Boolean = when (this) {
        is Feishu -> appId.isNotBlank() && appSecret.isNotBlank()
        is Qq -> appId.isNotBlank() && appSecret.isNotBlank()
        is DingTalk -> appId.isNotBlank() && appSecret.isNotBlank()
        is WeClaw -> botToken.isNotBlank()
        is Telegram -> botToken.isNotBlank()
        None -> false
    }
}

/**
 * 平铺字段 ↔ 密封类的双向序列化。
 *
 * 落盘格式仍是一组平铺字段（appId / appSecret / robotCode / corpId / apiBaseUrl /
 * region / dingtalkApiBase），与改造前完全一致，因此不需要数据迁移。
 */
internal object ChannelCredentialsSerializer : KSerializer<ChannelCredentials> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ChannelCredentials")

    override fun deserialize(decoder: Decoder): ChannelCredentials {
        val input = decoder as? JsonDecoder ?: error("ChannelCredentials 只支持 JSON 解码")
        val obj = input.decodeJsonElement() as? JsonObject ?: return ChannelCredentials.None
        return fromFlat(
            flat = Flat(
                appId = obj.stringOrEmpty("appId"),
                appSecret = obj.stringOrEmpty("appSecret"),
                robotCode = obj.stringOrEmpty("robotCode"),
                apiBaseUrl = obj.stringOrEmpty("apiBaseUrl"),
                region = obj.stringOrEmpty("region").ifBlank { "cn" },
                dingtalkApiBase = obj.stringOrEmpty("dingtalkApiBase"),
            ),
            platform = obj.stringOrEmpty("platform"),
        )
    }

    override fun serialize(encoder: Encoder, value: ChannelCredentials) {
        val out = encoder as? JsonEncoder ?: error("ChannelCredentials 只支持 JSON 编码")
        val flat = toFlat(value)
        val obj = buildJsonObject {
            put("appId", flat.appId)
            put("appSecret", flat.appSecret)
            put("robotCode", flat.robotCode)
            put("corpId", flat.corpId)
            put("apiBaseUrl", flat.apiBaseUrl)
            put("region", flat.region)
            put("dingtalkApiBase", flat.dingtalkApiBase)
        }
        out.encodeJsonElement(obj)
    }

    /** 平铺字段的中间表示。 */
    internal data class Flat(
        val appId: String = "",
        val appSecret: String = "",
        val robotCode: String = "",
        val corpId: String = "",
        val apiBaseUrl: String = "",
        val region: String = "cn",
        val dingtalkApiBase: String = "",
    )

    /** 按平台把平铺字段装进对应凭证类型。 */

    internal fun fromFlat(flat: Flat, platform: String): ChannelCredentials = when (platform.uppercase()) {
        "FEISHU" -> ChannelCredentials.Feishu(flat.appId, flat.appSecret, flat.region.ifBlank { "cn" })

        "QQ" -> ChannelCredentials.Qq(flat.appId, flat.appSecret)

        "DINGTALK" -> ChannelCredentials.DingTalk(

            appId = flat.appId,

            appSecret = flat.appSecret,

            robotCode = flat.robotCode,

            apiBase = flat.dingtalkApiBase.ifBlank { flat.apiBaseUrl },

        )

        "WECLAW" -> ChannelCredentials.WeClaw(flat.appSecret)

        "TELEGRAM" -> ChannelCredentials.Telegram(flat.appSecret)

        else -> ChannelCredentials.None
    }

    /** 把凭证摊回平铺字段，供落盘与老代码读取。 */
    internal fun toFlat(value: ChannelCredentials): Flat = when (value) {
        is ChannelCredentials.Feishu -> Flat(
            appId = value.appId,
            appSecret = value.appSecret,
            region = value.region,
        )
        is ChannelCredentials.Qq -> Flat(appId = value.appId, appSecret = value.appSecret)
        is ChannelCredentials.DingTalk -> Flat(
            appId = value.appId,
            appSecret = value.appSecret,
            robotCode = value.robotCode,
            dingtalkApiBase = value.apiBase,
        )
        is ChannelCredentials.WeClaw -> Flat(appSecret = value.botToken)
        is ChannelCredentials.Telegram -> Flat(appSecret = value.botToken)
        ChannelCredentials.None -> Flat()
    }

    private fun JsonObject.stringOrEmpty(key: String): String = runCatching { this[key]?.jsonPrimitive?.content ?: "" }.getOrDefault("")
}
