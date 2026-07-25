package io.zer0.muse.data.knowledge

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "knowledge_docs",
    indices = [
        Index(value = ["updated_at"]),
        // v1.133: 多知识库 — 按 kb_id 索引加速"列出 KB 下文档"
        Index(value = ["kb_id"]),
        // v1.133: 增量更新 — 按 content_hash 索引加速"同 hash 跳过"
        Index(value = ["content_hash"]),
    ],
)
data class KnowledgeDocEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(defaultValue = "") val content: String = "",
    @ColumnInfo(name = "file_path", defaultValue = "") val filePath: String = "",
    @ColumnInfo(name = "file_type", defaultValue = "") val fileType: String = "",  // pdf/txt/md
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    /** v1.54: 分块数(0 表示尚未索引,>0 表示已分块并生成 embedding)。 */
    @ColumnInfo(name = "chunk_count", defaultValue = "0") val chunkCount: Int = 0,
    /** v1.54: 索引时使用的 embedding 模型名(空串表示未索引)。 */
    @ColumnInfo(name = "embedding_model", defaultValue = "") val embeddingModel: String = "",

    // ── v1.133 新增字段(多知识库 / 增量更新 / 元数据过滤)──

    /**
     * v1.133: 所属知识库 ID。空串或 "default" 表示默认库(向后兼容)。
     * MIGRATION_38_39 会把所有现存文档的 kb_id 填为 "default"。
     */
    @ColumnInfo(name = "kb_id", defaultValue = "default") val kbId: String = "default",

    /**
     * v1.133: 内部文档标记(预置开发文档 devdoc 等)。true 时不参与用户库检索,
     * 仅用于 SkillExecutor knowledge_search 内部调用。
     * 替代原 `fileType="devdoc"` + `docId.startsWith("devdoc-")` 双重硬编码过滤。
     */
    @ColumnInfo(name = "is_internal", defaultValue = "0") val isInternal: Boolean = false,

    /**
     * v1.133: 内容哈希(SHA-256 of content),用于增量更新判断。
     * 导入同名文档时若 hash 一致则跳过重索引,不同则触发重索引。
     */
    @ColumnInfo(name = "content_hash", defaultValue = "") val contentHash: String = "",

    /**
     * v1.133: 元数据 JSON(source/page/section/tags 等),用于元数据过滤。
     * 例:{"source":"upload","page":12,"section":"第二章","tags":["法律","合同"]}
     */
    @ColumnInfo(name = "metadata_json", defaultValue = "{}") val metadataJson: String = "{}",
)
