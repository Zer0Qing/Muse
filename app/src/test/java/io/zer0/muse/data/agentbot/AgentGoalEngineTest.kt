package io.zer0.muse.data.agentbot

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGoalEngineTest {

    @Test
    fun `upsert creates an active goal and assigns ids to new milestones`() = runTest {
        val dao = FakeGoalDao()
        val engine = engine(dao)

        val created = engine.upsertGoal(
            botId = "bot-1",
            originSessionId = "session-1",
            title = "三个月内跑完半马",
            description = "每周三次训练",
            milestones = listOf(
                AgentGoalMilestone(title = "跑完 5 公里", done = true),
                AgentGoalMilestone(title = "跑完 10 公里"),
            ),
        )

        val goal = requireNotNull(created)
        assertEquals(AgentGoalStatus.ACTIVE, goal.status)
        assertEquals(50, goal.progress)
        assertEquals(1_000L, goal.createdAt)
        val stored = requireNotNull(dao.getById(goal.goalId))
        val milestones = engine.parseMilestones(stored.milestonesJson)
        assertEquals(2, milestones.size)
        assertTrue(milestones.all { it.id.isNotBlank() })
    }

    @Test
    fun `upsert updates content without resetting creation metadata`() = runTest {
        val dao = FakeGoalDao()
        val engine = engine(dao)
        dao.upsert(
            goal(
                goalId = "goal-1",
                botId = "bot-old",
                originSessionId = "session-old",
                title = "旧标题",
                description = "旧说明",
                createdAt = 10L,
                updatedAt = 10L,
            ),
        )

        val updated = engine.upsertGoal(
            goalId = "goal-1",
            title = "新标题",
            description = "新说明",
        )

        val goal = requireNotNull(updated)
        assertEquals("新标题", goal.title)
        assertEquals("新说明", goal.description)
        assertEquals("bot-old", goal.botId)
        assertEquals("session-old", goal.originSessionId)
        assertEquals(10L, goal.createdAt)
        assertEquals(1_000L, goal.updatedAt)
    }

    @Test
    fun `progress is the completed milestone ratio`() {
        val engine = engine(FakeGoalDao())

        assertEquals(0, engine.computeProgress(emptyList()))
        assertEquals(
            67,
            engine.computeProgress(
                listOf(
                    AgentGoalMilestone(id = "1", title = "a", done = true),
                    AgentGoalMilestone(id = "2", title = "b", done = true),
                    AgentGoalMilestone(id = "3", title = "c", done = false),
                ),
            ),
        )
        assertEquals(
            100,
            engine.computeProgress(listOf(AgentGoalMilestone(id = "1", title = "a", done = true))),
        )
    }

    @Test
    fun `completing the last milestone marks the goal achieved`() = runTest {
        val dao = FakeGoalDao()
        val engine = engine(dao)
        dao.upsert(
            goal(
                milestonesJson = """[{"id":"m1","title":"第一步","done":true},{"id":"m2","title":"第二步","done":false}]""",
            ),
        )

        val updated = engine.updateMilestone(goalId = "goal-1", milestoneId = "m2", done = true)

        val goal = requireNotNull(updated)
        assertEquals(100, goal.progress)
        assertEquals(AgentGoalStatus.ACHIEVED, goal.status)
    }

    @Test
    fun `unchecking a milestone reopens an achieved goal`() = runTest {
        val dao = FakeGoalDao()
        val engine = engine(dao)
        dao.upsert(
            goal(
                status = AgentGoalStatus.ACHIEVED,
                progress = 100,
                milestonesJson = """[{"id":"m1","title":"第一步","done":true},{"id":"m2","title":"第二步","done":true}]""",
            ),
        )

        val updated = engine.updateMilestone(goalId = "goal-1", milestoneId = "m1", done = false)

        val goal = requireNotNull(updated)
        assertEquals(50, goal.progress)
        assertEquals(AgentGoalStatus.ACTIVE, goal.status)
    }

    @Test
    fun `unknown milestone id with a title appends a new milestone`() = runTest {
        val dao = FakeGoalDao()
        val engine = engine(dao)
        dao.upsert(
            goal(milestonesJson = """[{"id":"m1","title":"第一步","done":false}]"""),
        )

        val updated = engine.updateMilestone(
            goalId = "goal-1",
            milestoneId = "missing",
            title = "补充里程碑",
            done = true,
        )

        val goal = requireNotNull(updated)
        val milestones = engine.parseMilestones(goal.milestonesJson)
        assertEquals(2, milestones.size)
        assertEquals("补充里程碑", milestones[1].title)
        assertTrue(milestones[1].done)
        assertTrue(milestones[1].id.isNotBlank())
    }

    @Test
    fun `removing a milestone recomputes progress`() = runTest {
        val dao = FakeGoalDao()
        val engine = engine(dao)
        dao.upsert(
            goal(
                progress = 50,
                milestonesJson = """[{"id":"m1","title":"第一步","done":false},{"id":"m2","title":"第二步","done":true}]""",
            ),
        )

        val updated = engine.updateMilestone(goalId = "goal-1", milestoneId = "m2", remove = true)

        val goal = requireNotNull(updated)
        assertEquals(0, goal.progress)
        assertEquals(1, engine.parseMilestones(goal.milestonesJson).size)
    }

    @Test
    fun `status transitions accept known values and reject unknown ones`() = runTest {
        val dao = FakeGoalDao()
        val engine = engine(dao)
        dao.upsert(goal())

        assertTrue(engine.pause("goal-1"))
        assertEquals(AgentGoalStatus.PAUSED, dao.getById("goal-1")?.status)
        assertTrue(engine.resume("goal-1"))
        assertEquals(AgentGoalStatus.ACTIVE, dao.getById("goal-1")?.status)
        assertFalse(engine.setStatus("goal-1", "BROKEN"))
        assertEquals(AgentGoalStatus.ACTIVE, dao.getById("goal-1")?.status)
    }

    @Test
    fun `abandoned goal stays abandoned after milestone changes`() = runTest {
        val dao = FakeGoalDao()
        val engine = engine(dao)
        dao.upsert(
            goal(
                status = AgentGoalStatus.ABANDONED,
                milestonesJson = """[{"id":"m1","title":"第一步","done":false}]""",
            ),
        )

        val updated = engine.updateMilestone(goalId = "goal-1", milestoneId = "m1", done = true)

        assertEquals(AgentGoalStatus.ABANDONED, updated?.status)
    }

    @Test
    fun `milestone parsing tolerates string arrays and malformed input`() {
        val engine = engine(FakeGoalDao())

        val fromStrings = engine.parseMilestones("""["第一步","第二步"]""")
        assertEquals(listOf("第一步", "第二步"), fromStrings.map { it.title })
        assertTrue(fromStrings.all { it.id.isNotBlank() })

        assertTrue(engine.parseMilestones("not-json").isEmpty())
        assertTrue(engine.parseMilestones("[]").isEmpty())
    }

    @Test
    fun `explicit progress overrides the milestone ratio`() = runTest {
        val dao = FakeGoalDao()
        val engine = engine(dao)

        val created = engine.upsertGoal(
            title = "准备作品集",
            milestones = listOf(AgentGoalMilestone(title = "整理旧作品")),
            progress = 80,
        )

        assertEquals(80, created?.progress)
    }

    @Test
    fun `refine context carries the current goal json`() = runTest {
        val dao = FakeGoalDao()
        val engine = engine(dao)
        dao.upsert(goal(title = "学习吉他", milestonesJson = """[{"id":"m1","title":"练熟和弦","done":false}]"""))

        val context = requireNotNull(engine.buildRefineContext("goal-1"))

        assertTrue(context.contains("entity_kind: goal"))
        assertTrue(context.contains("entity_id: goal-1"))
        assertTrue(context.contains("current_json:"))
        assertTrue(context.contains("学习吉他"))
        assertTrue(context.contains("upsert_goal"))
    }

    private fun engine(dao: FakeGoalDao): AgentGoalEngine = AgentGoalEngine(
        dao = dao,
        clock = { 1_000L },
    )

    private fun goal(
        goalId: String = "goal-1",
        botId: String = "bot-1",
        originSessionId: String = "session-1",
        title: String = "长期目标",
        description: String = "说明",
        milestonesJson: String = "[]",
        progress: Int = 0,
        status: String = AgentGoalStatus.ACTIVE,
        createdAt: Long = 1L,
        updatedAt: Long = 1L,
    ): AgentGoalEntity = AgentGoalEntity(
        goalId = goalId,
        botId = botId,
        originSessionId = originSessionId,
        title = title,
        description = description,
        milestonesJson = milestonesJson,
        progress = progress,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private class FakeGoalDao : AgentGoalDao {

    private val rows = linkedMapOf<String, AgentGoalEntity>()

    override suspend fun upsert(goal: AgentGoalEntity) {
        rows[goal.goalId] = goal
    }

    override suspend fun upsertAll(goals: List<AgentGoalEntity>) {
        goals.forEach { rows[it.goalId] = it }
    }

    override suspend fun getById(goalId: String): AgentGoalEntity? = rows[goalId]

    override fun observeAll(): Flow<List<AgentGoalEntity>> = flowOf(rows.values.toList())

    override fun observeByBot(botId: String): Flow<List<AgentGoalEntity>> =
        flowOf(rows.values.filter { it.botId == botId })

    override fun observeBySession(sessionId: String): Flow<List<AgentGoalEntity>> =
        flowOf(rows.values.filter { it.originSessionId == sessionId })

    override suspend fun listActive(): List<AgentGoalEntity> =
        rows.values.filter { it.status == AgentGoalStatus.ACTIVE }

    override suspend fun updateMilestones(
        goalId: String,
        milestonesJson: String,
        progress: Int,
        updatedAt: Long,
    ): Int {
        val current = rows[goalId] ?: return 0
        rows[goalId] = current.copy(
            milestonesJson = milestonesJson,
            progress = progress,
            updatedAt = updatedAt,
        )
        return 1
    }

    override suspend fun setStatus(goalId: String, status: String, updatedAt: Long): Int {
        val current = rows[goalId] ?: return 0
        rows[goalId] = current.copy(status = status, updatedAt = updatedAt)
        return 1
    }

    override suspend fun deleteById(goalId: String) {
        rows.remove(goalId)
    }

    override suspend fun deleteBySession(sessionId: String) {
        rows.values.removeAll { it.originSessionId == sessionId }
    }

    override suspend fun deleteByAssistant(assistantId: String) {
        // 测试不涉及助手级联
    }

    override suspend fun getAll(): List<AgentGoalEntity> = rows.values.toList()

    override suspend fun deleteAll() {
        rows.clear()
    }
}
