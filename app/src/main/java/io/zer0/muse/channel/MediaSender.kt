package io.zer0.muse.channel

/**
 * 出站媒体发送的统一入口。
 *
 * 只负责按平台分发，具体实现在各平台的独立文件里。
 * 这样每个文件职责单一，加平台不用动这里之外的地方。
 */
internal object MediaSender {
    /**
     * 发送媒体。调用前应先过 [ChannelPlatform.checkMediaSendable]，这里会再查一次。
     */
    suspend fun send(config: ChannelConfig, media: OutboundMedia, targetOverride: String? = null): Result<Unit> {
        config.platform.checkMediaSendable(media)?.let {
            return Result.failure(IllegalArgumentException(it))
        }
        return when (config.platform) {
            ChannelPlatform.TELEGRAM -> TelegramMediaSender.send(config, media, targetOverride)
            ChannelPlatform.FEISHU -> FeishuMediaSender.send(config, media, targetOverride)
            ChannelPlatform.QQ -> QqMediaSender.send(config, media, targetOverride)
            ChannelPlatform.DINGTALK -> DingtalkMediaSender.send(config, media, targetOverride)
            ChannelPlatform.WECLAW -> Result.failure(
                UnsupportedOperationException(
                    "微信渠道暂不支持发送${media.kind.name.lowercase()}，目前只能收发文字",
                ),
            )
        }
    }
}
