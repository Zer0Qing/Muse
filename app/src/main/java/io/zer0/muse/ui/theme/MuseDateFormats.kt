package io.zer0.muse.ui.theme

import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.75: 集中管理应用内日期时间格式。
 *
 * 原先 `"MM-dd HH:mm"`、`"HH:mm"`、`"yyyy-MM-dd HH:mm"` 等格式串散落在 16+ 文件,
 * 修改格式需多处同步易遗漏。集中此处统一调整。
 *
 * ## 为什么"月-日"这两档给的是工厂方法而不是格式串
 *
 * 走查 §十六 三-D1：这两档原来写成字面量 `"MM-dd HH:mm"` / `"MM-dd"`，
 * 却用 `Locale.getDefault()` 去格式化。字面量里"月在前"是美式习惯，而 es/pt/ru/fr
 * 的阅读顺序是日在前——同一串 `11-07`，俄文用户会读成 11 月 7 日，而它其实是 7 月 11 日。
 * 模式串本身带语言假设时，Locale 参数救不回来。
 *
 * 所以这两档改交 skeleton（"哪天+几点"这个语义），由系统按当前 Locale 选出该语言
 * 真正的写法（美式 `Nov 7, 12:36 PM`、俄式 `7 нояб., 12:36`、中式 `11月07日 12:36`）。
 * 调用方一律用 [dateTimeShort] / [dateShort]，不要再自己拼模式串。
 */
object MuseDateFormats {
    /** 短时间(时:分),如 "14:30"。 */
    const val TIME_SHORT = "HH:mm"

    /**
     * 12 小时制时间(带 AM/PM),如 "2:30 PM"。
     * L-DF1: "a" 占位符的 AM/PM 文本由调用处 SimpleDateFormat 的 Locale 决定
     * (Locale.US → "AM/PM",Locale.CHINA → "上午/下午")。如需固定英文显示,
     * 调用处应显式传 Locale.US 构造 SimpleDateFormat。
     */
    const val TIME_12H = "h:mm a"

    /** 年-月-日 时:分,如 "2026-07-11 14:30"。详情页/设置页时间显示。 */
    const val DATE_TIME_FULL = "yyyy-MM-dd HH:mm"

    /** 年-月-日 时:分:秒,如 "2026-07-11 14:30:45"。精确时间戳。 */
    const val DATE_TIME_FULL_SEC = "yyyy-MM-dd HH:mm:ss"

    /** 年-月-日,如 "2026-07-11"。仅日期。 */
    const val DATE_ONLY = "yyyy-MM-dd"

    /** 文件名时间戳,如 "20260711-143045"(Locale.US,用于备份/崩溃日志文件名)。 */
    const val FILE_TIMESTAMP = "yyyyMMdd-HHmmss"

    /** 文件名时间戳（下划线分隔），如 "20260711_143045"。备份/诊断导出用。 */
    const val FILE_TIMESTAMP_UNDERSCORE = "yyyyMMdd_HHmmss"

    /** 日志时间戳（毫秒），如 "2026-07-11 14:30:45.123"。 */
    const val DATE_TIME_FULL_MILLIS = "yyyy-MM-dd HH:mm:ss.SSS"

    /** 只到月的归档键，如 "2026-07"。按天/月聚合统计用。 */
    const val MONTH_ONLY = "yyyy-MM"

    /** 带时区缩写的完整时间，如 "2026-07-11 14:30:45 CST"。工具返回值用。 */
    const val DATE_TIME_FULL_TZ = "yyyy-MM-dd HH:mm:ss z"

    /** ISO-8601 带毫秒，如 "2026-07-11T06:30:45.123Z"。S3 签名用。 */
    const val ISO_8601_MILLIS = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

    /** ISO-8601 秒级，如 "2026-07-11T06:30:45Z"。 */
    const val ISO_8601 = "yyyy-MM-dd'T'HH:mm:ss'Z'"

    /** ISO-8601 紧凑（无分隔），如 "20260711T063045Z"。S3 对象键用。 */
    const val ISO_8601_COMPACT = "yyyyMMdd'T'HHmmss'Z'"

