package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpWardrobeItemEntity
import kotlinx.coroutines.flow.Flow

/** 衣橱读写：按角色看衣柜，换装时先把同类全部脱下再穿上这一件。 */
@Dao
interface MpWardrobeItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MpWardrobeItemEntity)

    @Query("SELECT * FROM mp_wardrobe_item WHERE characterId = :characterId ORDER BY updatedAt DESC")
    fun observeByCharacter(characterId: String): Flow<List<MpWardrobeItemEntity>>

    @Query("SELECT * FROM mp_wardrobe_item WHERE characterId = :characterId AND equipped = 1")
    fun observeEquipped(characterId: String): Flow<List<MpWardrobeItemEntity>>

    @Query("UPDATE mp_wardrobe_item SET equipped = 0 WHERE characterId = :characterId AND kind = :kind")
    suspend fun unequipKind(characterId: String, kind: String)

    @Query("UPDATE mp_wardrobe_item SET equipped = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun equip(id: String, updatedAt: Long)

    @Query("DELETE FROM mp_wardrobe_item WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM mp_wardrobe_item WHERE characterId = :characterId")
    suspend fun deleteByCharacter(characterId: String)
}
