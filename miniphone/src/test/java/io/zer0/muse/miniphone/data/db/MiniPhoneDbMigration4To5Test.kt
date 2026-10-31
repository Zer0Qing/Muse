package io.zer0.muse.miniphone.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 4 → 5 迁移:老群原样保留,新列 titleCustomized 落在 0(没被用户改过名)。
 *
 * 照导出的 4.json 造一个与真机同形的 v4 库(建表语句、索引、room_master_table 身份哈希都按 schema 写),
 * 里面放一条有群名、有成员、有消息的群,再让 Room 带着迁移链打开它。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneDbMigration4To5Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var databaseName: String? = null

    @After
    fun tearDown() {
        databaseName?.let { name -> context.deleteDatabase(name) }
    }

    @Test
    fun upgradeKeepsGroupRowsAndDefaultsTheCustomizedFlag() {
        val name = LEGACY_DB_NAME
        databaseName = name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        createVersion4Database(file)

        val db = openMigrated(name)
        try {
            runBlocking {
                val threads = db.threadDao().observeThreads().first()
                assertEquals("会话一条不少", listOf(GROUP_THREAD_ID, DM_THREAD_ID), threads.map { it.id })

                val group = requireNotNull(db.threadDao().getThread(GROUP_THREAD_ID))
                assertTrue("群标记跟着留下来", group.isGroup)
                assertEquals("群名跟着留下来", "阿远、小满", group.title)
                assertFalse("老群没有「用户改过名」的记录,一律当没改过", group.titleCustomized)

                assertEquals(
                    "成员表也不动",
                    listOf("c1", "c2"),
                    db.threadMemberDao().membersOf(GROUP_THREAD_ID).map { it.characterId },
                )
                assertEquals("消息还在", 1, db.messageDao().countInThread(GROUP_THREAD_ID))
                assertEquals("库版本升到 5", MINI_PHONE_DB_VERSION, db.openHelper.readableDatabase.version)
                assertEquals("这一版就是 5", 5, MINI_PHONE_DB_VERSION)
            }
        } finally {
            db.close()
        }
        assertEquals("新列带默认值 0", "0", columnDefault(file, table = "mp_thread", column = "titleCustomized"))
    }

    @Test
    fun aFreshGroupAfterUpgradeCanBeMarkedAsRenamed() {
        val name = LEGACY_DB_NAME
        databaseName = name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        createVersion4Database(file)

        runBlocking {
            val db = openMigrated(name)
            try {
                // 升级后按新语义写入:改名即置 1,没改过的老群仍为 0
                db.threadDao().updateTitle(GROUP_THREAD_ID, "周末小队", customized = true)
                db.threadDao().upsert(
                    MpThreadEntity(id = "fresh-group", characterId = "c1", isGroup = true, title = "新群"),
                )

                assertTrue(requireNotNull(db.threadDao().getThread(GROUP_THREAD_ID)).titleCustomized)
                assertFalse("新建的群默认没改过名", requireNotNull(db.threadDao().getThread("fresh-group")).titleCustomized)
                assertEquals("改名真的写进去了", "周末小队", db.threadDao().getThread(GROUP_THREAD_ID)?.title)
            } finally {
                db.close()
            }
        }
    }

    /** 按 4.json 原样造一个 v4 库,并写入一条群、一条私信、两行成员与一条群消息。 */
    private fun createVersion4Database(file: File) {
        val schema = JSONObject(readAsset(SCHEMA_ASSET)).getJSONObject("database")
        assertEquals("基线必须是 v4", 4, schema.getInt("version"))
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
                    "isGroup, title) VALUES ('$GROUP_THREAD_ID', 'c1', 0, 2, '晚上见', 3000, 0, 1, '阿远、小满')",
            )
            legacy.execSQL(
                "INSERT INTO mp_thread (id, characterId, pinned, unreadCount, lastMessagePreview, lastMessageAt, muted, " +
                    "isGroup, title) VALUES ('$DM_THREAD_ID', 'c1', 0, 0, '在吗', 1000, 0, 0, '')",
            )
            legacy.execSQL(
                "INSERT INTO mp_thread_member (threadId, characterId, joinedAt) VALUES ('$GROUP_THREAD_ID', 'c1', 1)",
            )
            legacy.execSQL(
                "INSERT INTO mp_thread_member (threadId, characterId, joinedAt) VALUES ('$GROUP_THREAD_ID', 'c2', 2)",
            )
            legacy.execSQL(
                "INSERT INTO mp_message (id, threadId, characterId, sender, type, content, createdAt, status) " +
                    "VALUES ('legacy-1', '$GROUP_THREAD_ID', 'c2', 'character', 'text', '晚上见', 3000, 0)",
            )
            legacy.version = 4
        } finally {
            legacy.close()
        }
    }

    private fun openMigrated(name: String): MiniPhoneDb = Room.databaseBuilder(context, MiniPhoneDb::class.java, name)
        .addMigrations(
            MINI_PHONE_MIGRATION_1_2,
            MINI_PHONE_MIGRATION_2_3,
            MINI_PHONE_MIGRATION_3_4,
            MINI_PHONE_MIGRATION_4_5,
        )
        .allowMainThreadQueries()
        .build()

    private fun columnDefault(file: File, table: String, column: String): String? =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                var found: String? = null
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == column) {
                        found = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                    }
                }
                found
            }
        }

    private fun readAsset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    private companion object {
        const val LEGACY_DB_NAME: String = "miniphone-migration-v4.db"
        const val GROUP_THREAD_ID: String = "t-group"
        const val DM_THREAD_ID: String = "t-dm"
        const val SCHEMA_ASSET: String = "io.zer0.muse.miniphone.data.db.MiniPhoneDb/4.json"
        const val TABLE_PLACEHOLDER: String = "\${TABLE_NAME}"
    }
}
