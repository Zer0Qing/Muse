package io.zer0.muse.data.agentbot

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentBotRedesignEngineTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        repo = AgentBotRepository(
            taskDao = db.botTaskDao(),
            runDao = db.taskRunDao(),
            stepDao = db.taskStepDao(),
            eventDao = db.botEventDao(),
            approvalDao = db.approvalRequestDao(),
            publicationDao = db.publicationOutboxDao(),
            bindingDao = db.botBindingDao(),
            database = db,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `progress bus tracks live steps, sandbox logs, and live steering queue`() {
        val bus = LiveMissionProgressBus()
        bus.onStepStarted(
            LiveMissionProgressBus.LiveStep(
                stepId = "s1",
                taskId = "t1",
                stepIndex = 0,
                toolName = "execute_python",
                displayLabel = "在沙盒电脑中运行 Python 脚本",
                status = StepExecutionStatus.RUNNING,
            ),
        )
        bus.onStepFinished(
            taskId = "t1",
            stepId = "s1",
            status = StepExecutionStatus.SUCCEEDED,
            summary = "已生成 12 行对比表",
        )
        bus.enqueueSteer("t1", "预算控制在 3000 以内")
        bus.enqueueSteer("t1", "优先直飞")

        assertEquals(1, bus.liveSteps.value["t1"]?.size)
        assertEquals(StepExecutionStatus.SUCCEEDED, bus.liveSteps.value["t1"]?.first()?.status)
        assertEquals(2, bus.pendingSteerCounts.value["t1"])
        assertTrue(bus.sandboxLogs.value.isNotEmpty())

        val drained = bus.drainSteer("t1")
        assertEquals(listOf("预算控制在 3000 以内", "优先直飞"), drained)
        assertNull(bus.pendingSteerCounts.value["t1"])
    }

    @Test
    fun `scope observes active bubble annotations and message cards and cleans up on delete`() = runBlocking {
        val annotationService = BotAnnotationService(db.botAnnotationDao())
        annotationService.annotate(
            botId = "bot-a",
            originSessionId = "sess-a",
            targetMessageId = "m-1",
            kind = AnnotationKind.REACTION,
            assetRef = "👌",
            contentHash = BotAnnotationService.hashOf("帮我装个环境"),
        )
        repo.upsertMessageCard(
            BotMessageCardEntity(
                cardId = "card-1",
                botId = "bot-a",
                originSessionId = "sess-a",
                anchorMessageId = "m-2",
                cardKind = BotCardKind.CHOICE,
                title = "你想怎么继续？",
                payloadJson = """[{"id":"opt_1","label":"换 Linux 方案","prompt":"换 Linux 方案"}]""",
            ),
        )
        repo.upsertRoutine(
            BotRoutineEntity(
                routineId = "r-1",
                botId = "bot-a",
                title = "早安简报",
                goalPrompt = "总结今日待办",
                triggerType = RoutineTriggerType.CRON,
                triggerExpr = "0 8 * * *",
            ),
        )

        val scope = repo.observeScope("bot-a", "sess-a").first()
        assertEquals(1, scope.annotations.size)
        assertEquals("👌", scope.annotations.first().assetRef)
        assertEquals(1, scope.cards.size)
        assertEquals("card-1", scope.cards.first().cardId)

        val cleanup = AgentBotDataCleanup(db)
        cleanup.deleteForSession("sess-a")
        val afterSessionDelete = repo.observeScope("bot-a", "sess-a").first()
        assertTrue(afterSessionDelete.annotations.isEmpty())
        assertTrue(afterSessionDelete.cards.isEmpty())

        cleanup.deleteForAssistant("bot-a")
        assertTrue(db.botRoutineDao().getAll().isEmpty())
    }

    @Test
    fun `teach recorder compiles multi-step demonstration into structured routine draft`() {
        val recorder = AgentTeachRecorder()
        recorder.start(botId = "bot-1", titleDraft = "每日报销整理")
        recorder.recordStep("打开邮箱筛出电子发票", targetApp = "Mail")
        recorder.recordStep("整理金额并写入工作区表格", targetApp = "Workspace")

        val draft = recorder.finish()
        assertNotNull(draft)
        assertEquals("每日报销整理", draft?.title)
        assertTrue(draft?.stepsSummary.orEmpty().contains("打开邮箱筛出电子发票"))
        assertTrue(draft?.goalPrompt.orEmpty().contains("1. （Mail）打开邮箱筛出电子发票"))
        assertTrue(draft?.goalPrompt.orEmpty().contains("2. （Workspace）整理金额并写入工作区表格"))
        assertNull(recorder.finish())
    }
}
