package io.zer0.muse.miniphone.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadMemberEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 3 → 4 迁移:老会话与消息逐行保留,会话行多出群聊标记与群名,成员表从这一版起存在且可写。
 *
 * 照导出的 3.json 造一个与真机同形的 v3 库(建表语句、索引、room_master_table 身份哈希都按 schema 写),
 * 里面塞两条会话(一条置顶)与三条消息,再让 Room 带着迁移链打开它。
 *
 * 断言分四层:Room 能打开并通过 schema 校验(说明新列与新表和实体对得上)、
 * 旧行与旧列原样保留、新列的默认值真的落在 0 与空串上、成员表能写入并按入群顺序读回。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneDbMigration3To4Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var databaseName: String? = null

    @After
    fun tearDown() {
        databaseName?.let { name -> context.deleteDatabase(name) }
    }

    @Test
    fun upgradeKeepsLegacyRowsAndDefaultsTheNewColumns() {
        val name = LEGACY_DB_NAME
        databaseName = name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        createVersion3Database(file)

        val db = openMigrated(name)
        try {
            runBlocking {
                val threads = db.threadDao().observeThreads().first()
                assertEquals("会话一条不少,顺序不变", listOf(PINNED_THREAD_ID, PLAIN_THREAD_ID), threads.map { it.id })
                assertEquals("置顶标记跟着行一起留下来", true, threads.first().pinned)
                assertEquals("未读数不能丢", 3, threads.first().unreadCount)
                assertEquals("预览文案不能丢", "第 2 句", threads.first().lastMessagePreview)

                assertTrue("老会话都落在私信这一侧", threads.none { it.isGroup })
                assertEquals("老会话没有群名", listOf("", ""), threads.map { it.title })

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
        assertEquals("群标记列带默认值 0", "0", columnDefault(file, table = "mp_thread", column = "isGroup"))
        assertEquals("群名列带默认值空串", "''", columnDefault(file, table = "mp_thread", column = "title"))
    }

    @Test
    fun memberTableIsCreatedWritableAndOrderedByJoinTime() {
        val name = LEGACY_DB_NAME
        databaseName = name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        createVersion3Database(file)

        runBlocking {
            val db = openMigrated(name)
            try {
                // 升级前没有这张表;升级后它要能当群成员表用
                db.threadDao().upsert(
                    MpThreadEntity(
                        id = GROUP_THREAD_ID,
                        characterId = "c-second",
                        isGroup = true,
                        title = "阿远、小满、我",
                    ),
                )
                db.threadMemberDao().upsertAll(
                    listOf(
                        MpThreadMemberEntity(threadId = GROUP_THREAD_ID, characterId = "c-second", joinedAt = 200L),
                        MpThreadMemberEntity(threadId = GROUP_THREAD_ID, characterId = "c-first", joinedAt = 100L),
                    ),
                )

                val members = db.threadMemberDao().observeMembers(GROUP_THREAD_ID).first()
                assertEquals("成员按入群先后读回", listOf("c-first", "c-second"), members.map { it.characterId })
                assertEquals(2, db.threadMemberDao().countInThread(GROUP_THREAD_ID))
                assertEquals("群行是群会话", true, db.threadDao().getThread(GROUP_THREAD_ID)?.isGroup)
                assertEquals("群名跟着行", "阿远、小满、我", db.threadDao().getThread(GROUP_THREAD_ID)?.title)
            } finally {
                db.close()
            }
        }
        assertEquals("成员表带 threadId 索引", true, indexNames(file, table = "mp_thread_member").contains("index_mp_thread_member_threadId"))
        assertTrue("旧表还在", tableNames(file).containsAll(listOf("mp_thread", "mp_message")))
    }

    @Test
    fun groupThreadsAreNotTreatedAsAPersonsDirectMessage() {
        val name = LEGACY_DB_NAME
        databaseName = name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        createVersion3Database(file)

        runBlocking {
            val db = openMigrated(name)
            try {
                // 升级后再建的群,characterId 落第一个成员;按角色找私信时不能命中它
                db.threadDao().upsert(
                    MpThreadEntity(
                        id = GROUP_THREAD_ID,
                        characterId = CHARACTER_ID,
                        isGroup = true,
                        title = "老会话里的人和另一个人",
                    ),
                )

                assertNotNull("老私信还在", db.threadDao().findThreadByCharacter(CHARACTER_ID))
                assertEquals("群不冒充这个人的私信", PINNED_THREAD_ID, db.threadDao().findThreadByCharacter(CHARACTER_ID)?.id)
                assertFalse(db.threadDao().findThreadByCharacter(CHARACTER_ID)?.isGroup ?: true)
            } finally {
                db.close()
            }
        }
    }

    /** 按 3.json 原样造一个 v3 库,并写入两条会话与三条消息。 */
    private fun createVersion3Database(file: File) {
        val schema = JSONObject(readAsset(SCHEMA_ASSET)).getJSONObject("database")
        assertEquals("基线必须是 v3", 3, schema.getInt("version"))
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
            legacy.version = 3
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

    private fun indexNames(file: File, table: String): Set<String> =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table'", null).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
        }

    private fun tableNames(file: File): Set<String> =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'mp_%'", null).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
        }

    private fun readAsset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    private companion object {
        const val LEGACY_DB_NAME: String = "miniphone-migration-v3.db"
        const val PINNED_THREAD_ID: String = "t-pinned"
        const val PLAIN_THREAD_ID: String = "t-plain"
        const val GROUP_THREAD_ID: String = "t-group"
        const val CHARACTER_ID: String = "c-legacy"
        const val SCHEMA_ASSET: String = "io.zer0.muse.miniphone.data.db.MiniPhoneDb/3.json"
        const val TABLE_PLACEHOLDER: String = "\${TABLE_NAME}"
    }
}
