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

/**
 * R9 迁移矩阵(计划 R9「用合成/授权脱敏库跑新装、全迁移链…生成迁移分类、行数/hash 报告」)。
 *
 * 用 Room MigrationTestHelper + 导出的 schema(125.json–129.json)实测:
 *  - 125 版本插入旧知识行 → 迁移到 129,旧行保留、V2 表建立;
 *  - 新装直接到 129 时 V2 表齐全。
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeMigrationMatrixTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, MuseDb::class.java)

    @Test
    fun migrate125To129PreservesLegacyKnowledgeRow() {
        val dbName = "kb-migration-matrix.db"
        context.deleteDatabase(dbName)
        helper.createDatabase(dbName, 125).use { db ->
            db.execSQL(
                "INSERT INTO knowledge_docs (id, title, content, kb_id, is_internal, content_hash) " +
                    "VALUES ('doc-legacy', '旧文档', '旧正文完整内容', 'default', 0, 'hash-legacy')",
            )
            db.execSQL(
                "INSERT INTO knowledge_bases (id, name, description, created_at, updated_at, doc_count) " +
                    "VALUES ('default', '默认知识库', '', 1, 1, 1)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName,
            129,
            true,
            *MuseDb.migrations(context).toTypedArray(),
        )

        migrated.query("SELECT id, content FROM knowledge_docs WHERE id = 'doc-legacy'").use { cursor ->
            assertTrue("旧知识行必须保留", cursor.moveToFirst())
            assertEquals("旧正文完整内容", cursor.getString(1))
        }
        assertTableExists(migrated, "knowledge_v2_docs")
        assertTableExists(migrated, "knowledge_v2_revisions")
        assertTableExists(migrated, "knowledge_v2_folders")
        assertTableExists(migrated, "knowledge_v2_gen_chunks")
        assertTableExists(migrated, "knowledge_v2_jobs")
        assertTableExists(migrated, "knowledge_v2_active_generation")
        migrated.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun freshInstallAt129HasKnowledgeV2Tables() {
        val dbName = "kb-migration-fresh.db"
        context.deleteDatabase(dbName)
        val db = helper.createDatabase(dbName, 129)
        assertTableExists(db, "knowledge_v2_docs")
        assertTableExists(db, "knowledge_v2_revisions")
        assertTableExists(db, "knowledge_v2_folders")
        assertTableExists(db, "knowledge_v2_bindings")
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun assertTableExists(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String) {
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name = ?", arrayOf(table)).use { cursor ->
            assertTrue("表不存在: $table", cursor.moveToFirst())
        }
    }
}
