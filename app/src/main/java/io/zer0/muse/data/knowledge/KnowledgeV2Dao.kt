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

/**
 * R2 V2 持久化层 — 原件/revision/epoch/租约四表(设计合同:四者分开,preview 不是真源)。
 *
 * 全部为**新增表**,不改旧 knowledge_docs/knowledge_chunks 结构;
 * 真实用户写入切换保持关闭(合成库先行,切换条件 = R9 备份回退完成)。
 */

/** V2 文档行 — 持久 owner 合同(计划 R2 步骤 4):归属写进 DB,不依赖内存注册表。 */
@Entity(
    tableName = "knowledge_v2_docs",
    indices = [
        Index(value = ["ownerType", "ownerId"]),
        Index(value = ["folderId"]),
    ],
)
data class KnowledgeV2DocEntity(
    /** 与旧链路 docId 同一命名空间:迁移时旧 docId 原样保留(citation 身份锚点)。 */
    @PrimaryKey val docId: String,
    /**
     * 持久 owner:UserLibrary(kbId) / SessionAttachment(sessionId) / BuiltinHelp(corpusVersion)。
     * 会话删除 = 删 ownerType=SessionAttachment 且 ownerId=该会话的行;附件转存 = 改 owner。
     */
    val ownerType: String,
    val ownerId: String,
    /** 稳定 folderId(R2 步骤 7 的归一目标;空串 = 库根)。 */
    @ColumnInfo(defaultValue = "") val folderId: String = "",
    /** 当前活动 revision。 */
    @ColumnInfo(name = "current_revision_id", defaultValue = "") val currentRevisionId: String = "",
    /** 摄取时的 datasetEpoch(恢复/回滚分配新 epoch 后,旧 epoch 行由协调器处理)。 */
    @ColumnInfo(name = "dataset_epoch", defaultValue = "0") val datasetEpoch: Long = 0L,
    /**
     * R2 步骤 5 补强(SourceRef 落点):原件可再达性唯一真源。
     * JSON 序列化 [io.zer0.muse.data.knowledge.SourceRef](scheme/location/contentHash/byteSize);
     * 旧链迁移行按其可验证信息填充(无原件 = location 空串)。
     */
    @ColumnInfo(name = "source_ref_json", defaultValue = "") val sourceRefJson: String = "",
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = 0L,
)

/** V2 revision 工件 — 完整正文真源;preview 是派生列(读模型)。 */
@Entity(
    tableName = "knowledge_v2_revisions",
    indices = [
        Index(value = ["docId", "revision_no"], unique = true),
        Index(value = ["content_hash"]),
    ],
    // R2 步骤 8:无损可迁行加 FK —— revision 不能引用不存在的 doc(CASCADE:doc 删除时
    // 版本链随行失效;原件 blob 内容寻址,由孤儿清理回收,不静默堆积)。
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = KnowledgeV2DocEntity::class,
            parentColumns = ["docId"],
            childColumns = ["docId"],
            onDelete = androidx.room.ForeignKey.CASCADE,
        ),
    ],
)
data class KnowledgeV2RevisionEntity(
    @PrimaryKey val revisionId: String,
    // 级联删除:文档删除时版本链随之失效(原件 blob 由内容寻址,孤儿可清理)。
    @ColumnInfo(name = "docId") val docId: String,
    @ColumnInfo(name = "revision_no") val revisionNo: Int,
    /** 完整正文 hash(与 blob 文件名一致,内容寻址)。 */
    @ColumnInfo(name = "content_hash") val contentHash: String,
    /**
     * 完整正文:小文本行内存储;超大文本存 blob 引用(hash),两者以 contentHash 可核对。
     * R2 行内存储(合成库规模),blob-only 优化留 R9 备份体积需要时。
     */
    @ColumnInfo(name = "full_content", defaultValue = "") val fullContent: String = "",
    /** blob 引用(content store hash);行内存储时为空串。 */
    @ColumnInfo(name = "blob_ref", defaultValue = "") val blobRef: String = "",
    /** 派生预览(读模型;不参与编辑/重建真源判定)。 */
    @ColumnInfo(name = "preview", defaultValue = "") val preview: String = "",
    @ColumnInfo(defaultValue = "") val origin: String = "",
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = 0L,
)

