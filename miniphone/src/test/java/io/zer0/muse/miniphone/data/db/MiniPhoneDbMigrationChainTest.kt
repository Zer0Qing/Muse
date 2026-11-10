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
 * 整链升级：从真实的 v5 库一路升到当前版本，每一段新增的表都要真的建出来。
 *
 * 逐段测试（5→6、6→7、7→8）各自都绿，但它们验的是「这一段跑完之后的样子」。
 * 真机升级走的是整条链，一段没接上就会在用户设备上炸；而「最后一张表存在」也不能说明前面几段都跑了
 * —— 所以这里把 v6/v7/v8 三个版本各自新增的表逐一点名核对。
 *
 * 基线库按导出的 `5.json` 原样造，不手抄建表语句：手抄的 SQL 一旦和真实 schema 漂移，
 * 这条测试就会替漂移背书。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneDbMigrationChainTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var databaseName: String? = null

    @After
    fun tearDown() {
        databaseName?.let { name -> context.deleteDatabase(name) }
    }

    @Test
    fun `v5 一路升到当前版本 每一段新增的表都在`() {
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
            assertEquals("整链要走到当前版本", MINI_PHONE_DB_VERSION, db.openHelper.readableDatabase.version)

            val present = tablesOf(db)
            // v6 新增
            assertTrue("v5→v6 的心声表没建出来", "mp_inner_voice" in present)
            // v7 新增
            listOf("mp_memo", "mp_offline_message", "mp_call_session").forEach { table ->
                assertTrue("v6→v7 的 $table 没建出来", table in present)
            }
            // v8 新增（十个模块的落地表）
            V8_TABLES.forEach { table ->
                assertTrue("v7→v8 的 $table 没建出来", table in present)
            }

            runBlocking {
                // 旧数据不丢
                assertEquals(GROUP_THREAD_ID, db.threadDao().getThread(GROUP_THREAD_ID)?.id)
                assertEquals("旧消息还在", 1, db.messageDao().countInThread(GROUP_THREAD_ID))

                // 每一段的新表都真的能写能读（建出来不等于可用：列约束不对时写入才会炸）
                db.innerVoiceDao().insert(
                    io.zer0.muse.miniphone.data.db.entity.MpInnerVoiceEntity(
                        id = "iv-1",
                        threadId = GROUP_THREAD_ID,
                        characterId = "c2",
                        messageId = null,
                        text = "整链心声",
                        createdAt = 9_000,
                    ),
                )
                assertEquals("整链心声", db.innerVoiceDao().observeByThread(GROUP_THREAD_ID).first().single().text)

                val now = 9_000L
                db.memoDao().insert(
                    io.zer0.muse.miniphone.data.db.entity.MpMemoEntity(
                        id = "memo-1",
                        characterId = "",
                        content = "整链备忘录",
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                assertEquals(1, db.memoDao().observeAll().first().size)

                db.callSessionDao().insert(
                    io.zer0.muse.miniphone.data.db.entity.MpCallSessionEntity(
                        id = "call-1",
                        threadId = GROUP_THREAD_ID,
                        characterId = "c2",
                        kind = "voice",
                        startedAt = now,
                        durationMs = 1_000L,
                    ),
                )
                assertEquals(1, db.callSessionDao().observeHistory(10).first().size)
            }
        } finally {
            db.close()
        }
    }

    /** 当前库里实际存在的表名。 */
    private fun tablesOf(db: MiniPhoneDb): Set<String> = db.openHelper.readableDatabase
        .query("SELECT name FROM sqlite_master WHERE type='table'")
        .use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toSet()
        }

    /** 按 5.json 原样造 v5 库，塞一条群 + 一条群消息。 */
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
                    legacy.execSQL(
                        indices.getJSONObject(indexAt).getString("createSql").replace(TABLE_PLACEHOLDER, table),
                    )
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
        const val LEGACY_DB_NAME: String = "miniphone-migration-chain.db"
        const val GROUP_THREAD_ID: String = "t-group"
        const val SCHEMA_ASSET: String = "io.zer0.muse.miniphone.data.db.MiniPhoneDb/5.json"
        const val TABLE_PLACEHOLDER: String = "\${TABLE_NAME}"

        /** v7→v8 一次建出来的十张表；少一张就说明这一段没跑完。 */
        val V8_TABLES: List<String> = listOf(
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
        )
    }
}
