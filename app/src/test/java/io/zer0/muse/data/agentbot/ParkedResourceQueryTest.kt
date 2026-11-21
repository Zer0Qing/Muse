package io.zer0.muse.data.agentbot;

import android.content.Context;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import io.zer0.muse.data.session.MuseDb;
import kotlinx.coroutines.test.runTest;
import org.junit.After;
import org.junit.Assert.assertEquals;
import org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 等资源的任务必须能被查回来。

 * 上一轮把 `WAITING_RESOURCE` 接进并发满路径，但恢复侧用 `claimCandidates`
 * 过滤 —— 那个查询只查 `QUEUED`，过滤 `WAITING_RESOURCE` 永远为空。
 *
 * 后果：任务进了等待态就再也出不来，比原来"留在 QUEUED"更糟。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ParkedResourceQueryTest {

    private lateinit var db: MuseDb;
    private lateinit var repo: AgentBotRepository;

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
    }

    @After
    fun tearDown() {
        db.close();
    }

    @Test
    fun `claimCandidates does not return parked tasks`() = runTest {
        // 这条钉住"为什么不能复用 claimCandidates"。
        val task = repo.createTask(
            botId = "bot-1", originSessionId = "s1", requestMessageId = "m1",
            goal = "查资料", acceptanceCriteria = "查到",
        );
        repo.transition(task.taskId, BotTaskStatusMachine.Event.WAIT_RESOURCE);

        assertTrue(
            "claimCandidates 只查 QUEUED，拿它恢复等资源任务永远为空",
            repo.claimCandidates(10).isEmpty(),
        );
    }

    @Test
    fun `parkedForResource returns parked tasks`() = runTest {
        val task = repo.createTask(
            botId = "bot-1", originSessionId = "s1", requestMessageId = "m1",
            goal = "查资料", acceptanceCriteria = "查到",
        );
        repo.transition(task.taskId, BotTaskStatusMachine.Event.WAIT_RESOURCE);

        val parked = repo.parkedForResource(10);
        assertEquals("必须能查回等资源的任务", 1, parked.size);
        assertEquals(task.taskId, parked.single().taskId);
    }

    @Test
    fun `parked task can resume back to queue`() = runTest {
        val task = repo.createTask(
            botId = "bot-1", originSessionId = "s1", requestMessageId = "m1",
            goal = "查资料", acceptanceCriteria = "查到",
        );
        repo.transition(task.taskId, BotTaskStatusMachine.Event.WAIT_RESOURCE);
        repo.transition(task.taskId, BotTaskStatusMachine.Event.RESUME_FROM_RESOURCE);

        assertEquals(
            "恢复后必须回到 QUEUED",
            BotTaskStatus.QUEUED,
            repo.getTask(task.taskId)!!.status,
        );
        assertTrue("并且能被重新领取", repo.claimCandidates(10).any { it.taskId == task.taskId });
    }
}
