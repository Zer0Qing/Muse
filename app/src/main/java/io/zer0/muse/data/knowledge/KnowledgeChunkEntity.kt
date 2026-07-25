package io.zer0.muse.data.knowledge

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * v1.54: 知识库分块实体(RAG 向量检索单元)。
 *
 * 导入文档时由 [io.zer0.muse.rag.TextChunker] 切分为多个 chunk,每个 chunk 生成 embedding 向量后存此表。
 *
 * v1.133 改造:
 *  - 新增 [embeddingBlob] 列(ByteArray,FloatArray 直接 toByteArray),替代 [embedding] JSON 字符串。
 *    新写入优先用 BLOB,旧 [embedding] JSON 列保留用于读旧数据兼容(MIGRATION_38_39 不做数据迁移)。
 *    读取顺序:embeddingBlob 非空优先,否则 fallback 解析 embedding JSON。
 *  - 新增 [metadataJson] 列(page/section/tags 等元数据,用于过滤)。
 *
 * @param id 主键,格式 "chunk-{docId}-{chunkIndex}"
 * @param docId 关联的 KnowledgeDocEntity.id
 * @param content 分块文本
 * @param embedding JSON 浮点数组字符串(旧数据,已弃用写入,仅兼容读取)
 * @param embeddingBlob BLOB 浮点数组(新数据,FloatArray 直接 toByteArray,5× 加速)
 * @param chunkIndex 块在原文档中的序号(0-based)
 * @param tokenCount 近似 token 数
 * @param metadataJson 元数据 JSON(page/section/tags 等)
 * @param createdAt 创建时间戳
 */
@Serializable
@Entity(
    tableName = "knowledge_chunks",
    indices = [
        Index(value = ["doc_id"]),
        Index(value = ["doc_id", "chunk_index"], unique = true),
    ],
)
data class KnowledgeChunkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "doc_id") val docId: String,
    @ColumnInfo(defaultValue = "") val content: String = "",
    /**
     * 旧 embedding 存储:JSON 浮点数组字符串(如 "[0.012, -0.034, ...]")。
     * v1.133 起仅用于兼容读旧数据,新写入一律走 [embeddingBlob]。
     */
    @ColumnInfo(name = "embedding", defaultValue = "") val embedding: String = "",
    /**
     * v1.133: 新 embedding 存储 — BLOB 浮点数组。
     * 由 FloatArray.toByteArray() 得到,读取时 FloatArray(size) { ByteBuffer.wrap(blob).float }.array()。
     * 相比 JSON 解析约 5× 加速,且体积减半。
     * null 表示尚未生成 embedding,或来自旧版 JSON 数据(读取时 fallback 到 [embedding] 列)。
     */
    @ColumnInfo(name = "embedding_blob", defaultValue = "NULL") val embeddingBlob: ByteArray? = null,
    @ColumnInfo(name = "chunk_index", defaultValue = "0") val chunkIndex: Int = 0,
    @ColumnInfo(name = "token_count", defaultValue = "0") val tokenCount: Int = 0,
    /** v1.133: 元数据 JSON(page/section/tags 等),用于过滤。 */
    @ColumnInfo(name = "metadata_json", defaultValue = "{}") val metadataJson: String = "{}",
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as KnowledgeChunkEntity
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
