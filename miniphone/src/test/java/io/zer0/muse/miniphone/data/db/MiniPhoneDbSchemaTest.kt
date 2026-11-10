package io.zer0.muse.miniphone.data.db

import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 库结构自检:Room 能按实体定义建表并通过自身 schema 校验。
 *
 * 用内存库打开一次即可触发 Room 的 schema 校验(实体/列/索引与建表语句不一致会直接抛异常);
 * 这里把全部表名和版本号逐个核对一遍,避免"能打开但表少了一张"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneDbSchemaTest {

    private lateinit var db: MiniPhoneDb

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun createsEveryExpectedTable() {
        val tables = tableNames()

        EXPECTED_TABLES.forEach { table ->
            assertTrue("缺表 $table", tables.contains(table))
        }
        assertEquals(EXPECTED_TABLES.toSet(), tables)
    }

    @Test
    fun reportsDeclaredVersion() {
        assertEquals(MINI_PHONE_DB_VERSION, db.openHelper.readableDatabase.version)
    }

    @Test
    fun everyDaoIsReachable() {
        db.profileDao()
        db.threadDao()
        db.threadMemberDao()
        db.messageDao()
        db.momentDao()
        db.momentLikeDao()
        db.momentCommentDao()
        db.diaryDao()
        db.albumDao()
        db.innerVoiceDao()
        db.memoDao()
        db.offlineMessageDao()
        db.callSessionDao()
    }

    private fun tableNames(): Set<String> = db.openHelper.readableDatabase
        .query("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'mp_%'")
        .use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }

    private companion object {
        val EXPECTED_TABLES: List<String> =
            listOf(
                "mp_profile",
                "mp_thread",
                "mp_thread_member",
                "mp_message",
                "mp_moment",
                "mp_moment_like",
                "mp_moment_comment",
                "mp_diary",
                "mp_album",
                "mp_inner_voice",
                "mp_memo",
                "mp_offline_message",
                "mp_call_session",
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
