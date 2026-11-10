package io.zer0.muse.miniphone.data.misc

import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.entity.MpWardrobeItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 衣橱门面：给角色添衣、换装、删衣。
 *
 * 核心不变量是「同一角色同一 kind 只能有一件 equipped=1」——
 * 换装必须先把同类全部脱下再穿上这一件，绝不能出现两件同时穿着。
 * 空白名字不落库；kind 只接受约定的五种，其余一律拒绝。
 */
internal class MpWardrobeStore(
    private val db: MiniPhoneDb,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    fun observeByCharacter(characterId: String): Flow<List<MpWardrobeItemEntity>> = db.wardrobeItemDao().observeByCharacter(characterId)

    fun observeEquipped(characterId: String): Flow<List<MpWardrobeItemEntity>> = db.wardrobeItemDao().observeEquipped(characterId)

    /** 添一件衣服，默认未穿着；名字空白或 kind 非法时不落库，返回 null。 */
    suspend fun addItem(characterId: String, name: String, kind: String, uri: String): String? {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty() || kind !in VALID_KINDS) return null
        val id = newId()
        db.wardrobeItemDao().insert(
            MpWardrobeItemEntity(
                id = id,
                characterId = characterId,
                name = trimmedName,
                kind = kind,
                uri = uri.trim(),
                equipped = 0,
                updatedAt = now(),
            ),
        )
        return id
    }

    /**
     * 穿上 [id] 这件衣服：先按它真实的 kind 把同类全部脱下，再穿上它。
     * 找不到这件衣服（不属于该角色或已删）就什么都不做，避免误脱别人的同类。
     */
    suspend fun equip(characterId: String, id: String) {
        val item = db.wardrobeItemDao().observeByCharacter(characterId).first()
            .firstOrNull { it.id == id } ?: return
        db.wardrobeItemDao().unequipKind(characterId, item.kind)
        db.wardrobeItemDao().equip(id, now())
    }

    suspend fun delete(id: String) {
        db.wardrobeItemDao().delete(id)
    }

    suspend fun deleteByCharacter(characterId: String) {
        db.wardrobeItemDao().deleteByCharacter(characterId)
    }

    companion object {
        const val KIND_TOP: String = "top"
        const val KIND_BOTTOM: String = "bottom"
        const val KIND_DRESS: String = "dress"
        const val KIND_SHOES: String = "shoes"
        const val KIND_ACCESSORY: String = "accessory"

        private val VALID_KINDS = setOf(KIND_TOP, KIND_BOTTOM, KIND_DRESS, KIND_SHOES, KIND_ACCESSORY)
    }
}
