package io.zer0.muse.channel

/**
 * 飞书长连接**写入结果**的判定（心跳与事件 ACK 共用）。
 *
 * 为什么要单独抽出来：OkHttp 的 `WebSocket.send()` 失败方式是**返回 false**，
 * 不抛异常。原来两处都写成 `runCatching { webSocket.send(...) }`，
 * 于是"返回 false"这一路连异常都拿不到、直接被丢掉 ——
 * 心跳发不出去意味着服务端会按超时把这条连接判死，期间**用户发的消息根本进不来**，
 * 而客户端日志里一片安静；ACK 发不出去意味着平台会**重投事件**（重复回复的成因之一）。
 *
 * 抽成纯函数是因为这两条判定要能被测，嵌在监听器回调里就不可达。
 */

/**
 * 一次 WS 写入出了什么问题；返回 null 表示确实发出去了。
 *
 * @param sent `WebSocket.send()` 的返回值（false = 没进缓冲区）
 * @param error 调用本身抛出的异常（构造帧失败等），没有则传 null
 */
internal fun feishuSendIssue(sent: Boolean, error: Throwable?): String? = when {
    error != null -> "调用抛异常: ${error.message ?: error.javaClass.simpleName}"
    !sent -> "send() 返回 false（连接已关闭或发送缓冲被拒）"
    else -> null
}

/**
 * 连续几次心跳失败就判定这条连接已废。
 *
 * 一次可能只是瞬时缓冲满；**两次**说明 socket 已经不可写却还没收到 `onFailure`，
 * 继续干等只会让"看似已连接、实则收不到消息"的时间变长。
 */
internal const val PING_FAILURES_BEFORE_RECONNECT = 2

/** 是否该因为心跳失败而放弃当前连接、走重连。 */
internal fun shouldReconnectOnPingFailure(consecutiveFailures: Int): Boolean = consecutiveFailures >= PING_FAILURES_BEFORE_RECONNECT
