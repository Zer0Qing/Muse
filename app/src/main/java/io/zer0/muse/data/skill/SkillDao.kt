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

    /**
     * P0-11: 内置技能启动 seed — 仅当主键不存在时插入。
     *
     * 与 [upsert] 的区别:已存在的主键整行保留(含用户手动设置的 enabled=false),
     * 只有全新安装/升级后缺失的内置技能才写入。Room 的 @Upsert 语义是
     * "INSERT ... ON CONFLICT DO UPDATE",仍会用 seed 的 enabled 覆盖用户选择,
     * 故此处用 IGNORE(冲突时跳过)满足"只在字段缺失时初始化"。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun seedIfAbsent(entity: SkillEntity)

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