/** 数据集纪元 — 恢复/回滚协调器分配新 epoch;旧 Worker/outbox 跨 epoch 提交被拒。 */
@Entity(tableName = "knowledge_v2_dataset_epoch")
data class KnowledgeV2DatasetEpochEntity(
    @PrimaryKey val epochId: String,
    /** 单调递增(R2 步骤 5 合同:恢复载荷不能覆盖运行身份)。 */
    @ColumnInfo(name = "epoch_no") val epochNo: Long,
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = 0L,
    /** 分配原因:import / restore / rollback。 */
    @ColumnInfo(defaultValue = "") val reason: String = "",
)

/** 内容保留租约 — 读取期间固定不可变内容依赖;到期由协调器回收。 */
@Entity(
    tableName = "knowledge_v2_retention_leases",
    indices = [Index(value = ["content_key"])],
)
data class KnowledgeV2RetentionLeaseEntity(
    @PrimaryKey val leaseId: String,
    @ColumnInfo(name = "content_key") val contentKey: String,
    @ColumnInfo(name = "holder_id") val holderId: String,
    @ColumnInfo(name = "acquired_at", defaultValue = "0") val acquiredAt: Long = 0L,
    @ColumnInfo(name = "expires_at", defaultValue = "0") val expiresAt: Long = 0L,
)

/**
 * V2 DAO — 新表全部走 Room 正式注册(无 FTS vtable,不需要 @SkipQueryVerification)。
 * R2 写入切换关闭:DAO 已可用(合成库/测试),生产入口接线归 R4+。
 */
@Dao
interface KnowledgeV2Dao {

    // ---------- docs(持久 owner 合同) ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDoc(doc: KnowledgeV2DocEntity)

    @Query("SELECT * FROM knowledge_v2_docs WHERE docId = :docId")
    suspend fun getDoc(docId: String): KnowledgeV2DocEntity?

