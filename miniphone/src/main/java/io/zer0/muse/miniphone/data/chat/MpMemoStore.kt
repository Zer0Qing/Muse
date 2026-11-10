package io.zer0.muse.miniphone.data.chat

import io.zer0.muse.miniphone.data.db.dao.MpMemoDao
import io.zer0.muse.miniphone.data.db.entity.MpMemoEntity
import kotlinx.coroutines.flow.Flow

/**
 * 备忘录门面：空白内容不落库，写入时把 updatedAt 一起推进。
 * id 与时间由调用方给，沿用模块写入器的可测约定。
 */
internal class MpMemoStore(
    private val dao: MpMemoDao,
) {
    fun observeForCharacter(characterId: String): Flow<List<MpMemoEntity>> = dao.observeForCharacter(characterId)

    fun observeAll(): Flow<List<MpMemoEntity>> = dao.observeAll()

    suspend fun save(id: String, characterId: String, content: String, createdAt: Long, updatedAt: Long): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false
        dao.insert(
            MpMemoEntity(
                id = id,
                characterId = characterId,
                content = trimmed,
                createdAt = createdAt,
                updatedAt = updatedAt,
            ),
        )
        return true
    }

    suspend fun delete(id: String) = dao.delete(id)
}
