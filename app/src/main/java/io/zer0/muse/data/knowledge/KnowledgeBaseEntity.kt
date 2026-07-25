package io.zer0.muse.data.knowledge

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * v1.133: 知识库(KB)实体 — 支持多知识库与助手绑定。
 *
 * 一个 KB 包含若干 [KnowledgeDocEntity],通过 [KnowledgeDocEntity.kbId] 关联。
 * 助手通过 [io.zer0.muse.data.assistant.AssistantEntity.knowledgeBaseIdsJson] 绑定一个或多个 KB。
 *
 * 默认 KB:id="default",name="默认知识库"。MIGRATION_38_39 会自动插入。
 */
@Serializable
@Entity(
    tableName = "knowledge_bases",
    indices = [Index(value = ["updated_at"])],
)
data class KnowledgeBaseEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "") val description: String = "",
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    /** 该 KB 下的文档数(冗余字段,Repository 双写维护,避免 GROUP BY)。 */
    @ColumnInfo(name = "doc_count", defaultValue = "0") val docCount: Int = 0,
)

@Dao
interface KnowledgeBaseDao {
    @Query("SELECT * FROM knowledge_bases ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<KnowledgeBaseEntity>>

    @Query("SELECT * FROM knowledge_bases ORDER BY updated_at DESC")
    suspend fun getAll(): List<KnowledgeBaseEntity>

    @Query("SELECT * FROM knowledge_bases WHERE id = :id")
    suspend fun getById(id: String): KnowledgeBaseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(kb: KnowledgeBaseEntity)

    @Query("DELETE FROM knowledge_bases WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM knowledge_bases")
    suspend fun count(): Int
}
