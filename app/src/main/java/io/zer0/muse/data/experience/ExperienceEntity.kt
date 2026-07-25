package io.zer0.muse.data.experience

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * v1.98: 经验库条目 — 存储"以后遇到类似任务应该怎么做"的经验性知识。
 *
 * 与普通记忆(fact)的区别:
 * - fact 记录"用户是什么样的人"(静态属性:姓名/职业/偏好)
 * - experience 记录"如何做某类任务"(动态经验:最佳实践/踩坑教训/工作流)
 *
 * 来源:
 * 1. LLM 自动提取(记忆编译时从对话中识别经验性内容)
 * 2. 用户手动添加(记忆页 CRUD)
 *
 * 注入 system prompt:当 experienceEnabled=true 时,把经验条目注入到长期记忆下方,
 * 让 AI 在遇到类似任务时参考过往经验。
 */
@Serializable
@Entity(tableName = "experiences")
data class ExperienceEntity(
    @PrimaryKey
    val id: String,
    /** 标题(简短描述,如"Kotlin 协程超时处理最佳实践") */
    val title: String,
    /** 详细内容(经验正文,可含步骤/注意事项/代码片段) */
    val content: String,
    /** 分类(如"编程"/"工具使用"/"工作流"/"通用"),便于过滤 */
    @ColumnInfo(defaultValue = "通用")
    val category: String = "通用",
    /** 标签(JSON 数组字符串,如 ["kotlin","coroutine"]),用于检索 */
    @ColumnInfo(defaultValue = "[]")
    val tagsJson: String = "[]",
    /** 来源: "manual"(手动添加) / "llm"(LLM 提取) */
    @ColumnInfo(defaultValue = "manual")
    val source: String = "manual",
    /** 提取来源会话 ID(LLM 提取时记录,便于回溯) */
    val sessionId: String? = null,
    /** 创建时间戳 */
    val createdAt: Long = System.currentTimeMillis(),
    /** 更新时间戳 */
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface ExperienceDao {
    @Query("SELECT * FROM experiences ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ExperienceEntity>>

    @Query("SELECT * FROM experiences ORDER BY updatedAt DESC")
    suspend fun getAll(): List<ExperienceEntity>

    @Query("SELECT * FROM experiences WHERE id = :id")
    suspend fun getById(id: String): ExperienceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ExperienceEntity)

    @Query("DELETE FROM experiences WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM experiences")
    suspend fun count(): Int

    @Query("DELETE FROM experiences")
    suspend fun deleteAll()
}
