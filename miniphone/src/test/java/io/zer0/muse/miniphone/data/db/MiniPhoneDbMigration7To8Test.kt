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
 * 7 → 8 迁移：R4–R9 的表组一次建齐，旧数据一条不动。
 *
 * 按导出的 7.json 造一个与真机同形的 v7 库，塞一条群 + 一条消息 + 一条备忘录，
 * 再让 Room 带全链打开它：14 张新表应全部建出、版本升到 8、老的群/消息/备忘录仍在。
 *
 * 这条迁移是 R4–R9 的地基。实体与建表语句由同一份规格生成，所以这里还顺带让 Room
 * 自己校验「注解算出的表结构」与「迁移实际建出的表」是否一致 —— 两边漂移时
 * Room 会在打开库的阶段直接拒绝，而不是等到某个模块写这张表时才炸。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneDbMigration7To8Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var databaseName: String? = null

    @After
    fun tearDown() {
        databaseName?.let { name -> context.deleteDatabase(name) }
    }

    @Test
    fun upgradeCreatesAllNewTablesAndKeepsOldRows() {
        val name = LEGACY_DB_NAME
        databaseName = name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        createVersion7Database(file)

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
            assertEquals("库版本升到 8", MINI_PHONE_DB_VERSION, db.openHelper.readableDatabase.version)
            val tables = db.openHelper.readableDatabase
                .query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'mp_%'")
                .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
            NEW_TABLES.forEach { table ->
                assertTrue("新表 $table 应被建出", tables.contains(table))
            }

            runBlocking {
                // 旧数据一条不动
                assertEquals(GROUP_THREAD_ID, db.threadDao().getThread(GROUP_THREAD_ID)?.id)
                assertEquals("消息还在", 1, db.messageDao().countInThread(GROUP_THREAD_ID))
                assertEquals("备忘录还在", "记得买伞", db.memoDao().observeAll().first().single().content)
            }
        } finally {
            db.close()
        }
    }

    /** 按 7.json 原样造 v7 库，塞一条群、一条群消息、一条备忘录。 */
    private fun createVersion7Database(file: File) {
        val schema = JSONObject(readAsset(SCHEMA_ASSET)).getJSONObject("database")
        assertEquals("基线必须是 v7", 7, schema.getInt("version"))
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
                "INSERT INTO mp_memo (id, characterId, content, createdAt, updatedAt) " +
                    "VALUES ('memo-1', 'c1', '记得买伞', 3000, 3000)",
            )
            legacy.version = 7
        } finally {
            legacy.close()
        }
    }

    private fun readAsset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    private companion object {
        const val LEGACY_DB_NAME: String = "miniphone-migration-v7.db"
        const val GROUP_THREAD_ID: String = "t-group"
        const val SCHEMA_ASSET: String = "io.zer0.muse.miniphone.data.db.MiniPhoneDb/7.json"
        const val TABLE_PLACEHOLDER: String = "\${TABLE_NAME}"

        /** R4–R9 一次建齐的 14 张表。 */
        val NEW_TABLES: List<String> = listOf(
            "mp_checkup_case",
            "mp_checkup_evidence",
            "mp_forum_post",
            "mp_forum_comment",
            "mp_relation_edge",
            "mp_roam_state",
            "mp_roam_npc",
            "mp_rp_script",
            "mp_rp_save",
            "mp_rp_ending",
            "mp_wardrobe_item",
            "mp_red_packet",
            "mp_holiday_event",
            "mp_ringtone",
        )
    }
}
