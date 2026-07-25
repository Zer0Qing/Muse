package io.zer0.muse.data.skill

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Phase 8.8: Skill DAO。
 */
@Dao
interface SkillDao {
    // L-SD2: observeAll 加 LIMIT 200,避免 skill 表过大时一次性加载全部到内存
    @Query("SELECT * FROM skills ORDER BY category ASC, name ASC LIMIT 200")
    fun observeAll(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills WHERE enabled = 1 ORDER BY category ASC, name ASC")
    suspend fun listEnabled(): List<SkillEntity>

    // M-SR1: 按 id 列表查询启用的 skill,避免全量加载再内存 filter
    @Query("SELECT * FROM skills WHERE enabled = 1 AND id IN (:ids) ORDER BY category ASC, name ASC")
    suspend fun listEnabledByIds(ids: List<String>): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE id = :id")
    suspend fun getById(id: String): SkillEntity?

    // M-SE12/L-SD3: 用 OnConflictStrategy.REPLACE。
    // 风险评估:当前 skills 表无外键引用(其它实体未声明 FOREIGN KEY → skills(id)),
    // 因此 REPLACE 不会触发级联删除。若后续新增引用 skills 的外键,需改用 @Upsert。
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SkillEntity)

    @Update
    suspend fun update(entity: SkillEntity)

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE skills SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long)

    @Query("SELECT * FROM skills")
    suspend fun getAll(): List<SkillEntity>

    @Query("DELETE FROM skills")
    suspend fun deleteAll()
}
