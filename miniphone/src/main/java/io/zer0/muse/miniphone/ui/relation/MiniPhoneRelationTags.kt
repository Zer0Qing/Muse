package io.zer0.muse.miniphone.ui.relation

/** 关系网页的稳定定位标签，供自动化测试使用。 */
internal object MiniPhoneRelationTags {
    const val PAGE = "mp-relation-page"
    const val EMPTY = "mp-relation-empty"
    const val BACK = "mp-relation-back"
    const val FROM_SECTION = "mp-relation-from-section"
    const val TO_SECTION = "mp-relation-to-section"
    const val FROM_EMPTY = "mp-relation-from-empty"
    const val TO_EMPTY = "mp-relation-to-empty"
    const val LABEL_INPUT = "mp-relation-label-input"
    const val SET = "mp-relation-set"
    const val DESCRIBE = "mp-relation-describe"
    const val LIST = "mp-relation-list"

    fun character(id: String): String = "mp-relation-character-$id"

    fun target(id: String): String = "mp-relation-target-$id"

    fun edgeRow(id: String): String = "mp-relation-edge-$id"

    fun delete(id: String): String = "mp-relation-delete-$id"
}
