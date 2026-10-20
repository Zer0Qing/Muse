package io.zer0.muse.data.session

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P3-1 / P2-2: v111 → v112 迁移的行为验证。
 *
 * 本次迁移给 `knowledge_bases` 新增 5 个 per-KB 覆盖列。这里用原生 SQLite
 * 直接执行与 [MuseDb.MIGRATION_111_112] 完全相同的 DDL，断言：
 *  - 5 个列都存在
 *  - 存量行不丢，且覆盖字段为空（= 继承全局配置，行为不变）
 *
 * 不依赖 room-testing（未在依赖里），避免为一个测试扩大构建面。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbMigration111To112Test {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun migration111To112_addsOverrideColumnsAndKeepsLegacyRows() {
        val dbName = "migration_111_112_test.db"
        context.deleteDatabase(dbName)

        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(dbName).also { it.parentFile?.mkdirs() },
            null,
        )
        try {
            // 1. 还原 v111 的 knowledge_bases 结构并插入存量行
            db.execSQL(
                """
                CREATE TABLE knowledge_bases (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    doc_count INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO knowledge_bases (id, name, description, created_at, updated_at) " +
                    "VALUES ('kb-legacy', 'Legacy KB', 'old', 1, 2)",
            )
            db.version = 111

            // 2. 执行与 MIGRATION_111_112 完全相同的 DDL
            db.execSQL("ALTER TABLE knowledge_bases ADD COLUMN chunk_size_override INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE knowledge_bases ADD COLUMN chunk_overlap_override INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE knowledge_bases ADD COLUMN top_k_override INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE knowledge_bases ADD COLUMN threshold_override REAL DEFAULT NULL")
            db.execSQL(
                "ALTER TABLE knowledge_bases ADD COLUMN embedding_model_override TEXT NOT NULL DEFAULT ''",
            )
            db.version = 112

            // 3. 断言：5 个列都存在
            val columns = mutableMapOf<String, String>()
            val infoCursor: android.database.Cursor = db.rawQuery("PRAGMA table_info(knowledge_bases)", null)
            infoCursor.use { c ->
                while (c.moveToNext()) {
                    columns[c.getString(1)] = c.getString(2)
                }
            }
            val expected: Map<String, String> = mapOf(
                "chunk_size_override" to "INTEGER",
                "chunk_overlap_override" to "INTEGER",
                "top_k_override" to "INTEGER",
                "threshold_override" to "REAL",
                "embedding_model_override" to "TEXT",
            )
            for ((name, type) in expected) {
                assertEquals("列 $name 应存在且类型为 $type", type, columns[name])
            }

            // 4. 断言：存量行保留，覆盖字段为空（继承全局）
            val rowCursor: android.database.Cursor = db.rawQuery(
                "SELECT name, chunk_size_override, top_k_override, embedding_model_override " +
                    "FROM knowledge_bases WHERE id = 'kb-legacy'",
                null,
            )
            rowCursor.use { c ->
                assertTrue("存量 KB 行应保留", c.moveToFirst())
                assertEquals("存量 KB 名称不应丢失", "Legacy KB", c.getString(0))
                assertTrue("chunk_size_override 应为 NULL（继承全局）", c.isNull(1))
                assertTrue("top_k_override 应为 NULL（继承全局）", c.isNull(2))
                assertEquals("embedding_model_override 应为空串", "", c.getString(3))
            }
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }
}
