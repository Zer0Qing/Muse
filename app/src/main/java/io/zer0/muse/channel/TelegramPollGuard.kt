package io.zer0.muse.channel

/**
 * Telegram 轮询失败的日志文本（纯函数，好测）。
 *
 * 为什么单独抽出来：`TelegramReceiver.pollOnce` 以前把拉取失败当成"这轮没有新消息"
 * 直接 `getOrNull() ?: return`，**一句日志都没有** —— 而它坏起来的用户观感是
 * 「从 Telegram 发消息，Muse 永远不回」。同函数里图片失败、写库失败都有日志，
 * 偏偏最要命的这条没有，所以补日志；补日志又必须能测，就抽成纯函数。
 *
 * ## 顺带堵住一个泄漏
 *
 * Telegram 的错误文本经常带请求 URL，而 URL 形如
 * `https://api.telegram.org/bot123456:AAA-BBB…/getUpdates` —— **令牌在路径里，不在查询串里**。
 * `Logger` 的脱敏认得 `token=…`、`?key=…` 这类形状，但认不出 `/bot<token>/`。
 * 日志文件是要从真机导出给外人看的，所以这里先把令牌段换成 `bot<已隐藏>`。
 */
internal fun telegramPollIssue(channelId: String, error: Throwable?): String {
    val raw = error?.message ?: error?.javaClass?.simpleName ?: "未知错误"
    val masked = BOT_TOKEN_IN_PATH.replace(raw) { "bot<已隐藏>/" }
    return "Telegram 拉取更新失败(渠道 $channelId)：${masked.take(MAX_ISSUE_CHARS)}"
}

/** `/bot<数字>:<令牌>/` 形式的路径段（Telegram Bot API 的 URL 形状）。 */
private val BOT_TOKEN_IN_PATH = Regex("/bot\\d+:[^/\\s]+/")

/** 日志里一条原因最多留多少字符（上游错误体可能整段是 HTML）。 */
private const val MAX_ISSUE_CHARS = 300
