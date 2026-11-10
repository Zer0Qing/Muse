package io.zer0.muse.miniphone.ui.checkup

/** 查岗页的稳定定位标签，供自动化测试使用。 */
internal object MiniPhoneCheckupTags {
    const val PAGE = "mp-checkup-page"
    const val EMPTY = "mp-checkup-empty"
    const val BACK = "mp-checkup-back"
    const val START = "mp-checkup-start"
    const val DIRECTION_OUT = "mp-checkup-direction-out"
    const val DIRECTION_IN = "mp-checkup-direction-in"
    const val INTENSITY_LIGHT = "mp-checkup-intensity-light"
    const val INTENSITY_STANDARD = "mp-checkup-intensity-standard"
    const val INTENSITY_DEEP = "mp-checkup-intensity-deep"

    fun character(id: String): String = "mp-checkup-character-$id"

    fun caseRow(id: String): String = "mp-checkup-case-$id"

    fun evidence(id: String): String = "mp-checkup-evidence-$id"

    fun interpret(id: String): String = "mp-checkup-interpret-$id"

    fun delete(id: String): String = "mp-checkup-delete-$id"
}
