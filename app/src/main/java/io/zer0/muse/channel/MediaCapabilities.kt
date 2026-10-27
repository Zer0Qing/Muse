package io.zer0.muse.channel

/**
 * 渠道媒体能力声明。
 *
 * 问题背景：此前每个渠道对媒体的支持程度只存在于实现细节里 ——
 * 飞书收图写死 `"[图片]"`、QQ 只能发文本、钉钉根本不处理图片。
 * 上层没有任何地方能查到「这个渠道到底能不能发图、能发多大、用什么方式发」，
 * 于是每个调用点各自 `when (platform)`，加渠道就漏一处。
 *
 * 这里把能力做成结构化声明，并强制校验：
 *  - 支持的每种媒体都必须声明投递方式
 *  - 每种输入方式都必须给出字节上限
 * 声明不完整在初始化时就会抛错，不会等到用户发图时才失败。
 */
data class MediaCapabilities(
    /** 这个渠道接收/发送媒体时支持的输入方式。 */
    val inputModes: Set<MediaInputMode>,
    /** 支持哪些媒体类型。 */
    val supportedKinds: Set<MediaKind>,
    /**
     * 每种输入方式下、每种媒体类型的大小上限（字节）。
     *
     * 同一张图走内存缓冲和走 URL 的上限往往不同，必须分开声明。
     */
    val maxBytes: Map<MediaInputMode, Map<MediaKind, Long>>,
    /** 每种媒体类型用什么方式投递。 */
    val deliveryByKind: Map<MediaKind, DeliveryMode>,
    /**
     * 是否必须带「最近一次来信」的上下文才能回复。
     *
     * 微信桥接端属于这种：只能对刚发来消息的人回复，不能凭空开新会话。
     */
    val requiresReplyContext: Boolean,
) {
    /** 结构自检。声明不完整直接抛错，不留给运行时。 */
    fun validate(platform: String) {
        require(inputModes.isNotEmpty()) { "$platform: mediaCapabilities.inputModes 不能为空" }
        require(supportedKinds.isNotEmpty()) { "$platform: mediaCapabilities.supportedKinds 不能为空" }
        supportedKinds.forEach { kind ->
            require(deliveryByKind.containsKey(kind)) {
                "$platform: mediaCapabilities.deliveryByKind 缺少 $kind"
            }
        }
        inputModes.forEach { mode ->
            val perKind = maxBytes[mode]
            requireNotNull(perKind) { "$platform: mediaCapabilities.maxBytes 缺少输入方式 $mode" }
            supportedKinds.forEach { kind ->
                require((perKind[kind] ?: 0L) > 0L) {
                    "$platform: mediaCapabilities.maxBytes[$mode] 缺少 $kind 的上限"
                }
            }
        }
    }

    /** 查询某个输入方式下、某种媒体的上限；不支持返回 0。 */
    fun limitFor(mode: MediaInputMode, kind: MediaKind): Long = maxBytes[mode]?.get(kind) ?: 0L

    /** 是否支持某种媒体。 */
    fun supports(kind: MediaKind): Boolean = kind in supportedKinds
}

/** 媒体的输入方式。 */
enum class MediaInputMode {
    /** 内存字节流（本地读取后直接上传）。 */
    BUFFER,

    /** 本地文件路径（部分平台只接受文件，不接受内存字节）。 */
    LOCAL_FILE,

    /** 需要鉴权的远端地址（平台自己去拉）。 */
    REMOTE_URL,

    /** 公开可访问的地址。 */
    PUBLIC_URL,
}

/** 媒体类型。 */
enum class MediaKind {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
}

/** 投递方式：决定上层怎么把媒体交给平台。 */
enum class DeliveryMode {
    /** 平台原生图片消息。 */
    NATIVE_IMAGE,

    /** 平台原生视频消息。 */
    NATIVE_VIDEO,

    /** 平台原生音频消息。 */
    NATIVE_AUDIO,

    /** 平台原生文件消息。 */
    NATIVE_DOCUMENT,

    /** 只能作为链接发送（平台不支持原生媒体）。 */
    LINK_ONLY,
}

/** 常用字节常量，避免各渠道重复写字面量。 */
internal object MediaLimits {
    const val MB = 1024L * 1024L
}
