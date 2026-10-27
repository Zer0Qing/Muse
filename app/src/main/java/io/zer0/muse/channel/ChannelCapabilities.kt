package io.zer0.muse.channel

/**
 * 渠道能力声明。
 *
 * 原来上层判断「这个平台能不能主动发 / 有没有正在输入」只能写
 * `when (platform)`，加平台就得改所有判断点，且容易漏。
 *
 * 把能力变成数据后，UI 和调度层只读这张表，新增平台只补一条。
 */
data class ChannelCapabilities(
    /** 能否主动发起消息（不需要对方先说话）。 */
    val proactive: Boolean,
    /** 是否支持「正在输入」指示。 */
    val typing: Boolean,
    /** 是否支持媒体消息。 */
    val media: Boolean,
    /** 是否支持消息回执/已读。 */
    val receipt: Boolean,
    /**
     * 单条消息是否必须带上「最近一次来信」的上下文才能回复。
     *
     * 微信桥接端属于这种：只能对刚发来消息的人回复，不能凭空开新会话。
     */
    val requiresRecentInbound: Boolean,
    /** 媒体能力表：输入方式、支持类型、字节上限、投递方式。 */
    val mediaCapabilities: MediaCapabilities,
)

/** 各平台的能力表。新增平台在这里补一条即可。 */
val ChannelPlatform.capabilities: ChannelCapabilities
    get() = when (this) {
        ChannelPlatform.FEISHU -> ChannelCapabilities(
            proactive = true,
            typing = false,
            media = true,
            receipt = true,
            requiresRecentInbound = false,
            mediaCapabilities = FEISHU_MEDIA,
        )
        ChannelPlatform.QQ -> ChannelCapabilities(
            proactive = true,
            typing = false,
            media = true,
            receipt = false,
            requiresRecentInbound = false,
            mediaCapabilities = QQ_MEDIA,
        )
        ChannelPlatform.DINGTALK -> ChannelCapabilities(
            proactive = true,
            typing = false,
            media = true,
            receipt = false,
            requiresRecentInbound = false,
            mediaCapabilities = DINGTALK_MEDIA,
        )
        ChannelPlatform.TELEGRAM -> ChannelCapabilities(
            proactive = true,
            typing = true,
            media = true,
            receipt = false,
            requiresRecentInbound = false,
            mediaCapabilities = TELEGRAM_MEDIA,
        )
        // 微信桥接端只能回复刚来信的人，不能主动开新会话。
        ChannelPlatform.WECLAW -> ChannelCapabilities(
            proactive = false,
            typing = false,
            media = true,
            receipt = false,
            requiresRecentInbound = true,
            mediaCapabilities = WECLAW_MEDIA,
        )
    }

// ── 各渠道媒体能力表 ────────────────────────────────────────────────
// 数值取自各平台官方接口的硬限制；未明确写明的取保守值。

/** 飞书：im/v1/images 上传，单图 10MB；文件走 im/v1/files。 */
private val FEISHU_MEDIA = MediaCapabilities(
    inputModes = setOf(MediaInputMode.BUFFER, MediaInputMode.LOCAL_FILE, MediaInputMode.PUBLIC_URL),
    supportedKinds = setOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.DOCUMENT),
    maxBytes = mapOf(
        MediaInputMode.BUFFER to mapOf(
            MediaKind.IMAGE to 10 * MediaLimits.MB,
            MediaKind.VIDEO to 30 * MediaLimits.MB,
            MediaKind.AUDIO to 30 * MediaLimits.MB,
            MediaKind.DOCUMENT to 30 * MediaLimits.MB,
        ),
        MediaInputMode.LOCAL_FILE to mapOf(
            MediaKind.IMAGE to 10 * MediaLimits.MB,
            MediaKind.VIDEO to 30 * MediaLimits.MB,
            MediaKind.AUDIO to 30 * MediaLimits.MB,
            MediaKind.DOCUMENT to 30 * MediaLimits.MB,
        ),
        MediaInputMode.PUBLIC_URL to mapOf(
            MediaKind.IMAGE to 10 * MediaLimits.MB,
            MediaKind.VIDEO to 30 * MediaLimits.MB,
            MediaKind.AUDIO to 30 * MediaLimits.MB,
            MediaKind.DOCUMENT to 30 * MediaLimits.MB,
        ),
    ),
    deliveryByKind = mapOf(
        MediaKind.IMAGE to DeliveryMode.NATIVE_IMAGE,
        MediaKind.VIDEO to DeliveryMode.NATIVE_VIDEO,
        MediaKind.AUDIO to DeliveryMode.NATIVE_AUDIO,
        MediaKind.DOCUMENT to DeliveryMode.NATIVE_DOCUMENT,
    ),
    requiresReplyContext = false,
)

