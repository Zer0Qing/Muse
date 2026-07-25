package io.zer0.memory.summary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CompiledSectionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CompiledSectionEntity)

    @Query("SELECT * FROM compiled_sections WHERE section_key = :key")
    suspend fun get(key: String): CompiledSectionEntity?

    @Query("SELECT * FROM compiled_sections")
    suspend fun getAll(): List<CompiledSectionEntity>

    @Query("UPDATE compiled_sections SET content = :content, fingerprint = :fingerprint, updated_at = :now WHERE section_key = :key")
    suspend fun updateContent(key: String, content: String, fingerprint: String?, now: String)

    @Query("UPDATE compiled_sections SET content = '', fingerprint = NULL, updated_at = :now")
    suspend fun clearAll(now: String)

    /** P2: 清空指定 section 的内容(用于记忆页 UI 删除 Compile 层单段)。 */
    @Query("UPDATE compiled_sections SET content = '', fingerprint = NULL, updated_at = :now WHERE section_key = :key")
    suspend fun clearByKey(key: String, now: String)

    /** 清空全部(备份恢复时用,Android 16 禁止 execSQL DML)。 */
    @Query("DELETE FROM compiled_sections")
    suspend fun deleteAll()
}
