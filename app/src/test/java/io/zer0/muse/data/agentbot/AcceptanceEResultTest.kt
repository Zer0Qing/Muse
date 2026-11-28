package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 验收清单 E 组（结果落地）的可静态验证部分。

 * E1「目标文件真的被改了」必须设备验（取决于子 Agent 行为）。
 * 但 E2/E3/E5 的**判定逻辑**可以在 JVM 里钉住：
 *
 *  - E2：目标被改过时不覆盖
 *  - E3：冲突的候选留在暂存区可丢弃
 *  - E5：不可回滚操作要出现在汇报里
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AcceptanceEResultTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
    private lateinit var dir: File
    private lateinit var staging: StagedArtifactStore

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
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
        )
        dir = File(System.getProperty("java.io.tmpdir"), "accept-e-" + System.nanoTime())
        staging = StagedArtifactStore(dir)
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun `E2 a changed target is not overwritten`() = runTest {
        // 产出时基线是"原内容"，之后用户改成了"用户改过的内容"。
        val baseHash = StagedArtifactStore.hashOf("原内容")
        val id = staging.stage(
            StagedArtifactStore.Candidate("notes.md", "改好的内容", baseHash, taskRevision = 1),
        )
        var written: String? = null

        val result = staging.apply(
            stagedId = id,
            currentTaskRevision = 1,
            currentTargetHash = StagedArtifactStore.hashOf("用户改过的内容"),
        ) { _, content -> written = content }

        assertTrue("目标被改过时必须报冲突", result is StagedArtifactStore.ApplyResult.Conflict)
        assertEquals("不能覆盖用户的修改", null, written)
    }

    @Test
    fun `E3 a conflicting candidate stays for discard`() = runTest {
        val baseHash = StagedArtifactStore.hashOf("原内容")
        val id = staging.stage(
            StagedArtifactStore.Candidate("notes.md", "改好的", baseHash, taskRevision = 1),
        )
        staging.apply(
            stagedId = id,
            currentTaskRevision = 1,
            currentTargetHash = StagedArtifactStore.hashOf("变了"),
        ) { _, _ -> }

        assertTrue(
            "冲突的候选必须留在暂存区，用户要能看到并决定丢不丢",
            staging.listStaged().any { it.stagedId == id },
        )
    }

    @Test
    fun `E5 irreversible steps are named in the ledger`() = runTest {
        val task = repo.createTask(
            botId = "bot-1",
            originSessionId = "s1",
            requestMessageId = "m1",
            goal = "发通知",
            acceptanceCriteria = "发出",
        )
        repo.transition(task.taskId, BotTaskStatusMachine.Event.START)
        val run = repo.openRun(task.taskId)!!
        repo.recordStep(
            runId = run.runId,
            stepIndex = 0,
            toolName = "device_notify",
            argumentsHash = "h",
            baseHash = "",
            approvalId = "",
            sideEffect = StepSideEffect.NO_BLIND_REPLAY,
        )

        val irreversible = repo.listSteps(run.runId)
            .filter { it.sideEffect == StepSideEffect.NO_BLIND_REPLAY }
        assertTrue("不可回滚的操作要能查出来", irreversible.isNotEmpty())
        assertEquals(
            "而且要带工具名 —— 只说「有副作用」等于没说",
            "device_notify",
            irreversible.single().toolName,
        )
    }

    @Test
    fun `E4 a newer revision invalidates an older candidate`() = runTest {
        val id = staging.stage(
            StagedArtifactStore.Candidate(
                "notes.md",
                "按旧要求改的",
                StagedArtifactStore.hashOf("原内容"),
                taskRevision = 1,
            ),
        )

        val result = staging.apply(
            stagedId = id,
            currentTaskRevision = 2,
            currentTargetHash = StagedArtifactStore.hashOf("原内容"),
        ) { _, _ -> }

        assertTrue(
            "任务版本已推进，旧候选不该再应用",
            result is StagedArtifactStore.ApplyResult.Stale,
        )
    }
}
