package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.card.CardEntity
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.flow.first
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
 * [AgentSpaceEngine] 的行为测试。
 *
 * 用内存 Room 库验证三件事：
 * 1. 钉入/取消钉入真的落到 card_entities 的 Spaces 列上，且卡片行不丢；
 * 2. 后台刷新只在到期时调用刷新器，并按卡片 id 写入 CardDataStore；
 * 3. 刷新失败或模型吐坏 JSON 时不推进 lastRefreshedAt，下一轮还能重试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentSpaceEngineTest {

    private lateinit var database: MuseDb
    private lateinit var writer: FakeSpaceDataWriter
    private lateinit var refresher: FakeSpaceRefresher
    private var now: Long = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
        writer = FakeSpaceDataWriter()
        refresher = FakeSpaceRefresher()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun engine(): AgentSpaceEngine = AgentSpaceEngine(
        dao = database.cardEntityDao(),
        dataWriter = writer,
        refresher = refresher,
        clock = { now },
        pollIntervalMs = 60_000L,
    )

    @Test
    fun pinMessageCardCreatesOnePinnedRowAndSeedsItsData() = runTest {
        val card = engine().pinMessageCard(
            messageId = "message-1",
            title = "支出看板",
            code = "<div class=\"m-shell\"><h1>本月支出</h1></div>",
            isSvg = false,
            dataJson = """{"total":10}""",
            originSessionId = "session-1",
            category = "生活",
            refreshIntervalMinutes = 60,
            refreshPrompt = "按最新账单更新",
        )

        val stored = requireNotNull(card)
        assertEquals("message-1", stored.originMessageId)
        assertTrue(stored.spacePinned)
        assertEquals("生活", stored.spaceCategory)
        assertEquals(60, stored.refreshIntervalMinutes)
        assertEquals("""{"total":10}""", writer.data[stored.id])
        assertEquals(listOf(stored.id), database.cardEntityDao().observePinnedSpaces().first().map { it.id })
    }

    @Test
    fun pinningTheSameMessageTwiceReusesTheExistingRow() = runTest {
        val first = requireNotNull(
            engine().pinMessageCard(
                messageId = "message-1",
                title = "支出看板",
                code = "<div>A</div>",
                isSvg = false,
                dataJson = """{"v":1}""",
            ),
        )
        val second = requireNotNull(
            engine().pinMessageCard(
                messageId = "message-1",
                title = "支出看板（更新）",
                code = "<div>A</div>",
                isSvg = false,
                dataJson = """{"v":2}""",
            ),
        )

        assertEquals(first.id, second.id)
        assertEquals(1, database.cardEntityDao().getByOriginMessageId("message-1").size)
        assertEquals("支出看板（更新）", database.cardEntityDao().getById(first.id)?.title)
        assertEquals("""{"v":2}""", writer.data[first.id])
    }

    @Test
    fun pinCardUpdatesAnExistingRowWithoutDuplicatingIt() = runTest {
        val existing = CardEntity(
            id = "card-1",
            title = "已有卡片",
            code = "<div>existing</div>",
            dataJson = """{"v":1}""",
            createdAt = 1L,
            updatedAt = 1L,
        )
        database.cardEntityDao().upsert(existing)

        val pinned = requireNotNull(
            engine().pinCard(existing, category = "工具", refreshIntervalMinutes = 120, refreshPrompt = "刷新"),
        )

        assertEquals("card-1", pinned.id)
        assertTrue(pinned.spacePinned)
        assertEquals("工具", pinned.spaceCategory)
        assertEquals(120, pinned.refreshIntervalMinutes)
        assertEquals(1, database.cardEntityDao().observePinnedSpaces().first().size)
        assertEquals("""{"v":1}""", writer.data["card-1"])
    }

    @Test
    fun unpinKeepsTheRowButRemovesItFromThePinnedFlow() = runTest {
        val card = requireNotNull(
            engine().pinMessageCard(
                messageId = "message-2",
                title = "快递追踪",
                code = "<div>B</div>",
                isSvg = false,
            ),
        )

        assertTrue(engine().unpinCard(card.id))
        val stored = database.cardEntityDao().getById(card.id)
        assertNotNull(stored)
        assertFalse(stored?.spacePinned ?: true)
        assertTrue(database.cardEntityDao().observePinnedSpaces().first().isEmpty())
    }

    @Test
    fun refreshDueRefreshesOnlyCardsWhoseIntervalElapsed() = runTest {
        val due = requireNotNull(
            engine().pinMessageCard(
                messageId = "message-due",
                title = "每日晨报",
                code = "<div>due</div>",
                isSvg = false,
                dataJson = """{"v":1}""",
                refreshIntervalMinutes = 30,
            ),
        )
        now = 2_000L
        val future = requireNotNull(
            engine().pinMessageCard(
                messageId = "message-future",
                title = "账单追踪",
                code = "<div>future</div>",
                isSvg = false,
                dataJson = """{"v":1}""",
                refreshIntervalMinutes = 30,
            ),
        )
        now = 3_000L
        val manual = requireNotNull(
            engine().pinMessageCard(
                messageId = "message-manual",
                title = "手动刷新",
                code = "<div>manual</div>",
                isSvg = false,
                dataJson = """{"v":1}""",
                refreshIntervalMinutes = 0,
            ),
        )
        database.cardEntityDao().updateSpaceRefresh(future.id, 30, "", 9_500L, 9_500L)

        refresher.reply = """{"value":2}"""
        val outcomes = engine().refreshDue(at = 10_000L)

        assertEquals(listOf(due.id), refresher.requests)
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is SpaceRefreshOutcome.Refreshed)
        assertEquals("""{"value":2}""", writer.data[due.id])
        assertEquals(10_000L, database.cardEntityDao().getById(due.id)?.lastRefreshedAt)
        assertEquals(9_500L, database.cardEntityDao().getById(future.id)?.lastRefreshedAt)
        assertEquals(0L, database.cardEntityDao().getById(manual.id)?.lastRefreshedAt)
    }

    @Test
    fun refreshFailureDoesNotAdvanceLastRefreshedAt() = runTest {
        val engine = engine()
        val card = requireNotNull(
            engine.pinMessageCard(
                messageId = "message-fail",
                title = "失败卡片",
                code = "<div>fail</div>",
                isSvg = false,
                dataJson = """{"v":1}""",
                refreshIntervalMinutes = 15,
            ),
        )
        refresher.failure = IllegalStateException("network down")

        val outcome = engine.refreshDue(at = 5_000L).single()

        val failure = requireNotNull(outcome as? SpaceRefreshOutcome.Failed)
        assertEquals("network down", failure.reason)
        assertEquals(0L, database.cardEntityDao().getById(card.id)?.lastRefreshedAt)
        assertEquals("""{"v":1}""", writer.data[card.id])
        assertTrue(engine.refreshFailures.first().containsKey(card.id))
        assertTrue(engine.refreshDue(at = 5_001L).isEmpty())
    }

    @Test
    fun refreshDoesNotCommitModelOutputAfterCardSettingsChange() = runTest {
        val engine = engine()
        val card = requireNotNull(
            engine().pinMessageCard(
                messageId = "message-changed",
                title = "会变化的卡片",
                code = "<div>changed</div>",
                isSvg = false,
                dataJson = """{"v":1}""",
                refreshIntervalMinutes = 15,
            ),
        )
        refresher.afterRefresh = {
            assertTrue(engine.setRefreshPolicy(card.id, 0, "用户已关闭"))
        }

        val outcome = engine.refreshDue(at = 5_000L).single()

        assertTrue(outcome is SpaceRefreshOutcome.Skipped)
        assertEquals("""{"v":1}""", writer.data[card.id])
        assertEquals(0, database.cardEntityDao().getById(card.id)?.refreshIntervalMinutes)
    }

    @Test
    fun refreshDoesNotOverwriteCardDataChangedWhileModelRuns() = runTest {
        val engine = engine()
        val card = requireNotNull(
            engine.pinMessageCard(
                messageId = "message-data-changed",
                title = "数据会变化的卡片",
                code = "<div>data</div>",
                isSvg = false,
                dataJson = """{"v":1}""",
                refreshIntervalMinutes = 15,
            ),
        )
        refresher.afterRefresh = { writer.write(card.id, """{"v":"user"}""") }

        val outcome = engine.refreshDue(at = 5_000L).single()

        assertTrue(outcome is SpaceRefreshOutcome.Skipped)
        assertEquals("""{"v":"user"}""", writer.data[card.id])
        assertEquals(0L, database.cardEntityDao().getById(card.id)?.lastRefreshedAt)
    }

    @Test
    fun refreshNowWorksEvenWhenAutoRefreshIsDisabled() = runTest {
        val card = requireNotNull(
            engine().pinMessageCard(
                messageId = "message-manual-now",
                title = "手动刷新",
                code = "<div>manual</div>",
                isSvg = false,
                dataJson = """{"v":1}""",
                refreshIntervalMinutes = 0,
            ),
        )
        refresher.reply = """```json
{"v":3}
```"""

        val outcome = engine().refreshNow(card.id)

        assertTrue(outcome is SpaceRefreshOutcome.Refreshed)
        assertEquals("""{"v":3}""", writer.data[card.id])
        assertEquals(1_000L, database.cardEntityDao().getById(card.id)?.lastRefreshedAt)
    }

    @Test
    fun invalidRefreshJsonIsRejectedWithoutTouchingTheCard() = runTest {
        val card = requireNotNull(
            engine().pinMessageCard(
                messageId = "message-invalid",
                title = "坏数据",
                code = "<div>invalid</div>",
                isSvg = false,
                dataJson = """{"v":1}""",
                refreshIntervalMinutes = 10,
            ),
        )
        refresher.reply = "这里不是 JSON"

        val outcome = engine().refreshNow(card.id)

        val failure = requireNotNull(outcome as? SpaceRefreshOutcome.Failed)
        assertEquals("invalid json", failure.reason)
        assertEquals("""{"v":1}""", writer.data[card.id])
        assertEquals(0L, database.cardEntityDao().getById(card.id)?.lastRefreshedAt)
    }

    @Test
    fun normalizeRefreshJsonAcceptsFencesAndRejectsProse() {
        assertEquals(
            """{"v":1}""",
            AgentSpaceEngine.normalizeRefreshJson(
                """说明
```json
{"v":1}
```
结束""",
            ),
        )
        assertEquals(
            """[1,2]""",
            AgentSpaceEngine.normalizeRefreshJson(
                """```
[1,2]
```""",
            ),
        )
        assertNull(AgentSpaceEngine.normalizeRefreshJson("没有 JSON"))
    }

    @Test
    fun deriveSpaceTitlePrefersTitleThenHeading() {
        assertEquals("支出看板", AgentSpaceEngine.deriveSpaceTitle("<html><title>支出看板</title></html>", false))
        assertEquals("本周计划", AgentSpaceEngine.deriveSpaceTitle("<div><h1>本周计划</h1></div>", false))
        assertEquals("SVG", AgentSpaceEngine.deriveSpaceTitle("<svg></svg>", true))
    }
}

