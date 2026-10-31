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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 2 → 3 迁移:老库里的会话与消息逐行保留,置顶列从这一版起带 SQL 默认值 0。
 *
 * 先用导出的 2.json 造一个与真机同形的 v2 库(建表语句、索引、room_master_table 身份哈希都按 schema 写),
 * 里面塞两条会话(一条已置顶)与几条消息,再让 Room 带着迁移链打开它。
 * 断言分三层:Room 能打开并通过 schema 校验(说明重建后的表与实体对得上)、
 * 行与列原样保留、置顶列在新写入不带它时落 0。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneDbMigration2To3Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var databaseName: String? = null

    @After
    fun tearDown() {
        databaseName?.let { name -> context.deleteDatabase(name) }
    }

    @Test
    fun upgradeKeepsThreadsAndMessagesAndGivesPinnedItsDefault() {
        val name = LEGACY_DB_NAME
        databaseName = name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        createVersion2Database(file)

        val db = openMigrated(name)
        try {
            runBlocking {
                val threads = db.threadDao().observeThreads().first()
                assertEquals("会话一条不少", listOf(PINNED_THREAD_ID, PLAIN_THREAD_ID), threads.map { it.id })
                assertEquals("置顶标记跟着行一起留下来", true, threads.first().pinned)
                assertEquals("未读数不能丢", 3, threads.first().unreadCount)
                assertEquals("预览文案不能丢", "第 2 句", threads.first().lastMessagePreview)
                assertEquals("未置顶那行还是未置顶", false, threads.last().pinned)

                assertEquals(
                    "消息按会话各自保留",
                    listOf("legacy-1", "legacy-2"),
                    db.messageDao().observeMessages(PINNED_THREAD_ID).first().map { it.id },
                )
                assertEquals("另一个会话的消息也还在", 1, db.messageDao().countInThread(PLAIN_THREAD_ID))
                assertEquals("库版本继续往后走,跑到当前版本", MINI_PHONE_DB_VERSION, db.openHelper.readableDatabase.version)
            }
        } finally {
            db.close()
        }
        assertEquals("置顶列带默认值 0", "0", columnDefault(file, table = "mp_thread", column = "pinned"))
    }

    @Test
    fun writesWithoutPinnedLandOnNotPinnedAndIndexSurvivesRebuild() {
        val name = LEGACY_DB_NAME
        databaseName = name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        createVersion2Database(file)

        val db = openMigrated(name)
        try {
            runBlocking {
                // 不带 pinned 的写入:旧代码路径、手工 SQL 都靠这个默认值落到「未置顶」
                db.openHelper.writableDatabase.execSQL(
                    "INSERT INTO mp_thread (id, characterId, unreadCount, lastMessagePreview, lastMessageAt, muted) " +
                        "VALUES ('fresh', 'c-fresh', 0, NULL, 7000, 0)",
                )
                assertEquals(false, db.threadDao().getThread("fresh")?.pinned)
                // 置顶开关能改写
                db.threadDao().setPinned("fresh", true)
                assertEquals(true, db.threadDao().getThread("fresh")?.pinned)
                // 重建过表之后,靠索引的那条查询照样能走
                assertEquals(PINNED_THREAD_ID, db.threadDao().findThreadByCharacter(CHARACTER_ID)?.id)
            }
        } finally {
            db.close()
        }
        assertEquals("重建后索引还在", true, indexNames(file).contains("index_mp_thread_lastMessageAt"))
    }

    /** 按 2.json 原样造一个 v2 库,并写入两条会话与三条消息。 */
    private fun createVersion2Database(file: File) {
        val schema = JSONObject(readAsset(SCHEMA_ASSET)).getJSONObject("database")
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
            // 一条置顶会话 + 一条未置顶会话;未置顶那条的消息更晚,迁移后顺序不能变
            legacy.execSQL(
                "INSERT INTO mp_thread (id, characterId, pinned, unreadCount, lastMessagePreview, lastMessageAt, muted) " +
                    "VALUES ('$PINNED_THREAD_ID', '$CHARACTER_ID', 1, 3, '第 2 句', 2000, 0)",
            )
            legacy.execSQL(
                "INSERT INTO mp_thread (id, characterId, pinned, unreadCount, lastMessagePreview, lastMessageAt, muted) " +
                    "VALUES ('$PLAIN_THREAD_ID', 'c-other', 0, 0, '别的一句话', 9000, 0)",
            )
            legacy.execSQL(
                "INSERT INTO mp_message (id, threadId, characterId, sender, type, content, createdAt, status) " +
                    "VALUES ('legacy-1', '$PINNED_THREAD_ID', '$CHARACTER_ID', 'character', 'text', '第 1 句', 1000, 0)",
            )
            legacy.execSQL(
                "INSERT INTO mp_message (id, threadId, characterId, sender, type, content, createdAt, status) " +
                    "VALUES ('legacy-2', '$PINNED_THREAD_ID', '$CHARACTER_ID', 'user', 'text', '第 2 句', 2000, 0)",
            )
            legacy.execSQL(
                "INSERT INTO mp_message (id, threadId, characterId, sender, type, content, createdAt, status) " +
                    "VALUES ('other-1', '$PLAIN_THREAD_ID', 'c-other', 'character', 'text', '别的一句话', 9000, 0)",
            )
            legacy.version = 2
        } finally {
            legacy.close()
        }
    }

    private fun openMigrated(name: String): MiniPhoneDb = Room.databaseBuilder(context, MiniPhoneDb::class.java, name)
        .addMigrations(MINI_PHONE_MIGRATION_1_2, MINI_PHONE_MIGRATION_2_3, MINI_PHONE_MIGRATION_3_4, MINI_PHONE_MIGRATION_4_5)
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

    private fun indexNames(file: File): Set<String> =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'mp_thread'", null).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
        }

    private fun readAsset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    private companion object {
        const val LEGACY_DB_NAME: String = "miniphone-migration-v2.db"
        const val PINNED_THREAD_ID: String = "t-pinned"
        const val PLAIN_THREAD_ID: String = "t-plain"
        const val CHARACTER_ID: String = "c-legacy"
        const val SCHEMA_ASSET: String = "io.zer0.muse.miniphone.data.db.MiniPhoneDb/2.json"
        const val TABLE_PLACEHOLDER: String = "\${TABLE_NAME}"
    }
}
