package io.zer0.muse.miniphone.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.zer0.muse.miniphone.data.db.dao.MpAlbumDao
import io.zer0.muse.miniphone.data.db.dao.MpDiaryDao
import io.zer0.muse.miniphone.data.db.dao.MpMessageDao
import io.zer0.muse.miniphone.data.db.dao.MpMomentCommentDao
import io.zer0.muse.miniphone.data.db.dao.MpMomentDao
import io.zer0.muse.miniphone.data.db.dao.MpMomentLikeDao
import io.zer0.muse.miniphone.data.db.dao.MpProfileDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadMemberDao
import io.zer0.muse.miniphone.data.db.entity.MpAlbumEntity
import io.zer0.muse.miniphone.data.db.entity.MpDiaryEntity
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentCommentEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentLikeEntity
import io.zer0.muse.miniphone.data.db.entity.MpProfileEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadMemberEntity

/**
 * 小手机数据库版本。本模块自带库,与宿主数据库互不相干,版本号独立递增。
 *
 * 2:mp_message 增加发送状态列(status)。
 * 3:mp_thread 的 pinned 补上 SQL 层默认值(0),并把存量取值收敛到 0/1。
 * 4:mp_thread 增加群聊标记(isGroup)与群名(title),新增群成员表(mp_thread_member)。
 * 5:mp_thread 增加 titleCustomized,区分「用户改过的群名」与「自动拼的群名」。
 */
internal const val MINI_PHONE_DB_VERSION: Int = 5

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

    companion object {
        /** 打开(或首次创建)miniphone.db。 */
        fun create(context: Context): MiniPhoneDb = Room.databaseBuilder(context, MiniPhoneDb::class.java, MINI_PHONE_DB_NAME)
            .addMigrations(MINI_PHONE_MIGRATION_1_2, MINI_PHONE_MIGRATION_2_3, MINI_PHONE_MIGRATION_3_4, MINI_PHONE_MIGRATION_4_5)
            .build()
    }
}
