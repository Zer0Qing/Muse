package io.zer0.muse.channel

import kotlinx.serialization.Serializable

/**
 * v1.0.92: 渠道平台 — 外部 IM 接入的消息渠道类型。
 *
 * v1 支持发送侧(把 Muse 的消息推到平台);接收侧与深度对接分步推进:
 *  - FEISHU  飞书(开放平台自建应用,tenant_access_token + im/v1 发送)
 *  - QQ      QQ 开放平台机器人(AppID/AppSecret 换 access_token + v2 发送)
 *  - WECLAW  微信 ClawBot(iLink 协议,2026-03 官方开放;扫码绑定 + 长轮询收发)
 *  - TELEGRAM Telegram 机器人(@BotFather 创建,Bot Token + 长轮询收发)
 *  - DINGTALK 钉钉机器人(开放平台应用,Stream 长连接接收 + sessionWebhook/OpenAPI 发送)
 */
@Serializable
enum class ChannelPlatform { FEISHU, QQ, WECLAW, TELEGRAM, DINGTALK }

/**
 * v1.0.92: 单条渠道配置。
 *
 * 凭据字段(APP Secret)在持久化前经 SecureKeyStore 加密,内存中为明文。
 */
@Serializable
data class ChannelConfig(
    val id: String,
    val platform: ChannelPlatform,
    val name: String = "",
    val enabled: Boolean = true,
    /** 平台应用凭据(飞书 App ID / QQ 机器人 AppID)。 */
    val appId: String = "",
    /** 平台应用密钥(飞书 App Secret / QQ AppSecret),存储时加密。 */
    val appSecret: String = "",
    /** 发送目标:飞书 chat_id / open_id;QQ group_openid / user_openid。 */
    val targetId: String = "",
    /** QQ 目标类型:"group" 群聊(默认) 或 "c2c" 单聊;飞书按 targetId 类型自动判定。 */
    val targetType: String = "group",
    /**
     * v2.0.1: 钉钉 Robot Code — 发送回复用;企业内部机器人通常与 Client ID(AppKey) 相同,留空回退 [appId]。
     */
    val robotCode: String = "",
    /**
     * v2.0.1: 钉钉企业标识 Corp ID(企业信息中获取;部分企业级接口需要,先随配置存储)。
     */
    val corpId: String = "",
    /**
     * v2.0.1: 钉钉 API Base URL — 留空用官方默认(https://api.dingtalk.com/v1.0),可指向兼容网关。
     */
    val apiBaseUrl: String = "",
    /**
     * v2.0.1: 飞书区域 — "cn" 中国版(open.feishu.cn) / "intl" 国际版 Lark(open.larksuite.com)。
     */
    val region: String = "cn",
    /**
     * v2.0: 自动回复 — 收到该平台入站消息时自动跑一轮并回发到消息来源。
     * 默认关闭,避免未预期时消耗模型额度。
     */
    val autoReply: Boolean = false,
    /**
     * v2.0.1: 自动回复绑定的助手 id — 空串表示默认助手;继承其人设/模型/参数。
     */
    val assistantId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** v2.0.1: 钉钉 API Base — 留空回退官方默认;去除尾斜杠避免拼接问题。 */
    val dingtalkApiBase: String
        get() = apiBaseUrl.trim().trimEnd('/').ifBlank { DINGTALK_DEFAULT_API_BASE }
}

/** v2.0.1: 钉钉 API 默认 Base URL。 */
internal const val DINGTALK_DEFAULT_API_BASE = "https://api.dingtalk.com/v1.0"

/** v1.0.92: 渠道发送结果。 */
data class ChannelSendResult(
    val ok: Boolean,
    val detail: String,
)
