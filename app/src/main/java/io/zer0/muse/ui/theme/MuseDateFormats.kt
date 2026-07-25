package io.zer0.muse.ui.theme

/**
 * v1.75: 集中管理应用内日期时间格式串。
 *
 * 原先 `"MM-dd HH:mm"`、`"HH:mm"`、`"yyyy-MM-dd HH:mm"` 等格式串散落在 16+ 文件,
 * 修改格式需多处同步易遗漏。集中此处便于统一调整。
 *
 * 注:SimpleDateFormat 对象本身仍在各调用处用 remember / 局部变量创建(v1.71 已优化高频重组路径)。
 * 这里只统一格式串字面量。
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

    /** 月-日 时:分,如 "07-11 14:30"。列表项时间显示最常用。 */
    const val DATE_TIME_SHORT = "MM-dd HH:mm"

    /** 月-日,如 "07-11"。 */
    const val DATE_SHORT = "MM-dd"

    /** 年-月-日 时:分,如 "2026-07-11 14:30"。详情页/设置页时间显示。 */
    const val DATE_TIME_FULL = "yyyy-MM-dd HH:mm"

    /** 年-月-日 时:分:秒,如 "2026-07-11 14:30:45"。精确时间戳。 */
    const val DATE_TIME_FULL_SEC = "yyyy-MM-dd HH:mm:ss"

    /** 年-月-日,如 "2026-07-11"。仅日期。 */
    const val DATE_ONLY = "yyyy-MM-dd"

    /** 文件名时间戳,如 "20260711-143045"(Locale.US,用于备份/崩溃日志文件名)。 */
    const val FILE_TIMESTAMP = "yyyyMMdd-HHmmss"

    /** 中文日期分隔符,如 "07月11日 星期五"(Locale.getDefault,EEEE 为本地化星期)。 */
    const val DATE_WEEKDAY_CN = "MM月dd日 EEEE"
}