private class FakeSpaceDataWriter : SpaceCardDataWriter {
    val data = linkedMapOf<String, String>()
    private val revisions = mutableMapOf<String, Long>()

    override fun read(cardId: String): String? = data[cardId]

    override fun snapshot(cardId: String): SpaceCardDataSnapshot = SpaceCardDataSnapshot(data[cardId], revisions[cardId] ?: 0L)

    override suspend fun write(cardId: String, jsonText: String) {
        data[cardId] = jsonText
        revisions[cardId] = (revisions[cardId] ?: 0L) + 1L
    }

    override suspend fun compareAndWrite(cardId: String, expected: SpaceCardDataSnapshot, jsonText: String): Long? {
        if (snapshot(cardId) != expected) return null
        write(cardId, jsonText)
        return snapshot(cardId).revision
    }

    override suspend fun compareAndRestore(cardId: String, expectedRevision: Long, previous: SpaceCardDataSnapshot): Boolean {
        if ((revisions[cardId] ?: 0L) != expectedRevision) return false
        if (previous.data == null) data.remove(cardId) else data[cardId] = previous.data
        revisions[cardId] = expectedRevision + 1L
        return true
    }
}

private class FakeSpaceRefresher : SpaceCardRefresher {
    var reply: String? = """{"v":2}"""
    var failure: Exception? = null
    val requests = mutableListOf<String>()
    var afterRefresh: (suspend () -> Unit)? = null

    override suspend fun refresh(card: CardEntity, previousData: String?): String? {
        requests += card.id
        failure?.let { throw it }
        afterRefresh?.invoke()
        return reply
    }
}
