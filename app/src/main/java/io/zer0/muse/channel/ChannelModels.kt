package io.zer0.muse.channel

import kotlinx.serialization.Serializable

/**
 * v1.0.92: 渠道平台 — 外部 IM 接入的消息渠道类型。
 *
 * v1 支持发送侧(把 Muse 的消息推到平台);接收侧与深度对接分步推进:
 *  - FEISHU  飞书(开放平台自建应用,tenant_access_token + im/v1 发送)
 *  - QQ      QQ 开放平台机器人(AppID/AppSecret 换 access_token + v2 发送)
 *  - WECLAW  微信 ClawBot(iLink 协议,2026-03 官方开放;绑定流程与协议待专项对接)
 */
@Serializable
enum class ChannelPlatform { FEISHU, QQ, WECLAW }

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
    val createdAt: Long = System.currentTimeMillis(),
)

/** v1.0.92: 渠道发送结果。 */
data class ChannelSendResult(
    val ok: Boolean,
    val detail: String,
)
