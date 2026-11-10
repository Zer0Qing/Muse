package io.zer0.muse.miniphone.ui.misc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.misc.MpRingtoneStore
import io.zer0.muse.miniphone.ui.launchGuarded
import org.koin.compose.koinInject

private const val TAG_RINGTONE = "MiniPhoneRingtone"

/**
 * 通话铃声的装配点：从容器取库，把纯渲染页面接上添加/删除动作。
 *
 * 取用走门面的 observeFor，空角色的通用铃声会一并被取到；
 * 空白名字/素材地址不落库由门面保证，添加/删除失败只记日志不崩应用。
 */
@Composable
internal fun MiniPhoneRingtoneFeature(characters: List<MpCharacter>, onBack: () -> Unit) {
    val db = koinInject<MiniPhoneDb>()
    val store = remember(db) { MpRingtoneStore(db) }
    val scope = rememberCoroutineScope()
    MiniPhoneRingtoneScreen(
        ringtonesFor = { characterId, kind -> store.observeFor(characterId, kind) },
        characters = characters,
        onAdd = { characterId, name, uri, kind ->
            scope.launchGuarded(TAG_RINGTONE, "添加铃声") { store.add(characterId = characterId, name = name, uri = uri, kind = kind) }
        },
        onDelete = { id -> scope.launchGuarded(TAG_RINGTONE, "删除铃声") { store.delete(id) } },
        onBack = onBack,
    )
}
