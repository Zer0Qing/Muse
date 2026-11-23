package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ToolCall
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 端到端可运行性：从"用户交代"到"结果落盘"整条链路。
 *
 * 用假的 [ForegroundCoordinator.ModelCaller] 代替真实模型，其余全部真实组件。
 * 这比逐层单测更能回答一个问题：**这条链路真的能跑通吗**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentBotEndToEndTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
    private lateinit var sessions: SessionRepository
    private lateinit var staging: StagedArtifactStore
    private lateinit var applier: BotResultApplier
    private lateinit var workspace: File
    private lateinit var wsIO: WorkspaceArtifactIO

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build();
        repo = AgentBotRepository(
            taskDao = db.botTaskDao(),
            runDao = db.taskRunDao(),
            stepDao = db.taskStepDao(),
            eventDao = db.botEventDao(),
            approvalDao = db.approvalRequestDao(),
            publicationDao = db.publicationOutboxDao(),
            bindingDao = db.botBindingDao(),
            database = db,
        );
        sessions = SessionRepository(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            database = db,
            context = context,
            messageImageStore = io.zer0.muse.data.session.MessageImageStore(File(context.cacheDir, "img")),
        );
        workspace = File(System.getProperty("java.io.tmpdir"), "e2e-ws-" + System.nanoTime());
        workspace.mkdirs();
        wsIO = WorkspaceArtifactIO(workspace);
        staging = StagedArtifactStore(File(workspace, ".staging"));
        applier = BotResultApplier(
            repository = repo,
            staging = staging,
            reviewer = BotReviewWorker(),
            readTarget = { ref -> wsIO.read(ref) },
            writeTarget = { ref, content -> wsIO.write(ref, content) },
        );
    }

    @After
    fun tearDown() {
        db.close();
        workspace.deleteRecursively();
    }

    /** 假模型：第一次返回"接单 + 派工"，第二次返回纯对话。 */
    private fun modelReturning(vararg completions: ChatCompletion): ForegroundCoordinator.ModelCaller {
        var index = 0
        return ForegroundCoordinator.ModelCaller {
            val result = completions[minOf(index, completions.lastIndex)]
            index++
            result
        };
    }

    @Test
    fun `dispatch from the bot becomes a queued task`() = runTest {
        val sessionId = sessions.createAgentSession("default");
        val coordinator = ForegroundCoordinator(
            modelCaller = modelReturning(
                ChatCompletion(
                    text = "",
                    toolCalls = listOf(
                        ToolCall("c1", ForegroundCoordinator.TOOL_SAY, """{"text":"我来处理"}"""),
                        ToolCall(
                            "c2",
                            ForegroundCoordinator.TOOL_DISPATCH,
                            """{"goal":"把 notes.md 的错字改掉","acceptance":"只改错字","resource_scope":"notes.md"}""",
                        ),
                    ),
                ),
            ),
            publication = PublicationService(db, repo, sessions),
            governor = BotResourceGovernor(),
        );

        val turn = coordinator.runTurn(
            botId = "bot-1",
            originSessionId = sessionId,
            history = emptyList(),
            userText = "帮我把 notes.md 的错字改掉",
            requestRevision = 1L,
        );

        assertNotNull(turn);
        assertEquals("我来处理", turn!!.say);
        assertTrue("模型派了工，回合要反映出来", turn.shouldDispatch);
        assertEquals("notes.md", turn.resourceScope);
    }

    @Test
    fun `bot reply reaches the public message table`() = runTest {
        val sessionId = sessions.createAgentSession("default");
        val publication = PublicationService(db, repo, sessions);
        val coordinator = ForegroundCoordinator(
            modelCaller = modelReturning(
                ChatCompletion(
                    text = "",
                    toolCalls = listOf(
                        ToolCall("c1", ForegroundCoordinator.TOOL_SAY, """{"text":"好的，我看下"}"""),
                    ),
                ),
            ),
            publication = publication,
            governor = BotResourceGovernor(),
        );

        coordinator.runTurn("bot-1", sessionId, emptyList(), "帮我看下", 1L);

        val messages = db.messageDao().getRecentBySession(sessionId, 10);
        assertTrue("主 Bot 的话必须经发布通道落到公共消息", messages.any { it.content == "好的，我看下" });
    }

    @Test
    fun `foreground reply is discarded after a newer input arrives`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        val facade = AgentConversationFacade(repo, mergeWindowMs = 0)
        val accepted = facade.acceptUserInput("bot-1", sessionId, "旧请求")!!
        val coordinator = ForegroundCoordinator(
            modelCaller = ForegroundCoordinator.ModelCaller {
                facade.acceptUserInput("bot-1", sessionId, "新请求")
                ChatCompletion(
                    text = "",
                    toolCalls = listOf(
                        ToolCall("say-old", ForegroundCoordinator.TOOL_SAY, """{"text":"旧回复"}"""),
                    ),
                )
            },
            publication = PublicationService(db, repo, sessions),
            governor = BotResourceGovernor(),
        )

        val turn = coordinator.runTurn(
            identity = AgentTurnIdentity("bot-1", sessionId, accepted.messageId, accepted.requestRevision),
            history = emptyList(),
            userText = "旧请求",
            currentRequestRevision = { repo.latestUserInputSequence("bot-1", sessionId) },
        )

        assertNotNull(turn)
        assertTrue("旧回合应标记为已失效", turn!!.requestSuperseded)
        assertTrue("旧回复不能写入会话", db.messageDao().getRecentBySession(sessionId, 10).isEmpty())
    }

    @Test
    fun `no bot_say means no message and the turn says so`() = runTest {
        val sessionId = sessions.createAgentSession("default");
        val coordinator = ForegroundCoordinator(
            modelCaller = modelReturning(ChatCompletion(text = "我只说了话但没用工具")),
            publication = PublicationService(db, repo, sessions),
            governor = BotResourceGovernor(),
        );

        val turn = coordinator.runTurn("bot-1", sessionId, emptyList(), "在吗", 1L);

        assertNotNull(turn);
        assertTrue("没调 bot_say 时必须明确报错，不能拿正文兜底", turn!!.missingBotSay);
        val messages = db.messageDao().getRecentBySession(sessionId, 10);
        assertFalse(
            "模型正文不得进入公共消息",
            messages.any { it.content == "我只说了话但没用工具" },
        );
    }

    @Test
    fun `staged result is applied to the workspace file`() = runTest {
        wsIO.write("notes.md", "原内容");
        val task = repo.createTask(
            botId = "bot-1",
            originSessionId = "s1",
            requestMessageId = "m1",
            goal = "改错字",
            acceptanceCriteria = "只改错字",
            resourceScope = "notes.md",
        );
        val run = repo.openRun(task.taskId)!!;
        repo.transition(task.taskId, BotTaskStatusMachine.Event.START);
        repo.transition(task.taskId, BotTaskStatusMachine.Event.EXECUTE_DONE);
        repo.closeRun(run.runId, TaskRunStatus.COMPLETED)
        val baseHash = StagedArtifactStore.hashOf("原内容");
        val stagedId = staging.stage(
            StagedArtifactStore.Candidate("notes.md", "改好的内容", baseHash, run.taskRevision),
        );

        val outcome = applier.process(
            task = task,
            run = run,
            payloadRef = "改完了\n" + BotResultApplier.STAGED_MARKER + stagedId + "]",
            expectedBaseHash = baseHash,
            criteria = "只改错字",
        );

        assertTrue("审核通过后必须真的写进工作区", outcome.applied);
        assertEquals("改好的内容", wsIO.read("notes.md"));
    }
}
