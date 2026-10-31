package io.zer0.muse.channel

import io.zer0.common.AppJson
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * QQ 开放平台发送失败的**可读化**。
 *
 * 为什么单独抽出来：发送失败以前只有一句 `HTTP 400: {...}`，
 * 用户看到的只是「它不回我」，完全无从判断是超时、没进群、还是没权限。
 * 这三个原因的排查方向完全不同，必须分开说。
 *
 * 只映射有公开文档依据的错误码，其余原样透传响应片段 ——
 * 编造码含义比不解释更糟。
 */
object QqSendErrorMapper {

    /** 问题出在「太晚了」，而不是配置错了 —— 重试无意义。 */
    const val CODE_PASSIVE_EXCEEDED = 40034128

    /** 机器人根本不在这个群里。 */
    const val CODE_BOT_NOT_IN_GROUP = 40034101

    /** 没有主动消息权限（群聊场景基本都没有）。 */
    const val CODE_NO_ACTIVE_PERMISSION = 40034102

    /** 主动消息发送异常（频控 / 配额）。 */
    const val CODE_ACTIVE_FAILED = 40034105

    /**
     * 从异常文本里抠出 QQ 错误码。
     *
     * [raw] 形如 `HTTP 400: {"code":40034102,"message":"主动消息失败, 无权限"}`；
     * 也兼容响应体里只有 `err_code` 的写法。
     */
    fun extractCode(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        // 只有带 “HTTP 400: ” 这类前缀时才剥一层；否则纯 JSON 会在第一个冒号上被切碎。
        val body = if (text.startsWith("HTTP", ignoreCase = true)) text.substringAfter(':', text) else text
        val jsonStart = body.indexOf('{')
        return if (text.isEmpty() || jsonStart < 0) {
            null
        } else {
            runCatching {
                val obj = AppJson.parseToJsonElement(body.substring(jsonStart)).jsonObject
                (obj["code"] ?: obj["err_code"])?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            }.getOrNull()
        }
    }

    /**
     * 把失败原因翻成用户能照它排查的一句话。
     *
     * 区分「本次太晚」与「配置不对」：前者下一次很快就可能成功，
     * 后者不改配置永远失败 —— 这两种给用户的建议必须不同。
     */
    fun describe(raw: String?, isGroup: Boolean): String {
        val code = extractCode(raw)
        return when (code) {
            CODE_PASSIVE_EXCEEDED ->
                "回复得太晚了：QQ 只允许在收到消息后 5 分钟内回复" +
                    if (isGroup) "，群聊且不支持主动消息" else ""
            CODE_BOT_NOT_IN_GROUP -> "机器人不在这个群里，请先把机器人拉进群"
            CODE_NO_ACTIVE_PERMISSION -> "该场景没有主动消息权限（群聊不支持主动推送）"
            CODE_ACTIVE_FAILED -> "主动消息发送失败（频控或配额已用完）"
            else -> raw?.take(200)?.takeIf { it.isNotBlank() } ?: "未知错误"
        }
    }

    /**
     * **这一条**消息再重试也发不出去 —— 与「配置永久坏了」不是一回事。
     *
     * 两种都算永久，但含义不同：
     *  - 配置类（[CODE_BOT_NOT_IN_GROUP] / [CODE_NO_ACTIVE_PERMISSION]）：不改配置永远失败；
     *  - 被动窗口过期（[CODE_PASSIVE_EXCEEDED]）：时间只会更晚，这条消息确实无救，
     *    但用户下一条新消息仍能正常回复。
     *
     * 所以调用方拿到 true 应当把**当前入站**收成终态（不再重试），
     * 而不是把渠道标记为不可用。
     */
    fun isPermanent(raw: String?): Boolean = when (extractCode(raw)) {
        CODE_BOT_NOT_IN_GROUP, CODE_NO_ACTIVE_PERMISSION, CODE_PASSIVE_EXCEEDED -> true
        else -> false
    }
}
