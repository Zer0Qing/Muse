package io.zer0.muse.knowledge.storage

import io.zer0.common.Logger
import io.zer0.muse.data.knowledge.KnowledgeFolderV2Dao
import io.zer0.muse.data.knowledge.KnowledgeV2BindingEntity
import io.zer0.muse.data.knowledge.KnowledgeV2Dao
import io.zer0.muse.data.knowledge.KnowledgeV2FolderEntity
import io.zer0.muse.data.knowledge.KnowledgeV2RevisionEntity
import io.zer0.muse.knowledge.storage.KnowledgeRepository.Companion.derivePreview
import java.util.UUID

/**
 * 删除文档时的外部派生物清理端口 — DAO 清不到的部分(内容寻址 blob、历史消息引用快照)
 * 通过注入实现,避免生命周期服务反向依赖备份/聊天模块。
 *
 * 默认实现是**明确记录为不支持**而不是静默成功:调用方拿到的文案必须与事实一致。
 */
interface KnowledgeArtifactCleaner {
    /**
     * 删除内容寻址 blob(按 revision contentHash)。
     * @return true = 确实删掉了一个存在的原件;false = 未找到(行内存储/已被清理)。
     */
    suspend fun deleteBlob(contentHash: String): Boolean

    /**
     * 清除历史消息中指向该文档的引用快照。
     * @return 清除的引用条数;null = 当前装配未接入清除通道(调用方须如实说明)。
     */
    suspend fun clearCitationSnapshots(docId: String): Int?

    companion object {
        /** 未接入任何外部清理通道的装配(合成库/单测)。 */
        fun unsupported(): KnowledgeArtifactCleaner = object : KnowledgeArtifactCleaner {
            override suspend fun deleteBlob(contentHash: String): Boolean = false

            override suspend fun clearCitationSnapshots(docId: String): Int? = null
        }
    }
}

/**
 * R8 统一生命周期 — 设计 §13「编辑、目录、删除与清理」与计划 R8。
 *
 * 核心规则(由 KnowledgeFolderIdentityTest / KnowledgeLifecycleTest 钉住):
 *  - 目录使用**稳定 folderId**,path 只是投影;重命名/移动不改变身份,绑定不失效;
 *  - 同父目录名称唯一;禁止环;禁止跨库 parent;
 *  - 目录删除明确二选一:删除子树文档,或解散目录并把文档搬回父目录 —— 没有中间语义;
 *  - 删除先撤查询可见性(活动代指针 + 代状态),再清派生物(代内 chunk/词法行、
 *    保留租约、内容 blob、指向被删目标的绑定),历史引用快照的清除结果如实报告;
 *  - 编辑必须传 expectedRevisionId(乐观锁),新内容产生新 revision,旧 revision 永不覆盖。
 */
