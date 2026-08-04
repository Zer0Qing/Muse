package io.zer0.muse.data.session

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * B6-03: MuseDb 迁移链测试。
 *
 * 从 v56 到 v63 的每个 schema JSON 手工建库，插入一条历史消息，
 * 再通过 Room 全部迁移到 v64，验证：
 * - messages.mood_skin 列存在且默认 NULL
 * - 历史消息内容与关键列完整保留
 *
 * 实现参考 memory/FactDbMigrationTest：不用 MigrationTestHelper，
 * 改用 FrameworkSQLiteOpenHelperFactory 直接按 schema JSON 建旧库。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbMigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun migrateEveryVersionTo68_keepsDataAndAddsReadColumns() {
        for (fromVersion in 56..67) {
            val dbFile = context.getDatabasePath("muse_migration_$fromVersion.db").apply {
                parentFile?.mkdirs()
                if (exists()) delete()
            }
            try {
                createSchemaAtVersion(fromVersion, dbFile.absolutePath)
                insertLegacyRow(dbFile.absolutePath, fromVersion)

                val db = Room.databaseBuilder(
                    context,
                    MuseDb::class.java,
                    dbFile.absolutePath,
                )
                    .addMigrations(
                        MuseDb.MIGRATION_56_57,
                        MuseDb.MIGRATION_57_58,
                        MuseDb.MIGRATION_58_59,
                        MuseDb.MIGRATION_59_60,
                        MuseDb.MIGRATION_60_61,
                        MuseDb.MIGRATION_61_62,
                        MuseDb.MIGRATION_62_63,
                        MuseDb.MIGRATION_63_64,
                        MuseDb.MIGRATION_64_65,
                        MuseDb.MIGRATION_65_66,
                        MuseDb.MIGRATION_66_67,
                        MuseDb.MIGRATION_67_68,
                    )
                    .allowMainThreadQueries()
                    .build()

                db.openHelper.writableDatabase.query("PRAGMA table_info(messages)").use { cursor ->
                    var moodSkinDefault: String? = null
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "moodSkin") {
                            moodSkinDefault = cursor.getString(4)
                            break
                        }
                    }
                    assertTrue("v$fromVersion 迁移后应有 moodSkin 列", moodSkinDefault != null)
                    assertTrue(
                        "moodSkin 默认值应为 NULL, got $moodSkinDefault",
                        moodSkinDefault == null || moodSkinDefault == "NULL",
                    )
                }

                db.openHelper.writableDatabase.query("PRAGMA table_info(sessions)").use { cursor ->
                    var hasLastRead = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "lastReadMessageId") {
                            hasLastRead = true
                            break
                        }
                    }
                    assertTrue("v$fromVersion 迁移后应有 lastReadMessageId 列", hasLastRead)
                }
                db.openHelper.writableDatabase.query("PRAGMA table_info(sessions)").use { cursor ->
                    var hasLastReadCount = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "lastReadCount") {
                            hasLastReadCount = true
                            break
                        }
                    }
                    assertTrue("v$fromVersion 迁移后应有 lastReadCount 列", hasLastReadCount)
                }
                db.openHelper.writableDatabase.query("PRAGMA table_info(sessions)").use { cursor ->
                    var hasProactiveNext = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "proactiveNextTriggerAt") {
                            hasProactiveNext = true
                            break
                        }
                    }
                    assertTrue("v$fromVersion 迁移后应有 proactiveNextTriggerAt 列", hasProactiveNext)
                }
                db.openHelper.writableDatabase.query(
                    "SELECT content, moodSkin FROM messages WHERE id='legacy-msg'"
                ).use { cursor ->
                    assertTrue("v$fromVersion 迁移后历史消息应保留", cursor.moveToFirst())
                    assertEquals("迁移前的历史消息", cursor.getString(0))
                    assertNull("历史消息 mood_skin 应为 NULL", cursor.getString(1))
                }

                db.close()
            } finally {
                if (dbFile.exists()) dbFile.delete()
                context.deleteDatabase(dbFile.name)
            }
        }
    }

    private fun createSchemaAtVersion(version: Int, dbPath: String) {
        val schema = loadSchema(version)
        val databaseJson = schema.getJSONObject("database")
        val entities = databaseJson.getJSONArray("entities")
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbPath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val tableName = entity.getString("tableName")
                            val createSql = entity.getString("createSql")
                                .replace("\${TABLE_NAME}", tableName)
                            db.execSQL(createSql)
                            if (entity.has("indices")) {
                                val indices = entity.getJSONArray("indices")
                                for (j in 0 until indices.length()) {
                                    val index = indices.getJSONObject(j)
                                    db.execSQL(
                                        index.getString("createSql")
                                            .replace("\${TABLE_NAME}", tableName)
                                    )
                                }
                            }
                        }
                        val setup = databaseJson.getJSONArray("setupQueries")
                        for (i in 0 until setup.length()) {
                            db.execSQL(setup.getString(i))
                        }
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) {
                        // Room migration 接管
                    }
                })
                .build(),
        ).writableDatabase
        helper.close()
    }

    private fun insertLegacyRow(dbPath: String, fromVersion: Int) {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbPath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(fromVersion) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) {
                    }
                })
                .build(),
        ).writableDatabase

        helper.execSQL(
            """
            INSERT INTO sessions (id, title, createdAt, updatedAt, lastMessagePreview, assistantId)
            VALUES ('legacy-session', '迁移测试', 1, 1, '', 'default')
            """.trimIndent()
        )
        helper.execSQL(
            """
            INSERT INTO messages (id, sessionId, role, content, createdAt)
            VALUES ('legacy-msg', 'legacy-session', 'ASSISTANT', '迁移前的历史消息', 1)
            """.trimIndent()
        )
        helper.close()
    }

    private fun loadSchema(version: Int): JSONObject {
        val candidates = listOf(
            File("schemas/io.zer0.muse.data.session.MuseDb/$version.json"),
            File("app/schemas/io.zer0.muse.data.session.MuseDb/$version.json"),
            File("../app/schemas/io.zer0.muse.data.session.MuseDb/$version.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 $version.json schema，请确认 app/schemas 已生成")
        return JSONObject(file.readText())
    }

    private fun Context.deleteDatabase(name: String) {
        runCatching { deleteDatabase(name) }
    }
}
