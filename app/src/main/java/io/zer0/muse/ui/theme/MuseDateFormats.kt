package io.zer0.muse.ui.theme

import android.text.format.DateFormat
import java.text.SimpleDateFormat
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
