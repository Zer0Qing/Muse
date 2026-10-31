package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.api.MpActivityKind

/**
 * 生成期间那一行文案。
 *
 * 只按 [MpActivityKind] 取句子:宿主不交出工具名、参数与返回值,这里也没有任何可以显示它们的地方。
 */
internal object MiniPhoneActivityText {

    fun of(kind: MpActivityKind): String = when (kind) {
        MpActivityKind.SEARCHING -> SEARCHING
        MpActivityKind.REMEMBERING -> REMEMBERING
        MpActivityKind.CALENDAR -> CALENDAR
        MpActivityKind.WEATHER -> WEATHER
        MpActivityKind.NOTES -> NOTES
        MpActivityKind.OTHER -> OTHER
    }

    private const val SEARCHING: String = "对方正在翻找…"
    private const val REMEMBERING: String = "对方想起了点什么…"
    private const val CALENDAR: String = "对方在看日程…"
    private const val WEATHER: String = "对方看了眼窗外…"
    private const val NOTES: String = "对方翻着笔记…"
    private const val OTHER: String = "对方正在忙别的…"
}
