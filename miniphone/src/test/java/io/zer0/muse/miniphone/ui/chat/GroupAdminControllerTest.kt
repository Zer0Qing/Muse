package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.chat.MpGroupActions
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadMemberEntity
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
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
 * 群管理四个写入口的规则（小手机 P2b 第二批）。
 *
 * 这几条错了用户会**直接骂**：
 *
 *  - 写库失败却先把群名改了 → 界面闪一下又弹回去，看着像坏了；
 *  - 用户手改过的群名被"自动重算"覆盖 → 精心起的名字没了；
 *  - 加人时没去重 → 同一个人出现两行；
 *  - 私信会话走到群管理路径 → 给一条一对一的会话改了名。
 *
 * 假 store 只记录"被要求做什么、返回什么"，成员表用真库读，
 * 这样 `autoTitle` 里"现在全部成员"是不是真的按库算，能被看见。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GroupAdminControllerTest {

    private companion object {
        const val GROUP_ID = "g1"
        const val DM_ID = "d1"
    }

    private lateinit var db: MiniPhoneDb
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    /** 记录调用的假写入层；[accept] 决定它点不点头。 */
    private class FakeStore(var accept: Boolean = true) : MpGroupActions {
        val renames = mutableListOf<Pair<String, String>>()
        val adds = mutableListOf<Triple<String, List<String>, String?>>()
        val removes = mutableListOf<Triple<String, String, String?>>()
        val disbands = mutableListOf<String>()

        override suspend fun createGroup(memberIds: List<String>, title: String): String? = null

        override suspend fun renameGroup(threadId: String, title: String): Boolean {
            renames += threadId to title
            return accept
        }

        override suspend fun addMembers(threadId: String, characterIds: List<String>, autoTitle: String?): Boolean {
            adds += Triple(threadId, characterIds, autoTitle)
            return accept
        }

        override suspend fun removeMember(threadId: String, characterId: String, autoTitle: String?): Boolean {
            removes += Triple(threadId, characterId, autoTitle)
            return accept
        }

        override suspend fun disbandGroup(threadId: String): Boolean {
            disbands += threadId
            return accept
        }
    }

    @Before
    fun seed() = runBlocking {
        db = newInMemoryMiniPhoneDb()
        db.threadDao().upsert(
            MpThreadEntity(
                id = GROUP_ID,
                characterId = "c1",
                isGroup = true,
                title = "阿远、小满",
                unreadCount = 0,
                lastMessageAt = 1L,
            ),
        )
        db.threadDao().upsert(
            MpThreadEntity(
                id = DM_ID,
                characterId = "c1",
                unreadCount = 0,
                lastMessageAt = 1L,
            ),
        )
        db.threadMemberDao().upsertAll(
            listOf(
                MpThreadMemberEntity(threadId = GROUP_ID, characterId = "c1", joinedAt = 1L),
                MpThreadMemberEntity(threadId = GROUP_ID, characterId = "c2", joinedAt = 2L),
            ),
        )
    }

    @After
    fun tearDown() {
        scope.coroutineContext[Job]?.cancel()
        db.close()
    }

    private fun controller(
        threadId: String = GROUP_ID,
        store: MpGroupActions,
        state: MutableStateFlow<ChatUiState> = MutableStateFlow(ChatUiState(threadId = threadId, isGroup = true)),
        characters: () -> List<MpCharacter> = {
            listOf(
                MpCharacter(id = "c1", name = "阿远"),
                MpCharacter(id = "c2", name = "小满"),
                MpCharacter(id = "c3", name = "大毛"),
            )
        },
    ): Pair<GroupAdminController, MutableStateFlow<ChatUiState>> = GroupAdminController(
        threadId = threadId,
        threadDao = db.threadDao(),
        memberDao = db.threadMemberDao(),
        store = store,
        characters = characters,
        state = state,
        scope = scope,
    ) to state

    private fun settleUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000L
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    @Test
    fun `空群名不写库也不改界面`() {
        val store = FakeStore()
        val (controller, state) = controller(store = store)

        listOf("", "   ", "\n").forEach { raw -> controller.renameGroup(raw) }
        Thread.sleep(150)

        assertTrue("空白名字不该写库: ${store.renames}", store.renames.isEmpty())
        assertEquals("界面上群名不动", "", state.value.groupTitle)
    }

    @Test
    fun `超长群名截断到上限后写入`() {
        val store = FakeStore()
        val (controller, state) = controller(store = store)

        controller.renameGroup("  " + "名".repeat(30) + "  ")
        settleUntil { store.renames.isNotEmpty() }

        val written = store.renames.single().second
        assertEquals("首尾空白去掉、超长截断", 20, written.length)
        assertTrue(written.all { ch -> ch == '名' })
        assertEquals("写成功了界面才跟着换", written, state.value.groupTitle)
    }

    @Test
    fun `写库没成功时界面不能先变一下再退回来`() {
        val store = FakeStore(accept = false)
        val (controller, state) = controller(store = store)

        controller.renameGroup("新群名")
        settleUntil { store.renames.isNotEmpty() }

        assertEquals(1, store.renames.size)
        assertEquals("写失败就别改界面", "", state.value.groupTitle)
    }

    @Test
    fun `加人会自动去重并跳过已在群里的`() {
        val store = FakeStore()
        val (controller, _) = controller(store = store)

        controller.addMembers(listOf("c2", " c3 ", "c3", "", "   ", "c4"))
        settleUntil { store.adds.isNotEmpty() }

        val (threadId, fresh, autoTitle) = store.adds.single()
        assertEquals(GROUP_ID, threadId)
        assertEquals("已在群里的 c2 与空串都滤掉，重复的 c3 只留一次", listOf("c3", "c4"), fresh)
        // 默认名（没手改过）按"现在全部成员"重算：c1、c2、c3、c4 → 超 3 人走「等 N 人」写法
        assertTrue("该带上自动群名，实际=$autoTitle", autoTitle != null && autoTitle.contains("等 4 人"))
    }

    @Test
    fun `用户手改过的群名加人时一个字都不动`() {
        runBlocking {
            db.threadDao().upsert(
                MpThreadEntity(
                    id = GROUP_ID,
                    characterId = "c1",
                    isGroup = true,
                    title = "我们的小群",
                    titleCustomized = true,
                    unreadCount = 0,
                    lastMessageAt = 1L,
                ),
            )
        }
        val store = FakeStore()
        val (controller, state) = controller(store = store)

        controller.addMembers(listOf("c3"))
        settleUntil { store.adds.isNotEmpty() }

        assertNull("改过名就不该再自动重算", store.adds.single().third)
        assertEquals("界面上仍由写入层决定，这里没动", "", state.value.groupTitle)
    }

    @Test
    fun `私信会话走群管理一律不处理`() {
        val store = FakeStore()
        val (controller, _) = controller(threadId = DM_ID, store = store)

        controller.addMembers(listOf("c3"))
        controller.removeMember("c1")
        Thread.sleep(150)

        assertTrue("私信不该被加人: ${store.adds}", store.adds.isEmpty())
        assertTrue("私信不该被移人: ${store.removes}", store.removes.isEmpty())
    }

    @Test
    fun `移人时自动群名按剩下的人算`() {
        val store = FakeStore()
        val (controller, _) = controller(store = store)

        controller.removeMember("c2")
        settleUntil { store.removes.isNotEmpty() }

        val (_, removedId, autoTitle) = store.removes.single()
        assertEquals("c2", removedId)
        // 只剩 c1 一个人：默认名就一个人的名字，不能还挂着两个人的旧名
        assertEquals("阿远", autoTitle)
    }

    @Test
    fun `写失败时移人不改界面上的群名`() {
        val store = FakeStore(accept = false)
        val (controller, state) = controller(store = store)

        controller.removeMember("c2")
        settleUntil { store.removes.isNotEmpty() }

        assertEquals("", state.value.groupTitle)
    }

    @Test
    fun `解散只把意图交给写入层`() {
        val store = FakeStore()
        val (controller, _) = controller(store = store)

        controller.disbandGroup()
        settleUntil { store.disbands.isNotEmpty() }

        assertEquals(listOf(GROUP_ID), store.disbands)
    }

    @Test
    fun `角色被删了也不能让群变成无名群`() {
        // 名单里查不到的成员退化成占位名，而不是整群没有名字
        val store = FakeStore()
        val (controller, _) = controller(store = store, characters = { listOf(MpCharacter(id = "c1", name = "阿远")) })

        controller.addMembers(listOf("ghost"))
        settleUntil { store.adds.isNotEmpty() }

        val autoTitle = store.adds.single().third
        assertTrue("查不到的角色要有占位名: $autoTitle", autoTitle != null && autoTitle.contains("阿远"))
        assertTrue("占位名不能把群变成无名: $autoTitle", !autoTitle.isNullOrBlank())
    }
}
