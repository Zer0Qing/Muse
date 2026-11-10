package io.zer0.muse.miniphone.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.zer0.muse.miniphone.data.db.dao.MpAlbumDao
import io.zer0.muse.miniphone.data.db.dao.MpCallSessionDao
import io.zer0.muse.miniphone.data.db.dao.MpCheckupCaseDao
import io.zer0.muse.miniphone.data.db.dao.MpCheckupEvidenceDao
import io.zer0.muse.miniphone.data.db.dao.MpDiaryDao
import io.zer0.muse.miniphone.data.db.dao.MpForumCommentDao
import io.zer0.muse.miniphone.data.db.dao.MpForumPostDao
import io.zer0.muse.miniphone.data.db.dao.MpHolidayEventDao
import io.zer0.muse.miniphone.data.db.dao.MpInnerVoiceDao
import io.zer0.muse.miniphone.data.db.dao.MpMemoDao
import io.zer0.muse.miniphone.data.db.dao.MpMessageDao
import io.zer0.muse.miniphone.data.db.dao.MpMomentCommentDao
import io.zer0.muse.miniphone.data.db.dao.MpMomentDao
import io.zer0.muse.miniphone.data.db.dao.MpMomentLikeDao
import io.zer0.muse.miniphone.data.db.dao.MpOfflineMessageDao
import io.zer0.muse.miniphone.data.db.dao.MpProfileDao
import io.zer0.muse.miniphone.data.db.dao.MpRedPacketDao
import io.zer0.muse.miniphone.data.db.dao.MpRelationEdgeDao
import io.zer0.muse.miniphone.data.db.dao.MpRingtoneDao
import io.zer0.muse.miniphone.data.db.dao.MpRoamNpcDao
import io.zer0.muse.miniphone.data.db.dao.MpRoamStateDao
import io.zer0.muse.miniphone.data.db.dao.MpRpEndingDao
import io.zer0.muse.miniphone.data.db.dao.MpRpSaveDao
import io.zer0.muse.miniphone.data.db.dao.MpRpScriptDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadMemberDao
import io.zer0.muse.miniphone.data.db.dao.MpWardrobeItemDao
import io.zer0.muse.miniphone.data.db.entity.MpAlbumEntity
import io.zer0.muse.miniphone.data.db.entity.MpCallSessionEntity
import io.zer0.muse.miniphone.data.db.entity.MpCheckupCaseEntity
import io.zer0.muse.miniphone.data.db.entity.MpCheckupEvidenceEntity
import io.zer0.muse.miniphone.data.db.entity.MpDiaryEntity
import io.zer0.muse.miniphone.data.db.entity.MpForumCommentEntity
import io.zer0.muse.miniphone.data.db.entity.MpForumPostEntity
import io.zer0.muse.miniphone.data.db.entity.MpHolidayEventEntity
import io.zer0.muse.miniphone.data.db.entity.MpInnerVoiceEntity
import io.zer0.muse.miniphone.data.db.entity.MpMemoEntity
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentCommentEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentLikeEntity
import io.zer0.muse.miniphone.data.db.entity.MpOfflineMessageEntity
import io.zer0.muse.miniphone.data.db.entity.MpProfileEntity
import io.zer0.muse.miniphone.data.db.entity.MpRedPacketEntity
import io.zer0.muse.miniphone.data.db.entity.MpRelationEdgeEntity
import io.zer0.muse.miniphone.data.db.entity.MpRingtoneEntity
import io.zer0.muse.miniphone.data.db.entity.MpRoamNpcEntity
import io.zer0.muse.miniphone.data.db.entity.MpRoamStateEntity
import io.zer0.muse.miniphone.data.db.entity.MpRpEndingEntity
import io.zer0.muse.miniphone.data.db.entity.MpRpSaveEntity
import io.zer0.muse.miniphone.data.db.entity.MpRpScriptEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadMemberEntity
import io.zer0.muse.miniphone.data.db.entity.MpWardrobeItemEntity

/**
 * 小手机数据库版本。本模块自带库,与宿主数据库互不相干,版本号独立递增。
 *
 * 2:mp_message 增加发送状态列(status)。
 * 3:mp_thread 的 pinned 补上 SQL 层默认值(0),并把存量取值收敛到 0/1。
 * 4:mp_thread 增加群聊标记(isGroup)与群名(title),新增群成员表(mp_thread_member)。
 * 5:mp_thread 增加 titleCustomized,区分「用户改过的群名」与「自动拼的群名」。
 * 6:新增心声表 mp_inner_voice（内心独白,与主线消息分离）。
 * 7:新增备忘录 mp_memo、待应答队列 mp_offline_message、通话记录 mp_call_session。
 * 8:新增查岗案卷与证据、论坛发帖与评论、关系边、漫游位置与路人、跑团剧本/存档/结局、
 *   衣橱、群红包、节日、铃声。表情包与世界书按计划与主应用互通,这里不建重复表。
 */
