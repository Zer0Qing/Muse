package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.api.MpActivityKind

/**
 * 生成期间那一行文案。
 *
 * 只按 [MpActivityKind] 取句子:宿主不交出工具名、参数与返回值,这里也没有任何可以显示它们的地方。
 */
internal object MiniPhoneActivityText {

    /** 取词交给界面：这里只给资源 id，宿主与状态层都不再拼中文句子。 */
    fun resOf(kind: MpActivityKind): Int = when (kind) {
        MpActivityKind.SEARCHING -> R.string.mp_activity_searching
        MpActivityKind.REMEMBERING -> R.string.mp_activity_remembering
        MpActivityKind.CALENDAR -> R.string.mp_activity_calendar
        MpActivityKind.WEATHER -> R.string.mp_activity_weather
        MpActivityKind.NOTES -> R.string.mp_activity_notes
        MpActivityKind.OTHER -> R.string.mp_activity_other
    }
}
