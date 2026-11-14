package io.zer0.memory.summary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScopedCompiledSectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScopedCompiledSectionEntity)

    @Query("SELECT * FROM compiled_sections_scoped WHERE section_key = :key AND scope = :scope AND space_id = :spaceId")
    suspend fun get(key: String, scope: String, spaceId: String): ScopedCompiledSectionEntity?

    @Query("SELECT * FROM compiled_sections_scoped")
    suspend fun getAll(): List<ScopedCompiledSectionEntity>

    @Query("SELECT * FROM compiled_sections_scoped WHERE scope = :scope AND space_id = :spaceId")
    suspend fun getForScope(scope: String, spaceId: String): List<ScopedCompiledSectionEntity>

    @Query("DELETE FROM compiled_sections_scoped WHERE scope = :scope AND space_id = :spaceId")
    suspend fun deleteForScope(scope: String, spaceId: String)

    @Query(
        "INSERT INTO compiled_sections_scoped (section_key, scope, space_id, content, fingerprint, updated_at, source_fingerprint, status) " +
            "VALUES (:key, :scope, :spaceId, :content, :fingerprint, :now, :sourceFingerprint, :status) " +
            "ON CONFLICT(section_key, scope, space_id) DO UPDATE SET " +
            "content = :content, fingerprint = :fingerprint, updated_at = :now, source_fingerprint = :sourceFingerprint, status = :status",
    )
    suspend fun updateContent(
        key: String,
        scope: String,
        spaceId: String,
        content: String,
        fingerprint: String?,
        now: String,
        sourceFingerprint: String = "",
        status: String = "READY",
    )

    @Query("UPDATE compiled_sections_scoped SET content = '', fingerprint = NULL, updated_at = :now")
    suspend fun clearAll(now: String)

    @Query(
        "UPDATE compiled_sections_scoped SET content = '', fingerprint = NULL, updated_at = :now WHERE section_key = :key AND scope = :scope AND space_id = :spaceId",
    )
    suspend fun clearByKey(key: String, scope: String, spaceId: String, now: String)

    @Query("DELETE FROM compiled_sections_scoped")
    suspend fun deleteAll()

    /**
     * 助手被删除时清掉该作用域的全部编译段落(所有 space)。
     *
     * 段落是按事实投影出来的产物,事实都删了却留着段落,记忆页与注入内容就会显示已删助手的旧记忆。
     *
     * @return 实际删除的行数
     */
    @Query("DELETE FROM compiled_sections_scoped WHERE scope = :scope")
    suspend fun deleteByScope(scope: String): Int
}