internal const val MINI_PHONE_DB_VERSION: Int = 8

/**
 * 1 → 2:给私信表补发送状态。
 *
 * 历史行没有发送状态,那时候也还没有「发送中/失败」这回事,所以默认值取 0(已发出),
 * 升级完老会话的观感与升级前一致。表里已有数据不能重建表,只能就地加列。
 */
internal val MINI_PHONE_MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE mp_message ADD COLUMN status INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * 2 → 3:把置顶标记的默认值写进 SQL。
 *
 * 置顶列从 v1 起就在表里(实体一直带 `pinned`),所以这里不能 ADD COLUMN —— 老库上再加减列
 * 会撞 `duplicate column name`。缺的只是「列有默认值」这件事:没有 DEFAULT 时,任何一条
 * 不写 pinned 的 INSERT(手工 SQL、旧代码路径)都会撞 NOT NULL。SQLite 不能就地改列默认值,
 * 因此重建一次这张只有几列的表:拷贝时把非 0 的旧值收敛成 1,保证列里只出现 0/1。
 */
internal val MINI_PHONE_MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS mp_thread_new (" +
                "id TEXT NOT NULL, characterId TEXT NOT NULL, pinned INTEGER NOT NULL DEFAULT 0, " +
                "unreadCount INTEGER NOT NULL, lastMessagePreview TEXT, lastMessageAt INTEGER NOT NULL, " +
                "muted INTEGER NOT NULL, PRIMARY KEY(id))",
        )
        db.execSQL(
            "INSERT INTO mp_thread_new " +
                "(id, characterId, pinned, unreadCount, lastMessagePreview, lastMessageAt, muted) " +
                "SELECT id, characterId, CASE WHEN pinned <> 0 THEN 1 ELSE 0 END, unreadCount, " +
                "lastMessagePreview, lastMessageAt, muted FROM mp_thread",
        )
        db.execSQL("DROP TABLE mp_thread")
        db.execSQL("ALTER TABLE mp_thread_new RENAME TO mp_thread")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_thread_characterId ON mp_thread (characterId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_thread_lastMessageAt ON mp_thread (lastMessageAt)")
    }
}

/**
 * 3 → 4:会话表分家出「群聊」,成员关系另起一张表。
 *
 * 两列都带默认值,存量行升级后自然落在「私信」:isGroup=0、title=''。这里只加列,不重建表 ——
 * 两张旧索引与行 id 都原样留着。
 *
 * 成员关系不能挤进会话行的某一列(群人数没有上限,拆成列就等于给人数设上限),所以另起
 * mp_thread_member,主键(会话, 角色)保证同一个人进同一个群只有一行。表里只存角色,
 * 本机用户不在表内。
 */
internal val MINI_PHONE_MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE mp_thread ADD COLUMN isGroup INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE mp_thread ADD COLUMN title TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS mp_thread_member (threadId TEXT NOT NULL, characterId TEXT NOT NULL, " +
                "joinedAt INTEGER NOT NULL, PRIMARY KEY(threadId, characterId))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_thread_member_threadId ON mp_thread_member (threadId)")
    }
}

/**
 * 4 → 5:记住群名是「自动拼的」还是「用户改过的」。
 *
 * 这确实是新列,就地 ALTER 加列即可,存量行全都落在 0(没改过)。
 * 旧群里没有这个信息,只能当没改过 —— 那些群名本来就是建群时自动拼的,
 * 与真实情况一致;用户此后一旦改名就置 1。
 */
internal val MINI_PHONE_MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE mp_thread ADD COLUMN titleCustomized INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * 5 → 6:新增心声表。纯新增表,不动旧表;老库升级后心声为空,与升级前观感一致。
 */
internal val MINI_PHONE_MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS mp_inner_voice (" +
                "id TEXT NOT NULL, threadId TEXT NOT NULL, characterId TEXT NOT NULL, " +
                "messageId TEXT, text TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_inner_voice_threadId ON mp_inner_voice (threadId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_inner_voice_createdAt ON mp_inner_voice (createdAt)")
    }
}