    /** 持久 owner 查询:同类同主只返回该 owner 的行(跨会话隔离的 DB 级保证)。 */
    @Query("SELECT * FROM knowledge_v2_docs WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun getByOwner(ownerType: String, ownerId: String): List<KnowledgeV2DocEntity>

    /** R9 便携备份:全量文档。 */
    @Query("SELECT * FROM knowledge_v2_docs")
    suspend fun getAllDocs(): List<KnowledgeV2DocEntity>

    /** R9 便携备份:全量 revision 工件。 */
    @Query("SELECT * FROM knowledge_v2_revisions")
    suspend fun getAllRevisions(): List<KnowledgeV2RevisionEntity>

    @Query("SELECT * FROM knowledge_v2_docs WHERE ownerType = :ownerType AND ownerId = :ownerId")
    fun observeByOwner(ownerType: String, ownerId: String): Flow<List<KnowledgeV2DocEntity>>

    /** 会话删除:按 owner 删除(附件随会话消失;转存过的行 owner 已改,不受影响)。 */
    @Query("DELETE FROM knowledge_v2_docs WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun deleteByOwner(ownerType: String, ownerId: String): Int

    @Query("UPDATE knowledge_v2_docs SET ownerId = :newOwnerId, ownerType = :newOwnerType, updated_at = :updatedAt WHERE docId = :docId")
    suspend fun reassignOwner(docId: String, newOwnerType: String, newOwnerId: String, updatedAt: Long)

    @Query("UPDATE knowledge_v2_docs SET current_revision_id = :revisionId, updated_at = :updatedAt WHERE docId = :docId")
    suspend fun setCurrentRevision(docId: String, revisionId: String, updatedAt: Long)

    /** R8 目录删除:取某批目录下的文档。 */
    @Query("SELECT * FROM knowledge_v2_docs WHERE folderId IN (:folderIds)")
    suspend fun getDocsInFolders(folderIds: List<String>): List<KnowledgeV2DocEntity>

    /** R8 目录删除:删除某批目录下的文档(revisions 由 FK CASCADE 清理)。 */
    @Query("DELETE FROM knowledge_v2_docs WHERE folderId IN (:folderIds)")
    suspend fun deleteDocsInFolders(folderIds: List<String>): Int

    /** R8 目录解散:把文档移到父目录。 */
    @Query("UPDATE knowledge_v2_docs SET folderId = :targetFolderId, updated_at = :updatedAt WHERE folderId IN (:folderIds)")
    suspend fun moveDocsToFolder(folderIds: List<String>, targetFolderId: String, updatedAt: Long): Int

    /** R8 删除文档。 */
    @Query("DELETE FROM knowledge_v2_docs WHERE docId = :docId")
    suspend fun deleteDoc(docId: String): Int

    // ---------- revisions(不可变版本链) ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(revision: KnowledgeV2RevisionEntity)

    @Query("SELECT * FROM knowledge_v2_revisions WHERE revisionId = :revisionId")
    suspend fun getRevision(revisionId: String): KnowledgeV2RevisionEntity?

    @Query("SELECT * FROM knowledge_v2_revisions WHERE docId = :docId ORDER BY revision_no ASC")
    suspend fun getRevisionsByDoc(docId: String): List<KnowledgeV2RevisionEntity>

    // ---------- dataset epoch(恢复/回滚身份) ----------

    /**
     * R2 步骤 5 合同实现:epoch 严格单调 —— 新 epochNo 必须 > 当前最大值。
     * 恢复载荷携带的旧 epoch 在此被拒(SQLite 层条件插入),运行身份不被覆盖。
     * @return 插入行的 rowid;-1 = 被单调守卫拒绝(调用方必须处理,不得静默)。
     */
    @Query(
        "INSERT INTO knowledge_v2_dataset_epoch (epochId, epoch_no, created_at, reason) " +
            "SELECT :epochId, :epochNo, :createdAt, :reason " +
            "WHERE :epochNo > COALESCE((SELECT MAX(epoch_no) FROM knowledge_v2_dataset_epoch), 0)",
    )
    suspend fun insertEpochIfMonotonic(epochId: String, epochNo: Long, createdAt: Long, reason: String): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEpoch(epoch: KnowledgeV2DatasetEpochEntity)

    @Query("SELECT * FROM knowledge_v2_dataset_epoch ORDER BY epoch_no DESC LIMIT 1")
    suspend fun getLatestEpoch(): KnowledgeV2DatasetEpochEntity?

    // ---------- retention leases(不可变内容依赖) ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLease(lease: KnowledgeV2RetentionLeaseEntity)

    @Query("SELECT * FROM knowledge_v2_retention_leases WHERE content_key = :contentKey AND expires_at > :nowMs")
    suspend fun getActiveLeases(contentKey: String, nowMs: Long): List<KnowledgeV2RetentionLeaseEntity>

    @Query("DELETE FROM knowledge_v2_retention_leases WHERE expires_at <= :nowMs")
    suspend fun deleteExpiredLeases(nowMs: Long): Int

    // ---------- R9 备份恢复:清空(V2 真源) ----------

    @Query("DELETE FROM knowledge_v2_revisions")
    suspend fun deleteAllRevisions(): Int

    @Query("DELETE FROM knowledge_v2_docs")
    suspend fun deleteAllDocs(): Int

    @Query("DELETE FROM knowledge_v2_dataset_epoch")
    suspend fun deleteAllEpochs(): Int

    @Query("DELETE FROM knowledge_v2_retention_leases")
    suspend fun deleteAllLeases(): Int
}
