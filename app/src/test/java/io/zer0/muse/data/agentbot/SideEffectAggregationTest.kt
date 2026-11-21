package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 步骤账本 → 审核副作用的汇总（V23 的数据来源）。
 *
 * 上一轮加了 `irreversibleSideEffects` 入参却没人填 —— 这轮补上，
 * 并在这里钉住"NO_BLIND_REPLAY 的步骤必须被汇总出来"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SideEffectAggregationTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
    private lateinit var dir: File
    private lateinit var staging: StagedArtifactStore
    private var clock = 1_000L
    private val targets = mutableMapOf<String, String>()

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
            now = { clock },
        );
        dir = File(System.getProperty("java.io.tmpdir"), "agg-" + System.nanoTime());
        staging = StagedArtifactStore(dir);
    }

    @After
    fun tearDown() {
        db.close();
        dir.deleteRecursively();
    }

    private fun applier() = BotResultApplier(
        repository = repo,
        staging = staging,
        reviewer = BotReviewWorker(),
        readTarget = { ref -> targets[ref] },
        writeTarget = { ref, content -> targets[ref] = content },
    );

    private suspend fun taskAndRun(): Pair<BotTaskEntity, TaskRunEntity> {
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
        return task to run;
    }

    @Test
    fun `no-blind-replay step becomes a reported side effect`() = runTest {
        targets["notes.md"] = "原内容";
        val (task, run) = taskAndRun();
        // 记一步不可盲重放的操作（例如发了外部请求）。
        repo.recordStep(
            runId = run.runId,
            stepIndex = 0,
            toolName = "send_notification",
            argumentsHash = "h",
            baseHash = "",
            approvalId = "",
            sideEffect = StepSideEffect.NO_BLIND_REPLAY,
        );
        val baseHash = StagedArtifactStore.hashOf("原内容");
        val stagedId = staging.stage(
            StagedArtifactStore.Candidate("notes.md", "改好的内容", baseHash, run.taskRevision),
        );

        val outcome = applier().process(
            task = task,
            run = run,
            payloadRef = "改完了\n" + BotResultApplier.STAGED_MARKER + stagedId + "]",
            expectedBaseHash = baseHash,
            criteria = "只改错字",
        );

        assertEquals(
            "有副作用时不能判全成功",
            ReviewDisposition.PARTIAL,
            outcome.disposition,
        );
        assertTrue(
            "必须写出哪个操作已经发生",
            outcome.findings.any { it.contains("send_notification") },
        );
    }

    @Test
    fun `retry-safe steps are not reported as side effects`() = runTest {
        targets["notes.md"] = "原内容";
        val (task, run) = taskAndRun();
        repo.recordStep(
            runId = run.runId,
            stepIndex = 0,
            toolName = "read_file",
            argumentsHash = "h",
            baseHash = "",
            approvalId = "",
            sideEffect = StepSideEffect.RETRY_SAFE,
        );
        val baseHash = StagedArtifactStore.hashOf("原内容");
        val stagedId = staging.stage(
            StagedArtifactStore.Candidate("notes.md", "改好的内容", baseHash, run.taskRevision),
        );

        val outcome = applier().process(
            task = task,
            run = run,
            payloadRef = "改完了\n" + BotResultApplier.STAGED_MARKER + stagedId + "]",
            expectedBaseHash = baseHash,
            criteria = "只改错字",
        );

        assertEquals(
            "可重放的操作不算副作用",
            ReviewDisposition.ACCEPT,
            outcome.disposition,
        );
    }

    @Test
    fun `side effect blocks acceptance even with a good artifact`() = runTest {
        targets["notes.md"] = "原内容";
        val (task, run) = taskAndRun();
        repo.recordStep(
            runId = run.runId,
            stepIndex = 0,
            toolName = "call_webhook",
            argumentsHash = "h",
            baseHash = "",
            approvalId = "",
            sideEffect = StepSideEffect.NO_BLIND_REPLAY,
        );
        val baseHash = StagedArtifactStore.hashOf("原内容");
        val stagedId = staging.stage(
            StagedArtifactStore.Candidate("notes.md", "改好的内容", baseHash, run.taskRevision),
        );

        val outcome = applier().process(
            task = task,
            run = run,
            payloadRef = "改完了\n" + BotResultApplier.STAGED_MARKER + stagedId + "]",
            expectedBaseHash = baseHash,
            criteria = "只改错字",
        );

        assertFalse(
            "有副作用的任务不能显示为完全成功",
            outcome.disposition == ReviewDisposition.ACCEPT,
        );
    }
}