package io.zer0.muse.miniphone.data.relation

import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 关系网门面：记看法、删看法、按方向读、让角色自述。
 *
 * 最关键的两条是「单向不等于双向」与「同向覆盖不重复」——
 * 关系是有向边，把方向搞混或把一次改口攒成两条，关系网就不可信了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpRelationStoreTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var host: SceneHost
    private lateinit var store: MpRelationStore
    private var seq = 0
    private var clock = 1_000L

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        host = SceneHost()
        seq = 0
        clock = 1_000L
        store = MpRelationStore(db, host, now = { clock }, newId = { "id-${seq++}" })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `记一条看法后两个端点各自能读到`() = runBlocking {
        store.setEdge("c1", "c2", "挚友", note = "一起长大")
        val from = store.observeFrom("c1").first()
        assertEquals(1, from.size)
        assertEquals("c2", from[0].toId)
        assertEquals("挚友", from[0].label)
        assertEquals("一起长大", from[0].note)
        val to = store.observeTo("c2").first()
        assertEquals(listOf(from[0].id), to.map { it.id })
        assertEquals(1, store.observeAll().first().size)
    }

    @Test
    fun `单向不等于双向 反方向读不到`() = runBlocking {
        store.setEdge("c1", "c2", "挚友")
        assertEquals(0, store.observeFrom("c2").first().size)
        assertEquals(0, store.observeTo("c1").first().size)
        assertNull(db.relationEdgeDao().get("c2", "c1"))
    }

    @Test
    fun `同向覆盖更新不攒两条`() = runBlocking {
        store.setEdge("c1", "c2", "挚友")
        val firstId = store.observeFrom("c1").first()[0].id
        clock = 2_000L
        store.setEdge("c1", "c2", "宿敌", note = "闹翻了")
        val rows = store.observeFrom("c1").first()
        assertEquals(1, rows.size)
        assertEquals(firstId, rows[0].id)
        assertEquals("宿敌", rows[0].label)
        assertEquals("闹翻了", rows[0].note)
        assertEquals(2_000L, rows[0].updatedAt)
        assertEquals(1, store.observeAll().first().size)
    }

    @Test
    fun `两个方向各存一条互不影响`() = runBlocking {
        store.setEdge("c1", "c2", "挚友")
        store.setEdge("c2", "c1", "陌生人")
        assertEquals(2, store.observeAll().first().size)
        assertEquals("挚友", store.observeFrom("c1").first()[0].label)
        assertEquals("陌生人", store.observeFrom("c2").first()[0].label)
        assertTrue(store.observeFrom("c1").first()[0].id != store.observeFrom("c2").first()[0].id)
    }

    @Test
    fun `空白称呼不落库 也不会抹掉既有记录`() = runBlocking {
        store.setEdge("c1", "c2", "   ")
        assertEquals(0, store.observeAll().first().size)
        store.setEdge("c1", "c2", "挚友")
        store.setEdge("c1", "c2", "  ")
        val rows = store.observeFrom("c1").first()
        assertEquals(1, rows.size)
        assertEquals("挚友", rows[0].label)
    }

    @Test
    fun `删一条只删那一条`() = runBlocking {
        store.setEdge("c1", "c2", "挚友")
        store.setEdge("c1", "c3", "同事")
        val target = store.observeFrom("c1").first().first { it.toId == "c2" }
        store.deleteEdge(target.id)
        assertNull(db.relationEdgeDao().get("c1", "c2"))
        val rows = store.observeFrom("c1").first()
        assertEquals(1, rows.size)
        assertEquals("c3", rows[0].toId)
    }

    @Test
    fun `按角色删全部时两个方向都清掉`() = runBlocking {
        store.setEdge("c1", "c2", "挚友")
        store.setEdge("c2", "c1", "陌生人")
        store.setEdge("c2", "c3", "同事")
        store.deleteByCharacter("c1")
        val rows = store.observeAll().first()
        assertEquals(1, rows.size)
        assertEquals("c2", rows[0].fromId)
        assertEquals("c3", rows[0].toId)
    }

    @Test
    fun `拿到自述短语就记成看法`() = runBlocking {
        host.scene = "竞争对手"
        store.describeEdge("c1", "c2", "阿离", "小满")
        val rows = store.observeFrom("c1").first()
        assertEquals(1, rows.size)
        assertEquals("竞争对手", rows[0].label)
        assertEquals("c2", rows[0].toId)
    }

    @Test
    fun `拿不到自述就不写不编 既有记录保持原样`() = runBlocking {
        host.scene = null
        store.describeEdge("c1", "c2", "阿离", "小满")
        assertEquals(0, store.observeAll().first().size)
        store.setEdge("c1", "c2", "挚友")
        store.describeEdge("c1", "c2", "阿离", "小满")
        val rows = store.observeFrom("c1").first()
        assertEquals(1, rows.size)
        assertEquals("挚友", rows[0].label)
    }

    @Test
    fun `自述覆盖称呼但保留既有备注`() = runBlocking {
        store.setEdge("c1", "c2", "挚友", note = "一起长大")
        host.scene = "竞争对手"
        store.describeEdge("c1", "c2", "阿离", "小满")
        val rows = store.observeFrom("c1").first()
        assertEquals(1, rows.size)
        assertEquals("竞争对手", rows[0].label)
        assertEquals("一起长大", rows[0].note)
    }
}

/** 只实现关系网用得到的那一条生成桥；其余能力按「没有」处理。 */
private class SceneHost : MiniPhoneHost {
    var scene: String? = null

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(emptyList())

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = ""))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flowOf(MpReplyEvent.Failed)

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun composeScene(characterId: String, brief: String): String? = scene

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}
