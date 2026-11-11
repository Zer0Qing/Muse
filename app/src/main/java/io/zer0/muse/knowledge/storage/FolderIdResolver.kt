package io.zer0.muse.knowledge.storage

import io.zer0.muse.rag.KnowledgeFolderBrowser

/**
 * R2 步骤 7:folderId 归一 — 显式与虚拟目录统一到稳定 folderId。
 *
 * 真源:`knowledge_folders` 表(`id = "kbfolder-{kbId}-{path}"`,(kb_id,path) 唯一索引,
 * 空文件夹也有一等公民行)。V2 文档行的 folderId 一律经 [resolve] 归一:
 *  - 表里有 (kbId, path) 行 → 返回该行 id;
 *  - 没有 → 创建该行(空文件夹语义既有)→ 返回 id;
 *  - 路径先经 [KnowledgeFolderBrowser.normalize] 归一(与旧虚拟路径同一规则);
 *  - 库根(空路径)→ folderId 空串(根不落表,与旧表语义一致)。
 *
 * 跨库隔离:id 内嵌 kbId,两个库的同名目录天然得到不同 folderId(计划 R2 步骤 7:
 * 「显式与虚拟目录归一为稳定 folderId,保留空目录」)。
 */
class FolderIdResolver(
    private val folders: FolderDirectory,
) {
    /** folder 目录抽象(生产 = KnowledgeFolderDao;测试 = 内存实现)。 */
    interface FolderDirectory {
        fun findById(kbId: String, path: String): String?
        fun create(kbId: String, path: String): String
    }

    fun resolve(kbId: String, path: String): String {
        require(kbId.isNotBlank()) { "kbId 不得为空:folderId 依赖 kbId 隔离" }
        val normalized = KnowledgeFolderBrowser.normalize(path)
        if (normalized.isEmpty()) return ""
        return folders.findById(kbId, normalized)
            ?: folders.create(kbId, normalized)
    }

    companion object {
        /** 与 KnowledgeFolderEntity 主键格式同源;单测钉住格式不漂移。 */
        fun folderIdOf(kbId: String, path: String): String = "kbfolder-$kbId-$path"
    }
}
