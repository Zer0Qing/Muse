package io.zer0.muse.miniphone.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 6 → 7 迁移:一次新增备忘录、待应答队列、通话记录三张表,旧数据一条不动。
 *
 * 按导出的 6.json 造一个与真机同形的 v6 库(含 mp_inner_voice,带 titleCustomized 列),
 * 塞一条群 + 一条消息 + 一条心声,再让 Room 带全链打开它:
 * 三张新表(带索引)应建出,版本升到 7,老的群/消息/心声仍在。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneDbMigration6To7Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var databaseName: String? = null

    @After
    fun tearDown() {
        databaseName?.let { name -> context.deleteDatabase(name) }
    }

    @Test
    fun upgradeCreatesThreeTablesAndKeepsOldRows() {
        val name = LEGACY_DB_NAME
        databaseName = name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        createVersion6Database(file)

        val db = Room.databaseBuilder(context, MiniPhoneDb::class.java, name)
            .addMigrations(
                MINI_PHONE_MIGRATION_1_2,
                MINI_PHONE_MIGRATION_2_3,
                MINI_PHONE_MIGRATION_3_4,
                MINI_PHONE_MIGRATION_4_5,
                MINI_PHONE_MIGRATION_5_6,
                MINI_PHONE_MIGRATION_6_7,
                MINI_PHONE_MIGRATION_7_8,
            )
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("从 v6 一路升到当前版本（不是停在 7）", MINI_PHONE_DB_VERSION, db.openHelper.readableDatabase.version)
            val tables = db.openHelper.readableDatabase
                .query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'mp_%'")
                .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
            NEW_TABLES.forEach { table ->
                assertTrue("新表 $table 应被建出", tables.contains(table))
            }

            runBlocking {
                // 旧群/旧消息/旧心声不受影响
                assertEquals(GROUP_THREAD_ID, db.threadDao().getThread(GROUP_THREAD_ID)?.id)
                assertEquals("消息还在", 1, db.messageDao().countInThread(GROUP_THREAD_ID))
                assertEquals("心声还在", 1, db.innerVoiceDao().observeByThread(GROUP_THREAD_ID).first().size)

                // 三张新表都能写能读
                db.memoDao().insert(
                    io.zer0.muse.miniphone.data.db.entity.MpMemoEntity(
                        id = "memo-1",
                        characterId = "c1",
                        content = "记得买伞",
                        createdAt = 1,
                        updatedAt = 2,
                    ),
                )
                assertEquals("记得买伞", db.memoDao().observeAll().first().single().content)

                db.offlineMessageDao().insert(
                    io.zer0.muse.miniphone.data.db.entity.MpOfflineMessageEntity(
                        id = "off-1",
                        threadId = GROUP_THREAD_ID,
                        characterId = "c2",
                        content = "晚点回我",
                        createdAt = 3,
                    ),
                )
                assertEquals("晚点回我", db.offlineMessageDao().observeByThread(GROUP_THREAD_ID).first().single().content)

                db.callSessionDao().insert(
                    io.zer0.muse.miniphone.data.db.entity.MpCallSessionEntity(
                        id = "call-1",
                        threadId = GROUP_THREAD_ID,
                        characterId = "c2",
                        kind = "voice",
                        startedAt = 4,
                        durationMs = 5000,
                    ),
                )
                assertEquals(5000L, db.callSessionDao().observeByThread(GROUP_THREAD_ID).first().single().durationMs)
            }
        } finally {
            db.close()
        }
    }

    /** 按 6.json 原样造 v6 库,塞一条群、一条群消息、一条心声。 */
    private fun createVersion6Database(file: File) {
        val schema = JSONObject(readAsset(SCHEMA_ASSET)).getJSONObject("database")
        assertEquals("基线必须是 v6", 6, schema.getInt("version"))
        val legacy = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                legacy.execSQL(entity.getString("createSql").replace(TABLE_PLACEHOLDER, table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (indexAt in 0 until indices.length()) {
                    legacy.execSQL(indices.getJSONObject(indexAt).getString("createSql").replace(TABLE_PLACEHOLDER, table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) {
                legacy.execSQL(setup.getString(index))
            }
            legacy.execSQL(
                "INSERT INTO mp_thread (id, characterId, pinned, unreadCount, lastMessagePreview, lastMessageAt, muted, " +
                    "isGroup, title, titleCustomized) VALUES ('$GROUP_THREAD_ID', 'c1', 0, 1, '晚上见', 3000, 0, 1, '阿远、小满', 0)",
            )
            legacy.execSQL(
                "INSERT INTO mp_message (id, threadId, characterId, sender, type, content, createdAt, status) " +
                    "VALUES ('legacy-1', '$GROUP_THREAD_ID', 'c2', 'character', 'text', '晚上见', 3000, 0)",
            )
            legacy.execSQL(
                "INSERT INTO mp_inner_voice (id, threadId, characterId, messageId, text, createdAt) " +
                    "VALUES ('iv-1', '$GROUP_THREAD_ID', 'c2', NULL, '其实我也很想他', 3000)",
            )
            legacy.version = 6
        } finally {
            legacy.close()
        }
    }

    private fun readAsset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    private companion object {
        const val LEGACY_DB_NAME: String = "miniphone-migration-v6.db"
        const val GROUP_THREAD_ID: String = "t-group"
        const val SCHEMA_ASSET: String = "io.zer0.muse.miniphone.data.db.MiniPhoneDb/6.json"
        const val TABLE_PLACEHOLDER: String = "\${TABLE_NAME}"
        val NEW_TABLES: List<String> = listOf("mp_memo", "mp_offline_message", "mp_call_session")
    }
}
