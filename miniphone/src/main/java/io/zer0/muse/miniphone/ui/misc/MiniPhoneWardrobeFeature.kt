package io.zer0.muse.miniphone.ui.misc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.misc.MpWardrobeStore
import io.zer0.muse.miniphone.ui.launchGuarded
import org.koin.compose.koinInject

private const val TAG_WARDROBE = "MiniPhoneWardrobe"

/**
 * 衣橱换装的装配点：从容器取库，持有门面与协程作用域，把纯渲染页面接上动作。
 *
 * 添加、换装、删除都走 [launchGuarded]，失败只记日志不崩应用；
 * 同 kind 只留一件穿着由门面保证，这里不重复判断。
 */
@Composable
internal fun MiniPhoneWardrobeFeature(characters: List<MpCharacter>, onBack: () -> Unit) {
    val db = koinInject<MiniPhoneDb>()
    val store = remember(db) { MpWardrobeStore(db) }
    val scope = rememberCoroutineScope()
    MiniPhoneWardrobeScreen(
        itemsFor = { store.observeByCharacter(it) },
        characters = characters,
        onAdd = { characterId, name, kind, uri ->
            scope.launchGuarded(TAG_WARDROBE, "添加衣物") { store.addItem(characterId = characterId, name = name, kind = kind, uri = uri) }
        },
        onEquip = { characterId, id ->
            scope.launchGuarded(TAG_WARDROBE, "换装") { store.equip(characterId, id) }
        },
        onDelete = { id -> scope.launchGuarded(TAG_WARDROBE, "删除衣物") { store.delete(id) } },
        onBack = onBack,
    )
}
