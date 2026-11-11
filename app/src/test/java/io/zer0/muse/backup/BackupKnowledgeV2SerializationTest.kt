package io.zer0.muse.backup

import io.zer0.muse.knowledge.backup.KnowledgeBackupDoc
import io.zer0.muse.knowledge.backup.KnowledgeBackupManifest
import io.zer0.muse.knowledge.backup.KnowledgeBackupRevision
import io.zer0.muse.knowledge.backup.KnowledgePortableBackup
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R9 备份线格式兼容(计划 R9「便携备份包含 source、完整 artifact、目录/绑定…」):
 *  - 新备份携带 `knowledgeV2Portable`,序列化往返保真;
 *  - 旧备份没有该字段时反序列化为 null,恢复照常(不因新增字段读不出历史备份)。
 */
class BackupKnowledgeV2SerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun portable() = KnowledgePortableBackup(
        manifest = KnowledgeBackupManifest(
            snapshotId = "snap-1",
            datasetEpoch = 3L,
            createdAt = 1000L,
            appSchemaVersion = 129,
            docCount = 1,
            revisionCount = 1,
            folderCount = 0,
            bindingCount = 0,
        ),
        docs = listOf(
            KnowledgeBackupDoc(
                docId = "doc-1",
                ownerType = "UserLibrary",
                ownerId = "kb-1",
                folderId = "",
                currentRevisionId = "rev-1",
                sourceRefJson = """{"scheme":"content","location":"inline"}""",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        ),
        revisions = listOf(
            KnowledgeBackupRevision(
                revisionId = "rev-1",
                docId = "doc-1",
                revisionNo = 1,
                contentHash = "hash-1",
                fullContent = "完整正文",
                preview = "完整",
                origin = "ingest",
                createdAt = 1L,
            ),
        ),
        folders = emptyList(),
        bindings = emptyList(),
    )

    @Test
    fun `new backup with knowledge v2 portable round trips`() {
        val backup = BackupService.Backup(
            version = 4,
            exportedAt = 1000L,
            sessions = emptyList(),
            messages = emptyList(),
            knowledgeV2Portable = portable(),
        )
        val encoded = json.encodeToString(BackupService.Backup.serializer(), backup)
        val decoded = json.decodeFromString(BackupService.Backup.serializer(), encoded)
        assertEquals("doc-1", decoded.knowledgeV2Portable?.docs?.first()?.docId)
        assertEquals("完整正文", decoded.knowledgeV2Portable?.revisions?.first()?.fullContent)
    }

    @Test
    fun `old backup without field decodes null`() {
        val oldJson = """{"version":3,"exportedAt":1,"sessions":[],"messages":[]}"""
        val decoded = json.decodeFromString(BackupService.Backup.serializer(), oldJson)
        assertNull("旧备份必须兼容为无 V2 便携数据", decoded.knowledgeV2Portable)
    }
}