/** QQ：富媒体接口需要先上传拿 file_info，再以 msg_type=7 发送。 */
private val QQ_MEDIA = MediaCapabilities(
    inputModes = setOf(MediaInputMode.LOCAL_FILE, MediaInputMode.PUBLIC_URL),
    supportedKinds = setOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.DOCUMENT),
    maxBytes = mapOf(
        MediaInputMode.LOCAL_FILE to mapOf(
            MediaKind.IMAGE to 30 * MediaLimits.MB,
            MediaKind.VIDEO to 30 * MediaLimits.MB,
            MediaKind.AUDIO to 30 * MediaLimits.MB,
            MediaKind.DOCUMENT to 30 * MediaLimits.MB,
        ),
        MediaInputMode.PUBLIC_URL to mapOf(
            MediaKind.IMAGE to 30 * MediaLimits.MB,
            MediaKind.VIDEO to 30 * MediaLimits.MB,
            MediaKind.AUDIO to 30 * MediaLimits.MB,
            MediaKind.DOCUMENT to 30 * MediaLimits.MB,
        ),
    ),
    deliveryByKind = mapOf(
        MediaKind.IMAGE to DeliveryMode.NATIVE_IMAGE,
        MediaKind.VIDEO to DeliveryMode.NATIVE_VIDEO,
        MediaKind.AUDIO to DeliveryMode.NATIVE_AUDIO,
        MediaKind.DOCUMENT to DeliveryMode.NATIVE_DOCUMENT,
    ),
    requiresReplyContext = false,
)

/** 钉钉：机器人消息只原生支持图片；视频与音频降级为链接。 */
private val DINGTALK_MEDIA = MediaCapabilities(
    inputModes = setOf(MediaInputMode.BUFFER, MediaInputMode.PUBLIC_URL),
    supportedKinds = setOf(MediaKind.IMAGE, MediaKind.DOCUMENT),
    maxBytes = mapOf(
        MediaInputMode.BUFFER to mapOf(
            MediaKind.IMAGE to 20 * MediaLimits.MB,
            MediaKind.DOCUMENT to 20 * MediaLimits.MB,
        ),
        MediaInputMode.PUBLIC_URL to mapOf(
            MediaKind.IMAGE to 20 * MediaLimits.MB,
            MediaKind.DOCUMENT to 20 * MediaLimits.MB,
        ),
    ),
    deliveryByKind = mapOf(
        MediaKind.IMAGE to DeliveryMode.NATIVE_IMAGE,
        MediaKind.DOCUMENT to DeliveryMode.NATIVE_DOCUMENT,
    ),
    requiresReplyContext = false,
)

/** Telegram：sendPhoto/sendVideo/sendAudio/sendDocument；bot 上传上限 50MB。 */
private val TELEGRAM_MEDIA = MediaCapabilities(
    inputModes = setOf(MediaInputMode.BUFFER, MediaInputMode.LOCAL_FILE, MediaInputMode.PUBLIC_URL),
    supportedKinds = setOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.DOCUMENT),
    maxBytes = mapOf(
        MediaInputMode.BUFFER to mapOf(
            MediaKind.IMAGE to 10 * MediaLimits.MB,
            MediaKind.VIDEO to 50 * MediaLimits.MB,
            MediaKind.AUDIO to 50 * MediaLimits.MB,
            MediaKind.DOCUMENT to 50 * MediaLimits.MB,
        ),
        MediaInputMode.LOCAL_FILE to mapOf(
            MediaKind.IMAGE to 10 * MediaLimits.MB,
            MediaKind.VIDEO to 50 * MediaLimits.MB,
            MediaKind.AUDIO to 50 * MediaLimits.MB,
            MediaKind.DOCUMENT to 50 * MediaLimits.MB,
        ),
        MediaInputMode.PUBLIC_URL to mapOf(
            MediaKind.IMAGE to 5 * MediaLimits.MB,
            MediaKind.VIDEO to 20 * MediaLimits.MB,
            MediaKind.AUDIO to 20 * MediaLimits.MB,
            MediaKind.DOCUMENT to 20 * MediaLimits.MB,
        ),
    ),
    deliveryByKind = mapOf(
        MediaKind.IMAGE to DeliveryMode.NATIVE_IMAGE,
        MediaKind.VIDEO to DeliveryMode.NATIVE_VIDEO,
        MediaKind.AUDIO to DeliveryMode.NATIVE_AUDIO,
        MediaKind.DOCUMENT to DeliveryMode.NATIVE_DOCUMENT,
    ),
    requiresReplyContext = false,
)

/** 微信桥接端：只能对刚来信的人回复，媒体走 CDN + AES。 */
private val WECLAW_MEDIA = MediaCapabilities(
    inputModes = setOf(MediaInputMode.BUFFER, MediaInputMode.LOCAL_FILE),
    supportedKinds = setOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.DOCUMENT),
    maxBytes = mapOf(
        MediaInputMode.BUFFER to mapOf(
            MediaKind.IMAGE to 10 * MediaLimits.MB,
            MediaKind.VIDEO to 20 * MediaLimits.MB,
            MediaKind.DOCUMENT to 20 * MediaLimits.MB,
        ),
        MediaInputMode.LOCAL_FILE to mapOf(
            MediaKind.IMAGE to 10 * MediaLimits.MB,
            MediaKind.VIDEO to 20 * MediaLimits.MB,
            MediaKind.DOCUMENT to 20 * MediaLimits.MB,
        ),
    ),
    deliveryByKind = mapOf(
        MediaKind.IMAGE to DeliveryMode.NATIVE_IMAGE,
        MediaKind.VIDEO to DeliveryMode.NATIVE_VIDEO,
        MediaKind.DOCUMENT to DeliveryMode.NATIVE_DOCUMENT,
    ),
    requiresReplyContext = true,
)

/**
 * 启动自检：遍历所有平台的媒体能力声明，缺字段直接抛错。
 *
 * 放在这里而不是等运行时，是因为这类错误一旦漏到线上，
 * 表现是「用户发图没反应」，从日志里很难定位。
 */
internal fun validateChannelCapabilities() {
    ChannelPlatform.entries.forEach { platform ->
        platform.capabilities.mediaCapabilities.validate(platform.name)
    }
}