/**
 * 6 → 7: 备忘录、待应答队列、通话记录三张表一次建齐。
 *
 * 纯新增表,不动旧表;老库升级后三张表都为空,与升级前观感一致。
 * 合并成一条迁移是因为 v5→v6 尚未在任何用户设备上跑过(v2.6.25 不含 v6 代码),
 * 不宜再叠加第二条同样未经验证的迁移。
 */
internal val MINI_PHONE_MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS mp_memo (" +
                "id TEXT NOT NULL, characterId TEXT NOT NULL, content TEXT NOT NULL, " +
                "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_memo_characterId ON mp_memo (characterId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_memo_updatedAt ON mp_memo (updatedAt)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS mp_offline_message (" +
                "id TEXT NOT NULL, threadId TEXT NOT NULL, characterId TEXT NOT NULL, " +
                "content TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_offline_message_threadId ON mp_offline_message (threadId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_offline_message_createdAt ON mp_offline_message (createdAt)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS mp_call_session (" +
                "id TEXT NOT NULL, threadId TEXT NOT NULL, characterId TEXT NOT NULL, " +
                "kind TEXT NOT NULL, startedAt INTEGER NOT NULL, durationMs INTEGER NOT NULL, PRIMARY KEY(id))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_call_session_threadId ON mp_call_session (threadId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_call_session_startedAt ON mp_call_session (startedAt)")
    }
}

/**
 * 7 → 8: R4–R9 的表组一次建齐（查岗、论坛、关系网、漫游、跑团、衣橱、群红包、节日、铃声）。
 *
 * 纯新增表,不动旧表;老库升级后新表都为空,与升级前观感一致。
 * 合并成一条迁移是刻意的: v5→v6、v6→v7 都还没在任何用户设备上跑过,
 * 再按模块拆成十条未经验证的迁移会让首次真实升级的失败面成倍扩大。
 * 表结构由同一份规格生成实体与本语句,列名/类型/默认值不会两边漂移。
 */
internal val MINI_PHONE_MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        createCheckupTables(db)
        createForumTables(db)
        createRelationTables(db)
        createRoamTables(db)
        createRoleplayTables(db)
        createWardrobeTables(db)
        createRedPacketTables(db)
        createHolidayTables(db)
        createRingtoneTables(db)
    }
}

