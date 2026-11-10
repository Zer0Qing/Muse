package io.zer0.muse.miniphone.data.roleplay

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 跑团门面：剧本、存档、推进、结局与级联删除。
 *
 * 关键约束是一次存档至多一个结局；生成失败时局面必须原封不动，
 * 不能靠多写一条空结局或凭空加回合来伪装成功。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpRoleplayStoreTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var host: SceneHost
    private lateinit var store: MpRoleplayStore
    private var seq = 0
    private var clock = 1_000L

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        host = SceneHost()
        seq = 0
        clock = 1_000L
        store = MpRoleplayStore(db, host, now = { clock++ }, newId = { "id-${seq++}" }, rollSource = { 17 })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun createScript(title: String = "雾港", body: String = "午夜抵达"): String =
        store.createScript(title, body) ?: error("有效剧本没有落库")

    private fun stateOf(json: String): MpRoleplayState = MpRoleplayState.fromJson(json) ?: error("局面快照无法解析")

    @Test
    fun `空白标题或正文不落库`() = runBlocking {
        assertEquals(null, store.createScript("   ", "正文"))
        assertEquals(null, store.createScript("标题", "   "))
        assertEquals(0, db.rpScriptDao().observeAll().first().size)
    }

    @Test
    fun `创建与修改都只存去掉首尾空白的内容`() = runBlocking {
        val scriptId = createScript("  雨夜列车  ", "  规则一  ")
        val created = db.rpScriptDao().get(scriptId) ?: error("剧本没有落库")
        assertEquals("雨夜列车", created.title)
        assertEquals("规则一", created.body)

        assertTrue(store.updateScript(scriptId, "  雾港车站  ", "  规则二  "))
        val updated = db.rpScriptDao().get(scriptId) ?: error("剧本没有更新")
        assertEquals("雾港车站", updated.title)
        assertEquals("规则二", updated.body)
        assertTrue(updated.updatedAt > created.updatedAt)
    }

    @Test
    fun `开局把剧本和角色写进第零回合`() = runBlocking {
        val scriptId = createScript()
        val saveId = store.startRun(scriptId, " c1 ", " ") ?: error("开局失败")
        val save = db.rpSaveDao().get(saveId) ?: error("存档没有落库")
        assertEquals(scriptId, save.scriptId)
        assertEquals("雾港", save.title)
        assertEquals(0, save.turn)
        val state = stateOf(save.stateJson)
        assertEquals("c1", state.characterId)
        assertEquals("午夜抵达", state.opening)
        assertTrue(state.turns.isEmpty())
    }

    @Test
    fun `推进成功后同时更新回合快照与骰果`() = runBlocking {
        val scriptId = createScript()
        val saveId = store.startRun(scriptId, "c1") ?: error("开局失败")
        host.scene = "汽笛穿过浓雾，月台尽头亮起一盏灯。"

        assertTrue(store.advance(saveId, "  走向灯火  "))
        val save = db.rpSaveDao().get(saveId) ?: error("推进后存档消失")
        val state = stateOf(save.stateJson)
        assertEquals(1, save.turn)
        val turn = state.turns.single()
        assertEquals(1, turn.turn)
        assertEquals("走向灯火", turn.action)
        assertEquals(17, turn.roll)
        assertEquals("汽笛穿过浓雾，月台尽头亮起一盏灯。", turn.text)
    }

    @Test
    fun `生成失败时不推进回合也不改写快照`() = runBlocking {
        val scriptId = createScript()
        val saveId = store.startRun(scriptId, "c1") ?: error("开局失败")
        val before = db.rpSaveDao().get(saveId) ?: error("存档没有落库")
        host.scene = null

        assertFalse(store.advance(saveId, "推开车门"))
        val after = db.rpSaveDao().get(saveId) ?: error("存档没有落库")
        assertEquals(before, after)
        assertEquals(1, host.briefs.size)
    }

    @Test
    fun `同一存档至多一个结局且重复结案覆盖旧结局`() = runBlocking {
        val scriptId = createScript()
        val saveId = store.startRun(scriptId, "c1") ?: error("开局失败")
        host.scene = "她在末班车上睡了过去。"
        val firstEndingId = store.conclude(saveId) ?: error("第一次结案失败")
        host.scene = "末班车改道，她回到了起点。"
        val secondEndingId = store.conclude(saveId) ?: error("第二次结案失败")

        assertEquals(firstEndingId, secondEndingId)
        val endings = store.observeEndings(scriptId).first()
        assertEquals(1, endings.size)
        assertEquals("末班车改道，她回到了起点。", endings.single().text)
        assertEquals("雾港", endings.single().title)
        assertEquals(saveId, endings.single().saveId)
    }

    @Test
    fun `结局生成失败时不凭空写一条`() = runBlocking {
        val scriptId = createScript()
        val saveId = store.startRun(scriptId, "c1") ?: error("开局失败")
        host.scene = " "

        assertEquals(null, store.conclude(saveId))
        assertEquals(0, store.observeEndings(scriptId).first().size)
        assertEquals(null, db.rpEndingDao().forSave(saveId))
    }

    @Test
    fun `同一剧本允许多个不同存档的结局`() = runBlocking {
        val scriptId = createScript()
        val firstSave = store.startRun(scriptId, "c1") ?: error("第一次开局失败")
        val secondSave = store.startRun(scriptId, "c2", "另一局") ?: error("第二次开局失败")
        host.scene = "雾散了。"
        store.conclude(firstSave)
        host.scene = "灯还亮着。"
        store.conclude(secondSave)

        val endings = store.observeEndings(scriptId).first()
        assertEquals(2, endings.size)
        assertTrue(endings.map { it.saveId }.toSet() == setOf(firstSave, secondSave))
    }

    @Test
    fun `删除存档清掉它唯一的结局但保留剧本`() = runBlocking {
        val scriptId = createScript()
        val saveId = store.startRun(scriptId, "c1") ?: error("开局失败")
        host.scene = "故事收束了。"
        store.conclude(saveId)

        store.deleteSave(saveId)
        assertEquals(null, db.rpSaveDao().get(saveId))
        assertEquals(0, store.observeEndings(scriptId).first().size)
        assertTrue(db.rpScriptDao().get(scriptId) != null)
    }

    @Test
    fun `删除剧本级联清掉存档与所有结局`() = runBlocking {
        val scriptId = createScript()
        val otherScriptId = createScript("白塔", "另一场冒险")
        val firstSave = store.startRun(scriptId, "c1") ?: error("第一次开局失败")
        val secondSave = store.startRun(scriptId, "c2") ?: error("第二次开局失败")
        val keptSave = store.startRun(otherScriptId, "c1") ?: error("对照存档开局失败")
        host.scene = "第一个结局。"
        store.conclude(firstSave)
        host.scene = "第二个结局。"
        store.conclude(secondSave)
        host.scene = "保留的结局。"
        store.conclude(keptSave)

        store.deleteScript(scriptId)
        assertEquals(null, db.rpScriptDao().get(scriptId))
        assertEquals(0, store.observeSaves(scriptId).first().size)
        assertEquals(0, store.observeEndings(scriptId).first().size)
        assertEquals(1, store.observeSaves(otherScriptId).first().size)
        assertEquals(1, store.observeEndings(otherScriptId).first().size)
    }
}

/** 只实现跑团需要的一次性生成桥；其余宿主能力按「没有」处理。 */
private class SceneHost : MiniPhoneHost {
    var scene: String? = null
    val briefs = mutableListOf<String>()

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(emptyList())

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = ""))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flowOf(MpReplyEvent.Failed)

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun composeScene(characterId: String, brief: String): String? {
        briefs += brief
        return scene
    }

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}
