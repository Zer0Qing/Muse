package io.zer0.muse.data.knowledge

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * R4 持久作业账本 + generation 化索引表(计划 R4 §8;路线 A:V2 表内扩展,旧表不动)。
 *
 * 表清单(均新增;旧 knowledge_chunks/knowledge_chunks_fts 保持不变,继续服务现有读路径):
 *  - knowledge_v2_jobs:批次总账(一次导入/重建)
 *  - knowledge_v2_job_items:逐文件账本(阶段、checkpoint、lease/fence、取消意图、结果)
 *  - knowledge_v2_generations:索引代(staging→active;同文档并发 fencing)
 *  - knowledge_v2_gen_chunks:某代的 chunk 行(join 活动代才能查询,暂存行不可见)
 *  - knowledge_v2_gen_lexical:某代的词法行(「词法记录已完整可查」的判定面)
 *  - knowledge_v2_active_generation:活动代指针(docId → generationId;独立表避开 126 docs 变更)
 *
 * 查询必须 join 活动代,因此 staging 行不会提前可见(计划 R4 §8.3 步骤 2)。
 */

/** 批次总账 — 一次导入/重建作业(可含多文件)。 */
@Entity(
    tableName = "knowledge_v2_jobs",
    indices = [Index(value = ["state"])],
)
data class KnowledgeJobEntity(
    @PrimaryKey val jobId: String,
    @ColumnInfo(name = "owner_type") val ownerType: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    /** [io.zer0.muse.knowledge.domain.KnowledgeJobState].name */
    @ColumnInfo val state: String,
    @ColumnInfo(name = "dataset_epoch", defaultValue = "0") val datasetEpoch: Long = 0L,
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = 0L,
)

/** 逐文件账本 — 领取/checkpoint/取消/结果的唯一真源(WorkManager 只是调度器)。 */
@Entity(
    tableName = "knowledge_v2_job_items",
    indices = [
        Index(value = ["jobId"]),
        Index(value = ["doc_id"]),
        Index(value = ["state"]),
    ],
)
data class KnowledgeJobItemEntity(
    @PrimaryKey val jobItemId: String,
    @ColumnInfo(name = "jobId") val jobId: String,
    /** 目标文档 id;创建后恒定(重入不重复创建 document)。 */
    @ColumnInfo(name = "doc_id") val docId: String,
    /** [io.zer0.muse.knowledge.domain.KnowledgeJobState].name */
    @ColumnInfo val state: String,
    /** [io.zer0.muse.knowledge.domain.KnowledgeJobStage].name。 */
    @ColumnInfo val stage: String,
    /** 阶段内 checkpoint(记录已处理 bytes/pages/chunks,不拼成完成时间)。 */
    @ColumnInfo(name = "checkpoint", defaultValue = "") val checkpoint: String = "",
    @ColumnInfo(name = "dataset_epoch", defaultValue = "0") val datasetEpoch: Long = 0L,
    /** 文档级 generation fence(单调;旧任务提交被拒)。 */
    @ColumnInfo(name = "generation_fence", defaultValue = "0") val generationFence: Long = 0L,
    /** 取消意图先落库:非空表示已请求取消。 */
    @ColumnInfo(name = "cancel_requested", defaultValue = "0") val cancelRequested: Int = 0,
    @ColumnInfo(name = "lease_holder", defaultValue = "") val leaseHolder: String = "",
    @ColumnInfo(name = "lease_expires_at", defaultValue = "0") val leaseExpiresAt: Long = 0L,
    @ColumnInfo(name = "result_reason", defaultValue = "") val resultReason: String = "",
    @ColumnInfo(name = "attempts", defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = 0L,
)

/** 索引代 — 同文档并发 fencing;新代未完成不覆盖活动代。 */
@Entity(
    tableName = "knowledge_v2_generations",
    indices = [
        Index(value = ["docId", "generation_no"], unique = true),
        Index(value = ["docId"]),
        Index(value = ["state"]),
    ],
)
data class KnowledgeGenerationEntity(
    @PrimaryKey val generationId: String,
    @ColumnInfo(name = "docId") val docId: String,
    @ColumnInfo(name = "generation_no") val generationNo: Int,
    /** [io.zer0.muse.data.knowledge.IndexGenerationState].name */
    @ColumnInfo val state: String,
    @ColumnInfo(name = "revision_id", defaultValue = "") val revisionId: String = "",
    @ColumnInfo(name = "embedding_space_id", defaultValue = "") val embeddingSpaceId: String = "",
    @ColumnInfo(name = "chunk_count", defaultValue = "0") val chunkCount: Int = 0,
    /** R5: 向量批是否已完整验证并写入(vectorReady);false 时不得报 HybridReady。 */
    @ColumnInfo(name = "vector_ready", defaultValue = "0") val vectorReady: Int = 0,
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = 0L,
)

/** 某代的 chunk 行 — 查询 join 活动代;staging 行不可见。 */
@Entity(
    tableName = "knowledge_v2_gen_chunks",
    indices = [
        Index(value = ["generationId", "chunk_index"], unique = true),
        Index(value = ["generationId"]),
    ],
)
data class KnowledgeGenChunkEntity(
    /** 主键 = generationId + '#' + index(代内唯一;不是跨版本身份)。 */
    @PrimaryKey val id: String,
    @ColumnInfo(name = "generationId") val generationId: String,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    @ColumnInfo(defaultValue = "") val content: String = "",
    @ColumnInfo(defaultValue = "") val locator: String = "",
    @ColumnInfo(defaultValue = "") val kind: String = "",
    @ColumnInfo(name = "token_count", defaultValue = "0") val tokenCount: Int = 0,
    @ColumnInfo(name = "metadata_json", defaultValue = "{}") val metadataJson: String = "{}",
    /** R5: 该 chunk 的向量(FloatArray.toByteArray);null = 尚未生成/未发布。 */
    @ColumnInfo(name = "embedding_blob", defaultValue = "NULL") val embeddingBlob: ByteArray? = null,
)

/** 某代的词法行 — 「词法记录已完整可查」的判定面(R4 词法发布前提)。 */
@Entity(
    tableName = "knowledge_v2_gen_lexical",
    indices = [
        Index(value = ["generationId", "chunk_index"], unique = true),
        Index(value = ["generationId"]),
    ],
)
data class KnowledgeGenLexicalEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "generationId") val generationId: String,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    @ColumnInfo(name = "lexical_text", defaultValue = "") val lexicalText: String = "",
)

