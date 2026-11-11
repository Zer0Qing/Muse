package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.knowledge.KnowledgeV2DocEntity
import io.zer0.muse.data.knowledge.KnowledgeV2RevisionEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.backup.KnowledgePortableBackupService
import io.zer0.muse.knowledge.storage.KnowledgeContentStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R9 步骤 1/4(计划 R9:「损坏blob、缺文件、恢复中断和恢复失败回滚用例」
 * 「恢复前暂停领取和隔离写入…旧执行者不得写回恢复后的同名对象」)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeRestoreFailureTest {

    private lateinit var sourceDb: MuseDb
    private lateinit var targetDb: MuseDb
    private lateinit var sourceService: KnowledgePortableBackupService
    private lateinit var targetService: KnowledgePortableBackupService

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        sourceDb = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        targetDb = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        sourceService = KnowledgePortableBackupService(
            v2Dao = sourceDb.knowledgeV2Dao(),
            folderDao = sourceDb.knowledgeFolderV2Dao(),
            jobDao = sourceDb.knowledgeJobDao(),
            clock = { 1000L },
        )
        targetService = KnowledgePortableBackupService(
            v2Dao = targetDb.knowledgeV2Dao(),
            folderDao = targetDb.knowledgeFolderV2Dao(),
            jobDao = targetDb.knowledgeJobDao(),
            clock = { 2000L },
        )
    }

    @After
    fun tearDown() {
        sourceDb.close()
        targetDb.close()
    }

    private suspend fun seed(db: MuseDb, docId: String, content: String) {
        val revId = "rev-$docId-1"
        db.knowledgeV2Dao().insertDoc(
            KnowledgeV2DocEntity(docId = docId, ownerType = "UserLibrary", ownerId = "kb-1", currentRevisionId = revId),
        )
        db.knowledgeV2Dao().insertRevision(
            KnowledgeV2RevisionEntity(
                revisionId = revId,
                docId = docId,
                revisionNo = 1,
                contentHash = KnowledgeContentStore.hashOf(content.toByteArray()),
                fullContent = content,
                preview = content,
            ),
        )
    }

    @Test
    fun `corrupted revision hash aborts whole restore without partial rows`() = runBlocking {
        seed(sourceDb, "doc-1", "good content")
        seed(sourceDb, "doc-2", "second content")
        val backup = sourceService.export()
        // 篡改第二条 revision 的正文,使 hash 不一致。
        val corrupted = backup.copy(
            revisions = backup.revisions.mapIndexed { index, revision ->
                if (index == 1) revision.copy(fullContent = "tampered content") else revision
            },
        )
        val report = targetService.restore(corrupted)
        assertTrue("hash 不一致必须失败", !report.success)
        assertTrue(report.failureReason!!.contains("hash"))
        // 全有或全无:第一条也不能写入。
        assertNull(targetDb.knowledgeV2Dao().getDoc("doc-1"))
        assertNull(targetDb.knowledgeV2Dao().getDoc("doc-2"))
    }

    @Test
    fun `revision referencing missing doc is rejected`() = runBlocking {
        seed(sourceDb, "doc-1", "content")
        val backup = sourceService.export()
        val orphan = backup.copy(revisions = backup.revisions + backup.revisions.first().copy(
            revisionId = "rev-orphan",
            docId = "doc-not-in-backup",
        ))
        val report = targetService.restore(orphan)
        assertTrue(!report.success)
        assertTrue(report.failureReason!!.contains("不在备份"))
        assertNull(targetDb.knowledgeV2Dao().getDoc("doc-1"))
    }

    @Test
    fun `restore allocates new epoch and never reuses backup epoch`() = runBlocking {
        seed(sourceDb, "doc-1", "content")
        // 目标库先有一个更高 epoch。
        targetDb.knowledgeV2Dao().insertEpochIfMonotonic("epoch-old", 5L, 0L, "import")
        val backup = sourceService.export()
        val report = targetService.restore(backup)
        assertTrue(report.success)
        assertTrue("新 epoch 必须大于目标库已有值", report.newDatasetEpoch > 5L)
    }
}
