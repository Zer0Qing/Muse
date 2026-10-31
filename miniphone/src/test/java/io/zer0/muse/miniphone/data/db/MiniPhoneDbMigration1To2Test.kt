package io.zer0.muse.miniphone.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 1 → 2 迁移:老库(没有 status 列)升上来之后数据还在、状态默认为「已发出」。
 *
 * 先用导出的 1.json 造一个与真机同形的 v1 库 —— 建表语句、索引、甚至 room_master_table 里的
 * 身份哈希都按 schema 写,所以这里跑的是「已经有数据的老设备升级」这条路,不是空库建表。
 * 断言分两层:Room 能打开并读到历史行(说明 schema 校验过关),以及 status 真的是 0。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneDbMigration1To2Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var databaseName: String? = null

    @After
    fun tearDown() {
        databaseName?.let { name -> context.deleteDatabase(name) }
    }

    @Test
    fun upgradeKeepsLegacyMessagesAndDefaultsTheirStatusToSent() {
        val name = LEGACY_DB_NAME
        databaseName = name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        createVersion1Database(file, messageCount = 3)

        // 迁移前:老结构里根本没有这一列
        assertFalse("v1 库里不该有 status 列", columnNames(file).contains("status"))

        val db = openMigrated(name)
        try {
            runBlocking {
                val messages = db.messageDao().observeMessages(LEGACY_THREAD_ID).first()

                assertEquals("历史消息不能丢", 3, messages.size)
                assertEquals(listOf("第 1 句", "第 2 句", "第 3 句"), messages.map { it.content })
                assertEquals("历史行全部按「已发出」补齐", List(3) { MpMessageStatus.SENT }, messages.map { it.status })
                assertEquals("会话行也还在", "第 3 句", db.threadDao().getThread(LEGACY_THREAD_ID)?.lastMessagePreview)
                assertEquals("库版本升到当前版本", MINI_PHONE_DB_VERSION, db.openHelper.readableDatabase.version)
            }
        } finally {
            db.close()
        }
        // 新库同样要在裸 SQL 里看得出默认值:后续任何一条不带 status 的写入都该落 0
        assertEquals("0", statusColumnDefault(file))
    }

    @Test
    fun columnDefaultKeepsOldStyleWritesAndStatusUpdatesWork() {
        val db = newInMemoryMiniPhoneDb()
        try {
            // 不带 status 的写入(旧代码路径、手工 SQL)落成「已发出」
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO mp_message (id, threadId, characterId, sender, type, content, createdAt) " +
                    "VALUES ('m1', 't1', 'c1', 'character', 'text', '在的', 1000)",
            )
            assertEquals(MpMessageStatus.SENT, runBlocking { db.messageDao().getMessage("m1") }?.status)

            // 状态能被改写:失败与重发成功的流转都靠这一句
            runBlocking { db.messageDao().updateStatus("m1", MpMessageStatus.FAILED) }
            assertEquals(MpMessageStatus.FAILED, runBlocking { db.messageDao().getMessage("m1") }?.status)
        } finally {
            db.close()
        }
    }

    /** 按 1.json 原样造一个 v1 库,并写入几条历史消息。 */
    private fun createVersion1Database(file: File, messageCount: Int) {
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
            // 与真机一致:room_master_table 里存的是 v1 的身份哈希
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) {
                legacy.execSQL(setup.getString(index))
            }
            legacy.execSQL(
                "INSERT INTO mp_thread (id, characterId, pinned, unreadCount, lastMessagePreview, lastMessageAt, muted) " +
                    "VALUES ('$LEGACY_THREAD_ID', '$LEGACY_CHARACTER_ID', 0, 0, '第 3 句', 3000, 0)",
            )
            repeat(messageCount) { offset ->
                val number = offset + 1
                legacy.execSQL(
                    "INSERT INTO mp_message (id, threadId, characterId, sender, type, content, createdAt) " +
                        "VALUES ('legacy-$number', '$LEGACY_THREAD_ID', '$LEGACY_CHARACTER_ID', 'character', 'text', " +
                        "'第 $number 句', ${number * 1000})",
                )
            }
            legacy.version = 1
        } finally {
            legacy.close()
        }
    }

    private fun openMigrated(name: String): MiniPhoneDb = Room.databaseBuilder(context, MiniPhoneDb::class.java, name)
        .addMigrations(MINI_PHONE_MIGRATION_1_2, MINI_PHONE_MIGRATION_2_3, MINI_PHONE_MIGRATION_3_4, MINI_PHONE_MIGRATION_4_5)
        .allowMainThreadQueries()
        .build()

    private fun columnNames(file: File): Set<String> =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA table_info(mp_message)", null).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
        }

    private fun statusColumnDefault(file: File): String? =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA table_info(mp_message)", null).use { cursor ->
                var found: String? = null
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "status") {
                        found = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                    }
                }
                found
            }
        }

    private fun readAsset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    private companion object {
        const val LEGACY_DB_NAME: String = "miniphone-migration-v1.db"
        const val LEGACY_THREAD_ID: String = "t-legacy"
        const val LEGACY_CHARACTER_ID: String = "c-legacy"
        const val SCHEMA_ASSET: String = "io.zer0.muse.miniphone.data.db.MiniPhoneDb/1.json"
        const val TABLE_PLACEHOLDER: String = "\${TABLE_NAME}"
    }
}
