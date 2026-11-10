package io.zer0.muse.miniphone.ui.roam

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.roam.MpRoamStore
import io.zer0.muse.miniphone.ui.launchGuarded
import org.koin.compose.getKoin
import org.koin.compose.koinInject

private const val TAG_ROAM = "MiniPhoneRoam"

/**
 * 漫游模块的装配点：从容器取库，持有门面与协程作用域，把纯渲染的页面接上出发/遇见/清空三个动作。
 *
 * 门面只需要库 —— 出发、读位置、记路人、清路人全走数据库，不碰宿主。
 * 只有「遇见路人」要生成性格设定，所以到那一步才从容器现取 [MiniPhoneHost] 并交给门面：
 * 生成拿不到结果时门面什么都不写，用户看到的仍是「这一带还没遇到谁」，而不是编出来的一句招呼。
 */
@Composable
internal fun MiniPhoneRoamFeature(characters: List<MpCharacter>, onBack: () -> Unit) {
    val db = koinInject<MiniPhoneDb>()
    val koin = getKoin()
    val store = remember(db) { MpRoamStore(db) }
    val scope = rememberCoroutineScope()
    MiniPhoneRoamScreen(
        locations = store.observeLocations(),
        characters = characters,
        npcsFor = { locationId -> store.observeNpcs(locationId) },
        onDepart = { characterId, locationName ->
            scope.launchGuarded(TAG_ROAM, "出发") { store.travel(characterId, locationName) }
        },
        onEncounter = { characterId, locationId, locationName ->
            scope.launchGuarded(TAG_ROAM, "遇见路人") {
                store.encounter(koin.get<MiniPhoneHost>(), characterId, locationId, locationName)
            }
        },
        onClearNpcs = { locationId ->
            scope.launchGuarded(TAG_ROAM, "清空路人") { store.clearNpcs(locationId) }
        },
        onBack = onBack,
    )
}
