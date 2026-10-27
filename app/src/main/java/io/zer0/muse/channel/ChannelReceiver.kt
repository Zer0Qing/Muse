package io.zer0.muse.channel

/**
 * 渠道接收端的统一契约。
 *
 * 五个平台的接收实现（微信广播、飞书/QQ/钉钉长连接、Telegram 轮询）
 * 此前各自独立，但生命周期方法其实已经一致：都是 `restart()` / `stop()`。
 * 这里把这个既有约定显式声明出来，让注册与生命周期管理只依赖接口，
 * 不再逐个 when (platform) 分支。
 *
 * 注意：这一步只统一生命周期，不改任何收发行为。
 * 入站消息仍然各自归一化成 [ChannelInbox.Inbound] 后交给上层。
 */
interface ChannelReceiver {
    /** 该接收端对应的平台。 */
    val platform: ChannelPlatform

    /**
     * 启动或重启接收。
     *
     * 允许重复调用：内部应自行停止旧连接再建立新的。
     */
    fun restart()

    /** 停止接收并释放资源。允许重复调用。 */
    fun stop()
}
