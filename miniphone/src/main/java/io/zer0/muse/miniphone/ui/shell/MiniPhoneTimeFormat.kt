package io.zer0.muse.miniphone.ui.shell

import io.zer0.muse.miniphone.R
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
    fun separator(context: android.content.Context, epochMs: Long, now: Long = System.currentTimeMillis()): String {
        if (epochMs <= 0L) return ""
        val zone = ZoneId.systemDefault()
        val clock = clock(epochMs)
        val thenDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val nowDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            thenDate == nowDate -> clock
            thenDate == nowDate.minusDays(1) -> context.getString(R.string.mp_time_yesterday_at, clock)
            // 日期部分交给平台的格式化器：月份名、顺序、是否有点都由 locale 决定，
            // 自己拼「M 月 D 日」在别的语言里就是错的
            else -> dateText(context, epochMs, includeYear = thenDate.year != nowDate.year) + " $clock"
        }
    }

    private fun dateText(context: android.content.Context, epochMs: Long, includeYear: Boolean): String {
        val flags = android.text.format.DateUtils.FORMAT_SHOW_DATE or
            (if (includeYear) android.text.format.DateUtils.FORMAT_SHOW_YEAR else 0)
        return android.text.format.DateUtils.formatDateTime(context, epochMs, flags).toString()
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
internal fun relativeTimeOf(context: android.content.Context, epochMs: Long, now: Long = System.currentTimeMillis()): String = when {
    epochMs <= 0L -> ""
    else -> {
        val diff = now - epochMs
        val zone = ZoneId.systemDefault()
        val then = Instant.ofEpochMilli(epochMs).atZone(zone)
        val thenDate = then.toLocalDate()
        val nowDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        when {
            diff < MINUTE_MS -> context.getString(R.string.mp_time_just_now)
            diff < HOUR_MS -> context.resources.getQuantityString(
                R.plurals.mp_time_minutes_ago,
                (diff / MINUTE_MS).toInt(),
                diff / MINUTE_MS,
            )

            thenDate == nowDate -> context.resources.getQuantityString(
                R.plurals.mp_time_hours_ago,
                (diff / HOUR_MS).toInt(),
                diff / HOUR_MS,
            )

            thenDate == nowDate.minusDays(1) ->
                context.getString(R.string.mp_time_yesterday_at, then.format(RelativeClock))

            else -> {
                val crossYear = thenDate.year != nowDate.year
                val flags = android.text.format.DateUtils.FORMAT_SHOW_DATE or
                    (if (crossYear) android.text.format.DateUtils.FORMAT_SHOW_YEAR else 0)
                val date = android.text.format.DateUtils.formatDateTime(context, epochMs, flags)
                // 跨年时年份本身已经足够定位，不再补时刻（与原口径一致）
                if (crossYear) date.toString() else "$date ${then.format(RelativeClock)}"
            }
        }
    }
}
