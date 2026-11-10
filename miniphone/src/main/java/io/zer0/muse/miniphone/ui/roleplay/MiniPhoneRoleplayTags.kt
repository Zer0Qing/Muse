package io.zer0.muse.miniphone.ui.roleplay

/** 跑团页的稳定定位标签，供自动化测试使用。 */
internal object MiniPhoneRoleplayTags {
    const val PAGE = "mp-roleplay-page"
    const val BACK = "mp-roleplay-back"
    const val EMPTY = "mp-roleplay-empty"
    const val TITLE_INPUT = "mp-roleplay-title-input"
    const val BODY_INPUT = "mp-roleplay-body-input"
    const val COMMIT_SCRIPT = "mp-roleplay-commit-script"
    const val CANCEL_EDIT = "mp-roleplay-cancel-edit"

    fun scriptRow(id: String): String = "mp-roleplay-script-$id"

    fun editScript(id: String): String = "mp-roleplay-edit-$id"

    fun deleteScript(id: String): String = "mp-roleplay-delete-script-$id"

    fun startRun(id: String): String = "mp-roleplay-start-$id"

    fun noCharacters(id: String): String = "mp-roleplay-no-characters-$id"

    fun character(scriptId: String, characterId: String): String = "mp-roleplay-character-$scriptId-$characterId"

    fun savesEmpty(id: String): String = "mp-roleplay-saves-empty-$id"

    fun saveRow(id: String): String = "mp-roleplay-save-$id"

    fun actionInput(id: String): String = "mp-roleplay-action-$id"

    fun advance(id: String): String = "mp-roleplay-advance-$id"

    fun conclude(id: String): String = "mp-roleplay-conclude-$id"

    fun deleteSave(id: String): String = "mp-roleplay-delete-save-$id"

    fun endingsEmpty(id: String): String = "mp-roleplay-endings-empty-$id"

    fun endingRow(id: String): String = "mp-roleplay-ending-$id"
}
