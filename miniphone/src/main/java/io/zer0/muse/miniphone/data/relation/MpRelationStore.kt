package io.zer0.muse.miniphone.data.relation

import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.entity.MpRelationEdgeEntity
import kotlinx.coroutines.flow.Flow

/**
 * 关系网门面：记看法、删看法、按两个方向读看法。
 *
 * 关系是单向有向边 —— 「A 认为 B 是挚友」不代表「B 也这么看 A」，两边各存一条，
 * 读写都严格按方向区分：[observeFrom] 是「他怎么看别人」，[observeTo] 是「别人怎么看他」。
 *
 * 自述走宿主的通用一次性生成桥；拿不到结果就不写、不编内容，
 * 原有记录（或没有记录这件事）保持原样。
 */
internal class MpRelationStore(
    private val db: MiniPhoneDb,
    private val host: MiniPhoneHost,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    /** 他怎么看别人：以 [characterId] 为起点的全部边。 */
    fun observeFrom(characterId: String): Flow<List<MpRelationEdgeEntity>> = db.relationEdgeDao().observeFrom(characterId)

    /** 别人怎么看他：以 [characterId] 为终点的全部边。 */
    fun observeTo(characterId: String): Flow<List<MpRelationEdgeEntity>> = db.relationEdgeDao().observeTo(characterId)

    fun observeAll(): Flow<List<MpRelationEdgeEntity>> = db.relationEdgeDao().observeAll()

    /**
     * 记一条看法：同一 [fromId]→[toId] 覆盖更新，不攒两条；空白 label 不落库。
     *
     * 覆盖时沿用旧边主键，让 REPLACE 落在同一行上；反方向的边不受影响。
     */
    suspend fun setEdge(fromId: String, toId: String, label: String, note: String = "") {
        val text = label.trim()
        if (text.isEmpty()) return
        val existing = db.relationEdgeDao().get(fromId, toId)
        db.relationEdgeDao().insert(
            MpRelationEdgeEntity(
                id = existing?.id ?: newId(),
                fromId = fromId,
                toId = toId,
                label = text,
                note = note.trim(),
                updatedAt = now(),
            ),
        )
    }

    suspend fun deleteEdge(id: String) {
        db.relationEdgeDao().delete(id)
    }

    /** 角色离开时清掉涉及他的两个方向的全部边，不留指向幽灵的看法。 */
    suspend fun deleteByCharacter(characterId: String) {
        db.relationEdgeDao().deleteByCharacter(characterId)
    }

    /**
     * 让 [fromId] 自己说怎么看 [toId]，产出的短语记成这条边的 label，既有备注保留。
     *
     * 拿不到结果就不写、不编内容 —— 不覆盖既有记录，也不凭空造一条。
     */
    suspend fun describeEdge(fromId: String, toId: String, fromName: String, toName: String) {
        val phrase = host.composeScene(
            fromId,
            "你是$fromName。用一个短语概括你怎么看$toName（比如「挚友」「竞争对手」「陌生人」）。只输出这个短语，不要解释。",
        )
        if (phrase.isNullOrBlank()) return
        val note = db.relationEdgeDao().get(fromId, toId)?.note.orEmpty()
        setEdge(fromId, toId, phrase, note)
    }
}
