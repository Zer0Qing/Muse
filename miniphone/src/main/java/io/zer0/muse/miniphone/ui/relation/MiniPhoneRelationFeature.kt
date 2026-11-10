package io.zer0.muse.miniphone.ui.relation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.relation.MpRelationStore
import io.zer0.muse.miniphone.ui.launchGuarded
import org.koin.compose.koinInject

private const val TAG_RELATION = "MiniPhoneRelation"

/**
 * 关系网模块的装配点：从容器取库与宿主，持有门面与协程作用域，把纯渲染的页面接上动作。
 *
 * 页面拿到的是全部边，两个方向的分区由页面按主角过滤；
 * 「让他自己说」走宿主的一次性生成桥，拿不到结果就不写，列表保持原样。
 */
@Composable
internal fun MiniPhoneRelationFeature(characters: List<MpCharacter>, onBack: () -> Unit) {
    val db = koinInject<MiniPhoneDb>()
    val host = koinInject<MiniPhoneHost>()
    val store = remember(db, host) { MpRelationStore(db, host) }
    val scope = rememberCoroutineScope()
    MiniPhoneRelationScreen(
        edges = store.observeAll(),
        characters = characters,
        onSet = { fromId, toId, label ->
            scope.launchGuarded(TAG_RELATION, "记下关系") { store.setEdge(fromId = fromId, toId = toId, label = label) }
        },
        onDescribe = { fromId, toId ->
            scope.launchGuarded(TAG_RELATION, "让角色自述关系") {
                val fromName = characters.firstOrNull { it.id == fromId }?.name ?: fromId
                val toName = characters.firstOrNull { it.id == toId }?.name ?: toId
                store.describeEdge(fromId, toId, fromName, toName)
            }
        },
        onDelete = { id -> scope.launchGuarded(TAG_RELATION, "删除关系") { store.deleteEdge(id) } },
        onBack = onBack,
    )
}