/** 活动代指针 — 独立表以避开 126 docs 实体 schema 变更(R4 增量,127 建表)。 */
@Entity(
    tableName = "knowledge_v2_active_generation",
    indices = [Index(value = ["generationId"])],
)
data class KnowledgeActiveGenerationEntity(
    @PrimaryKey val docId: String,
    @ColumnInfo(name = "generationId") val generationId: String,
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = 0L,
)

/**
 * 数据库事务边界 — 让上层服务(代发布/作业入队/生命周期)声明「这一段必须原子」,
 * 而不必各自持有 RoomDatabase。
 *
 * [direct] 不开启事务,只用于没有数据库句柄的单元测试;生产装配必须用 [room],
 * 否则多步写入在中途失败时会留下半套状态。
 */
interface KnowledgeTransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T

    companion object {
        /** 无外层事务(测试用):逐条写入,失败后由调用方自行补偿。 */
        fun direct(): KnowledgeTransactionRunner = object : KnowledgeTransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        }

        /** Room 事务:块内所有 DAO 调用共用同一事务,异常即整体回滚。 */
        fun room(db: androidx.room.RoomDatabase): KnowledgeTransactionRunner =
            object : KnowledgeTransactionRunner {
                override suspend fun <T> run(block: suspend () -> T): T =
                    db.withTransaction { block() }
            }
    }
}

/** R6 词法召回投影 — 词法行 + chunk 正文/定位 + 文档归属。 */
data class KnowledgeLexicalRow(
    val generationId: String,
    val chunkIndex: Int,
    val lexicalText: String,
    val content: String,
    val locator: String,
    val docId: String,
)

/** R6 向量召回投影 — chunk 正文/定位 + 文档归属 + 向量字节。 */
data class KnowledgeVectorRow(
    val generationId: String,
    val chunkIndex: Int,
    val content: String,
    val locator: String,
    val docId: String,
    val embeddingBlob: ByteArray,
)

@Dao
interface KnowledgeJobDao {

    // ---------- 批次 ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJob(job: KnowledgeJobEntity)

    @Query("SELECT * FROM knowledge_v2_jobs WHERE jobId = :jobId")
    suspend fun getJob(jobId: String): KnowledgeJobEntity?

    @Query("UPDATE knowledge_v2_jobs SET state = :state, updated_at = :updatedAt WHERE jobId = :jobId")
    suspend fun updateJobState(jobId: String, state: String, updatedAt: Long)

    // ---------- 逐文件账本 ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJobItem(item: KnowledgeJobItemEntity)

