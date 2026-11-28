package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 中枢输入接纳与短回合的行为契约。
 *
 * 核心断言：**后台在跑的时候用户还能说话**。
 * 普通聊天在流式期间拒绝新输入，中枢不能 —— 那正是这个功能存在的理由。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentConversationFacadeTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
    private lateinit var sessions: SessionRepository
    private var clock = 1_000L

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .addCallback(
                object : androidx.room.RoomDatabase.Callback() {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(io.zer0.muse.data.session.MessageFtsDdl.createSql(useFts5 = false))
                    }
                },
            )
            .allowMainThreadQueries()
            .build()
        sessions = SessionRepository(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            database = db,
            context = context,
            messageImageStore = MessageImageStore(File(context.cacheDir, "facade-images-${System.nanoTime()}")),
        )
        repo = AgentBotRepository(
            taskDao = db.botTaskDao(),
            runDao = db.taskRunDao(),
            stepDao = db.taskStepDao(),
            eventDao = db.botEventDao(),
            approvalDao = db.approvalRequestDao(),
            publicationDao = db.publicationOutboxDao(),
            bindingDao = db.botBindingDao(),
            now = { clock },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `input is accepted and wakes the coordinator once`() = runTest {
        val facade = AgentConversationFacade(repo, mergeWindowMs = 0, now = { clock })
        val batch = facade.acceptUserInput("bot-1", "agent-session-1", "帮我把这份稿子的错字改掉")

        assertNotNull(batch)
        assertEquals(1, batch!!.mergedCount)
        assertTrue("每条输入都要有消息 id", batch.messageId.isNotBlank())
        assertTrue("输入版本必须由事件序号生成", batch.requestRevision > 0L)
        val events = repo.pendingEvents("bot-1")
        assertEquals(1, events.size)
        assertEquals(BotEventKind.USER_INPUT_RECEIVED, events.single().kind)
        assertEquals(batch.eventId, events.single().eventId)
    }

    @Test
    fun `input is never rejected while a task is running`() = runTest {
        val task = repo.createTask(
            botId = "bot-1",
            originSessionId = "agent-session-1",
            requestMessageId = "m0",
            goal = "改错字",
            acceptanceCriteria = "只改错字",
        )
        repo.transition(task.taskId, BotTaskStatusMachine.Event.START)
        val facade = AgentConversationFacade(repo, mergeWindowMs = 0, now = { clock })

        val batch = facade.acceptUserInput("bot-1", "agent-session-1", "对了，今晚吃什么？")

        assertNotNull("任务在跑时输入必须被接纳", batch)
        assertEquals(
            "任务不因新输入被取消",
            BotTaskStatus.RUNNING,
            repo.getTask(task.taskId)!!.status,
        )
    }

    @Test
    fun `blank input is not accepted`() = runTest {
        val facade = AgentConversationFacade(repo, mergeWindowMs = 0, now = { clock })
        assertNull(facade.acceptUserInput("bot-1", "agent-session-1", "   "))
        assertTrue(repo.pendingEvents("bot-1").isEmpty())
    }

    @Test
    fun `image only input is accepted`() = runTest {
        val facade = AgentConversationFacade(repo, mergeWindowMs = 0, now = { clock })
        val sessionId = sessions.createAgentSession("bot-1")

        val batch = facade.acceptUserInput(
            botId = "bot-1",
            originSessionId = sessionId,
            text = "  ",
            sessions = sessions,
            imageBase64List = listOf("aGVsbG8="),
        )

        assertNotNull(batch)
        assertEquals(listOf(""), batch?.texts)
        val stored = sessions.getRecentMessages(sessionId, 5).single()
        assertEquals("", stored.content)
        assertEquals(listOf("aGVsbG8="), stored.imageBase64List)
        assertEquals(BotEventKind.USER_INPUT_RECEIVED, repo.pendingEvents("bot-1").single().kind)
    }

    @Test
    fun `missing origin session is refused instead of falling back`() = runTest {
        val facade = AgentConversationFacade(repo, mergeWindowMs = 0, now = { clock })
        val batch = facade.acceptUserInput("bot-1", "", "随便说点什么")
        assertNull("缺归属必须拒绝，不能拿当前会话兜底", batch)
        assertTrue(repo.pendingEvents("bot-1").isEmpty())
    }

    @Test
    fun `burst within merge window coalesces but keeps every line`() = runTest {
        val facade = AgentConversationFacade(repo, mergeWindowMs = 500, now = { clock })
        val a = facade.acceptUserInput("bot-1", "agent-session-1", "帮我看下这个")!!
        clock += 100
        val b = facade.acceptUserInput("bot-1", "agent-session-1", "顺便")!!
        clock += 100
        val c = facade.acceptUserInput("bot-1", "agent-session-1", "算了先别动")!!

        assertEquals("三条连发合成一批", a.eventId, c.eventId)
        assertEquals(3, c.mergedCount)
        assertEquals(
            "合并只影响何时唤醒，不影响用户到底说了什么",
            listOf("帮我看下这个", "顺便", "算了先别动"),
            c.texts,
        )
        assertEquals("每句仍有独立接纳事件", 3, repo.pendingEvents("bot-1").size)
        assertEquals("批次引用最后一条输入", "算了先别动", repo.pendingEvents("bot-1").last().payloadRef)
        assertTrue("每条输入推进版本", c.requestRevision > b.requestRevision && b.requestRevision > a.requestRevision)
    }

    @Test
    fun `burst outside merge window wakes twice`() = runTest {
        val facade = AgentConversationFacade(repo, mergeWindowMs = 500, now = { clock })
        val a = facade.acceptUserInput("bot-1", "agent-session-1", "第一句")!!
        clock += 600
        val b = facade.acceptUserInput("bot-1", "agent-session-1", "第二句")!!

        assertTrue("超出窗口是两批", a.eventId != b.eventId)
        assertEquals(2, repo.pendingEvents("bot-1").size)
    }

    @Test
    fun `immediate command breaks the merge window`() = runTest {
        val facade = AgentConversationFacade(repo, mergeWindowMs = 500, now = { clock })
        facade.acceptUserInput("bot-1", "agent-session-1", "先做这个")

        val ok = facade.acceptImmediateCommand(
            botId = "bot-1",
            originSessionId = "agent-session-1",
            kind = BotEventKind.TASK_CANCELLED,
            dedupeKey = "cancel:t1",
        )
        assertTrue(ok)

        // 命令之后的输入必须开新批，不能被合进命令之前那批。
        clock += 50
        val after = facade.acceptUserInput("bot-1", "agent-session-1", "那换个说法")!!
        assertEquals(1, after.mergedCount)
        assertEquals(3, repo.pendingEvents("bot-1").size)
    }

    @Test
    fun `same message id does not wake twice`() = runTest {
        val facade = AgentConversationFacade(repo, mergeWindowMs = 0, now = { clock })
        facade.acceptUserInput("bot-1", "agent-session-1", "这句话", messageId = "m-1")
        facade.acceptUserInput("bot-1", "agent-session-1", "这句话", messageId = "m-1")
        assertEquals("同一条消息重复投递只唤醒一次", 1, repo.pendingEvents("bot-1").size)
    }

    @Test
    fun `turn consumes handled events and reports backlog`() = runTest {
        val facade = AgentConversationFacade(repo, mergeWindowMs = 0, now = { clock })
        facade.acceptUserInput("bot-1", "agent-session-1", "一")
        facade.acceptUserInput("bot-1", "agent-session-1", "二")

        val inbox = BotInboxProcessor(repo)
        val handled = mutableListOf<String>()
        val result = inbox.runTurn("bot-1") { event ->
            handled += event.eventId
            true
        }

        assertEquals(2, result.consumed)
        assertFalse(result.hasBacklog)
        assertEquals(2, handled.size)
        assertTrue(repo.pendingEvents("bot-1").isEmpty())
    }

    @Test
    fun `unhandled event stays in the queue`() = runTest {
        val facade = AgentConversationFacade(repo, mergeWindowMs = 0, now = { clock })
        facade.acceptUserInput("bot-1", "agent-session-1", "等一下再说")

        val inbox = BotInboxProcessor(repo)
        val result = inbox.runTurn("bot-1") { false }

        assertEquals("处理不了就不能消费", 0, result.consumed)
        assertEquals(1, repo.pendingEvents("bot-1").size)
    }

    @Test
    fun `failing handler does not consume the event`() = runTest {
        val facade = AgentConversationFacade(repo, mergeWindowMs = 0, now = { clock })
        facade.acceptUserInput("bot-1", "agent-session-1", "会失败的那种")

        val inbox = BotInboxProcessor(repo)
        val result = inbox.runTurn("bot-1") { throw IllegalStateException("boom") }

        assertEquals(0, result.consumed)
        assertEquals("异常不能吞掉事件", 1, repo.pendingEvents("bot-1").size)
    }

    @Test
    fun `cancel outranks ordinary input`() {
        val inbox = BotInboxProcessor(repo)
        assertTrue(
            "用户说停下的时候，系统不能还在慢慢读上一条闲聊",
            inbox.priorityOf(BotEventKind.TASK_CANCELLED) <
                inbox.priorityOf(BotEventKind.USER_INPUT_RECEIVED),
        )
        assertTrue(
            inbox.priorityOf(BotEventKind.APPROVAL_REQUIRED) <
                inbox.priorityOf(BotEventKind.TASK_PROGRESSED),
        )
    }

    @Test
    fun `two bots do not block each other`() = runTest {
        val facade = AgentConversationFacade(repo, mergeWindowMs = 0, now = { clock })
        facade.acceptUserInput("bot-1", "session-1", "给一号的话")
        facade.acceptUserInput("bot-2", "session-2", "给二号的话")

        assertEquals(1, repo.pendingEvents("bot-1").size)
        assertEquals(1, repo.pendingEvents("bot-2").size)

        val inbox = BotInboxProcessor(repo)
        val r1 = inbox.runTurn("bot-1") { true }
        assertEquals(1, r1.consumed)
        assertEquals("一号消费完不该动二号", 1, repo.pendingEvents("bot-2").size)
    }

    @Test
    fun `switching bot clears the draft instead of carrying it over`() {
        val ui = AgentBotUiState()
        ui.setComposerBot("bot-1")
        ui.setComposerText("写给一号的草稿")
        assertEquals("写给一号的草稿", ui.composer.value.text)

        ui.setComposerBot("bot-2")
        assertEquals("换 Bot 时草稿不能串", "", ui.composer.value.text)
    }

    @Test
    fun `published message is deduplicated by message id`() {
        val ui = AgentBotUiState()
        val msg = AgentBotUiState.PublishedMessage(
            messageId = "m-1",
            role = AgentBotUiState.Role.BOT,
            text = "我来处理，结果出来告诉你",
            createdAt = 1L,
        )
        ui.appendPublished(msg)
        ui.appendPublished(msg)
        assertEquals("恢复时可能重投已提交的 item", 1, ui.transcript.value.size)
    }

    @Test
    fun `busy state is per domain so task progress does not rebuild composer`() {
        val ui = AgentBotUiState()
        val composerBefore = ui.composer.value

        ui.setTaskBoard(
            AgentBotUiState.TaskBoard(
                tasks = listOf(
                    AgentBotUiState.TaskRow(
                        taskId = "t1",
                        goal = "改错字",
                        status = BotTaskStatus.RUNNING,
                    ),
                ),
            ),
        )

        assertTrue("任务看板变化不该改动输入区", composerBefore == ui.composer.value)
        assertFalse("后台任务独自执行时不该显示正在输入", ui.busy.value.typing)
    }
}
