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
 * 5 → 6 迁移:新增心声表 mp_inner_voice,旧数据一条不动。
 *
 * 按导出的 5.json 造一个与真机同形的 v5 库(含 titleCustomized 列),塞一条群 + 一条消息,
 * 再让 Room 带全链打开它:应新建 mp_inner_voice(带索引),版本升到 6,老群/老消息仍在。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneDbMigration5To6Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var databaseName: String? = null

    @After
    fun tearDown() {
        databaseName?.let { name -> context.deleteDatabase(name) }
    }

    @Test
    fun upgradeCreatesInnerVoiceTableAndKeepsOldRows() {
        val name = LEGACY_DB_NAME
        databaseName = name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        createVersion5Database(file)

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
            assertEquals("从 v5 一路升到当前版本（不是停在 6）", MINI_PHONE_DB_VERSION, db.openHelper.readableDatabase.version)
            val tables = db.openHelper.readableDatabase
                .query("SELECT name FROM sqlite_master WHERE type='table' AND name='mp_inner_voice'")
                .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
            assertTrue("心声表应被建出", tables.contains("mp_inner_voice"))

            runBlocking {
                // 旧群/旧消息不受影响
                val thread = db.threadDao().getThread(GROUP_THREAD_ID)
                assertEquals(GROUP_THREAD_ID, thread?.id)
                assertEquals("消息还在", 1, db.messageDao().countInThread(GROUP_THREAD_ID))

                // 新表可用：写一条心声再读回
                db.innerVoiceDao().insert(
                    io.zer0.muse.miniphone.data.db.entity.MpInnerVoiceEntity(
                        id = "iv-1",
                        threadId = GROUP_THREAD_ID,
                        characterId = "c2",
                        messageId = null,
                        text = "其实我也很想他",
                        createdAt = 9999,
                    ),
                )
                val voices = db.innerVoiceDao().observeByThread(GROUP_THREAD_ID).first()
                assertEquals("能读回心声", 1, voices.size)
                assertEquals("其实我也很想他", voices.first().text)
            }
        } finally {
            db.close()
        }
    }

    /** 按 5.json 原样造 v5 库,塞一条群 + 一条群消息。 */
    private fun createVersion5Database(file: File) {
        val schema = JSONObject(readAsset(SCHEMA_ASSET)).getJSONObject("database")
        assertEquals("基线必须是 v5", 5, schema.getInt("version"))
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
            legacy.version = 5
        } finally {
            legacy.close()
        }
    }

    private fun readAsset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    private companion object {
        const val LEGACY_DB_NAME: String = "miniphone-migration-v5.db"
        const val GROUP_THREAD_ID: String = "t-group"
        const val SCHEMA_ASSET: String = "io.zer0.muse.miniphone.data.db.MiniPhoneDb/5.json"
        const val TABLE_PLACEHOLDER: String = "\${TABLE_NAME}"
    }
}