    @Query("SELECT * FROM knowledge_v2_job_items WHERE jobItemId = :jobItemId")
    suspend fun getJobItem(jobItemId: String): KnowledgeJobItemEntity?

    @Query("SELECT * FROM knowledge_v2_job_items WHERE jobId = :jobId ORDER BY created_at ASC")
    suspend fun getJobItems(jobId: String): List<KnowledgeJobItemEntity>

    @Query("SELECT * FROM knowledge_v2_job_items WHERE jobId = :jobId AND state = :state")
    suspend fun getJobItemsByState(jobId: String, state: String): List<KnowledgeJobItemEntity>

    @Query("SELECT * FROM knowledge_v2_job_items WHERE jobId = :jobId ORDER BY created_at ASC")
    fun observeJobItems(jobId: String): Flow<List<KnowledgeJobItemEntity>>

    /**
     * 原子领取:仅当(无人持有 或 lease 已过期)且非终态时,把持有者写为自己。
     * @return 受影响行数;0 = 已被别的 Worker 领走或已终态。
     */
    @Query(
        "UPDATE knowledge_v2_job_items SET lease_holder = :holder, lease_expires_at = :expiresAt, " +
            "state = :runningState, attempts = attempts + 1, updated_at = :nowMs " +
            "WHERE jobItemId = :jobItemId " +
            "AND state NOT IN (:terminalStates) " +
            "AND (lease_holder = '' OR lease_expires_at <= :nowMs)",
    )
    suspend fun tryAcquire(
        jobItemId: String,
        holder: String,
        expiresAt: Long,
        runningState: String,
        terminalStates: List<String>,
        nowMs: Long,
    ): Int

    /** 推进阶段 + checkpoint(仅本持有者)。 */
    @Query(
        "UPDATE knowledge_v2_job_items SET stage = :stage, checkpoint = :checkpoint, updated_at = :updatedAt " +
            "WHERE jobItemId = :jobItemId AND lease_holder = :holder",
    )
    suspend fun updateProgress(jobItemId: String, holder: String, stage: String, checkpoint: String, updatedAt: Long): Int

    /** 请求取消:先落库(非终态转 CANCEL_REQUESTED)。 */
    @Query(
        "UPDATE knowledge_v2_job_items SET cancel_requested = 1, " +
            "state = CASE WHEN state IN (:terminalStates) THEN state ELSE :cancelRequestedState END, " +
            "updated_at = :updatedAt WHERE jobItemId = :jobItemId",
    )
    suspend fun requestCancel(jobItemId: String, cancelRequestedState: String, terminalStates: List<String>, updatedAt: Long): Int

    /**
     * 终态提交 CAS:仅当仍为本持有者、fence 未变、且非终态时才写终态。
     * @return 1 = 提交成功;0 = 已终态/被抢占/fence 已变。
     */
    @Query(
        "UPDATE knowledge_v2_job_items SET state = :terminalState, stage = :stage, result_reason = :reason, " +
            "lease_holder = '', lease_expires_at = 0, updated_at = :updatedAt " +
            "WHERE jobItemId = :jobItemId AND state NOT IN (:terminalStates) " +
            "AND lease_holder = :holder AND generation_fence = :expectedFence",
    )
    suspend fun commitTerminal(
        jobItemId: String,
        holder: String,
        expectedFence: Long,
        terminalState: String,
        stage: String,
        reason: String,
        terminalStates: List<String>,
        updatedAt: Long,
    ): Int

    @Query("UPDATE knowledge_v2_job_items SET generation_fence = :fence WHERE jobItemId = :jobItemId")
    suspend fun setGenerationFence(jobItemId: String, fence: Long)