class KnowledgeLifecycleService(
    private val folderDao: KnowledgeFolderV2Dao,
    private val v2Dao: KnowledgeV2Dao,
    private val jobDao: io.zer0.muse.data.knowledge.KnowledgeJobDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newFolderId: () -> String = { "kbf-${UUID.randomUUID()}" },
    /** 外部派生物清理端口;未注入时按「不支持」处理并在文案中说明。 */
    private val artifactCleaner: KnowledgeArtifactCleaner = KnowledgeArtifactCleaner.unsupported(),
) {

    /** 目录操作结果 — 类型化,不用异常表达业务冲突。 */
    sealed interface FolderResult {
        data class Ok(val folder: KnowledgeV2FolderEntity) : FolderResult
        data class NameConflict(val name: String) : FolderResult
        data object NotFound : FolderResult
        data object CrossKbDenied : FolderResult

        /** 移动到自己的子树下会形成环。 */
        data object Cycle : FolderResult
        data class Invalid(val reason: String) : FolderResult
    }

    /** 目录删除策略 — 必须二选一,没有"删表但文档又把目录推导回来"的中间态。 */
    enum class FolderDeletePolicy {
        /** 删除子树及其中文档。 */
        DELETE_SUBTREE_DOCS,

        /** 仅解散目录:文档搬回父目录。 */
        DISSOLVE_MOVE_DOCS_TO_PARENT,
    }

    /** 编辑结果。 */
    sealed interface EditResult {
        data class Ok(val revisionId: String, val revisionNo: Int) : EditResult
        data object Conflict : EditResult
        data object NotFound : EditResult
        data class Invalid(val reason: String) : EditResult
    }

    /** 删除结果。 */
    data class DeleteResult(
        val deletedDocs: Int,
        val deletedFolders: Int,
        /** 历史消息中的引用快照是否真的被清除(按实际执行结果,不按调用意图)。 */
        val citationAction: String,
        val clearedCitationCount: Int = 0,
        val clearedBlobCount: Int = 0,
        val clearedGenerationCount: Int = 0,
    )

    // ---------- 目录 ----------

    suspend fun createFolder(kbId: String, parentId: String, name: String): FolderResult {
        val trimmed = name.trim()
        val validation = validateFolderName(trimmed)
        if (validation != null) return validation
        if (kbId.isBlank()) return FolderResult.Invalid("kbId 为空")
        if (parentId.isNotBlank()) {
            val parent = folderDao.getFolder(parentId) ?: return FolderResult.NotFound
            if (parent.kbId != kbId) return FolderResult.CrossKbDenied
        }
        if (folderDao.getByNameInParent(kbId, parentId, trimmed) != null) {
            return FolderResult.NameConflict(trimmed)
        }
        val now = clock()
        val folder = KnowledgeV2FolderEntity(
            folderId = newFolderId(),
            kbId = kbId,
            parentId = parentId,
            name = trimmed,
            createdAt = now,
            updatedAt = now,
        )
        folderDao.insertFolder(folder)
        return FolderResult.Ok(folder)
    }

    suspend fun renameFolder(folderId: String, newName: String): FolderResult {
        val folder = folderDao.getFolder(folderId) ?: return FolderResult.NotFound
        val trimmed = newName.trim()
        validateFolderName(trimmed)?.let { return it }
        val conflict = folderDao.getByNameInParent(folder.kbId, folder.parentId, trimmed)
        if (conflict != null && conflict.folderId != folderId) return FolderResult.NameConflict(trimmed)
        folderDao.updateName(folderId, trimmed, clock())
        return FolderResult.Ok(folder.copy(name = trimmed))
    }

    suspend fun moveFolder(folderId: String, newParentId: String): FolderResult {
        val folder = folderDao.getFolder(folderId) ?: return FolderResult.NotFound
        if (newParentId == folderId) return FolderResult.Cycle
        if (newParentId.isNotBlank()) {
            val moveError = validateMoveTarget(folder, newParentId)
            if (moveError != null) return moveError
        }
        folderDao.updateParent(folderId, newParentId, clock())
        return FolderResult.Ok(folder.copy(parentId = newParentId))
    }

    /** 目录名合法性(空 / 含分隔符)。 */
    private fun validateFolderName(trimmed: String): FolderResult? = when {
        trimmed.isBlank() -> FolderResult.Invalid("目录名为空")
        trimmed.contains('/') -> FolderResult.Invalid("目录名不能包含 /")
        else -> null
    }

    /** 移动目标合法性(存在性 / 跨库 / 成环 / 同级重名)。 */
    private suspend fun validateMoveTarget(folder: KnowledgeV2FolderEntity, newParentId: String): FolderResult? {
        val parent = folderDao.getFolder(newParentId) ?: return FolderResult.NotFound
        if (parent.kbId != folder.kbId) return FolderResult.CrossKbDenied
        if (isDescendantOf(candidateAncestorId = folder.folderId, nodeId = newParentId)) {
            return FolderResult.Cycle
        }
        val conflict = folderDao.getByNameInParent(folder.kbId, newParentId, folder.name)
        if (conflict != null && conflict.folderId != folder.folderId) {
            return FolderResult.NameConflict(folder.name)
        }
        return null
    }

    /** 路径投影:从根到该目录的 "a/b/c";根返回空串。**不是身份**。 */
    suspend fun pathOf(folderId: String): String {
        val parts = mutableListOf<String>()
        var current = folderDao.getFolder(folderId)
        var guard = 0
        while (current != null && current.parentId.isNotBlank() && guard++ < MAX_DEPTH) {
            parts.add(0, current.name)
            current = folderDao.getFolder(current.parentId)
        }
        if (current != null) parts.add(0, current.name)
        return parts.joinToString("/")
    }

    /** 判断 nodeId 是否为 candidateAncestorId 的后代(用于环检测)。 */
    private suspend fun isDescendantOf(candidateAncestorId: String, nodeId: String): Boolean {
        var current = folderDao.getFolder(nodeId)
        var guard = 0
        while (current != null && current.parentId.isNotBlank() && guard++ < MAX_DEPTH) {
            if (current.parentId == candidateAncestorId) return true
            current = folderDao.getFolder(current.parentId)
        }
        return false
    }

    /** 收集目录及全部子目录的稳定 id。 */
    private suspend fun collectSubtree(folderId: String): List<String> {
        val folder = folderDao.getFolder(folderId) ?: return emptyList()
        val result = mutableListOf(folderId)
        val queue = ArrayDeque(listOf(folderId))
        var guard = 0
        while (queue.isNotEmpty() && guard++ < MAX_FOLDER_SCAN) {
            val current = queue.removeFirst()
            for (child in folderDao.getChildren(folder.kbId, current)) {
                result.add(child.folderId)
                queue.add(child.folderId)
            }
        }
        return result
    }

    /**
     * 删除目录:按 [policy] 二选一。先算出子树文档,再撤可见性并清理派生物。
     */
    suspend fun deleteFolder(folderId: String, policy: FolderDeletePolicy): FolderResult {
        val folder = folderDao.getFolder(folderId) ?: return FolderResult.NotFound
        val subtree = collectSubtree(folderId)
        if (subtree.isEmpty()) return FolderResult.NotFound

        val now = clock()
        when (policy) {
            FolderDeletePolicy.DELETE_SUBTREE_DOCS -> {
                // 先撤可见性:清活动代指针(R8 的"先撤查询可见性再清派生物")。
                val docs = v2Dao.getDocsInFolders(subtree)
                for (doc in docs) {
                    clearDocVisibility(doc.docId)
                }
                // 再清派生物(代内 chunk/词法行、租约、blob),最后删文档行。
                var clearedBlobs = 0
                for (doc in docs) {
                    clearedBlobs += purgeDocArtifacts(doc.docId).clearedBlobCount
                }
                v2Dao.deleteDocsInFolders(subtree)
                // 指向被删文档的绑定同样要删(否则留下指向不存在目标的死绑定)。
                for (doc in docs) {
                    folderDao.deleteBindingsByTarget("DOC", doc.docId)
                }
                // 删除目录行(子→父顺序无关,PK 独立)。
                for (id in subtree.asReversed()) {
                    folderDao.deleteFolder(id)
                }
                // 绑定指向被删目录时一并清理(绑定不隐式改指全库);子树内每个目录都要清。
                for (id in subtree) {
                    folderDao.deleteBindingsByTarget("FOLDER", id)
                }
                Logger.i(
                    TAG,
                    "删除目录 $folderId 完成,子树=${subtree.size},文档=${docs.size},清理 blob=$clearedBlobs",
                )
            }
            FolderDeletePolicy.DISSOLVE_MOVE_DOCS_TO_PARENT -> {
                v2Dao.moveDocsToFolder(subtree, folder.parentId, now)
                // 删除目录行(子→父顺序无关,PK 独立)。
                for (id in subtree.asReversed()) {
                    folderDao.deleteFolder(id)
                }
                for (id in subtree) {
                    folderDao.deleteBindingsByTarget("FOLDER", id)
                }
                Logger.i(TAG, "解散目录 $folderId 完成,子树=${subtree.size}")
            }
        }
        return FolderResult.Ok(folder)
    }

    // ---------- 文档 ----------

    /**
     * 编辑正文 — expectedRevisionId 乐观锁。
     * 新内容产生新 revision;旧 revision 保留(不可变版本链)。
     */
    suspend fun editDocument(docId: String, expectedRevisionId: String, newContent: String): EditResult {
        val doc = v2Dao.getDoc(docId) ?: return EditResult.NotFound
        if (doc.currentRevisionId != expectedRevisionId) return EditResult.Conflict
        if (newContent.isBlank()) return EditResult.Invalid("正文为空")
        val revisions = v2Dao.getRevisionsByDoc(docId)
        val nextNo = (revisions.maxOfOrNull { it.revisionNo } ?: 0) + 1
        val now = clock()
        val revisionId = "rev-$docId-$nextNo-${KnowledgeContentStore.hashOf(newContent.toByteArray()).take(8)}"
        v2Dao.insertRevision(
            KnowledgeV2RevisionEntity(
                revisionId = revisionId,
                docId = docId,
                revisionNo = nextNo,
                contentHash = KnowledgeContentStore.hashOf(newContent.toByteArray()),
                fullContent = newContent,
                preview = derivePreview(newContent),
                origin = "edit",
                createdAt = now,
            ),
        )
        v2Dao.setCurrentRevision(docId, revisionId, now)
        return EditResult.Ok(revisionId = revisionId, revisionNo = nextNo)
    }

    /**
     * 删除文档 — 先撤可见性(活动代指针),再清派生物(chunk/词法/租约/blob/绑定),
     * 最后删行。历史引用快照的清除结果按**实际执行**报告,不按调用意图。
     */
    suspend fun deleteDocument(docId: String, clearCitationSnapshots: Boolean): DeleteResult? {
        v2Dao.getDoc(docId) ?: return null
        clearDocVisibility(docId)
        val purge = purgeDocArtifacts(docId)
        v2Dao.deleteDoc(docId)
        // 指向被删文档的绑定必须真实删除(否则恢复/查询会指向不存在的目标)。
        val removedBindings = folderDao.deleteBindingsByTarget("DOC", docId)
        val citation = resolveCitationAction(docId, clearCitationSnapshots)
        Logger.i(
            TAG,
            "删除文档 $docId 完成,代=${purge.clearedGenerationCount},blob=${purge.clearedBlobCount}," +
                " 绑定=$removedBindings,引用=${citation.message}",
        )
        return DeleteResult(
            deletedDocs = 1,
            deletedFolders = 0,
            citationAction = citation.message,
            clearedCitationCount = citation.clearedCount,
            clearedBlobCount = purge.clearedBlobCount,
            clearedGenerationCount = purge.clearedGenerationCount,
        )
    }

    /** 引用快照清除结果 — 文案与条数同源,不分别推导。 */
    private data class CitationOutcome(val message: String, val clearedCount: Int)

    /** 清除历史引用快照并给出与事实一致的文案。 */
    private suspend fun resolveCitationAction(docId: String, clearCitationSnapshots: Boolean): CitationOutcome {
        if (!clearCitationSnapshots) {
            return CitationOutcome("保留历史引用快照:旧消息仍显示原片段,并标注来源已删除", 0)
        }
        val cleared = artifactCleaner.clearCitationSnapshots(docId)
            ?: return CitationOutcome(
                "历史引用快照未清除:当前装配未接入快照清除通道,旧消息仍显示原片段并标注来源已删除",
                0,
            )
        return CitationOutcome("已清除 $cleared 条历史引用快照", cleared)
    }

    /** 撤查询可见性:先清活动代指针,再把该文档所有未 RETIRED 的代置 RETIRED。 */
    private suspend fun clearDocVisibility(docId: String) {
        jobDao.clearActiveGeneration(docId)
        jobDao.retireGenerationsByDoc(docId, io.zer0.muse.data.knowledge.IndexGenerationState.RETIRED.name)
    }

    /** 派生物清理结果(逐项计数,便于测试与日志核对)。 */
    private data class PurgeReport(
        val clearedGenerationCount: Int,
        val clearedBlobCount: Int,
    )

    /**
     * 清理某文档的存储派生物:代内 chunk/词法行、代行本身、保留租约、内容 blob。
     *
     * 顺序很重要:先取 revision 内容键(删 doc 后 revision 会被 FK 级联清掉,再取就没了),
     * 再删 chunk/词法/代行,最后删 blob 与租约。
     * 引用快照不在本方法内处理:它由调用方按「是否请求清除」决定,见 [resolveCitationAction]。
     */
    private suspend fun purgeDocArtifacts(docId: String): PurgeReport {
        val revisions = v2Dao.getRevisionsByDoc(docId)
        val contentKeys = revisions.flatMap { revision ->
            listOfNotNull(revision.contentHash.takeIf { it.isNotBlank() }, revision.blobRef.takeIf { it.isNotBlank() })
        }.distinct()

        val generationIds = jobDao.getGenerationIdsByDoc(docId)
        for (generationId in generationIds) {
            jobDao.deleteGenChunks(generationId)
            jobDao.deleteGenLexical(generationId)
        }
        if (generationIds.isNotEmpty()) {
            jobDao.deleteGenerationsByDoc(docId)
        }

        if (contentKeys.isNotEmpty()) {
            jobDao.deleteLeasesByContentKeys(contentKeys)
        }
        var clearedBlobs = 0
        for (key in contentKeys) {
            // 只统计真实删掉的原件:行内存储的 revision 没有 blob,不能计入「已清理」。
            if (artifactCleaner.deleteBlob(key)) clearedBlobs++
        }
        return PurgeReport(
            clearedGenerationCount = generationIds.size,
            clearedBlobCount = clearedBlobs,
        )
    }

    /** 绑定:助手/会话 → 恰好一种目标(KB/FOLDER/DOC)。 */
    suspend fun bind(ownerType: String, ownerId: String, targetType: String, targetId: String): KnowledgeV2BindingEntity {
        require(targetType in setOf("KB", "FOLDER", "DOC")) { "非法 targetType: $targetType" }
        val binding = KnowledgeV2BindingEntity(
            bindingId = "bind-${UUID.randomUUID()}",
            ownerType = ownerType,
            ownerId = ownerId,
            targetType = targetType,
            targetId = targetId,
            createdAt = clock(),
        )
        folderDao.insertBinding(binding)
        return binding
    }

    private companion object {
        const val TAG = "KnowledgeLifecycleService"
        const val MAX_DEPTH = 64
        const val MAX_FOLDER_SCAN = 10_000
    }
}
