package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpRelationEdgeEntity
import kotlinx.coroutines.flow.Flow

/** 关系边读写：单向边，按起点看「他怎么看别人」，按终点看「别人怎么看他」。 */
@Dao
interface MpRelationEdgeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(edge: MpRelationEdgeEntity)

    @Query("SELECT * FROM mp_relation_edge WHERE fromId = :fromId AND toId = :toId LIMIT 1")
    suspend fun get(fromId: String, toId: String): MpRelationEdgeEntity?

    @Query("SELECT * FROM mp_relation_edge WHERE fromId = :fromId ORDER BY updatedAt DESC")
    fun observeFrom(fromId: String): Flow<List<MpRelationEdgeEntity>>

    @Query("SELECT * FROM mp_relation_edge WHERE toId = :toId ORDER BY updatedAt DESC")
    fun observeTo(toId: String): Flow<List<MpRelationEdgeEntity>>

    @Query("SELECT * FROM mp_relation_edge ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MpRelationEdgeEntity>>

    @Query("DELETE FROM mp_relation_edge WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM mp_relation_edge WHERE fromId = :characterId OR toId = :characterId")
    suspend fun deleteByCharacter(characterId: String)
}
