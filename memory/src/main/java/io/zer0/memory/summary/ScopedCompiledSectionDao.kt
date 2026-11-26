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

    /** 指定 Space 下出现过编译段落的作用域（合并前读，用来知道哪些 scope 要重建）。 */
    @Query("SELECT DISTINCT scope FROM compiled_sections_scoped WHERE space_id = :spaceId")
    suspend fun scopesForSpace(spaceId: String): List<String>

    /** Space 消失时清掉它的全部段落（派生自那个空间的结论不再有意义）。 */
    @Query("DELETE FROM compiled_sections_scoped WHERE space_id = :spaceId")
    suspend fun deleteForSpace(spaceId: String): Int

    /**
     * 把一组段落标成指定状态而不动正文。
     *
     * 用于 Space 合并：TODAY/WEEK/LONGTERM 没有可立即重建的数据源，只能先标失效，
     * 让界面与 Dream 快照不再把旧文本当可信版本（内容留到下一轮编译覆盖）。
     */
    @Query(
        "UPDATE compiled_sections_scoped SET status = :status, updated_at = :now " +
            "WHERE scope = :scope AND space_id = :spaceId AND section_key IN (:sectionKeys)",
    )
    suspend fun markStatus(scope: String, spaceId: String, sectionKeys: List<String>, status: String, now: String): Int

    @Query("DELETE FROM compiled_sections_scoped WHERE scope = :scope AND space_id = :spaceId")
    suspend fun deleteForScope(scope: String, spaceId: String)

    /** 回滚 Dream 新增的段落时按 key 删除，不动同作用域的其它行。 */
    @Query("DELETE FROM compiled_sections_scoped WHERE section_key = :key AND scope = :scope AND space_id = :spaceId")
    suspend fun delete(key: String, scope: String, spaceId: String): Int

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
