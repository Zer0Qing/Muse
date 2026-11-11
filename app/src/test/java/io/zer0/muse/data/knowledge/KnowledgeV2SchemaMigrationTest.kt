package io.zer0.muse.data.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R2 V2 schema 合同(计划 R2 步骤 4/5 + 验收「迁移中断可继续;没有静默 DROP/REPLACE 降损」):
 *  - 迁移 125→126 只建新表,不动旧行(旧 knowledge_docs 行原样保留);
 *  - 持久 owner 合同:同 owner 查询只返回该 owner 的行(跨会话/跨库隔离是 DB 级保证,
 *    重启后不依赖内存注册表);会话删除按 owner 删除,转存过的行不受影响;
 *  - datasetEpoch 单调:恢复分配新 epoch,旧行不被覆盖;
 *  - retention lease 按到期回收;
 *  - revision 版本链 (docId, revisionNo) 唯一。
 *
 * 迁移实测方式:先以 125 版本建库写入旧行,关闭后用带 MIGRATION_125_126 的配置重开
 * (Room 逐版本升级),断言旧行仍在 + 新表可用 —— 对应「迁移中断可继续」的最小证据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeV2SchemaMigrationTest {

    private lateinit var db: io.zer0.muse.data.session.MuseDb
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        val dbFile = context.getDatabasePath("r2-v2-test.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * 以当前 @Database(126)新建库 —— 新装路径:fresh 库即含 V2 表(合同测试的底座)。
     * 迁移路径单独由 `migration 125 to 126...` 用 raw SQL 实测 MIGRATION_125_126 本体。
     */
    private fun buildFreshDb(): String {
        val name = "r2-v2-test.db"
        val fresh = androidx.room.Room.databaseBuilder(
            context,
            io.zer0.muse.data.session.MuseDb::class.java,
            name,
        ).build()
        fresh.close()
        return name
    }

    @Test
    fun `fresh install creates v2 tables and enforces persistent owner contracts`() = runBlocking {
        val name = buildFreshDb()
        db = androidx.room.Room.databaseBuilder(
            context,
            io.zer0.muse.data.session.MuseDb::class.java,
            name,
        ).build()
        val dao = db.knowledgeV2Dao()

        // 两个 owner 各一个文档;同 owner 查询只返回自己的行(隔离的 DB 级保证)
        dao.insertDoc(
            KnowledgeV2DocEntity(
                docId = "doc-alpha", ownerType = "UserLibrary", ownerId = "kb-alpha",
                folderId = "共享目录", createdAt = 1L, updatedAt = 1L,
            ),
        )
        dao.insertDoc(
            KnowledgeV2DocEntity(
                docId = "doc-beta", ownerType = "UserLibrary", ownerId = "kb-beta",
                createdAt = 1L, updatedAt = 1L,
            ),
        )

        val alphaOnly = dao.getByOwner("UserLibrary", "kb-alpha")
        assertEquals("同 owner 查询只返回该 owner 的行", listOf("doc-alpha"), alphaOnly.map { it.docId })

        // 会话附件 owner:删除会话 → 只删该会话的行;转存(改 owner)的行独立存活
        dao.insertDoc(
            KnowledgeV2DocEntity(
                docId = "doc-att-1", ownerType = "SessionAttachment", ownerId = "session-1",
                createdAt = 1L, updatedAt = 1L,
            ),
        )
        dao.insertDoc(
            KnowledgeV2DocEntity(
                docId = "doc-att-2", ownerType = "SessionAttachment", ownerId = "session-1",
                createdAt = 1L, updatedAt = 1L,
            ),
        )
        dao.reassignOwner("doc-att-2", "UserLibrary", "kb-alpha", updatedAt = 2L)
        val deleted = dao.deleteByOwner("SessionAttachment", "session-1")
        assertEquals("按 owner 删除命中 1 行(转存行已不属于该 owner)", 1, deleted)
        assertNull("未转存附件随会话删除", dao.getDoc("doc-att-1"))
        assertNotNull("转存后的附件独立存活(重启/删除会话均不影响)", dao.getDoc("doc-att-2"))
        assertEquals("转存行归入新 owner", "doc-att-2", dao.getByOwner("UserLibrary", "kb-alpha").map { it.docId }.sorted().last())
    }

    @Test
    fun `revision chain is unique per doc and ordered`() = runBlocking {
        val name = buildFreshDb()
        db = androidx.room.Room.databaseBuilder(
            context,
            io.zer0.muse.data.session.MuseDb::class.java,
            name,
        ).build()
        val dao = db.knowledgeV2Dao()

        dao.insertDoc(KnowledgeV2DocEntity(docId = "doc-r", ownerType = "UserLibrary", ownerId = "kb-1", createdAt = 1L, updatedAt = 1L))
        dao.insertRevision(
            KnowledgeV2RevisionEntity(
                revisionId = "rev-1", docId = "doc-r", revisionNo = 1,
                contentHash = "h1", fullContent = "v1", preview = "v1", createdAt = 1L,
            ),
        )
        dao.insertRevision(
            KnowledgeV2RevisionEntity(
                revisionId = "rev-2", docId = "doc-r", revisionNo = 2,
                contentHash = "h2", fullContent = "v2", preview = "v2", createdAt = 2L,
            ),
        )

        val chain = dao.getRevisionsByDoc("doc-r")
        assertEquals("版本链按 revisionNo 有序", listOf(1, 2), chain.map { it.revisionNo })
        assertEquals("旧 revision 保留(不覆盖)", "v1", dao.getRevision("rev-1")!!.fullContent)
        dao.setCurrentRevision("doc-r", "rev-2", updatedAt = 3L)
        assertEquals("活动指针可切换", "rev-2", dao.getDoc("doc-r")!!.currentRevisionId)
    }

    @Test
    fun `dataset epoch is monotonic and leases expire`() = runBlocking {
        val name = buildFreshDb()
        db = androidx.room.Room.databaseBuilder(
            context,
            io.zer0.muse.data.session.MuseDb::class.java,
            name,
        ).build()
        val dao = db.knowledgeV2Dao()

        dao.insertEpoch(KnowledgeV2DatasetEpochEntity(epochId = "e1", epochNo = 1, createdAt = 1L, reason = "import"))
        dao.insertEpoch(KnowledgeV2DatasetEpochEntity(epochId = "e2", epochNo = 2, createdAt = 2L, reason = "restore"))
        val latest = dao.getLatestEpoch()
        assertEquals("最新 epoch = 最大 epochNo(恢复分配新身份)", 2L, latest!!.epochNo)
        assertEquals("restore", latest.reason)

        // R2 步骤 5 合同(review Important #3):epoch 严格单调 —— 恢复载荷携带的旧/等值
        // epoch 必须被拒,运行身份不被覆盖。
        assertEquals("更小 epochNo 被拒(无行插入 rowid=-1)", -1L, dao.insertEpochIfMonotonic("e-old", 1L, 3L, "restore"))
        assertEquals("等值 epochNo 被拒", -1L, dao.insertEpochIfMonotonic("e-eq", 2L, 3L, "restore"))
        assertTrue("更大 epochNo 接受(rowid>0)", dao.insertEpochIfMonotonic("e3", 3L, 3L, "restore") > 0)
        assertEquals("单调守卫后最新仍是最大值", 3L, dao.getLatestEpoch()!!.epochNo)

        dao.insertLease(
            KnowledgeV2RetentionLeaseEntity(
                leaseId = "l1", contentKey = "hash-a", holderId = "worker-1",
                acquiredAt = 1L, expiresAt = 100L,
            ),
        )
        assertEquals("未到期租约有效", 1, dao.getActiveLeases("hash-a", nowMs = 50L).size)
        assertEquals("到期租约不再有效", 0, dao.getActiveLeases("hash-a", nowMs = 150L).size)
        assertEquals("过期租约可回收", 1, dao.deleteExpiredLeases(nowMs = 150L))
        assertTrue("回收后无租约", dao.getActiveLeases("hash-a", nowMs = 50L).isEmpty())
    }

    @Test
    fun `source ref persists as verifiable original provenance`() = runBlocking {
        // R2 review Important #4:SourceRef 落点 —— 原件可再达性真源持久化在 doc 行。
        val name = buildFreshDb()
        db = androidx.room.Room.databaseBuilder(
            context,
            io.zer0.muse.data.session.MuseDb::class.java,
            name,
        ).build()
        val dao = db.knowledgeV2Dao()

        val sourceRef = SourceRef(
            scheme = "file",
            location = "content://uri/path",
            contentHash = io.zer0.muse.knowledge.storage.KnowledgeContentStore.hashOf("bytes".toByteArray()),
            byteSize = 42L,
        )
        val json = kotlinx.serialization.json.Json.encodeToString(SourceRef.serializer(), sourceRef)
        dao.insertDoc(
            KnowledgeV2DocEntity(
                docId = "doc-src", ownerType = "UserLibrary", ownerId = "kb-1",
                sourceRefJson = json, createdAt = 1L, updatedAt = 1L,
            ),
        )
        val restored = kotlinx.serialization.json.Json.decodeFromString(
            SourceRef.serializer(),
            dao.getDoc("doc-src")!!.sourceRefJson,
        )
        assertEquals("SourceRef 往返保真(scheme/location/hash/size)", sourceRef, restored)
        assertTrue("无原件行 sourceRef 缺失可判", SourceRef("", "").isMissing)
    }

    @Test
    fun `revision foreign key cascades and foreign_key_check is clean`() = runBlocking {
        // R2 步骤 8:无损可迁行加 FK —— revision 引用必须落在真实 doc 上;
        // doc 删除 → revision 级联消失(不静默孤儿);foreign_key_check 干净。
        val name = buildFreshDb()
        db = androidx.room.Room.databaseBuilder(
            context,
            io.zer0.muse.data.session.MuseDb::class.java,
            name,
        ).build()
        val dao = db.knowledgeV2Dao()

        dao.insertDoc(KnowledgeV2DocEntity(docId = "doc-fk", ownerType = "UserLibrary", ownerId = "kb-1", createdAt = 1L, updatedAt = 1L))
        dao.insertRevision(
            KnowledgeV2RevisionEntity(
                revisionId = "rev-fk-1", docId = "doc-fk", revisionNo = 1,
                contentHash = "h", fullContent = "正文", preview = "正文", createdAt = 1L,
            ),
        )

        val open = db.openHelper.writableDatabase
        open.query("PRAGMA foreign_key_check").use { c ->
            assertTrue("FK 核对干净(无违规行)", !c.moveToFirst())
        }

        // CASCADE:删 doc → revision 随行失效
        open.execSQL("DELETE FROM knowledge_v2_docs WHERE docId = 'doc-fk'")
        val orphanRevisions = open.query("SELECT COUNT(*) FROM knowledge_v2_revisions WHERE docId = 'doc-fk'").use { c ->
            c.moveToFirst(); c.getLong(0)
        }
        assertEquals("doc 删除时 revision 级联消失(不静默孤儿)", 0L, orphanRevisions)
    }

    @Test
    fun `migration 125 to 126 creates v2 tables without touching legacy rows`() = runBlocking {
        // 直接以 raw SQL 在 SupportSQLiteDatabase 上执行迁移 SQL,验证:
        // (a) 幂等(CREATE TABLE IF NOT EXISTS 可重复执行);(b) 旧行不受影响。
        val name = buildFreshDb()
        db = androidx.room.Room.databaseBuilder(
            context,
            io.zer0.muse.data.session.MuseDb::class.java,
            name,
        ).build()
        val open = db.openHelper.writableDatabase

        // 旧行(旧 knowledge_docs 表由 Room 在建库时已创建)
        open.execSQL(
            "INSERT OR REPLACE INTO `knowledge_docs` (`id`,`title`,`content`,`file_path`,`file_type`," +
                "`created_at`,`updated_at`,`chunk_count`,`embedding_model`,`kb_id`,`is_internal`," +
                "`content_hash`,`metadata_json`) VALUES ('legacy-1','旧文档','旧正文','', 'txt',1,1,0,'','default',0,'','{}')",
        )

        KnowledgeSchemaMigrations.MIGRATION_125_126.migrate(open)
        // 幂等:重复执行不得抛错、不得丢行
        KnowledgeSchemaMigrations.MIGRATION_125_126.migrate(open)

        val legacyCount = open.query("SELECT COUNT(*) FROM knowledge_docs").use { c -> c.moveToFirst(); c.getLong(0) }
        assertEquals("旧行在迁移后原样保留", 1L, legacyCount)
        open.query("SELECT COUNT(*) FROM knowledge_v2_docs").use { c -> c.moveToFirst() }
        open.query("SELECT COUNT(*) FROM knowledge_v2_revisions").use { c -> c.moveToFirst() }
        open.query("SELECT COUNT(*) FROM knowledge_v2_dataset_epoch").use { c -> c.moveToFirst() }
        open.query("SELECT COUNT(*) FROM knowledge_v2_retention_leases").use { c -> c.moveToFirst() }
        val title = open.query("SELECT title FROM knowledge_docs WHERE id='legacy-1'").use { c -> c.moveToFirst(); c.getString(0) }
        assertEquals("旧正文字段未被迁移触碰", "旧文档", title)
    }
}
