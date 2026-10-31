package io.zer0.muse.miniphone.data.db

/**
 * 一个动作的来源方:本机用户、手机里的角色,或手机自己。
 *
 * 私信发送者、动态点赞者、动态评论者共用这一套取值,落库时统一存小写字符串。
 * [SYSTEM] 只用于私信里的系统提示(例如对方没有回应),不属于任何一方。
 */
enum class MpActorType(
    val value: String,
) {
    USER("user"),
    CHARACTER("character"),
    SYSTEM("system"),
    ;

    companion object {
        fun fromValue(value: String): MpActorType = entries.firstOrNull { it.value == value } ?: USER
    }
}

/** 消息类型。 */
enum class MpMessageType(
    val value: String,
) {
    TEXT("text"),
    IMAGE("image"),
    VOICE("voice"),
    SYSTEM("system"),
    ;

    companion object {
        fun fromValue(value: String): MpMessageType = entries.firstOrNull { it.value == value } ?: TEXT
    }
}

/**
 * 一条私信的发送状态。
 *
 * 库里存整数(而不是枚举名),既有的行没有这一列,迁移时统一按「已发出」补齐:
 * 老数据都是发出过的消息,把它们标成发送中会让界面上凭空多出一排进行态。
 */
enum class MpMessageStatus(
    val value: Int,
) {
    SENT(0),
    SENDING(1),
    FAILED(2),
    ;

    companion object {
        fun fromValue(value: Int): MpMessageStatus = entries.firstOrNull { it.value == value } ?: SENT
    }
}

/** 动态的来源:角色生成的,还是本机用户自己发的。 */
enum class MpMomentSource(
    val value: String,
) {
    AI("ai"),
    USER("user"),
    ;

    companion object {
        fun fromValue(value: String): MpMomentSource = entries.firstOrNull { it.value == value } ?: USER
    }
}

/** 相册条目的来源:角色生成图,或用户从外部导入。 */
enum class MpAlbumSource(
    val value: String,
) {
    AI("ai"),
    IMPORTED("imported"),
    ;

    companion object {
        fun fromValue(value: String): MpAlbumSource = entries.firstOrNull { it.value == value } ?: IMPORTED
    }
}
