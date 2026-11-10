package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpMemoEntity
import kotlinx.coroutines.flow.Flow

/** 备忘录读写：按角色看，或看全部；空白内容不落库由门面保证。 */
@Dao
interface MpMemoDao {
    @Query("SELECT * FROM mp_memo WHERE characterId = :characterId ORDER BY updatedAt DESC")
    fun observeForCharacter(characterId: String): Flow<List<MpMemoEntity>>

    @Query("SELECT * FROM mp_memo ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MpMemoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memo: MpMemoEntity)

    @Query("DELETE FROM mp_memo WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM mp_memo")
    suspend fun deleteAll()
}
