package io.zer0.muse.miniphone.ui.roleplay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.roleplay.MpRoleplayStore
import io.zer0.muse.miniphone.ui.launchGuarded
import org.koin.compose.koinInject

private const val TAG_ROLEPLAY = "MiniPhoneRoleplay"

/**
 * 跑团模块的装配点：从容器取库与宿主，把门面动作接到纯渲染页。
 *
 * 开局只建立局面，不调生成桥；推进与结案才让角色续写。生成失败由门面守住，
 * 回合与结局都不会被凭空写进存档。
 */
@Composable
internal fun MiniPhoneRoleplayFeature(characters: List<MpCharacter>, onBack: () -> Unit) {
    val db = koinInject<MiniPhoneDb>()
    val host = koinInject<MiniPhoneHost>()
    val store = remember(db, host) { MpRoleplayStore(db, host) }
    val scope = rememberCoroutineScope()
    MiniPhoneRoleplayScreen(
        scripts = store.observeScripts(),
        characters = characters,
        savesFor = { scriptId -> store.observeSaves(scriptId) },
        endingsFor = { scriptId -> store.observeEndings(scriptId) },
        onCreateScript = { title, body -> scope.launchGuarded(TAG_ROLEPLAY, "新建剧本") { store.createScript(title, body) } },
        onUpdateScript = { id, title, body ->
            scope.launchGuarded(TAG_ROLEPLAY, "修改剧本") { store.updateScript(id, title, body) }
        },
        onDeleteScript = { id -> scope.launchGuarded(TAG_ROLEPLAY, "删除剧本") { store.deleteScript(id) } },
        onStartRun = { scriptId, characterId, title ->
            scope.launchGuarded(TAG_ROLEPLAY, "开一局") { store.startRun(scriptId = scriptId, characterId = characterId, title = title) }
        },
        onAdvance = { saveId, action -> scope.launchGuarded(TAG_ROLEPLAY, "推进回合") { store.advance(saveId, action) } },
        onConclude = { saveId -> scope.launchGuarded(TAG_ROLEPLAY, "生成结局") { store.conclude(saveId) } },
        onDeleteSave = { saveId -> scope.launchGuarded(TAG_ROLEPLAY, "删除存档") { store.deleteSave(saveId) } },
        onBack = onBack,
    )
}
