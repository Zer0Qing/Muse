package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReviewCommitRecoveryTest {

    private lateinit var db: MuseDb
    private lateinit var repository: AgentBotRepository
    private lateinit var staging: StagedArtifactStore
    private lateinit var stagingDir: File

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
        )
        stagingDir = File(System.getProperty("java.io.tmpdir"), "review-commit-${System.nanoTime()}")
        staging = StagedArtifactStore(stagingDir)
    }

    @After
    fun tearDown() {
        db.close()
        stagingDir.deleteRecursively()
    }

    private suspend fun completedExecution(): Pair<BotTaskEntity, TaskRunEntity> {
        val task = repository.createTask(
            botId = "assistant-a",
            originSessionId = "session-a",
            requestMessageId = "message-a",
            goal = "Update notes",
            acceptanceCriteria = "Target content is updated",
            resourceScope = "notes.md",
        )
        val run = repository.openRun(task.taskId) ?: error("run must open")
        repository.transition(task.taskId, BotTaskStatusMachine.Event.START)
        assertTrue(repository.finishExecution(task.taskId, run.runId, run.taskRevision, "done"))
        return task to run.copy(status = TaskRunStatus.COMPLETED)
    }

    private fun acceptedFact(task: BotTaskEntity, run: TaskRunEntity, target: String = "notes.md"): String =
        BotReviewWorker().reviewDecision(
            decision = BotReviewWorker.Decision(ReviewDisposition.ACCEPT),
            goal = task.goal,
            verifiedChecks = listOf("baseline matched"),
            sideEffects = emptyList(),
            candidateRefs = listOf(target),
            publicationAllowed = true,
            publicationKey = PublicationService.publicationKeyFor(task.taskId, run.runId, run.taskRevision),
            taskRevision = run.taskRevision,
        ).encode()

    @Test
    fun `execution completion stores run state task state and recovery event together`() = runTest {
        val task = repository.createTask(
            botId = "assistant-a",
            originSessionId = "session-a",
            requestMessageId = "message-a",
            goal = "Update notes",
            acceptanceCriteria = "",
        )
        val run = repository.openRun(task.taskId) ?: error("run must open")
        repository.transition(task.taskId, BotTaskStatusMachine.Event.START)

        assertTrue(repository.finishExecution(task.taskId, run.runId, run.taskRevision, "result"))
        assertTrue(repository.finishExecution(task.taskId, run.runId, run.taskRevision, "result"))

        assertEquals(BotTaskStatus.REVIEWING, repository.getTask(task.taskId)?.status)
        assertEquals(TaskRunStatus.COMPLETED, repository.listRuns(task.taskId).single().status)
        val resultEvent = repository.latestTaskEvent(task.taskId, run.runId, BotEventKind.TASK_RESULT_READY)
        assertEquals("result", resultEvent?.payloadRef)
        assertNotNull(resultEvent)
    }

    @Test
    fun `review task state and publication facts commit idempotently`() = runTest {
        val (task, run) = completedExecution()
        val payload = acceptedFact(task, run)

        val first = repository.commitReview(
            taskId = task.taskId,
            runId = run.runId,
            taskRevision = run.taskRevision,
            transition = BotTaskStatusMachine.Event.REVIEW_ACCEPT,
            reviewPayload = payload,
        )
        val second = repository.commitReview(
            taskId = task.taskId,
            runId = run.runId,
            taskRevision = run.taskRevision,
            transition = BotTaskStatusMachine.Event.REVIEW_ACCEPT,
            reviewPayload = payload,
        )

        assertEquals(AgentBotRepository.ReviewCommitResult.COMMITTED, first)
        assertEquals(AgentBotRepository.ReviewCommitResult.ALREADY_COMMITTED, second)
        assertEquals(BotTaskStatus.SUCCEEDED, repository.getTask(task.taskId)?.status)
        assertEquals(payload, repository.latestTaskEvent(task.taskId, run.runId, BotEventKind.REVIEW_READY)?.payloadRef)
        assertEquals(
            payload,
            repository.latestTaskEvent(task.taskId, run.runId, BotEventKind.TASK_PUBLICATION_READY)?.payloadRef,
        )
    }

    @Test
    fun `rejected apply effect leaves review state and publication facts untouched`() = runTest {
        val (task, run) = completedExecution()

        val result = repository.commitReview(
            taskId = task.taskId,
            runId = run.runId,
            taskRevision = run.taskRevision,
            transition = BotTaskStatusMachine.Event.REVIEW_ACCEPT,
            reviewPayload = acceptedFact(task, run),
            applyEffect = { false },
        )

        assertEquals(AgentBotRepository.ReviewCommitResult.EFFECT_REJECTED, result)
        assertEquals(BotTaskStatus.REVIEWING, repository.getTask(task.taskId)?.status)
        assertEquals(null, repository.latestTaskEvent(task.taskId, run.runId, BotEventKind.REVIEW_READY))
        assertEquals(null, repository.latestTaskEvent(task.taskId, run.runId, BotEventKind.TASK_PUBLICATION_READY))
    }

    @Test
    fun `target write followed by transaction failure is recovered without writing twice`() = runTest {
        val (task, run) = completedExecution()
        val baseHash = StagedArtifactStore.hashOf("original")
        val candidateId = staging.stage(
            StagedArtifactStore.Candidate(
                targetRef = "notes.md",
                content = "updated",
                baseHash = baseHash,
                taskRevision = run.taskRevision,
                sourceRunId = run.runId,
            ),
        )
        val payload = acceptedFact(task, run)
        var target = "original"
        var writes = 0

        val failed = runCatching {
            repository.commitReview(
                taskId = task.taskId,
                runId = run.runId,
                taskRevision = run.taskRevision,
                transition = BotTaskStatusMachine.Event.REVIEW_ACCEPT,
                reviewPayload = payload,
                applyEffect = {
                    val applied = staging.apply(
                        stagedId = candidateId,
                        currentTaskRevision = run.taskRevision,
                        currentTargetHash = StagedArtifactStore.hashSnapshot(target),
                    ) { _, content ->
                        writes++
                        target = content
                    }
                    assertTrue(applied is StagedArtifactStore.ApplyResult.Applied)
                    error("simulate process death after file write")
                },
            )
        }.isFailure

        assertTrue(failed)
        assertEquals("updated", target)
        assertEquals(BotTaskStatus.REVIEWING, repository.getTask(task.taskId)?.status)
        assertEquals(null, repository.latestTaskEvent(task.taskId, run.runId, BotEventKind.REVIEW_READY))

        val recovered = repository.commitReview(
            taskId = task.taskId,
            runId = run.runId,
            taskRevision = run.taskRevision,
            transition = BotTaskStatusMachine.Event.REVIEW_ACCEPT,
            reviewPayload = payload,
            applyEffect = {
                staging.apply(
                    stagedId = candidateId,
                    currentTaskRevision = run.taskRevision,
                    currentTargetHash = StagedArtifactStore.hashSnapshot(target),
                ) { _, content ->
                    writes++
                    target = content
                } is StagedArtifactStore.ApplyResult.Applied
            },
        )

        assertEquals(AgentBotRepository.ReviewCommitResult.COMMITTED, recovered)
        assertEquals(1, writes)
        assertEquals(BotTaskStatus.SUCCEEDED, repository.getTask(task.taskId)?.status)
        assertNotNull(repository.latestTaskEvent(task.taskId, run.runId, BotEventKind.TASK_PUBLICATION_READY))
        assertTrue(staging.read(candidateId) != null)
    }
}
