package io.zer0.muse.miniphone.ui.memo

/** 备忘录页的稳定定位标签，供自动化测试使用。 */
internal object MiniPhoneMemoTags {
    const val PAGE = "mp-memo-page"
    const val INPUT = "mp-memo-input"
    const val ADD = "mp-memo-add"
    const val EMPTY = "mp-memo-empty"
    const val BACK = "mp-memo-back"

    fun row(id: String): String = "mp-memo-row-$id"

    fun delete(id: String): String = "mp-memo-delete-$id"
}
