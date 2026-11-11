package io.zer0.muse.knowledge.backup

import io.zer0.common.Logger
import io.zer0.muse.data.knowledge.KnowledgeFolderV2Dao
import io.zer0.muse.data.knowledge.KnowledgeJobDao
import io.zer0.muse.data.knowledge.KnowledgeV2BindingEntity
import io.zer0.muse.data.knowledge.KnowledgeV2Dao
import io.zer0.muse.data.knowledge.KnowledgeV2DocEntity
import io.zer0.muse.data.knowledge.KnowledgeV2FolderEntity
import io.zer0.muse.data.knowledge.KnowledgeV2RevisionEntity
import io.zer0.muse.knowledge.index.GenerationPublisher
import io.zer0.muse.knowledge.ingest.StructuredChunker
import io.zer0.muse.knowledge.storage.KnowledgeContentStore
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * R9 便携备份 — 设计 §14.1「便携备份」与计划 R9。
 *
 * 备份必须包含:source 引用、**完整 revision artifact**、目录、绑定、coverage、不可再生 legacy chunks。
 * 向量可选(可重建),ANN/cache 不是必需内容。
 *
 * 恢复协议(设计 §14.2):
 *  1. 校验版本与清单 → 分配**不复用**的新 datasetEpoch;
 *  2. 逐条校验 revision 内容 hash(不通过则整体失败,不发布半套);
 *  3. 依赖顺序恢复:folders → docs → revisions → 词法重建 → bindings;
 *  4. 词法重建通过才 KeywordReady;无完整正文的 ChunkOnly 资料保持 StoredOnly,**绝不假装 HybridReady**。
 */
@Serializable
data class KnowledgeBackupManifest(
    /** 备份格式版本;未知的更新版本必须拒绝,不静默按当前 schema 接收。 */
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val snapshotId: String,
    /** 导出时的 datasetEpoch(恢复时**不**沿用,只记录)。 */
    val datasetEpoch: Long,
    val createdAt: Long,
    val appSchemaVersion: Int,
    val docCount: Int,
    val revisionCount: Int,
    val folderCount: Int,
    val bindingCount: Int,
    /** 是否包含向量(本实现暂不导出向量,恒 false;可重建)。 */
    val includesVectors: Boolean = false,
) {
    companion object {
        /** 当前便携备份格式版本。 */
        const val CURRENT_FORMAT_VERSION = 1
    }
}

