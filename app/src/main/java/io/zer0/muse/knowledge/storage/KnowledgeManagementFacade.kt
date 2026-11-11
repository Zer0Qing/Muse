package io.zer0.muse.knowledge.storage

/**
 * R8 管理门面 — UI / 工具 / Web 三个管理入口的唯一公共出口(计划 R8 输入/产出:
 * 「所有 UI/工具/Web 管理行为共用仓储事务和作业服务」)。
 *
 * 各入口只做参数校验与用户意图转换,实际目录/文档生命周期全部委托 [KnowledgeLifecycleService],
 * 禁止某个入口绕过统一服务直接调用 delete DAO —— 这保证三处结果一致、状态与可见性同步。
 */
class KnowledgeManagementFacade(
    private val lifecycle: KnowledgeLifecycleService,
) {

    suspend fun deleteFromUi(docId: String, clearCitationSnapshots: Boolean) =
        lifecycle.deleteDocument(docId, clearCitationSnapshots)

    suspend fun deleteFromTool(docId: String, clearCitationSnapshots: Boolean) =
        lifecycle.deleteDocument(docId, clearCitationSnapshots)

    suspend fun deleteFromWeb(docId: String, clearCitationSnapshots: Boolean) =
        lifecycle.deleteDocument(docId, clearCitationSnapshots)

    suspend fun createFolderFromUi(kbId: String, parentId: String, name: String) =
        lifecycle.createFolder(kbId, parentId, name)

    suspend fun createFolderFromTool(kbId: String, parentId: String, name: String) =
        lifecycle.createFolder(kbId, parentId, name)

    suspend fun renameFolderFromUi(folderId: String, newName: String) =
        lifecycle.renameFolder(folderId, newName)

    suspend fun renameFolderFromTool(folderId: String, newName: String) =
        lifecycle.renameFolder(folderId, newName)

    suspend fun moveFolderFromUi(folderId: String, newParentId: String) =
        lifecycle.moveFolder(folderId, newParentId)

    suspend fun deleteFolderFromUi(folderId: String, policy: KnowledgeLifecycleService.FolderDeletePolicy) =
        lifecycle.deleteFolder(folderId, policy)

    suspend fun editDocument(docId: String, expectedRevisionId: String, newContent: String) =
        lifecycle.editDocument(docId, expectedRevisionId, newContent)
}
