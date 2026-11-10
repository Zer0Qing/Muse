package io.zer0.muse.data.knowledge

import kotlinx.serialization.Serializable

/**
 * R2 V2 内容模型 — 原件、完整 revision、preview、chunk/向量四者分开(设计合同)。
 *
 * 与旧 [KnowledgeDocEntity] 的关系:V2 实体是**新增表**,不修改旧表结构;
 * 旧 [KnowledgeDocEntity] 通过兼容投影继续服务现有读路径(R2 保持真实用户写入切换关闭,
 * 合成库先行;切换条件 = R9 便携备份与回退完成)。
 *
 * 身份链:SourceRef(原件)→ KnowledgeRevision(完整版本)→ 预览/chunk 为派生物。
 */

/** 资料来源引用 — 原件可再达性的唯一真源。 */
@Serializable
data class SourceRef(
    /** 来源方案: file / content / archive:// / asset 等与旧 filePath 语义对齐。 */
    val scheme: String,
    /**
     * 方案内地址。file = SAF/绝对路径;content = 行内全文(小文本直接入庠);
     * archive = 归档虚拟路径。空串 = 无原件(SourceMissing)。
     */
    val location: String,
    /** 原件字节 hash(SHA-256,hex);原件缺失时为空串。 */
    val contentHash: String = "",
    /** 原件字节长度;-1 = 未知。 */
    val byteSize: Long = -1L,
) {
    /** 无原件:恢复与重建都拿不到原始字节。 */
    val isMissing: Boolean get() = location.isBlank()
}

/**
 * 完整版本工件 — 编辑与重建的真源;preview 绝不能作为编辑/重建真源(设计合同)。
 */
@Serializable
data class KnowledgeRevision(
    val revisionId: String,
    val docId: String,
    /** 单调递增版本号(每 doc 从 1 起);编辑 API 必须传 expectedRevisionId 做乐观锁。 */
    val revisionNo: Int,
    /** 完整正文字节 hash(SHA-256,hex)。 */
    val contentHash: String,
    /** 完整正文(或 SourceBlob 引用键;大文本走 blob)。 */
    val fullContent: String,
    /** 派生预览(只读投影,截断于上限;不得用于重建)。 */
    val preview: String,
    val createdAt: Long,
    /** 产生该版本的来源(导入/编辑/恢复);审计用。 */
    val origin: String,
)

/** 索引代 — 同文档并发用 generation fencing;新代未完成不覆盖活动代。 */
@Serializable
data class IndexGeneration(
    val generationId: String,
    val docId: String,
    /** 单调递增;发布时原子切换活动指针。 */
    val generationNo: Int,
    /** staging → active;失败代不覆盖活动代。 */
    val state: IndexGenerationState,
    /** 该代分块时使用的 revisionId(内容依赖固定)。 */
    val revisionId: String,
    /** embedding 空间标识(模型+维度);跨空间不得比较分数。 */
    val embeddingSpaceId: String = "",
    val chunkCount: Int = 0,
    val createdAt: Long,
)

@Serializable
enum class IndexGenerationState { STAGING, ACTIVE, FAILED, RETIRED }

/** 数据集纪元 — 恢复/回滚分配新 epoch,旧 Worker/响应/outbox 不得跨 epoch 提交。 */
@Serializable
data class DatasetEpoch(
    val epochId: String,
    /** 单调递增;恢复协调日志分配新值。 */
    val epochNo: Long,
    val createdAt: Long,
    /** 分配原因(import / restore / rollback)。 */
    val reason: String,
)

/** 内容保留租约 — 读取期间固定不可变内容依赖;到期由协调器回收。 */
@Serializable
data class ContentRetentionLease(
    val leaseId: String,
    /** 租约固定的内容键(revision/blob)。 */
    val contentKey: String,
    /** 持有者(Worker/查询)标识。 */
    val holderId: String,
    val acquiredAt: Long,
    val expiresAt: Long,
)
