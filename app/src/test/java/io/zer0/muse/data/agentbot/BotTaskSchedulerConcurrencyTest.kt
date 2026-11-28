package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.tools.SubagentRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BotTaskSchedulerConcurrencyTest {

    private lateinit var db: MuseDb
    private lateinit var repository: AgentBotRepository
    private var schedulerScope: CoroutineScope? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
    }

    @After
    fun tearDown() {
        schedulerScope?.cancel()
        db.close()
    }

    @Test
    fun concurrentTicksNeverExceedCapacityOrCreateDuplicateRuns() = runTest {
        repeat(3) { index ->
            repository.createTask(
                botId = "bot-a",
                originSessionId = "session-$index",
                requestMessageId = "message-$index",
                goal = "task-$index",
                acceptanceCriteria = "complete",
            )
        }
        val runner = mockk<SubagentRunner>()
        coEvery { runner.run(any(), any()) } coAnswers { awaitCancellation() }
        schedulerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val scheduler = BotTaskScheduler(
            repository = repository,
            runner = runner,
            scope = requireNotNull(schedulerScope),
            dependencies = BotTaskSchedulerDependencies(
                execution = BotTaskSchedulerDependencies.Execution(maxConcurrentRuns = 2),
            ),
        )

        coroutineScope {
            listOf(async { scheduler.tick() }, async { scheduler.tick() }).awaitAll()
        }
        testScheduler.runCurrent()

        val tasks = repository.listTasks("bot-a")
        val running = tasks.filter { it.status == BotTaskStatus.RUNNING }
        var persistedRunCount = 0
        for (task in running) persistedRunCount += repository.listRuns(task.taskId).size
        assertEquals(2, running.size)
        assertEquals(2, repository.runningRuns().size)
        assertEquals(2, persistedRunCount)
        assertEquals(1, tasks.count { it.status == BotTaskStatus.WAITING_RESOURCE })
        schedulerScope?.cancel()
        testScheduler.runCurrent()
    }
}
