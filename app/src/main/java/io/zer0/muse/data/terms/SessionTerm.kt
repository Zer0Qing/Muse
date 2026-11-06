package io.zer0.muse.data.terms

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.serialization.Serializable

/**
 * v2.7.x(E2): 会话术语表 —— 用户自己世界里的新词与黑话。
 *
 * 为什么单独一张表而不是塞进记忆事实:
 *  事实说的是「用户是谁、做了什么」,会长期沉淀、参与画像与衰减;
 *  术语说的是「这个词在这段对话里指什么」,它高频、局部、且**很可能是猜的**。
 *  混在一张表里,要么术语把事实池灌满,要么事实的衰减把术语一起带走。
 *
 * 作用域默认在会话内([SCOPE_SESSION]),用户可以显式提升为助手级([SCOPE_ASSISTANT])。
 * 提升是**用户动作**,不是系统看到重复就自动搬 —— 自动搬等于把 A 群里的黑话带到 B 群去用,
 * 而那些词往往只在原语境里成立。
 */
@Serializable
@Entity(
    tableName = "session_terms",
    indices = [
        Index(value = ["assistant_id"]),
        Index(value = ["scope", "session_id", "term"], unique = true),
    ],
)
data class SessionTermEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** 术语本身(原文,已去空白与标点)。 */
    @ColumnInfo(name = "term")
    val term: String,

    /** 解释/定义;没有就是"我们只知道它是个新词",注入时必须标不确定。 */
    @ColumnInfo(name = "meaning")
    val meaning: String? = null,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "assistant_id")
    val assistantId: String,

    /** [SCOPE_SESSION] 或 [SCOPE_ASSISTANT]。 */
    @ColumnInfo(name = "scope", defaultValue = "session")
    val scope: String = SCOPE_SESSION,

    /** 出现过几次。达到阈值才值得问用户。 */
    @ColumnInfo(name = "occurrences", defaultValue = "1")
    val occurrences: Int = 1,

    /** 用户确认过=会进上下文;未确认只是候选。 */
    @ColumnInfo(name = "confirmed", defaultValue = "0")
    val confirmed: Boolean = false,

    @ColumnInfo(name = "first_seen_at")
    val firstSeenAt: Long,

    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long,
) {
    companion object {
        const val SCOPE_SESSION = "session"
        const val SCOPE_ASSISTANT = "assistant"
    }
}

/**
 * 术语表的数据访问。
 *
 * 一律先查后写而不是 REPLACE:同一 `(scope, session, term)` 的行要**累加次数**,
 * REPLACE 会把已有的 occurrences 与 confirmed 一起抹掉 —— 那意味着
 * "用户确认过的词被下一次复现打回候选",是比丢一条记录更坏的行为。
 */
@Dao
interface SessionTermDao {

    @Query(
        """
        SELECT * FROM session_terms
        WHERE scope = :scope AND (session_id = :scopeKey OR :scopeKey IS NULL)
        ORDER BY last_seen_at DESC LIMIT :limit
        """,
    )
    suspend fun listForScope(scope: String, scopeKey: String?, limit: Int = 100): List<SessionTermEntity>

    @Query("SELECT * FROM session_terms WHERE assistant_id = :assistantId ORDER BY last_seen_at DESC LIMIT :limit")
    suspend fun listByAssistant(assistantId: String, limit: Int = 200): List<SessionTermEntity>

    /** 注入用的选择:已确认优先,其次高频的候选。 */
    @Query(
        """
        SELECT * FROM session_terms
        WHERE (scope = 'session' AND session_id = :sessionId)
           OR (scope = 'assistant' AND assistant_id = :assistantId)
        ORDER BY confirmed DESC, occurrences DESC, last_seen_at DESC
        LIMIT :limit
        """,
    )
    suspend fun pickForContext(sessionId: String, assistantId: String, limit: Int): List<SessionTermEntity>

    @Query(
        """
        SELECT * FROM session_terms
        WHERE scope = :scope AND session_id = :sessionId AND term = :term LIMIT 1
        """,
    )
    suspend fun find(scope: String, sessionId: String, term: String): SessionTermEntity?

    /** 助手级同词条去重要用它:助手级的身份是 (assistant_id, term),与来自哪个会话无关。 */
    @Query(
        """
        SELECT * FROM session_terms
        WHERE scope = 'assistant' AND assistant_id = :assistantId AND term = :term LIMIT 1
        """,
    )
    suspend fun findAssistantScoped(assistantId: String, term: String): SessionTermEntity?

    @androidx.room.Insert
    suspend fun insert(entity: SessionTermEntity): Long

    @androidx.room.Update
    suspend fun update(entity: SessionTermEntity)

    @androidx.room.Delete
    suspend fun delete(entity: SessionTermEntity)

    /** 备份导出用:按 id 升序全量取,保证两次导出的字节可比。 */
    @Query("SELECT * FROM session_terms ORDER BY id ASC")
    suspend fun getAll(): List<SessionTermEntity>

    @Upsert
    suspend fun upsert(entity: SessionTermEntity)

    /** 备份恢复前的清空阶段用。 */
    @Query("DELETE FROM session_terms")
    suspend fun deleteAll()

    @Query("SELECT * FROM session_terms WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SessionTermEntity?

    @Query("DELETE FROM session_terms WHERE session_id = :sessionId")
    suspend fun deleteForSession(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM session_terms WHERE confirmed = 0 AND occurrences >= :threshold")
    suspend fun countAwaitingConfirmation(threshold: Int): Int
}
