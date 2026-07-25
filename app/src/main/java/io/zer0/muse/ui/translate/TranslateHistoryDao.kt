package io.zer0.muse.ui.translate

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslateHistoryDao {
    @Query("SELECT * FROM translate_history ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<TranslateHistoryEntity>>

    // v1.0.30 gap4.3: 翻译收藏夹
    @Query("SELECT * FROM translate_history WHERE favorite = 1 ORDER BY created_at DESC")
    fun observeFavorites(): Flow<List<TranslateHistoryEntity>>

    @Query("SELECT * FROM translate_history WHERE source_text LIKE '%' || :keyword || '%' OR translated_text LIKE '%' || :keyword || '%' ORDER BY created_at DESC LIMIT :limit")
    suspend fun search(keyword: String, limit: Int = 50): List<TranslateHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TranslateHistoryEntity)

    @Query("DELETE FROM translate_history WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM translate_history")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM translate_history")
    suspend fun count(): Int

    // v1.0.30 gap4.3: 收藏 / 取消收藏
    @Query("UPDATE translate_history SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)
}
