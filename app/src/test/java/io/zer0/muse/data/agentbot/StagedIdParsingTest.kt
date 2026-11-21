package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * payloadRef 解析的健壮性。
 *
 * 摘要正文是模型输出，可能恰好包含 `[staged=` 字样。
 * 解析错了会拿别人的候选去应用 —— 那是把 A 的产物写到 B 的目标上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StagedIdParsingTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
    private lateinit var dir: File
    private lateinit var staging: StagedArtifactStore
    private lateinit var applier: BotResultApplier
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
        );
        dir = File(System.getProperty("java.io.tmpdir"), "parse-" + System.nanoTime());
        staging = StagedArtifactStore(dir);
        applier = BotResultApplier(
            repository = repo,
            staging = staging,
            reviewer = BotReviewWorker(),
            readTarget = { ref -> targets[ref] },
            writeTarget = { ref, content -> targets[ref] = content },
        );
    }

    @After
    fun tearDown() {
        db.close();
        dir.deleteRecursively();
    }

    private suspend fun taskAndRun(): Pair<BotTaskEntity, TaskRunEntity> {
        val task = repo.createTask(
            botId = "bot-1", originSessionId = "s1", requestMessageId = "m1",
            goal = "改错字", acceptanceCriteria = "只改错字", resourceScope = "notes.md",
        );
        val run = repo.openRun(task.taskId)!!;
        repo.transition(task.taskId, BotTaskStatusMachine.Event.START);
        repo.transition(task.taskId, BotTaskStatusMachine.Event.EXECUTE_DONE);
        return task to run;
    }

    @Test
    fun `a marker inside the summary does not hijack the real id`() = runTest {
        targets["notes.md"] = "原内容";
        val (task, run) = taskAndRun();
        val baseHash = StagedArtifactStore.hashOf("原内容");
        val realId = staging.stage(
            StagedArtifactStore.Candidate("notes.md", "改好的内容", baseHash, run.taskRevision),
        );

        // 摘要里故意塞一个假的 staged 标记。
        val payload = "我按 [staged=cand-999999] 的方式处理了\n" +
            BotResultApplier.STAGED_MARKER + realId + "]";

        val outcome = applier.process(
            task = task, run = run, payloadRef = payload,
            expectedBaseHash = baseHash, criteria = "只改错字",
        );

        assertTrue("必须用真正的 id，不是摘要里的假标记", outcome.applied);
    }

    @Test
    fun `a malformed id is treated as no candidate`() = runTest {
        targets["notes.md"] = "原内容";
        val (task, run) = taskAndRun();
        val baseHash = StagedArtifactStore.hashOf("原内容");

        // id 格式不对（不是 cand-数字）。
        val payload = "改完了\n" + BotResultApplier.STAGED_MARKER + "not-a-valid-id]";

        val outcome = applier.process(
            task = task, run = run, payloadRef = payload,
            expectedBaseHash = baseHash, criteria = "只改错字",
        );

        assertFalse("非法 id 不该被当成候选", outcome.applied);
        assertTrue("目标不能被写", targets["notes.md"] == "原内容");
    }

    @Test
    fun `summary without any marker still processes`() = runTest {
        val (task, run) = taskAndRun();
        val outcome = applier.process(
            task = task, run = run, payloadRef = "只返回了文字",
            expectedBaseHash = "", criteria = "只改错字",
        );

        assertFalse("没有候选就不该应用", outcome.applied);
    }
}