@Serializable
data class KnowledgeBackupDoc(
    val docId: String,
    val ownerType: String,
    val ownerId: String,
    val folderId: String,
    val currentRevisionId: String,
    val sourceRefJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class KnowledgeBackupRevision(
    val revisionId: String,
    val docId: String,
    val revisionNo: Int,
    val contentHash: String,
    val fullContent: String,
    val preview: String,
    val origin: String,
    val createdAt: Long,
)

@Serializable
data class KnowledgeBackupFolder(
    val folderId: String,
    val kbId: String,
    val parentId: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class KnowledgeBackupBinding(
    val bindingId: String,
    val ownerType: String,
    val ownerId: String,
    val targetType: String,
    val targetId: String,
)

@Serializable
data class KnowledgePortableBackup(
    val manifest: KnowledgeBackupManifest,
    val docs: List<KnowledgeBackupDoc>,
    val revisions: List<KnowledgeBackupRevision>,
    val folders: List<KnowledgeBackupFolder>,
    val bindings: List<KnowledgeBackupBinding>,
    /** 仅剩 chunks 的旧资料的 docId —— 这些不能宣称重建出完整正文。 */
    val legacyChunkOnlyDocIds: List<String> = emptyList(),
)

/** 恢复报告 — 逐项状态,不把部分成功报成全部成功。 */
data class RestoreReport(
    val success: Boolean,
    val newDatasetEpoch: Long,
    val restoredDocs: Int,
    val restoredRevisions: Int,
    val keywordReadyDocs: Int,
    val storedOnlyDocIds: List<String>,
    val missingContentDocIds: List<String>,
    val failureReason: String? = null,
    val warnings: List<String> = emptyList(),
)

class KnowledgePortableBackupService(
    private val v2Dao: KnowledgeV2Dao,
    private val folderDao: KnowledgeFolderV2Dao,
    private val jobDao: KnowledgeJobDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newSnapshotId: () -> String = { "snap-${UUID.randomUUID()}" },
    private val appSchemaVersion: Int = 129,
) {

    /** 导出便携备份(纯数据;文件打包由调用方负责)。 */
    suspend fun export(): KnowledgePortableBackup {
        val docs = v2Dao.getAllDocs()
        val revisions = v2Dao.getAllRevisions()
        val folders = folderDao.getAllFolders()
        val bindings = folderDao.getAllBindings()
        val now = clock()
        val latestEpoch = v2Dao.getLatestEpoch()?.epochNo ?: 0L

        val revisionByDoc = revisions.groupBy { it.docId }
        val chunkOnlyDocs = docs.mapNotNull { doc ->
            val revs = revisionByDoc[doc.docId].orEmpty()
            val active = revs.firstOrNull { it.revisionId == doc.currentRevisionId }
            // ChunkOnly 判定:当前 revision 完整正文为空(R2 迁移的只读快照)。
            if (active != null && active.fullContent.isBlank()) doc.docId else null
        }

        return KnowledgePortableBackup(
            manifest = KnowledgeBackupManifest(
                snapshotId = newSnapshotId(),
                datasetEpoch = latestEpoch,
                createdAt = now,
                appSchemaVersion = appSchemaVersion,
                docCount = docs.size,
                revisionCount = revisions.size,
                folderCount = folders.size,
                bindingCount = bindings.size,
            ),
            docs = docs.map {
                KnowledgeBackupDoc(
                    docId = it.docId,
                    ownerType = it.ownerType,
                    ownerId = it.ownerId,
                    folderId = it.folderId,
                    currentRevisionId = it.currentRevisionId,
                    sourceRefJson = it.sourceRefJson,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            },
            revisions = revisions.map {
                KnowledgeBackupRevision(
                    revisionId = it.revisionId,
                    docId = it.docId,
                    revisionNo = it.revisionNo,
                    contentHash = it.contentHash,
                    fullContent = it.fullContent,
                    preview = it.preview,
                    origin = it.origin,
                    createdAt = it.createdAt,
                )
            },
            folders = folders.map {
                KnowledgeBackupFolder(
                    folderId = it.folderId,
                    kbId = it.kbId,
                    parentId = it.parentId,
                    name = it.name,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            },
            bindings = bindings.map {
                KnowledgeBackupBinding(
                    bindingId = it.bindingId,
                    ownerType = it.ownerType,
                    ownerId = it.ownerId,
                    targetType = it.targetType,
                    targetId = it.targetId,
                )
            },
            legacyChunkOnlyDocIds = chunkOnlyDocs,
        )
    }

    /**
     * 恢复便携备份。
     *
     * 全有或全无:任何 revision hash 校验失败 → 不写入任何行,返回失败报告。
     * 成功时分配新 datasetEpoch(严格大于当前),旧 epoch 永不重用。
     */
    suspend fun restore(backup: KnowledgePortableBackup): RestoreReport {
        // 1-2. 版本与内容校验:任何一项不通过都不写任何行。
        validateBackup(backup)?.let { return failed(it) }

        // 3. 分配新 datasetEpoch(单调,不随备份回滚)。
        val latestEpoch = v2Dao.getLatestEpoch()?.epochNo ?: 0L
        val newEpochNo = latestEpoch + 1
        val inserted = v2Dao.insertEpochIfMonotonic(
            epochId = "epoch-${UUID.randomUUID()}",
            epochNo = newEpochNo,
            createdAt = clock(),
            reason = "restore",
        )
        if (inserted <= 0) return failed("datasetEpoch 分配失败(单调守卫拒绝)")

        val state = RestoreState(
            newEpochNo = newEpochNo,
            warnings = mutableListOf(),
            storedOnly = mutableListOf(),
            missingContent = mutableListOf(),
        )

        try {
            // 4. 依赖顺序:folders → docs → revisions。
            writeFoldersAndDocs(backup, state)
            // 5. 词法重建:完整正文重建 generation;ChunkOnly/空正文保持 StoredOnly。
            rebuildLexical(backup, state)
            // 6. 绑定恢复(目标不存在只记警告,不静默扩成全库)。
            restoreBindings(backup, state)
        } catch (e: Exception) {
            Logger.w("KnowledgePortableBackupService", "恢复失败: ${e.message}", e)
            // 失败:本方法内已写入的行由调用方以恢复点回滚;报告如实失败。
            return failed("恢复中断: ${e.message}", newEpochNo, state.warnings)
        }

        return RestoreReport(
            success = true,
            newDatasetEpoch = newEpochNo,
            restoredDocs = backup.docs.size,
            restoredRevisions = backup.revisions.size,
            keywordReadyDocs = state.keywordReady,
            storedOnlyDocIds = state.storedOnly,
            missingContentDocIds = state.missingContent,
            warnings = state.warnings,
        )
    }

    /** 恢复过程的可变状态(计数 + 分类清单)。 */
    private class RestoreState(
        val newEpochNo: Long,
        val warnings: MutableList<String>,
        val storedOnly: MutableList<String>,
        val missingContent: MutableList<String>,
        var keywordReady: Int = 0,
    )

    /** 版本与清单校验;返回 null = 通过,非空 = 失败原因。 */
    private fun validateBackup(backup: KnowledgePortableBackup): String? {
        if (backup.manifest.formatVersion > KnowledgeBackupManifest.CURRENT_FORMAT_VERSION) {
            return "未知的较新备份格式版本: ${backup.manifest.formatVersion}"
        }
        val docIds = backup.docs.map { it.docId }.toSet()
        for (revision in backup.revisions) {
            if (revision.docId !in docIds) {
                return "revision ${revision.revisionId} 引用了不在备份中的 doc: ${revision.docId}"
            }
            val actual = KnowledgeContentStore.hashOf(revision.fullContent.toByteArray())
            if (revision.fullContent.isNotEmpty() &&
                revision.contentHash.isNotBlank() &&
                actual != revision.contentHash
            ) {
                return "revision ${revision.revisionId} 内容 hash 不一致"
            }
        }
        return null
    }

    private suspend fun writeFoldersAndDocs(backup: KnowledgePortableBackup, state: RestoreState) {
        for (folder in backup.folders) {
            folderDao.insertFolder(
                KnowledgeV2FolderEntity(
                    folderId = folder.folderId,
                    kbId = folder.kbId,
                    parentId = folder.parentId,
                    name = folder.name,
                    createdAt = folder.createdAt,
                    updatedAt = folder.updatedAt,
                ),
            )
        }
        for (doc in backup.docs) {
            v2Dao.insertDoc(
                KnowledgeV2DocEntity(
                    docId = doc.docId,
                    ownerType = doc.ownerType,
                    ownerId = doc.ownerId,
                    folderId = doc.folderId,
                    currentRevisionId = doc.currentRevisionId,
                    datasetEpoch = state.newEpochNo,
                    sourceRefJson = doc.sourceRefJson,
                    createdAt = doc.createdAt,
                    updatedAt = doc.updatedAt,
                ),
            )
        }
        for (revision in backup.revisions) {
            v2Dao.insertRevision(
                KnowledgeV2RevisionEntity(
                    revisionId = revision.revisionId,
                    docId = revision.docId,
                    revisionNo = revision.revisionNo,
                    contentHash = revision.contentHash,
                    fullContent = revision.fullContent,
                    preview = revision.preview,
                    origin = revision.origin,
                    createdAt = revision.createdAt,
                ),
            )
        }
    }

    /** 逐文档重建词法 generation;失败/空正文保持 StoredOnly,不发布半代。 */
    private suspend fun rebuildLexical(backup: KnowledgePortableBackup, state: RestoreState) {
        val publisher = GenerationPublisher(jobDao)
        val chunker = StructuredChunker(chunkSize = 500, chunkOverlap = 50)
        for (doc in backup.docs) {
            val revisions = backup.revisions.filter { it.docId == doc.docId }
            val active = revisions.firstOrNull { it.revisionId == doc.currentRevisionId }
                ?: revisions.maxByOrNull { it.revisionNo }
            if (active == null || active.fullContent.isBlank()) {
                state.storedOnly.add(doc.docId)
                if (doc.docId in backup.legacyChunkOnlyDocIds) {
                    state.warnings.add("${doc.docId}: ChunkOnly 旧资料,仅保留只读 chunks,不宣称完整正文")
                } else {
                    state.missingContent.add(doc.docId)
                }
                continue
            }
            if (rebuildOneDoc(publisher, chunker, doc.docId, active, state)) state.keywordReady++
        }
    }

    /** 单文档词法重建;返回 true = KeywordReady。 */
    private suspend fun rebuildOneDoc(
        publisher: GenerationPublisher,
        chunker: StructuredChunker,
        docId: String,
        active: KnowledgeBackupRevision,
        state: RestoreState,
    ): Boolean {
        val chunks = chunker.chunkFromBlocks(
            listOf(
                io.zer0.muse.knowledge.ingest.ParsedBlock(
                    text = active.fullContent,
                    page = 0,
                    section = 1,
                    kind = "paragraph",
                ),
            ),
        )
        val genId = publisher.stageGeneration(docId, active.revisionId, chunks)
        val validation = publisher.validate(genId, active.revisionId, active.contentHash) { texts ->
            KnowledgeContentStore.hashOf(chunker.reconstruct(texts).toByteArray())
        }
        if (validation != null) {
            // 重建失败:保持 StoredOnly,不发布半代。
            publisher.abandon(genId)
            state.storedOnly.add(docId)
            state.warnings.add("$docId: 词法重建校验失败($validation),保持 StoredOnly")
            return false
        }
        val published = publisher.publish(genId, active.revisionId)
        return if (published is io.zer0.muse.knowledge.domain.PublishResult.Published) {
            true
        } else {
            state.storedOnly.add(docId)
            state.warnings.add("$docId: 词法发布失败($published),保持 StoredOnly")
            false
        }
    }

    /** 绑定恢复:目标不存在只记警告并跳过。 */
    private suspend fun restoreBindings(backup: KnowledgePortableBackup, state: RestoreState) {
        val docIds = backup.docs.map { it.docId }.toSet()
        val folderIds = backup.folders.map { it.folderId }.toSet()
        for (binding in backup.bindings) {
            val targetExists = when (binding.targetType) {
                "DOC" -> binding.targetId in docIds
                "FOLDER" -> binding.targetId in folderIds
                "KB" -> true
                else -> false
            }
            if (!targetExists) {
                state.warnings.add(
                    "绑定 ${binding.bindingId} 目标不存在(${binding.targetType}:${binding.targetId}),已跳过",
                )
                continue
            }
            folderDao.insertBinding(
                KnowledgeV2BindingEntity(
                    bindingId = binding.bindingId,
                    ownerType = binding.ownerType,
                    ownerId = binding.ownerId,
                    targetType = binding.targetType,
                    targetId = binding.targetId,
                    datasetEpoch = state.newEpochNo,
                    createdAt = clock(),
                ),
            )
        }
    }

    private fun failed(reason: String, epoch: Long = 0L, warnings: List<String> = emptyList()) = RestoreReport(
        success = false,
        newDatasetEpoch = epoch,
        restoredDocs = 0,
        restoredRevisions = 0,
        keywordReadyDocs = 0,
        storedOnlyDocIds = emptyList(),
        missingContentDocIds = emptyList(),
        failureReason = reason,
        warnings = warnings,
    )

    companion object {
        private val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** 便携备份 → UTF-8 JSON 文本(后续文件/流式导出复用同一格式)。 */
        fun encode(backup: KnowledgePortableBackup): String =
            json.encodeToString(KnowledgePortableBackup.serializer(), backup)

        /** JSON 文本 → 便携备份;格式错误抛出,调用方必须按失败处理(不静默恢复空数据)。 */
        fun decode(text: String): KnowledgePortableBackup =
            json.decodeFromString(KnowledgePortableBackup.serializer(), text)
    }
}
