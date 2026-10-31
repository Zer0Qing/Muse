package io.zer0.muse.web

/**
 * 命令层可直接抛出的协议错误。
 *
 * serve 循环捕获它并回一个 `type = "error"` 事件,消息文本即 error 字段。
 */
internal class HostProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
