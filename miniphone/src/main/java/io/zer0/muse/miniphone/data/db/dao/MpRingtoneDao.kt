package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpRingtoneEntity
import kotlinx.coroutines.flow.Flow

/** 铃声读写：按角色与通话种类取可用铃声，空角色表示通用。 */
@Dao
interface MpRingtoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(ringtone: MpRingtoneEntity)

    @Query("SELECT * FROM mp_ringtone WHERE (characterId = :characterId OR characterId = '') AND kind = :kind ORDER BY createdAt DESC")
    fun observeFor(characterId: String, kind: String): Flow<List<MpRingtoneEntity>>

    @Query("SELECT * FROM mp_ringtone ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MpRingtoneEntity>>

    @Query("DELETE FROM mp_ringtone WHERE id = :id")
    suspend fun delete(id: String)
}
