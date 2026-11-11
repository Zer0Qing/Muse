package io.zer0.muse.knowledge

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.zer0.muse.data.session.MuseDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * R9 全量迁移矩阵报告(计划 R9「用合成/授权脱敏库跑新装、全迁移链…生成迁移分类、
 * 行数/hash、无效绑定和待补源报告」)。
 *
 * 在真实 Android 上以 125 合成库(多库/多文档/多 chunk/孤儿目录)迁移到 130,
 * 输出可核对的报告:逐表行数、内容 hash、孤儿/无效绑定计数。
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeMigrationReportTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, MuseDb::class.java)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun migrate125To130ProducesVerifiableReport() {
        val dbName = "kb-migration-report.db"
        context.deleteDatabase(dbName)

        // 合成库:2 个 KB、3 篇文档(含内部文档)、4 个 chunk、1 个空目录、1 个孤儿目录。
        helper.createDatabase(dbName, 125).use { db ->
            db.execSQL(
                "INSERT INTO knowledge_bases (id, name, description, created_at, updated_at, doc_count) " +
                    "VALUES ('kb-a', '库A', '', 1, 1, 2), ('kb-b', '库B', '', 1, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO knowledge_docs (id, title, content, kb_id, is_internal, content_hash, file_type) VALUES " +
                    "('doc-1', '文档一', '正文一', 'kb-a', 0, ?, 'txt'), " +
                    "('doc-2', '文档二', '正文二', 'kb-a', 0, ?, 'md'), " +
                    "('doc-internal', '内置帮助', '帮助正文', 'kb-b', 1, ?, 'devdoc')",
                arrayOf(sha256("正文一"), sha256("正文二"), sha256("帮助正文")),
            )
            db.execSQL(
                "INSERT INTO knowledge_chunks (id, doc_id, content, chunk_index) VALUES " +
                    "('c1', 'doc-1', '正文一', 0), ('c2', 'doc-2', '正文二', 0), " +
                    "('c3', 'doc-2', '正文二续', 1), ('c4', 'doc-internal', '帮助正文', 0)",
            )
            db.execSQL(
                "INSERT INTO knowledge_folders (id, kb_id, path, name) VALUES " +
                    "('kbfolder-kb-a-空目录', 'kb-a', '空目录', '空目录'), " +
                    "('kbfolder-kb-a-孤儿', 'kb-a', '孤儿目录', '孤儿目录')",
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName,
            130,
            true,
            *MuseDb.migrations(context).toTypedArray(),
        )

        // --- 报告:逐表行数 ---
        val kbCount = count(db, "knowledge_bases")
        val docCount = count(db, "knowledge_docs")
        val chunkCount = count(db, "knowledge_chunks")
        val folderCount = count(db, "knowledge_folders")
        val v2DocCount = count(db, "knowledge_v2_docs")

        // --- 报告:内容 hash 未变 ---
        val doc1Hash = queryString(db, "SELECT content_hash FROM knowledge_docs WHERE id = 'doc-1'")
        val doc1Content = queryString(db, "SELECT content FROM knowledge_docs WHERE id = 'doc-1'")

        // --- 报告:孤儿目录(无文档引用) ---
        val orphanFolders = count(
            db,
            "knowledge_folders f WHERE NOT EXISTS " +
                "(SELECT 1 FROM knowledge_docs d WHERE d.kb_id = f.kb_id AND d.metadata_json LIKE '%' || f.path || '%')",
        )

        val report = buildString {
            append("MIGRATION-REPORT from=125 to=130 ")
            append("knowledge_bases=$kbCount knowledge_docs=$docCount ")
            append("knowledge_chunks=$chunkCount knowledge_folders=$folderCount ")
            append("knowledge_v2_docs=$v2DocCount ")
            append("doc1_hash_ok=${doc1Hash == sha256(doc1Content)} ")
            append("orphan_folders=$orphanFolders")
        }
        println(report)

        // --- 断言:迁移不丢行、不改内容、V2 表为空待导入 ---
        assertEquals("KB 行数必须保留", 2, kbCount)
        assertEquals("文档行数必须保留", 3, docCount)
        assertEquals("chunk 行数必须保留", 4, chunkCount)
        assertEquals("目录行数必须保留(含空目录)", 2, folderCount)
        assertEquals("迁移不导入 V2 数据(切换前为空)", 0, v2DocCount)
        assertEquals("内容 hash 与正文一致", sha256(doc1Content), doc1Hash)
        assertTrue("孤儿目录应被报告(>0)", orphanFolders > 0)

        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun freshInstallAt130HasAllV2Tables() {
        val dbName = "kb-migration-fresh-130.db"
        context.deleteDatabase(dbName)
        val db = helper.createDatabase(dbName, 130)
        listOf(
            "knowledge_v2_docs",
            "knowledge_v2_revisions",
            "knowledge_v2_folders",
            "knowledge_v2_bindings",
            "knowledge_v2_generations",
            "knowledge_v2_gen_chunks",
            "knowledge_v2_gen_lexical",
            "knowledge_v2_active_generation",
            "knowledge_v2_jobs",
            "knowledge_v2_job_items",
        ).forEach { table ->
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name = ?", arrayOf(table)).use { c ->
                assertTrue("表不存在: $table", c.moveToFirst())
            }
        }
        // messages 新列必须存在
        db.query("PRAGMA table_info(messages)").use { c ->
            var found = false
            while (c.moveToNext()) {
                if (c.getString(c.getColumnIndexOrThrow("name")) == "knowledgeEvidenceJson") found = true
            }
            assertTrue("messages.knowledgeEvidenceJson 必须存在", found)
        }
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun count(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Int =
        db.query("SELECT COUNT(*) FROM $sql").use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    private fun queryString(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
}
