package io.zer0.muse.data.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.session.TurnPhase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * A9: 启动 sweep 收敛悬挂 turn 的行为。
 *
 * 钉住三件事:
 *  1. 只收敛 `finishedAt IS NULL` 且 `startedAt` 早于窗口的行,落终态并盖完成时间;
 *  2. 窗口内的行与已完成的行一律不动;
 *  3. 重复执行结果一致 —— 第二次返回 0 行且不改动任何数据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionRepositoryStaleTurnSweepTest {

    private lateinit var context: Context
    private lateinit var database: MuseDb
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SessionRepository(
            sessionDao = database.sessionDao(),
            messageDao = database.messageDao(),
            database = database,
            context = context,
            messageImageStore = MessageImageStore(File(context.cacheDir, "stale-turn-${UUID.randomUUID()}")),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private val grace = SessionRepository.STALE_TURN_GRACE_MS

    private suspend fun seedSession(id: String) {
        database.sessionDao().insert(
            SessionEntity(id = id, title = id, createdAt = 1_000L, updatedAt = 1_000L),
        )
    }

    /** 插入一条 turn;[finishedAt] 为 null 即“悬挂行”(生产里由进程被杀留下)。 */
    private suspend fun seedTurn(
        turnId: String,
        sessionId: String,
        startedAt: Long,
        phase: String = "STREAMING",
        finishedAt: Long? = null,
    ) {
        database.conversationTurnDao().upsert(
            ConversationTurnEntity(
                turnId = turnId,
                sessionId = sessionId,
                inputUserMessageId = "$turnId-user",
                assistantMessageId = "$turnId-assistant",
                phase = phase,
                startedAt = startedAt,
                finishedAt = finishedAt,
                updatedAt = finishedAt ?: startedAt,
            ),
        )
    }

    private suspend fun turn(turnId: String): ConversationTurnEntity? =
        database.conversationTurnDao().getById(turnId)

    @Test
    fun `sweep closes stale open turns with a terminal phase and finishedAt`() = runBlocking {
        val now = 10_000_000L
        seedSession("session-a")
        seedSession("session-b")
        seedTurn("stale", "session-a", startedAt = now - grace - 60_000L)
        seedTurn("stale-2", "session-b", startedAt = now - grace - 1L)
        seedTurn("fresh", "session-a", startedAt = now - 60_000L)
        seedTurn("boundary", "session-a", startedAt = now - grace)
        seedTurn("done", "session-a", startedAt = now - grace - 60_000L, phase = "COMPLETED", finishedAt = now - 500L)

        assertEquals(2, repository.closeStaleOpenTurns(now))

        val stale = turn("stale")!!
        assertEquals(TurnPhase.FAILED.name, stale.phase)
        assertEquals(now, stale.finishedAt)
        assertEquals(now, stale.updatedAt)
        // 跨会话一次性收敛,不需要按 sessionId 逐条处理
        assertEquals(TurnPhase.FAILED.name, turn("stale-2")!!.phase)
        assertEquals(now, turn("stale-2")!!.finishedAt)

        // 窗口内(含刚好落在窗口边界)的悬挂行不动
        assertEquals("STREAMING", turn("fresh")!!.phase)
        assertNull(turn("fresh")!!.finishedAt)
        assertEquals("STREAMING", turn("boundary")!!.phase)
        assertNull(turn("boundary")!!.finishedAt)
        // 已完成行不被回退 phase 或覆盖完成时间
        val done = turn("done")!!
        assertEquals("COMPLETED", done.phase)
        assertEquals(now - 500L, done.finishedAt)
    }

    @Test
    fun `sweep is idempotent`() = runBlocking {
        val now = 10_000_000L
        seedSession("session-a")
        seedTurn("stale", "session-a", startedAt = now - grace - 60_000L)
        seedTurn("fresh", "session-a", startedAt = now - 1_000L)

        assertEquals(1, repository.closeStaleOpenTurns(now))
        val firstPass = listOf(turn("stale"), turn("fresh"))

        assertEquals(0, repository.closeStaleOpenTurns(now))
        assertEquals(0, repository.closeStaleOpenTurns(now))
        assertEquals(firstPass, listOf(turn("stale"), turn("fresh")))
    }

    @Test
    fun `a turn already swept is never swept again even when time advances`() = runBlocking {
        val now = 10_000_000L
        seedSession("session-a")
        seedTurn("stale", "session-a", startedAt = now - grace - 60_000L)

        assertEquals(1, repository.closeStaleOpenTurns(now))
        // 时钟继续走:同一行已带 finishedAt,后续启动的 sweep 不该再命中它
        assertEquals(0, repository.closeStaleOpenTurns(now + 2 * grace))
        assertEquals(TurnPhase.FAILED.name, turn("stale")!!.phase)
        assertEquals(now, turn("stale")!!.finishedAt)

        // 窗口内的行只有在跨过窗口之后才被收敛
        seedTurn("later", "session-a", startedAt = now - 60_000L)
        assertEquals(1, repository.closeStaleOpenTurns(now + grace))
        assertEquals(TurnPhase.FAILED.name, turn("later")!!.phase)
        assertEquals(now + grace, turn("later")!!.finishedAt)
        assertEquals(0, repository.closeStaleOpenTurns(now + grace))
    }
}
