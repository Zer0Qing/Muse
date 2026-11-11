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
 * R8 统一生命周期 — 设计 §13「编辑、目录、删除与清理」与计划 R8。
 *
 * 核心规则(由 KnowledgeFolderIdentityTest / KnowledgeLifecycleTest 钉住):
 *  - 目录使用**稳定 folderId**,path 只是投影;重命名/移动不改变身份,绑定不失效;
 *  - 同父目录名称唯一;禁止环;禁止跨库 parent;
 *  - 目录删除明确二选一:删除子树文档,或解散目录并把文档搬回父目录 —— 没有中间语义;
 *  - 删除先撤查询可见性(活动代指针 + 代状态),再清派生物;历史引用保留/清除由调用方显式选择;
 *  - 编辑必须传 expectedRevisionId(乐观锁),新内容产生新 revision,旧 revision 永不覆盖。
 */
class KnowledgeLifecycleService(
    private val folderDao: KnowledgeFolderV2Dao,
    private val v2Dao: KnowledgeV2Dao,
    private val jobDao: io.zer0.muse.data.knowledge.KnowledgeJobDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newFolderId: () -> String = { "kbf-${UUID.randomUUID()}" },
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
        /** 提示调用方:历史消息中的引用快照是否同时清除。 */
        val citationAction: String,
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
    private suspend fun validateMoveTarget(
        folder: KnowledgeV2FolderEntity,
        newParentId: String,
    ): FolderResult? {
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
     * 删除目录:按 [policy] 二选一。先算出子树文档,再撤可见性并清理。
     */
    suspend fun deleteFolder(folderId: String, policy: FolderDeletePolicy): FolderResult {
        val folder = folderDao.getFolder(folderId) ?: return FolderResult.NotFound
        val subtree = collectSubtree(folderId)
        if (subtree.isEmpty()) return FolderResult.NotFound

        val now = clock()
        when (policy) {
            FolderDeletePolicy.DELETE_SUBTREE_DOCS -> {
                // 先撤可见性:清活动代指针(R8 的"先撤查询可见性再清派生物")。
                for (doc in v2Dao.getDocsInFolders(subtree)) {
                    jobDao.clearActiveGeneration(doc.docId)
                }
                v2Dao.deleteDocsInFolders(subtree)
            }
            FolderDeletePolicy.DISSOLVE_MOVE_DOCS_TO_PARENT -> {
                v2Dao.moveDocsToFolder(subtree, folder.parentId, now)
            }
        }
        // 删除目录行(子→父顺序无关,PK 独立)。
        for (id in subtree.asReversed()) {
            folderDao.deleteFolder(id)
        }
        // 绑定指向被删目录时一并清理(绑定不隐式改指全库)。
        folderDao.deleteBindingsByTarget("FOLDER", folderId)
        Logger.i(TAG, "删除目录 $folderId 完成,pipeline=$policy,子树=${subtree.size}")
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
     * 删除文档 — 先撤可见性(活动代指针),再删行;历史引用快照由调用方按 [citationAction] 处理。
     */
    suspend fun deleteDocument(docId: String, clearCitationSnapshots: Boolean): DeleteResult? {
        val doc = v2Dao.getDoc(docId) ?: return null
        jobDao.clearActiveGeneration(docId)
        // 代状态标 RETIRED,查询侧不再可见。
        val gen = jobDao.getLatestGeneration(docId)
        if (gen != null) {
            jobDao.updateGenerationState(gen.generationId, io.zer0.muse.data.knowledge.IndexGenerationState.RETIRED.name)
        }
        v2Dao.deleteDoc(docId)
        return DeleteResult(
            deletedDocs = 1,
            deletedFolders = 0,
            citationAction = if (clearCitationSnapshots) "已请求同步清除历史引用快照" else "保留历史引用快照(仅标记来源不可用)",
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
