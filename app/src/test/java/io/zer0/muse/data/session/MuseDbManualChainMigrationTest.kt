package io.zer0.muse.data.session

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 审查修复 (2.0 B-27): 早期迁移链 22..54 的手动逐步覆盖。
 *
 * Room 校验路径(RoomOpenHelper.onValidateSchema)在 Robolectric 下存在双连接伪影:
 * 迁移事务内新建的索引对校验连接不可见,导致 22..54 起点在 Robolectric 下误报
 * "Migration didn't properly handle: scheduled_tasks"(55+ 起点索引为快照预建,
 * 不受影响;真机单连接无此问题)。本测试用原生 SupportSQLiteOpenHelper 手动逐步
 * 执行与 Room 完全相同的迁移链(含 75_76/76_77/88_89 函数迁移),断言迁移后:
 *  - messages 含当前全部关键列(parentGroupId / videoFileUri / moodSkin 等);
 *  - messages.imageUrlsJson 具备 DEFAULT '[]'(29_30/88_89 修复目标);
 *  - scheduled_tasks 索引 index_scheduled_tasks_enabled_next_run_at 存在(88_89 修复目标);
 *  - 历史遗留行数据保留。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbManualChainMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val legacyVersions: List<Int> = (22..54).filter { version ->
        File("schemas/io.zer0.muse.data.session.MuseDb/$version.json").exists()
    }

    @Test
    fun legacyChainMigration_keepsColumnsIndexesAndData() {
        for (fromVersion in legacyVersions) {
            migrateChainManually(fromVersion)
        }
    }

    /**
     * 逐步执行完整迁移链并断言终态。
     *
     * 函数较长/复杂度高是测试场景使然:需要线性复刻 Room 迁移调度
     * (含 75_76/76_77/88_89 函数迁移与事务包装),并对每个起点版本断言
     * 列/索引/默认值/数据保留四类终态;拆分反而引入间接层,故整体豁免。
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun migrateChainManually(fromVersion: Int) {
        val dbFile = File(context.cacheDir, "manual_chain_$fromVersion.db").apply { if (exists()) delete() }
        val json = org.json.JSONObject(
            File("schemas/io.zer0.muse.data.session.MuseDb/$fromVersion.json").readText(),
        )
        val entities = json.getJSONObject("database").getJSONArray("entities")
        val factory = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(fromVersion) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        for (i in 0 until entities.length()) {
                            val e = entities.getJSONObject(i)
                            val table = e.getString("tableName")
                            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                            if (e.has("indices")) {
                                val idx = e.getJSONArray("indices")
                                for (j in 0 until idx.length()) {
                                    val indexSql = idx.getJSONObject(j).getString("createSql")
                                        .replace("\${TABLE_NAME}", table)
                                    db.execSQL(indexSql)
                                }
                            }
                        }
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit

                    override fun onDowngrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )
        val db = helper.writableDatabase
        // 插入一条历史消息,验证数据跨迁移保留。
        // 早期快照(22-54)的 messages 表存在 NOT NULL 且无默认值的列(如 imageUrlsJson),
        // 按 PRAGMA 动态补齐,避免约束违反。
        val provided = setOf("id", "sessionId", "role", "content", "createdAt")
        val requiredNotNull = mutableListOf<Pair<String, String>>()
        db.query("PRAGMA table_info(messages)").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                val notNull = c.getInt(3) == 1
                val dflt = c.getString(4)
                if (notNull && dflt == null && name !in provided) {
                    requiredNotNull.add(name to if (c.getString(2).contains("INT")) "0" else "''")
                }
            }
        }
        db.execSQL(
            "INSERT INTO sessions (id, title, createdAt, updatedAt, lastMessagePreview, assistantId) " +
                "VALUES ('legacy-session', '迁移测试', 1, 1, '', 'default')",
        )
        val extraCols = requiredNotNull.joinToString("") { ", " + it.first }
        val extraVals = requiredNotNull.joinToString("") { ", " + it.second }
        db.execSQL(
            "INSERT INTO messages (id, sessionId, role, content, createdAt$extraCols) " +
                "VALUES ('legacy-msg', 'legacy-session', 'ASSISTANT', '迁移前的历史消息', 1$extraVals)",
        )

        // 收集与 Room 完全一致的迁移链(常量 + 函数附加)
        val migrations = MuseDb::class.java.declaredFields
            .filter { it.name.startsWith("MIGRATION_") }
            .mapNotNull { f ->
                val parts = f.name.removePrefix("MIGRATION_").split("_")
                val from = parts[0].toIntOrNull() ?: return@mapNotNull null
                val to = parts[1].toIntOrNull() ?: return@mapNotNull null
                if (from < fromVersion) return@mapNotNull null
                f.isAccessible = true
                (from to to) to (f.get(null) as androidx.room.migration.Migration)
            }
            .sortedBy { it.first.first }
            .map { it.second }
            .let { chain ->
                val with75 = if (fromVersion <= 75) {
                    chain + MuseDb.migrate75To76(File(context.cacheDir, "muse_images"))
                } else {
                    chain
                }
                if (fromVersion <= 76) with75 + MuseDb.migrate76To77() else with75
            }

        db.execSQL("PRAGMA foreign_keys=OFF")
        db.beginTransaction()
        try {
            var current = fromVersion
            for (m in migrations) {
                if (m.startVersion < current) continue
                m.migrate(db)
                current = m.endVersion
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        db.execSQL("PRAGMA foreign_keys=ON")

        // ── 断言: 迁移后状态与当前 Entity 一致 ──
        val msgColumns = mutableSetOf<String>()
        db.query("PRAGMA table_info(messages)").use { c ->
            while (c.moveToNext()) msgColumns.add(c.getString(1))
        }
        assertTrue("v$fromVersion 迁移后应有 parentGroupId 列", "parentGroupId" in msgColumns)
        assertTrue("v$fromVersion 迁移后应有 videoFileUri 列", "videoFileUri" in msgColumns)
        assertTrue("v$fromVersion 迁移后应有 moodSkin 列", "moodSkin" in msgColumns)
        assertTrue("v$fromVersion 迁移后应有 toolCallInfoJson 列", "toolCallInfoJson" in msgColumns)
        // v92: seq 列必须存在(迁移 ADD COLUMN + 回填)
        assertTrue("v$fromVersion 迁移后应有 seq 列", "seq" in msgColumns)
        // v92: seq 索引名必须与注解一致(idx_messages_sessionId_seq)—
        // 真机 Room schema 校验索引名,不一致直接崩(Migration didn't properly handle: messages)
        val messageIndexes = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='messages'").use { c ->
            while (c.moveToNext()) messageIndexes.add(c.getString(0))
        }
        assertTrue("v$fromVersion 迁移后应有 idx_messages_sessionId_seq 索引", "idx_messages_sessionId_seq" in messageIndexes)

        var imageUrlsDefault = ""
        db.query("PRAGMA table_info(messages)").use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == "imageUrlsJson") imageUrlsDefault = c.getString(4)?.trim('\'') ?: ""
            }
        }
        assertEquals("v$fromVersion 迁移后 imageUrlsJson 应有 DEFAULT '[]'(B-27 漂移修复)", "[]", imageUrlsDefault)

        val scheduledIndexes = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='scheduled_tasks'").use { c ->
            while (c.moveToNext()) scheduledIndexes.add(c.getString(0))
        }
        assertTrue(
            "v$fromVersion 迁移后 scheduled_tasks 应有 getDueTasks 索引(B-27 修复)",
            "index_scheduled_tasks_enabled_next_run_at" in scheduledIndexes,
        )

        val msgIndexes = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='messages'").use { c ->
            while (c.moveToNext()) msgIndexes.add(c.getString(0))
        }
        val expectedMsgIndexes = listOf(
            "index_messages_sessionId",
            "index_messages_role",
            "index_messages_sessionId_createdAt",
            "index_messages_sessionId_createdAt_role",
        )
        assertTrue(
            "v$fromVersion 迁移后 messages 应保留 4 个业务索引",
            msgIndexes.containsAll(expectedMsgIndexes),
        )

        db.query("SELECT COUNT(*) FROM messages WHERE id='legacy-msg'").use { c ->
            assertTrue("v$fromVersion 迁移后历史消息应保留", c.moveToFirst() && c.getInt(0) == 1)
        }
        helper.close()
        dbFile.delete()
    }
}
