package io.zer0.muse.miniphone.data.chat

import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 建群与群名的规则。
 *
 * 库里跑真 Room(内存库):「一条群会话 + 若干成员行」到底有没有一起写进去、
 * 顺序靠不靠得住,只有真跑一遍 SQL 才看得出来。群名与「能不能按完成」是纯规则,单独断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpGroupChatStoreTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var store: MpGroupChatStore

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        store = MpGroupChatStore(
            db = db,
            threadDao = db.threadDao(),
            memberDao = db.threadMemberDao(),
            messageDao = db.messageDao(),
            now = { JOINED_AT },
            newId = { GROUP_THREAD_ID },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun selectingThreeCharactersWritesOneGroupThreadAndThreeMembers() = runBlocking {
        val threadId = store.createGroup(listOf("c1", "c2", "c3"), groupTitleOf(listOf("阿远", "小满", "阿吾")))

        assertEquals(GROUP_THREAD_ID, threadId)
        val thread = requireNotNull(db.threadDao().getThread(GROUP_THREAD_ID))
        assertTrue("会话是群聊", thread.isGroup)
        assertEquals("群名就是拼接结果", "阿远、小满、阿吾", thread.title)
        assertEquals("要找人开口时用第一个成员", "c1", thread.characterId)

        val members = db.threadMemberDao().observeMembers(GROUP_THREAD_ID).first()
        assertEquals("成员表三行", 3, members.size)
        assertEquals("顺序就是选择顺序", listOf("c1", "c2", "c3"), members.map { it.characterId })
        assertEquals("入群时间逐行递增", listOf(JOINED_AT, JOINED_AT + 1, JOINED_AT + 2), members.map { it.joinedAt })
        assertEquals("一个群只占一条会话", 1, db.threadDao().observeThreads().first().size)
    }

    @Test
    fun fewerThanTwoMembersCreatesNothing() = runBlocking {
        assertNull("一个人不成群", store.createGroup(listOf("c1"), "阿远、我"))
        assertNull("一个人重复两次也不成", store.createGroup(listOf("c1", "c1"), "阿远、我"))
        assertNull("空名单不成群", store.createGroup(emptyList(), "我"))

        assertTrue("会话表没被动过", db.threadDao().observeThreads().first().isEmpty())
        assertEquals("成员表也没被动过", 0, db.threadMemberDao().countInThread(GROUP_THREAD_ID))
    }

    @Test
    fun groupTitleJoinsSelectedNamesAndSkipsBlanks() {
        assertEquals("阿远、小满", groupTitleOf(listOf("阿远", "小满")))
        assertEquals("只有一个也照实写", "阿远", groupTitleOf(listOf(" 阿远 ")))
        assertEquals("三个还是逐个列", "阿远、小满、阿吾", groupTitleOf(listOf("阿远", "小满", "阿吾")))
        assertEquals("没有名字的人不占一个位置", "阿远、小满、阿吾", groupTitleOf(listOf("阿远", "", "小满", "  ", "阿吾")))
        assertEquals("没有名字就交给界面兜底", "", groupTitleOf(listOf("", "  ")))
    }

    @Test
    fun groupTitleFoldsLongMemberListsIntoACount() {
        val names = listOf("阿远", "小满", "阿吾", "阿岑", "阿迟")

        assertEquals("超过三个人只列前两个,数字是全部角色数", "阿远、小满等 5 人", groupTitleOf(names))
        assertEquals("刚好四个也开始收", "阿远、小满等 4 人", groupTitleOf(names.take(4)))
        assertEquals("收口写法里的 N 不含本机用户", "等 5 人", groupTitleTailOf(5))
    }

    @Test
    fun finishIsOnlyAllowedFromTwoSelectedCharactersOn() {
        assertEquals(false, canCreateGroup(0))
        assertEquals(false, canCreateGroup(1))
        assertEquals(true, canCreateGroup(2))
        assertEquals(true, canCreateGroup(3))
    }

    @Test
    fun aGroupDoesNotStealTheDirectMessageOfItsFirstMember() = runBlocking {
        db.threadDao().upsert(MpThreadEntity(id = "t-1v1", characterId = "c1", lastMessageAt = 1_000L))
        store.createGroup(listOf("c1", "c2"), "阿远、小满")

        assertEquals("按角色找会话只认私信", "t-1v1", db.threadDao().findThreadByCharacter("c1")?.id)
        assertNull("群里的第二个人本来就没有私信", db.threadDao().findThreadByCharacter("c2"))
    }

    @Test
    fun renamingWritesTheTitleAndMarksItCustomized() = runBlocking {
        store.createGroup(listOf("c1", "c2"), "阿远、小满")

        assertTrue(store.renameGroup(GROUP_THREAD_ID, "  周末小队  "))

        val thread = requireNotNull(db.threadDao().getThread(GROUP_THREAD_ID))
        assertEquals("首尾空白去掉后写入", "周末小队", thread.title)
        assertTrue("用户改过名", thread.titleCustomized)
    }

    @Test
    fun blankTitleIsRejectedAndTooLongIsCut() = runBlocking {
        store.createGroup(listOf("c1", "c2"), "阿远、小满")

        assertFalse("空名不许保存", store.renameGroup(GROUP_THREAD_ID, "   "))
        val untouched = requireNotNull(db.threadDao().getThread(GROUP_THREAD_ID))
        assertEquals("空名不覆盖原群名", "阿远、小满", untouched.title)
        assertFalse("空名也不该把群标成改过名", untouched.titleCustomized)

        val tooLong = "一二三四五六七八九十".repeat(3)
        assertTrue(store.renameGroup(GROUP_THREAD_ID, tooLong))
        val cut = requireNotNull(db.threadDao().getThread(GROUP_THREAD_ID)).title
        assertEquals("截到 20 字", GROUP_TITLE_MAX_LENGTH, cut.length)
        assertEquals(tooLong.take(GROUP_TITLE_MAX_LENGTH), cut)
    }

    @Test
    fun addingMembersSkipsThoseAlreadyInAndRecomputesTheDefaultTitle() = runBlocking {
        store.createGroup(listOf("c1", "c2"), "阿远、小满")

        assertTrue(store.addMembers(GROUP_THREAD_ID, listOf("c2", "c3", "c3"), autoTitle = "阿远、小满、阿吾"))

        assertEquals("重复与已在群里的都跳过", listOf("c1", "c2", "c3"), memberIds())
        assertEquals("没改过名的群跟着重算", "阿远、小满、阿吾", db.threadDao().getThread(GROUP_THREAD_ID)?.title)
        assertFalse("重算不算用户改名", db.threadDao().getThread(GROUP_THREAD_ID)?.titleCustomized ?: true)
    }

    @Test
    fun addingMembersLeavesACustomizedTitleAlone() = runBlocking {
        store.createGroup(listOf("c1", "c2"), "阿远、小满")
        store.renameGroup(GROUP_THREAD_ID, "周末小队")

        assertTrue(store.addMembers(GROUP_THREAD_ID, listOf("c3"), autoTitle = null))

        assertEquals("改过的名字不被动", "周末小队", db.threadDao().getThread(GROUP_THREAD_ID)?.title)
        assertEquals(3, db.threadMemberDao().countInThread(GROUP_THREAD_ID))
    }

    @Test
    fun removingAMemberDropsTheRowButKeepsHisWords() = runBlocking {
        store.createGroup(listOf("c1", "c2", "c3"), "阿远、小满、阿吾")
        db.messageDao().insert(
            MpMessageEntity(
                id = "said-1",
                threadId = GROUP_THREAD_ID,
                characterId = "c2",
                sender = MpActorType.CHARACTER,
                type = MpMessageType.TEXT,
                content = "我说过的",
                createdAt = 1L,
            ),
        )

        assertTrue(store.removeMember(GROUP_THREAD_ID, "c2", autoTitle = "阿远、阿吾"))

        assertEquals(listOf("c1", "c3"), memberIds())
        assertEquals("他说过的话留在群里", 1, db.messageDao().countInThread(GROUP_THREAD_ID))
        assertEquals("默认名跟着人数重算", "阿远、阿吾", db.threadDao().getThread(GROUP_THREAD_ID)?.title)
    }

    @Test
    fun theLastMemberCannotBeRemoved() = runBlocking {
        store.createGroup(listOf("c1", "c2"), "阿远、小满")
        assertTrue(store.removeMember(GROUP_THREAD_ID, "c2", autoTitle = "阿远"))

        assertFalse("只剩一个人时不许再移", store.removeMember(GROUP_THREAD_ID, "c1", autoTitle = ""))

        assertEquals(1, db.threadMemberDao().countInThread(GROUP_THREAD_ID))
        assertEquals("群行还在", GROUP_THREAD_ID, db.threadDao().getThread(GROUP_THREAD_ID)?.id)
    }

    @Test
    fun removingTheOpeningCharacterHandsTheRoleToTheNextMember() = runBlocking {
        store.createGroup(listOf("c1", "c2", "c3"), "阿远、小满、阿吾")

        assertTrue(store.removeMember(GROUP_THREAD_ID, "c1", autoTitle = null))

        assertEquals("群里还得有个人能开口", "c2", db.threadDao().getThread(GROUP_THREAD_ID)?.characterId)
    }

    @Test
    fun disbandingClearsThreadMembersAndMessages() = runBlocking {
        store.createGroup(listOf("c1", "c2"), "阿远、小满")
        db.messageDao().insert(
            MpMessageEntity(
                id = "msg-1",
                threadId = GROUP_THREAD_ID,
                characterId = "c1",
                sender = MpActorType.CHARACTER,
                type = MpMessageType.TEXT,
                content = "在吗",
                createdAt = 1L,
            ),
        )

        assertTrue(store.disbandGroup(GROUP_THREAD_ID))

        assertNull("会话没了", db.threadDao().getThread(GROUP_THREAD_ID))
        assertEquals("成员没了", 0, db.threadMemberDao().countInThread(GROUP_THREAD_ID))
        assertEquals("消息没了", 0, db.messageDao().countInThread(GROUP_THREAD_ID))
        assertTrue("列表里也不剩", db.threadDao().observeThreads().first().isEmpty())
    }

    @Test
    fun privateThreadsCannotBeManaged() = runBlocking {
        db.threadDao().upsert(MpThreadEntity(id = "t-dm", characterId = "c1", lastMessageAt = 1L))

        assertFalse(store.renameGroup("t-dm", "名字"))
        assertFalse(store.addMembers("t-dm", listOf("c2"), autoTitle = "x"))
        assertFalse(store.removeMember("t-dm", "c1", autoTitle = null))
        assertFalse(store.disbandGroup("t-dm"))

        assertEquals("私信行一点没动", "", db.threadDao().getThread("t-dm")?.title)
        assertTrue(db.threadMemberDao().membersOf("t-dm").isEmpty())
    }

    @Test
    fun normalizeCutsBlanksAndOverlongNames() {
        assertEquals("周末小队", normalizeGroupTitle("  周末小队  "))
        assertEquals("", normalizeGroupTitle("   "))
        assertEquals(20, normalizeGroupTitle("一".repeat(30)).length)
    }

    private suspend fun memberIds(): List<String> = db.threadMemberDao().membersOf(GROUP_THREAD_ID).map { member -> member.characterId }

    private companion object {
        const val GROUP_THREAD_ID: String = "t-group"
        const val JOINED_AT: Long = 5_000L
    }
}
