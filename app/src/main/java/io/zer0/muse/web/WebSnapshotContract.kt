package io.zer0.muse.web

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 网页正文的**快照合同**（对比清单 P2-01「网页冻结正文」）。
 *
 * 浏览器工具返回的是**某一刻**抓到的正文。没有时间戳时，模型会把它当成"当前页面"，
 * 于是在用户已经翻页、登录态失效、内容更新之后，仍然基于旧正文作答 ——
 * 这类错误很难被用户察觉，因为回答看起来完全合理。
 *
 * 所以每次返回正文都带一行头：来源地址 + 抓取时刻 + "这是快照"。
 */
object WebSnapshotContract {

    /** 抓取时刻的展示格式：只到秒，够用且不占宽度。 */
    private const val TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"

    /**
     * @param url 抓取时的页面地址；为空时写"未知"，不猜
     * @param fetchedAtMillis 抓取时刻
     */
    fun header(url: String?, fetchedAtMillis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val formatter = SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).apply { timeZone = zone }
        val stamp = formatter.format(Date(fetchedAtMillis))
        val source = url?.takeIf { it.isNotBlank() } ?: "未知"
        return "--- 网页快照 | url=$source | 抓取于 $stamp | 这是当时的正文，页面可能已变化；需要最新内容请重新抓取 ---"
    }

    /** 头信息 + 正文。 */
    fun render(url: String?, fetchedAtMillis: Long, body: String, zone: TimeZone = TimeZone.getDefault()): String =
        header(url, fetchedAtMillis, zone) + "\n" + body
}