    /**
     * 重试回排:把可重试失败项从 FAILED 退回 QUEUED,并释放租约。
     *
     * 必需而不是可选的:JobRunner 把可重试失败也落成 FAILED 终态,而终态不再被领取。
     * 没有这一步,Worker 返回 retry 只会重跑一遍空转。
     * @return 1 = 已回排;0 = 已被其他路径改写(不强行覆盖)。
     */
    @Query(
        "UPDATE knowledge_v2_job_items SET state = :queuedState, lease_holder = '', lease_expires_at = 0, " +
            "updated_at = :updatedAt WHERE jobItemId = :jobItemId AND state = :failedState",
    )
    suspend fun requeueFailedItem(
        jobItemId: String,
        failedState: String,
        queuedState: String,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM knowledge_v2_job_items WHERE jobId = :jobId")
    suspend fun deleteJobItems(jobId: String)

    // ---------- generation 表 ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGeneration(gen: KnowledgeGenerationEntity)

    /**
     * 并发安全的代号占位:(docId, generation_no) 唯一索引冲突时返回 -1 而不是抛异常,
     * 调用方据此重算下一个代号。避免「两个 Worker 同时 max+1 抢同一号」变成永久失败。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGenerationIfAbsent(gen: KnowledgeGenerationEntity): Long

    /** 某文档的全部代(生命周期删除时按代清理 chunk/词法行)。 */
    @Query("SELECT generationId FROM knowledge_v2_generations WHERE docId = :docId")
    suspend fun getGenerationIdsByDoc(docId: String): List<String>

    /** 删除文档时连同其代行一起清掉(行已无用,残留会让协调器反复扫描)。 */
    @Query("DELETE FROM knowledge_v2_generations WHERE docId = :docId")
    suspend fun deleteGenerationsByDoc(docId: String): Int

    /**
     * 删除前的可见性撤回:该文档所有未 RETIRED 的代一律置 RETIRED。
     * 只改最新一代会留下早先的 ACTIVE 残留,协调器会反复把它们当悬挂状态处理。
     */
    @Query("UPDATE knowledge_v2_generations SET state = :retiredState WHERE docId = :docId AND state != :retiredState")
    suspend fun retireGenerationsByDoc(docId: String, retiredState: String): Int

    @Query("SELECT * FROM knowledge_v2_generations WHERE generationId = :generationId")
    suspend fun getGeneration(generationId: String): KnowledgeGenerationEntity?

    @Query("SELECT * FROM knowledge_v2_generations WHERE docId = :docId ORDER BY generation_no DESC LIMIT 1")
    suspend fun getLatestGeneration(docId: String): KnowledgeGenerationEntity?

    @Query("SELECT COALESCE(MAX(generation_no), 0) FROM knowledge_v2_generations WHERE docId = :docId")
    suspend fun maxGenerationNo(docId: String): Int

    /** R4 协调器:按代状态列出候选文档(避免全表扫描文档)。 */
    @Query("SELECT DISTINCT docId FROM knowledge_v2_generations WHERE state IN (:states)")
    suspend fun getDocIdsByGenerationStates(states: List<String>): List<String>

    @Query("UPDATE knowledge_v2_generations SET state = :state WHERE generationId = :generationId")
    suspend fun updateGenerationState(generationId: String, state: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGenChunks(chunks: List<KnowledgeGenChunkEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGenLexical(rows: List<KnowledgeGenLexicalEntity>)

    @Query("SELECT COUNT(*) FROM knowledge_v2_gen_chunks WHERE generationId = :generationId")
    suspend fun countGenChunks(generationId: String): Int

    @Query("SELECT COUNT(*) FROM knowledge_v2_gen_lexical WHERE generationId = :generationId")
    suspend fun countGenLexical(generationId: String): Int

    @Query("SELECT * FROM knowledge_v2_gen_chunks WHERE generationId = :generationId ORDER BY chunk_index ASC")
    suspend fun getGenChunks(generationId: String): List<KnowledgeGenChunkEntity>

    /** R5: 写入单条 chunk 的向量(仅限 staging 代;活动代内容不可变)。 */
    @Query("UPDATE knowledge_v2_gen_chunks SET embedding_blob = :blob WHERE id = :chunkRowId")
    suspend fun updateGenChunkEmbedding(chunkRowId: String, blob: ByteArray)

    /** R5: 标记向量批完整就绪;仅 staging 代可标(发布后不可补写)。 */
    @Query("UPDATE knowledge_v2_generations SET vector_ready = 1 WHERE generationId = :generationId")
    suspend fun markVectorReady(generationId: String)

    @Query("SELECT vector_ready FROM knowledge_v2_generations WHERE generationId = :generationId")
    suspend fun isVectorReady(generationId: String): Int?

    /**
     * R6 向量召回:取指定活动代内已写入向量的 chunk 行。
     * 只返回 `embedding_blob` 非空的行 —— 未完成向量批次不可被 dense 查询看见。
     * 范围(活动代列表)在候选配额前生效。
     */
    @Query(
        "SELECT c.generationId AS generationId, c.chunk_index AS chunkIndex, c.content AS content, " +
            "c.locator AS locator, g.docId AS docId, c.embedding_blob AS embeddingBlob " +
            "FROM knowledge_v2_gen_chunks c " +
            "INNER JOIN knowledge_v2_generations g ON g.generationId = c.generationId " +
            "WHERE c.generationId IN (:generationIds) AND c.embedding_blob IS NOT NULL " +
            "AND c.embedding_blob != x'' LIMIT :limit",
    )
    suspend fun getVectorRows(generationIds: List<String>, limit: Int): List<KnowledgeVectorRow>

    @Query("DELETE FROM knowledge_v2_gen_chunks WHERE generationId = :generationId")
    suspend fun deleteGenChunks(generationId: String)

    @Query("DELETE FROM knowledge_v2_gen_lexical WHERE generationId = :generationId")
    suspend fun deleteGenLexical(generationId: String)

    /**
     * R6 词法召回:取指定活动代内的词法行 + chunk 正文/定位 + 文档归属。
     * 范围(活动代列表)在候选配额前生效;LIMIT 约束候选预算。
     */
    @Query(
        "SELECT l.generationId AS generationId, l.chunk_index AS chunkIndex, " +
            "l.lexical_text AS lexicalText, c.content AS content, c.locator AS locator, g.docId AS docId " +
            "FROM knowledge_v2_gen_lexical l " +
            "INNER JOIN knowledge_v2_gen_chunks c ON c.generationId = l.generationId AND c.chunk_index = l.chunk_index " +
            "INNER JOIN knowledge_v2_generations g ON g.generationId = l.generationId " +
            "WHERE l.generationId IN (:generationIds) LIMIT :limit",
    )
    suspend fun getLexicalRows(generationIds: List<String>, limit: Int): List<KnowledgeLexicalRow>

    /**
     * R6 词法召回(单关键词):在 SQL 侧过滤词项后再 LIMIT —— 避免"先取前 N 行再内存过滤"
     * 漏掉排在预算之后的命中(正确性缺陷,非仅性能问题)。
     */
    @Query(
        "SELECT l.generationId AS generationId, l.chunk_index AS chunkIndex, " +
            "l.lexical_text AS lexicalText, c.content AS content, c.locator AS locator, g.docId AS docId " +
            "FROM knowledge_v2_gen_lexical l " +
            "INNER JOIN knowledge_v2_gen_chunks c ON c.generationId = l.generationId AND c.chunk_index = l.chunk_index " +
            "INNER JOIN knowledge_v2_generations g ON g.generationId = l.generationId " +
            "WHERE l.generationId IN (:generationIds) AND l.lexical_text LIKE '%' || :term || '%' ESCAPE '\\' " +
            "LIMIT :limit",
    )
    suspend fun searchLexicalRows(
        generationIds: List<String>,
        term: String,
        limit: Int,
    ): List<KnowledgeLexicalRow>

    // ---------- 活动代指针 ----------

    @Query("SELECT generationId FROM knowledge_v2_active_generation WHERE docId = :docId")
    suspend fun getActiveGeneration(docId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertActiveGeneration(pointer: KnowledgeActiveGenerationEntity)

    @Query("DELETE FROM knowledge_v2_active_generation WHERE docId = :docId")
    suspend fun clearActiveGeneration(docId: String)

    // ---------- 生命周期删除:内容保留租约 / KB 存在性 ----------

    /**
     * 回收被删文档的保留租约(按内容键)。租约表没有 docId 列,内容键是唯一关联,
     * 因此由调用方传入待删文档的内容键集合。
     */
    @Query("DELETE FROM knowledge_v2_retention_leases WHERE content_key IN (:contentKeys)")
    suspend fun deleteLeasesByContentKeys(contentKeys: List<String>): Int

    /** 恢复 KB 绑定时校验目标库真实存在(绑定不隐式改指全库)。 */
    @Query("SELECT COUNT(*) FROM knowledge_bases WHERE id = :kbId")
    suspend fun countKnowledgeBase(kbId: String): Int

    // ---------- R9 备份恢复:清空 ----------

    @Query("DELETE FROM knowledge_v2_gen_lexical")
    suspend fun deleteAllGenLexical(): Int

    @Query("DELETE FROM knowledge_v2_gen_chunks")
    suspend fun deleteAllGenChunks(): Int

    @Query("DELETE FROM knowledge_v2_generations")
    suspend fun deleteAllGenerations(): Int

    @Query("DELETE FROM knowledge_v2_active_generation")
    suspend fun deleteAllActiveGenerations(): Int

    @Query("DELETE FROM knowledge_v2_job_items")
    suspend fun deleteAllJobItems(): Int

    @Query("DELETE FROM knowledge_v2_jobs")
    suspend fun deleteAllJobs(): Int
}