/** R4–R9 迁移分组：checkup 模块的表。 */
private fun createCheckupTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS mp_checkup_case (id TEXT NOT NULL, " +
            "characterId TEXT NOT NULL, " +
            "initiatorId TEXT NOT NULL DEFAULT '', " +
            "direction TEXT NOT NULL, " +
            "intensity TEXT NOT NULL, " +
            "status TEXT NOT NULL, " +
            "summary TEXT NOT NULL DEFAULT '', " +
            "createdAt INTEGER NOT NULL, " +
            "finishedAt INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY(id))",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_checkup_case_characterId ON mp_checkup_case (characterId)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_checkup_case_createdAt ON mp_checkup_case (createdAt)")
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS mp_checkup_evidence (id TEXT NOT NULL, " +
            "caseId TEXT NOT NULL, " +
            "kind TEXT NOT NULL, " +
            "excerpt TEXT NOT NULL, " +
            "refId TEXT NOT NULL DEFAULT '', " +
            "verdict TEXT NOT NULL DEFAULT '', " +
            "createdAt INTEGER NOT NULL, " +
            "PRIMARY KEY(id))",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_checkup_evidence_caseId ON mp_checkup_evidence (caseId)")
}

/** R4–R9 迁移分组：forum 模块的表。 */
private fun createForumTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS mp_forum_post (id TEXT NOT NULL, " +
            "authorId TEXT NOT NULL, " +
            "authorName TEXT NOT NULL, " +
            "content TEXT NOT NULL, " +
            "likeIdsJson TEXT NOT NULL DEFAULT '[]', " +
            "createdAt INTEGER NOT NULL, " +
            "PRIMARY KEY(id))",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_forum_post_createdAt ON mp_forum_post (createdAt)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_forum_post_authorId ON mp_forum_post (authorId)")
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS mp_forum_comment (id TEXT NOT NULL, " +
            "postId TEXT NOT NULL, " +
            "authorId TEXT NOT NULL, " +
            "authorName TEXT NOT NULL, " +
            "content TEXT NOT NULL, " +
            "createdAt INTEGER NOT NULL, " +
            "PRIMARY KEY(id))",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_forum_comment_postId ON mp_forum_comment (postId)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_forum_comment_createdAt ON mp_forum_comment (createdAt)")
}

/** R4–R9 迁移分组：relation 模块的表。 */
private fun createRelationTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS mp_relation_edge (id TEXT NOT NULL, " +
            "fromId TEXT NOT NULL, " +
            "toId TEXT NOT NULL, " +
            "label TEXT NOT NULL, " +
            "note TEXT NOT NULL DEFAULT '', " +
            "updatedAt INTEGER NOT NULL, " +
            "PRIMARY KEY(id))",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_relation_edge_fromId ON mp_relation_edge (fromId)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_relation_edge_toId ON mp_relation_edge (toId)")
}

/** R4–R9 迁移分组：roam 模块的表。 */
private fun createRoamTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS mp_roam_state (characterId TEXT NOT NULL, " +
            "locationId TEXT NOT NULL, " +
            "locationName TEXT NOT NULL, " +
            "note TEXT NOT NULL DEFAULT '', " +
            "updatedAt INTEGER NOT NULL, " +
            "PRIMARY KEY(characterId))",
    )
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS mp_roam_npc (id TEXT NOT NULL, " +
            "locationId TEXT NOT NULL, " +
            "name TEXT NOT NULL, " +
            "persona TEXT NOT NULL DEFAULT '', " +
            "characterId TEXT NOT NULL DEFAULT '', " +
            "metAt INTEGER NOT NULL, " +
            "PRIMARY KEY(id))",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_roam_npc_locationId ON mp_roam_npc (locationId)")
}

/** R4–R9 迁移分组：roleplay 模块的表。 */
private fun createRoleplayTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS mp_rp_script (id TEXT NOT NULL, " +
            "title TEXT NOT NULL, " +
            "body TEXT NOT NULL, " +
            "createdAt INTEGER NOT NULL, " +
            "updatedAt INTEGER NOT NULL, " +
            "PRIMARY KEY(id))",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_rp_script_updatedAt ON mp_rp_script (updatedAt)")
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS mp_rp_save (id TEXT NOT NULL, " +
            "scriptId TEXT NOT NULL, " +
            "title TEXT NOT NULL, " +
            "stateJson TEXT NOT NULL, " +
            "turn INTEGER NOT NULL DEFAULT 0, " +
            "updatedAt INTEGER NOT NULL, " +
            "PRIMARY KEY(id))",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_rp_save_scriptId ON mp_rp_save (scriptId)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_rp_save_updatedAt ON mp_rp_save (updatedAt)")
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS mp_rp_ending (id TEXT NOT NULL, " +
            "scriptId TEXT NOT NULL, " +
            "saveId TEXT NOT NULL, " +
            "title TEXT NOT NULL, " +
            "text TEXT NOT NULL, " +
            "createdAt INTEGER NOT NULL, " +
            "PRIMARY KEY(id))",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_rp_ending_scriptId ON mp_rp_ending (scriptId)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_rp_ending_saveId ON mp_rp_ending (saveId)")
}

/** R4–R9 迁移分组：wardrobe 模块的表。 */
private fun createWardrobeTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS mp_wardrobe_item (id TEXT NOT NULL, " +
            "characterId TEXT NOT NULL, " +
            "name TEXT NOT NULL, " +
            "kind TEXT NOT NULL, " +
            "uri TEXT NOT NULL, " +
            "equipped INTEGER NOT NULL DEFAULT 0, " +
            "updatedAt INTEGER NOT NULL, " +
            "PRIMARY KEY(id))",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_wardrobe_item_characterId ON mp_wardrobe_item (characterId)")
}

/** R4–R9 迁移分组：redPacket 模块的表。 */
private fun createRedPacketTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS mp_red_packet (id TEXT NOT NULL, " +
            "threadId TEXT NOT NULL, " +
            "senderId TEXT NOT NULL, " +
            "amount TEXT NOT NULL, " +
            "note TEXT NOT NULL DEFAULT '', " +
            "state TEXT NOT NULL, " +
            "claimedByJson TEXT NOT NULL DEFAULT '[]', " +
            "createdAt INTEGER NOT NULL, " +
            "PRIMARY KEY(id))",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_red_packet_threadId ON mp_red_packet (threadId)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_red_packet_createdAt ON mp_red_packet (createdAt)")
}

/** R4–R9 迁移分组：holiday 模块的表。 */
private fun createHolidayTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS mp_holiday_event (id TEXT NOT NULL, " +
            "dateKey TEXT NOT NULL, " +
            "name TEXT NOT NULL, " +
            "note TEXT NOT NULL DEFAULT '', " +
            "year INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY(id))",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_holiday_event_dateKey ON mp_holiday_event (dateKey)")
}

/** R4–R9 迁移分组：ringtone 模块的表。 */
private fun createRingtoneTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS mp_ringtone (id TEXT NOT NULL, " +
            "characterId TEXT NOT NULL DEFAULT '', " +
            "name TEXT NOT NULL, " +
            "uri TEXT NOT NULL, " +
            "kind TEXT NOT NULL, " +
            "createdAt INTEGER NOT NULL, " +
            "PRIMARY KEY(id))",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mp_ringtone_characterId ON mp_ringtone (characterId)")
}

/** 独立库文件名。 */
internal const val MINI_PHONE_DB_NAME: String = "miniphone.db"

/**
 * 小手机自己的 Room 数据库。
 *
 * 角色本体、AI 能力、TTS 都不在这里 —— 那些由宿主通过 [io.zer0.muse.miniphone.api.MiniPhoneHost]
 * 注入;这里只存「角色的生活面」在本模块内的持久化状态:
 * 资料、会话(私信与群聊)、群成员、消息、动态与互动、日记、相册。
 */
@Database(
    entities = [
        MpProfileEntity::class,
        MpThreadEntity::class,
        MpThreadMemberEntity::class,
        MpMessageEntity::class,
        MpMomentEntity::class,
        MpMomentLikeEntity::class,
        MpMomentCommentEntity::class,
        MpDiaryEntity::class,
        MpAlbumEntity::class,
        MpInnerVoiceEntity::class,
        MpMemoEntity::class,
        MpOfflineMessageEntity::class,
        MpCallSessionEntity::class,
        MpCheckupCaseEntity::class,
        MpCheckupEvidenceEntity::class,
        MpForumPostEntity::class,
        MpForumCommentEntity::class,
        MpRelationEdgeEntity::class,
        MpRoamStateEntity::class,
        MpRoamNpcEntity::class,
        MpRpScriptEntity::class,
        MpRpSaveEntity::class,
        MpRpEndingEntity::class,
        MpWardrobeItemEntity::class,
        MpRedPacketEntity::class,
        MpHolidayEventEntity::class,
        MpRingtoneEntity::class,
    ],
    version = MINI_PHONE_DB_VERSION,
    exportSchema = true,
)
@TypeConverters(MpConverters::class)
abstract class MiniPhoneDb : RoomDatabase() {
    abstract fun profileDao(): MpProfileDao

    abstract fun threadDao(): MpThreadDao

    abstract fun threadMemberDao(): MpThreadMemberDao

    abstract fun messageDao(): MpMessageDao

    abstract fun momentDao(): MpMomentDao

    abstract fun momentLikeDao(): MpMomentLikeDao

    abstract fun momentCommentDao(): MpMomentCommentDao

    abstract fun diaryDao(): MpDiaryDao

    abstract fun albumDao(): MpAlbumDao

    abstract fun innerVoiceDao(): MpInnerVoiceDao

    abstract fun memoDao(): MpMemoDao

    abstract fun offlineMessageDao(): MpOfflineMessageDao

    abstract fun callSessionDao(): MpCallSessionDao

    abstract fun checkupCaseDao(): MpCheckupCaseDao

    abstract fun checkupEvidenceDao(): MpCheckupEvidenceDao

    abstract fun forumPostDao(): MpForumPostDao

    abstract fun forumCommentDao(): MpForumCommentDao

    abstract fun relationEdgeDao(): MpRelationEdgeDao

    abstract fun roamStateDao(): MpRoamStateDao

    abstract fun roamNpcDao(): MpRoamNpcDao

    abstract fun rpScriptDao(): MpRpScriptDao

    abstract fun rpSaveDao(): MpRpSaveDao

    abstract fun rpEndingDao(): MpRpEndingDao

    abstract fun wardrobeItemDao(): MpWardrobeItemDao

    abstract fun redPacketDao(): MpRedPacketDao

    abstract fun holidayEventDao(): MpHolidayEventDao

    abstract fun ringtoneDao(): MpRingtoneDao

    companion object {
        /** 打开(或首次创建)miniphone.db。 */
        fun create(context: Context): MiniPhoneDb = Room.databaseBuilder(context, MiniPhoneDb::class.java, MINI_PHONE_DB_NAME)
            .addMigrations(
                MINI_PHONE_MIGRATION_1_2,
                MINI_PHONE_MIGRATION_2_3,
                MINI_PHONE_MIGRATION_3_4,
                MINI_PHONE_MIGRATION_4_5,
                MINI_PHONE_MIGRATION_5_6,
                MINI_PHONE_MIGRATION_6_7,
                MINI_PHONE_MIGRATION_7_8,
            )
            .build()
    }
}
