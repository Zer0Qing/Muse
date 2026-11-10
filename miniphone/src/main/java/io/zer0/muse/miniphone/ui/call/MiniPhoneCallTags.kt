package io.zer0.muse.miniphone.ui.call

/** 通话相关页面的稳定定位标签，供自动化测试使用。 */
internal object MiniPhoneCallTags {
    const val PAGE = "mp-calls-page"
    const val EMPTY = "mp-calls-empty"
    const val BACK = "mp-calls-back"

    /** 正在进行的通话页。 */
    const val LIVE_PAGE = "mp-call-live-page"
    const val LIVE_PHASE = "mp-call-live-phase"
    const val LIVE_TIMER = "mp-call-live-timer"
    const val LIVE_HANGUP = "mp-call-live-hangup"
    const val LIVE_NOTE = "mp-call-live-note"
    const val LIVE_SAY_INPUT = "mp-call-live-say-input"
    const val LIVE_SAY_SEND = "mp-call-live-say-send"

    fun turn(index: Int): String = "mp-call-live-turn-$index"

    fun row(id: String): String = "mp-calls-row-$id"
}
