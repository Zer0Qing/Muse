package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 审核 → 应用 的闭环。
 *
 * 没有这一层，暂存区就是死的：候选躺在那里永远不会被应用，
 * 用户看到"任务完成"但目标文件没有任何变化。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BotResultApplierTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository;
    private lateinit var dir: File;
    private lateinit var staging: StagedArtifactStore;
    private var clock = 1_000L

    /** 被写入的目标，测试断言用。 */
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
            database = db,
            now = { clock },
        );
        dir = File(System.getProperty("java.io.tmpdir"), "applier-" + System.nanoTime());
        staging = StagedArtifactStore(dir);
    }

    @After
    fun tearDown() {
        db.close();
        dir.deleteRecursively();
    }

    private fun applier(
        read: (suspend (String) -> String?)? = { ref -> targets[ref] },
        write: (suspend (String, String) -> Unit)? = { ref, content -> targets[ref] = content },
    ) = BotResultApplier(
        repository = repo,
        staging = staging,
        reviewer = BotReviewWorker(),
        readTarget = read,
        writeTarget = write,
    );

    private suspend fun newTaskAndRun(resourceScope: String = "notes.md"): Pair<BotTaskEntity, TaskRunEntity> {
        val task = repo.createTask(
            botId = "bot-1",
            originSessionId = "s1",
            requestMessageId = "m1",
            goal = "改错字",
            acceptanceCriteria = "只改错字",
            resourceScope = resourceScope,
        );
        val run = repo.openRun(task.taskId)!!;
        repo.transition(task.taskId, BotTaskStatusMachine.Event.START);
        repo.transition(task.taskId, BotTaskStatusMachine.Event.EXECUTE_DONE);
        repo.closeRun(run.runId, TaskRunStatus.COMPLETED)
        return task to run;
    }

    @Test
    fun `accepted result is written to the target`() = runTest {
        targets["notes.md"] = "原内容";
        val (task, run) = newTaskAndRun();
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

        assertTrue("审核通过应真的写目标", outcome.applied);
        assertEquals("改好的内容", targets["notes.md"]);
        assertNull("审核事务完成后才清理候选", staging.read(stagedId))
    }

    @Test
    fun `multiple distinct candidates apply and publish as one reviewed result`() = runTest {
        targets["notes.md"] = "notes-v1"
        targets["todo.md"] = "todo-v1"
        val (task, run) = newTaskAndRun("notes.md,todo.md")
        val notesId = staging.stage(
            StagedArtifactStore.Candidate(
                targetRef = "notes.md",
                content = "notes-v2",
                baseHash = StagedArtifactStore.hashSnapshot(targets["notes.md"]),
                taskRevision = run.taskRevision,
                sourceRunId = run.runId,
            ),
        )
        val todoId = staging.stage(
            StagedArtifactStore.Candidate(
                targetRef = "todo.md",
                content = "todo-v2",
                baseHash = StagedArtifactStore.hashSnapshot(targets["todo.md"]),
                taskRevision = run.taskRevision,
                sourceRunId = run.runId,
            ),
        )

        val outcome = applier().process(
            task = task,
            run = run,
            payloadRef = "两份文件已更新\n${BotResultApplier.STAGED_MARKER}$notesId,$todoId]",
            expectedBaseHash = "",
            criteria = "只改错字",
        )

        assertTrue(outcome.applied)
        assertTrue(outcome.accepted)
        assertEquals("notes-v2", targets["notes.md"])
        assertEquals("todo-v2", targets["todo.md"])
        assertEquals(BotTaskStatus.SUCCEEDED, repo.getTask(task.taskId)?.status)
        val publication = db.botEventDao().latestOfKind(
            task.taskId,
            run.runId,
            BotEventKind.TASK_PUBLICATION_READY,
        )
        assertEquals(2, ReviewDecision.decode(publication?.payloadRef.orEmpty())?.candidateRefs?.size)
        assertNull(staging.read(notesId))
        assertNull(staging.read(todoId))
    }

    @Test
    fun `target changed after staging is not overwritten`() = runTest {
        targets["notes.md"] = "原内容";
        val (task, run) = newTaskAndRun();
        val baseHash = StagedArtifactStore.hashOf("原内容");
        val stagedId = staging.stage(
            StagedArtifactStore.Candidate("notes.md", "改好的内容", baseHash, run.taskRevision),
        );
        // 用户在任务跑完后手动改了文件
        targets["notes.md"] = "用户自己改的";

        val outcome = applier().process(
            task = task,
            run = run,
            payloadRef = "改完了\n" + BotResultApplier.STAGED_MARKER + stagedId + "]",
            expectedBaseHash = baseHash,
            criteria = "只改错字",
        );

        assertFalse("目标被改过不能覆盖", outcome.applied);
        assertEquals("用户自己改的", targets["notes.md"]);
    }

    @Test
    fun `result without artifacts is not applied`() = runTest {
        val (task, run) = newTaskAndRun();
        val outcome = applier().process(
            task = task,
            run = run,
            payloadRef = "我做完了",
            expectedBaseHash = "",
            criteria = "只改错字",
        );

        assertFalse("只返回文字不算完成", outcome.applied);
        assertTrue(targets.isEmpty());
    }

    @Test
    fun `missing write capability keeps the candidate`() = runTest {
        targets["notes.md"] = "原内容";
        val (task, run) = newTaskAndRun();
        val baseHash = StagedArtifactStore.hashOf("原内容");
        val stagedId = staging.stage(
            StagedArtifactStore.Candidate("notes.md", "改好的内容", baseHash, run.taskRevision),
        );

        val outcome = applier(write = null).process(
            task = task,
            run = run,
            payloadRef = "改完了\n" + BotResultApplier.STAGED_MARKER + stagedId + "]",
            expectedBaseHash = baseHash,
            criteria = "只改错字",
        );

        assertFalse(outcome.applied);
        assertTrue("要说明为什么没应用", outcome.blockedReason.contains("没有写入能力"));
        assertTrue("候选要留着，等用户手动处理", staging.read(stagedId) != null);
    }

    @Test
    fun `staged id is parsed from payload ref`() = runTest {
        targets["notes.md"] = "原内容";
        val (task, run) = newTaskAndRun();
        val baseHash = StagedArtifactStore.hashOf("原内容");
        val stagedId = staging.stage(
            StagedArtifactStore.Candidate("notes.md", "改好的内容", baseHash, run.taskRevision),
        );

        val outcome = applier().process(
            task = task,
            run = run,
            payloadRef = "摘要在这里\n" + BotResultApplier.STAGED_MARKER + stagedId + "]",
            expectedBaseHash = baseHash,
            criteria = "只改错字",
        );

        assertTrue("必须能从 payloadRef 里认出 staged id", outcome.applied);
    }
}
