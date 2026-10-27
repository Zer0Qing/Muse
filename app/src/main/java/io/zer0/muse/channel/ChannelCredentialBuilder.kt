package io.zer0.muse.channel

/**
 * 用设置页当前输入值拼出一个凭证对象，用于校验「能不能保存」。
 *
 * 设置页在用户还没点保存时，字段是分散的局部状态，没有完整的 ChannelConfig；
 * 这个函数让校验逻辑仍然由凭证类型自己负责，而不是在 UI 里再写一遍 when。
 */
fun ChannelPlatform.buildCredentials(
    appId: String,
    secret: String,
    robotCode: String = "",
    apiBaseUrl: String = "",
    region: String = "cn",
): ChannelCredentials = when (this) {
    ChannelPlatform.FEISHU -> ChannelCredentials.Feishu(
        appId = appId,
        appSecret = secret,
        region = region.ifBlank { "cn" },
    )
    ChannelPlatform.QQ -> ChannelCredentials.Qq(appId = appId, appSecret = secret)
    ChannelPlatform.DINGTALK -> ChannelCredentials.DingTalk(
        appId = appId,
        appSecret = secret,
        robotCode = robotCode,
        apiBase = apiBaseUrl,
    )
    ChannelPlatform.WECLAW -> ChannelCredentials.WeClaw(botToken = secret)
    ChannelPlatform.TELEGRAM -> ChannelCredentials.Telegram(botToken = secret)
}
