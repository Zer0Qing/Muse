package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.skill.SkillRepository
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

/**
 * 技能与惯例双层解耦的契约：
 * - 一条成功任务可同时沉淀为全局技能与当前队友的惯例；
 * - 重复沉淀不产生重复行；
 * - 同一技能可被不同 botId 的惯例共同引用（跨队友共享）；
 * - 例行运行无异常且无实质变化时只归档、不要求主对话呈现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SkillRoutineDecouplingTest {

    private lateinit var db: MuseDb
    private lateinit var repository: AgentBotRepository
    private lateinit var engine: AgentRoutineEngine
    private var clock = 1_000L

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AgentBotRepository(
            taskDao = db.botTaskDao(),
            runDao = db.taskRunDao(),
            stepDao = db.taskStepDao(),
            eventDao = db.botEventDao(),
            approvalDao = db.approvalRequestDao(),
            publicationDao = db.publicationOutboxDao(),
            bindingDao = db.botBindingDao(),
            database = db,
            now = { clock },
        )
        engine = AgentRoutineEngine(
            repository = repository,
            scheduler = mockk<BotTaskScheduler>(relaxed = true),
            scheduledTaskDao = db.scheduledTaskDao(),
            skillRepository = SkillRepository(db.skillDao()),
            now = { clock },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `save task as skill and routine writes one shared skill and one skill-backed routine`() = runTest {
        val task = newTask()
        val run = startRun(task)
        recordStep(run.runId, 0, "打开签到页", StepSideEffect.READ_ONLY)
        recordStep(run.runId, 1, "点击签到", StepSideEffect.RETRY_SAFE)
        recordStep(run.runId, 2, "读取积分余额", StepSideEffect.READ_ONLY)

        val bundle = engine.saveTaskAsSkillAndRoutine(task, title = "每日签到")

        assertNotNull(bundle)
        val saved = requireNotNull(bundle)
        assertEquals("task_skill_${task.taskId}", saved.skill.id)
        assertEquals("每日签到", saved.skill.name)
        assertEquals("user", saved.skill.category)
        assertTrue(saved.skill.implementationKotlin.startsWith("prompt:"))
        assertTrue(saved.skill.implementationKotlin.contains("点击签到"))

        val storedSkill = db.skillDao().getById(saved.skill.id)
        assertNotNull(storedSkill)
        val storedRoutine = db.botRoutineDao().getById(saved.routine.routineId)
        assertNotNull(storedRoutine)
        assertEquals(saved.skill.id, storedRoutine?.skillId)
        assertEquals(task.taskId, storedRoutine?.sourceTaskId)
        assertEquals("bot-a", storedRoutine?.botId)
        assertTrue(storedRoutine?.isSkillBacked == true)
    }

    @Test
    fun `repeated save is idempotent for both skill and routine`() = runTest {
        val task = newTask()
        val run = startRun(task)
        recordStep(run.runId, 0, "点击签到", StepSideEffect.RETRY_SAFE)

        engine.saveTaskAsSkillAndRoutine(task)
        engine.saveTaskAsSkillAndRoutine(task)

        assertEquals(1, db.skillDao().getAll().size)
        assertEquals(1, db.botRoutineDao().getAll().size)
    }

    @Test
    fun `same skill can back routines from different teammates`() = runTest {
        val task = newTask()
        val run = startRun(task)
        recordStep(run.runId, 0, "点击签到", StepSideEffect.RETRY_SAFE)

        val bundle = requireNotNull(engine.saveTaskAsSkillAndRoutine(task))
        repository.upsertRoutine(
            BotRoutineEntity(
                routineId = "routine-teammate-b",
                botId = "bot-b",
                title = "队友 B 的签到",
                goalPrompt = "执行每日签到",
                skillId = bundle.skill.id,
                sourceTaskId = "task-b",
            ),
        )

        val linked = db.botRoutineDao().listBySkillId(bundle.skill.id)
        assertEquals(2, linked.size)
        assertEquals(setOf("bot-a", "bot-b"), linked.map { it.botId }.toSet())
    }

    @Test
    fun `successful run without substantive change is archived silently`() = runTest {
        val task = newTask()
        val run = startRun(task)
        recordStep(run.runId, 0, "打开签到页", StepSideEffect.READ_ONLY)
        recordStep(run.runId, 1, "检查今日是否已签到", StepSideEffect.READ_ONLY)
        val routine = engine.createOrUpdate(
            botId = "bot-a",
            title = "每日签到检查",
            goalPrompt = "打开签到页确认今日状态",
        )

        val outcome = engine.archiveRoutineRun(routine, task.copy(status = BotTaskStatus.SUCCEEDED))

        assertTrue(outcome.isSilentArchive)
        assertFalse(outcome.shouldNotifyMainConversation)
        assertTrue(outcome.changeSummary.isBlank())
        assertEquals(BotTaskStatus.SUCCEEDED, db.botRoutineDao().getById(routine.routineId)?.lastRunStatus)
        val archived = engine.archivedRuns.value.single()
        assertTrue(archived.silent)
        assertEquals(task.taskId, archived.taskId)
    }

    @Test
    fun `successful run with a write step must reach the main conversation`() = runTest {
        val task = newTask()
        val run = startRun(task)
        recordStep(run.runId, 0, "更新价格表", StepSideEffect.RETRY_SAFE)
        val routine = engine.createOrUpdate(
            botId = "bot-a",
            title = "每日价格检查",
            goalPrompt = "检查价格并更新价格表",
        )

        val outcome = engine.archiveRoutineRun(routine, task.copy(status = BotTaskStatus.SUCCEEDED))

        assertTrue(outcome.shouldNotifyMainConversation)
        assertTrue(outcome.hasSubstantiveChange)
        assertTrue(outcome.changeSummary.contains("更新价格表"))
        assertEquals(BotTaskStatus.SUCCEEDED, db.botRoutineDao().getById(routine.routineId)?.lastRunStatus)
        assertFalse(engine.archivedRuns.value.single().silent)
    }

    @Test
    fun `failed run is never silently archived`() = runTest {
        val task = newTask()
        val routine = engine.createOrUpdate(
            botId = "bot-a",
            title = "每日签到",
            goalPrompt = "执行每日签到",
        )

        val outcome = engine.archiveRoutineRun(routine, task.copy(status = BotTaskStatus.FAILED))

        assertTrue(outcome.hasError)
        assertTrue(outcome.shouldNotifyMainConversation)
        assertEquals(BotTaskStatus.FAILED, db.botRoutineDao().getById(routine.routineId)?.lastRunStatus)
    }

    @Test
    fun `explicit change signal reports even when every recorded step is read only`() = runTest {
        val task = newTask()
        val run = startRun(task)
        recordStep(run.runId, 0, "读取今日日程", StepSideEffect.READ_ONLY)
        val routine = engine.createOrUpdate(
            botId = "bot-a",
            title = "日程检查",
            goalPrompt = "检查今日日程",
        )

        val outcome = engine.archiveRoutineRun(
            routine = routine,
            task = task.copy(status = BotTaskStatus.SUCCEEDED),
            hasSubstantiveChange = true,
            changeSummary = "新增一场下午三点的评审会",
        )

        assertTrue(outcome.shouldNotifyMainConversation)
        assertEquals("新增一场下午三点的评审会", outcome.changeSummary)
    }

    @Test
    fun `manual routine run includes current enabled prompt skill and terminal status is persisted`() = runTest {
        val source = newTask()
        val bundle = requireNotNull(engine.saveTaskAsSkillAndRoutine(source, title = "技能例行"))
        val refreshedSkill = bundle.skill.copy(
            implementationKotlin = io.zer0.muse.tools.SkillImporter.encodePromptText("先检查状态，再执行签到"),
        )
        db.skillDao().upsert(refreshedSkill)

        val queued = requireNotNull(engine.runNow(bundle.routine, "session-a"))

        assertTrue(queued.goal.contains("先检查状态，再执行签到"))
        assertTrue(queued.requestMessageId.startsWith("routine-task:${bundle.routine.routineId}:"))
        repository.transition(queued.taskId, BotTaskStatusMachine.Event.START)
        repository.transition(queued.taskId, BotTaskStatusMachine.Event.MARK_UNKNOWN)
        assertEquals(BotTaskStatus.UNKNOWN, db.botRoutineDao().getById(bundle.routine.routineId)?.lastRunStatus)
    }

    @Test
    fun `scheduled routine enters the same skill backed agent task queue`() = runTest {
        val routine = engine.createOrUpdate(
            botId = "bot-a",
            title = "Cron routine",
            goalPrompt = "检查签到",
            triggerType = RoutineTriggerType.CRON,
            triggerExpr = "0 8 * * *",
            originSessionId = "session-a",
        )
        val skill = io.zer0.muse.data.skill.SkillEntity(
            id = "routine-skill",
            name = "签到技能",
            description = "",
            parametersJson = io.zer0.muse.tools.SkillImporter.EMPTY_PARAMETERS_JSON,
            requiredJson = "[]",
            implementationKotlin = io.zer0.muse.tools.SkillImporter.encodePromptText("先确认签到状态"),
            enabled = true,
            category = "user",
            createdAt = clock,
            updatedAt = clock,
        )
        db.skillDao().upsert(skill)
        val linked = routine.copy(skillId = skill.id)
        repository.upsertRoutine(linked)

        val scheduled = requireNotNull(db.scheduledTaskDao().getById("routine:${routine.routineId}"))
        val queued = engine.runScheduled(routine.routineId, "session-a", scheduled.nextRunAt)
        val retried = engine.runScheduled(routine.routineId, "session-a", scheduled.nextRunAt)

        assertTrue(queued.goal.contains("先确认签到状态"))
        assertEquals(BotTaskStatus.QUEUED, queued.status)
        assertTrue(queued.requestMessageId.startsWith("routine-task:${routine.routineId}:"))
        assertEquals("routine:${routine.routineId}", db.scheduledTaskDao().getById("routine:${routine.routineId}")?.id)
        assertEquals(queued.taskId, retried.taskId)
        assertEquals(1, repository.listTasks("bot-a").count { it.requestMessageId.startsWith("routine-task:") })
        assertEquals(1, db.botRoutineDao().getById(routine.routineId)?.runCount)
        repository.transition(queued.taskId, BotTaskStatusMachine.Event.START)
        repository.transition(queued.taskId, BotTaskStatusMachine.Event.MARK_UNKNOWN)
        val retriedAfterTerminal = engine.runScheduled(routine.routineId, "session-a", scheduled.nextRunAt)
        assertEquals(queued.taskId, retriedAfterTerminal.taskId)
        assertEquals(1, repository.listTasks("bot-a").count { it.requestMessageId.startsWith("routine-task:") })
        assertEquals(BotTaskStatus.UNKNOWN, db.botRoutineDao().getById(routine.routineId)?.lastRunStatus)
    }

    @Test
    fun `missing bound skill is reported and routine is not queued`() = runTest {
        val routine = engine.createOrUpdate(
            botId = "bot-a",
            title = "已失效技能",
            goalPrompt = "执行任务",
            skillId = "deleted-skill",
        )

        assertNull(engine.runNow(routine, "session-a"))

        assertEquals(RoutineRunStatus.SKILL_UNAVAILABLE, db.botRoutineDao().getById(routine.routineId)?.lastRunStatus)
        assertTrue(repository.listTasks("bot-a").isEmpty())
    }

    @Test
    fun `review commit settles the routine terminal status without incrementing run count twice`() = runTest {
        val routine = engine.createOrUpdate(
            botId = "bot-a",
            title = "检查例行",
            goalPrompt = "检查状态",
        )
        val queued = requireNotNull(engine.runNow(routine, "session-a"))
        val run = requireNotNull(repository.claimAndOpenRun(queued.taskId)).second
        assertTrue(repository.finishExecution(queued.taskId, run.runId, run.taskRevision, "review payload"))
        val review = ReviewDecision(
            disposition = ReviewDisposition.ACCEPT,
            publicationAllowed = true,
            finalState = BotTaskStatus.SUCCEEDED,
            userVisibleSummary = "检查完成",
            publicationKey = PublicationService.publicationKeyFor(queued.taskId, run.runId, run.taskRevision),
            taskRevision = run.taskRevision,
        )

        repository.commitReview(
            taskId = queued.taskId,
            runId = run.runId,
            taskRevision = run.taskRevision,
            transition = BotTaskStatusMachine.Event.REVIEW_ACCEPT,
            reviewPayload = review.encode(),
        )

        val stored = requireNotNull(db.botRoutineDao().getById(routine.routineId))
        assertEquals(BotTaskStatus.SUCCEEDED, stored.lastRunStatus)
        assertEquals(1, stored.runCount)
    }

    @Test
    fun `cron routine rejects invalid expression without fallback schedule`() = runTest {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                engine.createOrUpdate(
                    botId = "bot-a",
                    title = "无效定时",
                    goalPrompt = "执行任务",
                    triggerType = RoutineTriggerType.CRON,
                    triggerExpr = "not cron",
                )
            }
        }

        assertTrue(db.botRoutineDao().getAll().isEmpty())
        assertTrue(db.scheduledTaskDao().getAll().isEmpty())
    }

    private suspend fun newTask(): BotTaskEntity = repository.createTask(
        botId = "bot-a",
        originSessionId = "session-a",
        requestMessageId = "msg-1",
        goal = "每天打开签到页领取积分",
        acceptanceCriteria = "签到成功后回报积分余额",
    )

    private suspend fun startRun(task: BotTaskEntity): TaskRunEntity =
        repository.openRun(task.taskId)!! // 新任务的首轮 run 必能创建，仓储仅在任务不存在时返回 null

    private suspend fun recordStep(runId: String, index: Int, label: String, sideEffect: String) {
        val step = repository.recordStep(
            runId = runId,
            stepIndex = index,
            toolName = "tool_$index",
            argumentsHash = "args-$index",
            baseHash = "",
            approvalId = "",
            sideEffect = sideEffect,
            displayLabel = label,
        )
        repository.finishStep(step, StepExecutionStatus.SUCCEEDED, "")
    }
}
