package io.zer0.muse.miniphone.data.roam

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
 * 漫游门面：出发覆盖、读位置、记路人、遇见生成、按位置清空。
 *
 * 最要紧的一条是「同一角色换地点是覆盖不是新增」—— characterId 是主键，
 * 若这里写成追加，一个角色就会同时出现在好几个地方，漫游的前提就塌了。
 * 其次是「遇见拿不到结果就不写」，否则用户会把编出来的招呼当成真遇到过的人。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpRoamStoreTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var host: RoamSceneHost
    private lateinit var store: MpRoamStore
    private var seq = 0

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        host = RoamSceneHost()
        seq = 0
        store = MpRoamStore(db, now = { 1_000L }, newId = { "id-${seq++}" })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `出发后能读到当前位置且地点名兜底成地点id`() = runBlocking {
        assertTrue(store.travel("c1", "  咖啡馆  ", note = " 靠窗 "))
        val state = store.currentLocation("c1")
        assertEquals("咖啡馆", state?.locationName)
        assertEquals("咖啡馆", state?.locationId)
        assertEquals("靠窗", state?.note)
    }

    @Test
    fun `同一角色换地点是覆盖不是新增`() = runBlocking {
        store.travel("c1", "咖啡馆")
        store.travel("c1", "书店")
        assertEquals("书店", store.currentLocation("c1")?.locationName)
        val all = store.observeLocations().first()
        assertEquals(1, all.size)
        assertEquals(1, all.count { it.characterId == "c1" })
    }

    @Test
    fun `空白地点名不落库`() = runBlocking {
        assertFalse(store.travel("c1", "   "))
        assertNull(store.currentLocation("c1"))
        assertEquals(0, store.observeLocations().first().size)
    }

    @Test
    fun `多个角色各在一处都进列表`() = runBlocking {
        store.travel("c1", "咖啡馆")
        store.travel("c2", "书店")
        assertEquals(2, store.observeLocations().first().size)
        assertEquals("咖啡馆", store.currentLocation("c1")?.locationName)
        assertEquals("书店", store.currentLocation("c2")?.locationName)
    }

    @Test
    fun `记录路人能按位置读到且空名字不写`() = runBlocking {
        val id = store.recordNpc("loc", "  阿岚  ", "性子冷")
        assertNotNull(id)
        val npcs = store.observeNpcs("loc").first()
        assertEquals(1, npcs.size)
        assertEquals("阿岚", npcs.first().name)
        assertNull(store.recordNpc("loc", "   "))
        assertEquals(1, store.observeNpcs("loc").first().size)
    }

    @Test
    fun `遇见拿不到结果就不写 不编一句招呼`() = runBlocking {
        host.scene = null
        val id = store.encounter(host, "c1", "loc", "咖啡馆")
        assertNull(id)
        assertEquals(0, store.observeNpcs("loc").first().size)
    }

    @Test
    fun `遇见拿到结果就落一位只属于这个位置的路人`() = runBlocking {
        host.scene = "阿岚\n他性子冷，抬眼说了句：让一下。"
        val id = store.encounter(host, "c1", "loc", "咖啡馆")
        assertNotNull(id)
        val npc = store.observeNpcs("loc").first().single()
        assertEquals("阿岚", npc.name)
        assertTrue(npc.persona.contains("性子冷"))
        assertEquals("", npc.characterId)
    }

    @Test
    fun `按位置清路人只清这一带`() = runBlocking {
        store.recordNpc("locA", "甲")
        store.recordNpc("locB", "乙")
        store.clearNpcs("locA")
        assertEquals(0, store.observeNpcs("locA").first().size)
        assertEquals(1, store.observeNpcs("locB").first().size)
    }
}

/** 只实现漫游用得到的那一条生成桥；其余能力按「没有」处理。 */
private class RoamSceneHost : MiniPhoneHost {
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
