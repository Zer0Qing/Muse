package io.zer0.muse.ui.moment

/** 小手机桌面应用的稳定 id 与默认显示顺序。 */
internal object MiniPhoneApps {
    const val MOMENTS = "moments"
    const val MESSAGES = "messages"
    const val ALBUM = "album"
    const val QUICK_NOTES = "quick_notes"
    const val WEATHER = "weather"
    const val DIARY = "diary"
    const val SETTINGS = "settings"

    val all: List<Pair<String, String>> = listOf(
        MOMENTS to "朋友圈",
        MESSAGES to "消息",
        ALBUM to "相册",
        QUICK_NOTES to "备忘录",
        WEATHER to "天气",
        DIARY to "日记本",
        SETTINGS to "设置",
    )
}
