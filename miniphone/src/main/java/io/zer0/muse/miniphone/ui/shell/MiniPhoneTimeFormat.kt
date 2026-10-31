package io.zer0.muse.miniphone.ui.shell

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 界面上的时间文本。
 *
 * 相对时间只有 [relativeTimeOf] 一处实现:朋友圈、评论、消息列表都调它,不各写一套。
 * 状态栏与聊天页的时间分隔另有口径(要的是「几点」而不是「多久以前」),仍在下面这个对象里。
 */
internal object MiniPhoneTimeFormat {

    private val clockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** 状态栏时钟:`HH:mm`。 */
    fun clock(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(clockFormatter)

    /**
     * 聊天页的时间分隔:"今天"只给时间,"昨天"与更早的日期带上日期。
     *
     * 分隔行本来就是给"翻页时突然跳时间"做标记的,所以同一天不必写"今天"两字。
     */
    fun separator(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        if (epochMs <= 0L) return ""
        val zone = ZoneId.systemDefault()
        val clock = clock(epochMs)
        val thenDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val nowDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            thenDate == nowDate -> clock
            thenDate == nowDate.minusDays(1) -> "昨天 $clock"
            thenDate.year == nowDate.year -> "${thenDate.monthValue} 月 ${thenDate.dayOfMonth} 日 $clock"
            else -> "${thenDate.year} 年 ${thenDate.monthValue} 月 ${thenDate.dayOfMonth} 日 $clock"
        }
    }
}

private const val MINUTE_MS: Long = 60_000L
private const val HOUR_MS: Long = 60L * MINUTE_MS

private val RelativeClock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 相对时间:多久以前那件事。
 *
 * 六个分支,顺序即优先级:
 * 1. 一分钟内(含时间戳在未来)「刚刚」
 * 2. 一小时内「12 分钟前」
 * 3. 同一天「3 小时前」
 * 4. 昨天「昨天 21:30」
 * 5. 今年更早「10月28日 21:30」
 * 6. 跨年「2025年10月28日」—— 跨年只写日期,年份已经足够定位
 *
 * 时间戳为 0(没有时间)返回空串,由调用方决定留白。日历部分按本地时区算:
 * 「昨天」是日历上的昨天,不是「24 小时以前」—— 凌晨看一小时的对话,它仍然属于昨天。
 *
 * @param now 作为「现在」的时间戳,默认系统时钟;测试用它钉死每一个分支
 */
internal fun relativeTimeOf(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMs <= 0L) return ""
    val diff = now - epochMs
    if (diff < MINUTE_MS) return "刚刚"
    if (diff < HOUR_MS) return "${diff / MINUTE_MS} 分钟前"

    val zone = ZoneId.systemDefault()
    val then = Instant.ofEpochMilli(epochMs).atZone(zone)
    val thenDate = then.toLocalDate()
    val nowDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        thenDate == nowDate -> "${diff / HOUR_MS} 小时前"
        thenDate == nowDate.minusDays(1) -> "昨天 ${then.format(RelativeClock)}"
        thenDate.year == nowDate.year -> "${thenDate.monthValue}月${thenDate.dayOfMonth}日 ${then.format(RelativeClock)}"
        else -> "${thenDate.year}年${thenDate.monthValue}月${thenDate.dayOfMonth}日"
    }
}
