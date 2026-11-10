package io.zer0.muse.miniphone.ui.roam

/** 漫游页的稳定定位标签，供自动化测试使用。 */
internal object MiniPhoneRoamTags {
    const val PAGE = "mp-roam-page"
    const val EMPTY = "mp-roam-empty"
    const val BACK = "mp-roam-back"
    const val DEPART = "mp-roam-depart"
    const val LOCATION_INPUT = "mp-roam-location-input"
    const val NPC_PANEL = "mp-roam-npc-panel"
    const val ENCOUNTER = "mp-roam-encounter"
    const val CLEAR = "mp-roam-clear"

    fun character(id: String): String = "mp-roam-character-$id"

    fun locationRow(characterId: String): String = "mp-roam-location-$characterId"

    fun npc(id: String): String = "mp-roam-npc-$id"
}