    /** ISO-8601 带时区偏移，如 "2026-07-11T14:30:45+08:00"。审计日志用。 */
    const val ISO_8601_OFFSET = "yyyy-MM-dd'T'HH:mm:ssXXX"

    /** 毫秒级时间（时:分:秒.毫秒），如 "14:30:45.123"。调试页用。 */
    const val TIME_WITH_MILLIS = "HH:mm:ss.SSS"

    /** 紧凑文件名（短），如 "0711_1430"。截图/临时文件用。 */
    const val FILE_TIMESTAMP_COMPACT = "MMdd_HHmm"

    // ── 便捷格式化：调用方直接给毫秒或 Date，不必自己 new SimpleDateFormat ──
    //
    // 这些方法存在的意义不只是省一行代码：SimpleDateFormat 不是线程安全的，
    // 每次调用新建实例是唯一安全的用法，散落在各处时很容易被误改成共享字段。

    /** 用 [pattern] 格式化 [millis]，按当前语言。 */
    fun format(pattern: String, millis: Long): String = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))

    /** 用 [pattern] 格式化当前时刻。 */
    fun formatNow(pattern: String): String = format(pattern, System.currentTimeMillis())

    /** 用 [pattern] 按 [locale] 格式化 [millis]（需要固定语言时用，如文件名里的英文月份）。 */
    fun format(pattern: String, millis: Long, locale: Locale): String = SimpleDateFormat(pattern, locale).format(Date(millis))

    // ── 高频路径：ThreadLocal 缓存 ──
    //
    // [format] 每次新建实例，适合一次性调用；但日循环里按天/按月取 key 这类
    // 每秒可能调用多次的场景，新建实例的代价明显。这两档走 ThreadLocal：
    // 每线程一份独立实例，既避开 SimpleDateFormat 的线程安全问题，又不必重复构造。

    private val dayKeyFormat = ThreadLocal.withInitial { SimpleDateFormat(DATE_ONLY, Locale.getDefault()) }
    private val monthKeyFormat = ThreadLocal.withInitial { SimpleDateFormat(MONTH_ONLY, Locale.getDefault()) }

    /** 按天取 key，如 "2026-07-11"。ThreadLocal 缓存，适合高频调用。 */
    fun dayKey(millis: Long): String = dayKeyFormat.get()!!.format(Date(millis))

    /** 按月取 key，如 "2026-07"。ThreadLocal 缓存，适合高频调用。 */
    fun monthKey(millis: Long): String = monthKeyFormat.get()!!.format(Date(millis))

    // ── 解析：把字符串读回毫秒 ──
    //
    // 解析用的格式化器必须指定时区，否则同一串 "2026-07-11T06:30:45Z" 会被按本地时区
    // 解释成不同的时刻。这里的 ISO 两档固定 UTC。

    /** 解析 ISO-8601（带或不带毫秒，末尾 Z 表示 UTC）。解析失败返回 null。 */
    fun parseIsoUtc(text: String): Long? {
        val patterns = listOf(ISO_8601_MILLIS, ISO_8601)
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(text)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return null
    }

    /** 「月-日 时:分」这一档的语义 skeleton,顺序与分隔交给系统按语言决定。 */
    private const val SKELETON_DATE_TIME_SHORT = "MMMdHHmm"

    /** 「月-日」这一档的语义 skeleton。 */
    private const val SKELETON_DATE_SHORT = "MMMd"

    /** 列表项时间显示最常用的一档：月-日 + 时:分。 */
    fun dateTimeShort(): SimpleDateFormat = SimpleDateFormat(bestPattern(SKELETON_DATE_TIME_SHORT), Locale.getDefault())

    /** 只要月-日的一档。 */
    fun dateShort(): SimpleDateFormat = SimpleDateFormat(bestPattern(SKELETON_DATE_SHORT), Locale.getDefault())

    /**
     * skeleton → 当前语言的模式串。
     *
     * 系统调用在个别 ROM 上可能给不出结果，此时退回原来那串美式写法：
     * 退回后至少还能显示时间，不至于整页空白。
     */
    private fun bestPattern(skeleton: String): String = runCatching {
        DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
    }.getOrDefault(if (skeleton == SKELETON_DATE_SHORT) "MM-dd" else "MM-dd HH:mm")
}
