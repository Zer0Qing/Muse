package io.zer0.muse.data.terms

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
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
 * v2.7.x(E2): 术语累计与用户动作。
 *
 * 钉的都是"用户已经给出的信息不许被系统自己弄丢"这类行为:
 *  - 复现要累加次数(阈值决定是否打扰用户);
 *  - 确认过的不能被下一次复现打回候选;
 *  - 已知解释不能被后来的裸候选清空;
 *  - 提升助手级时同名词只留一条(留两条会在注入时随机挤掉一条)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionTermStoreTest {

    private lateinit var db: MuseDb
    private lateinit var dao: SessionTermDao
    private lateinit var store: SessionTermStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.sessionTermDao()
        store = SessionTermStore(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun hit(term: String, meaning: String? = null) = SessionTerms.TermHit(term, meaning)

    @Test
    fun `复现累加次数并刷新最近出现时间`() = runTest {
        store.recordObserved("s-1", "default", listOf(hit("开黑", "一起打排位")), now = 1_000L)
        store.recordObserved("s-1", "default", listOf(hit("开黑")), now = 2_000L)

        val saved = dao.find(SessionTermEntity.SCOPE_SESSION, "s-1", "开黑")
        assertNotNull(saved)
        saved!!
        assertEquals(2, saved.occurrences)
        assertEquals(2_000L, saved.lastSeenAt)
        assertEquals(1_000L, saved.firstSeenAt)
        assertEquals("一起打排位", saved.meaning)
    }

    @Test
    fun `裸候选不许把已知解释清空`() = runTest {
        store.recordObserved("s-1", "default", listOf(hit("双推", "两次灰度发布")), now = 1L)
        store.recordObserved("s-1", "default", listOf(hit("双推")), now = 2L)

        assertEquals("两次灰度发布", dao.find(SessionTermEntity.SCOPE_SESSION, "s-1", "双推")!!.meaning)
    }

    @Test
    fun `确认过的词条不被后续复现打回候选`() = runTest {
        store.recordObserved("s-1", "default", listOf(hit("拉条子")), now = 1L)
        val id = dao.find(SessionTermEntity.SCOPE_SESSION, "s-1", "拉条子")!!.id
        assertTrue(store.confirm(id, "列周报"))

        store.recordObserved("s-1", "default", listOf(hit("拉条子")), now = 5L)

        val after = dao.find(SessionTermEntity.SCOPE_SESSION, "s-1", "拉条子")!!
        assertTrue("用户确认是既成事实,复现只能加次数", after.confirmed)
        assertEquals("确认前后各复现一次,共两次", 2, after.occurrences)
        assertEquals("列周报", after.meaning)
    }

    @Test
    fun `不同会话各自一行_作用域默认在会话内`() = runTest {
        store.recordObserved("s-1", "default", listOf(hit("开黑")), now = 1L)
        store.recordObserved("s-2", "default", listOf(hit("开黑")), now = 1L)

        assertEquals(1, dao.listForScope(SessionTermEntity.SCOPE_SESSION, "s-1").size)
        assertEquals(1, dao.listForScope(SessionTermEntity.SCOPE_SESSION, "s-2").size)
    }

    @Test
    fun `注入取用已确认优先且限五条`() = runTest {
        // 三个候选高频 + 两个已确认低频:顺序必须让确认的先入,而不是让次数说话
        (1..4).forEach { _ -> store.recordObserved("s-1", "default", listOf(hit("候选甲")), now = 1L) }
        (1..3).forEach { _ -> store.recordObserved("s-1", "default", listOf(hit("候选乙")), now = 1L) }
        listOf("确定甲", "确定乙").forEach { term ->
            store.recordObserved("s-1", "default", listOf(hit(term)), now = 2L)
        }
        dao.listForScope(SessionTermEntity.SCOPE_SESSION, "s-1").forEach { entity ->
            if (entity.term.startsWith("确定")) store.confirm(entity.id, "解释" + entity.term)
        }
        store.recordObserved("s-1", "default", listOf(hit("候选丙")), now = 3L)
        store.recordObserved("s-1", "default", listOf(hit("候选丁")), now = 3L)

        val picked = store.termsForContext("s-1", "default")

        assertTrue("最多 5 条: ${picked.map { it.term }}", picked.size <= SessionTerms.MAX_INJECT)
        // 两条都已确认、次数相同,彼此不该有先后 —— 断言"前两条是已确认的那两条",而不是它们的顺序
        assertEquals(
            "已确认的必须排在候选之前",
            setOf("确定甲", "确定乙"),
            picked.take(2).map { it.term }.toSet(),
        )
        assertTrue(
            "后面的位置才轮到未确认的高频候选",
            picked.drop(2).all { term -> !term.confirmed },
        )
    }

    @Test
    fun `提升助手级后同名词只留一条`() = runTest {
        store.recordObserved("s-1", "a-1", listOf(hit("开黑", "一起打排位")), now = 1L)
        store.recordObserved("s-2", "a-1", listOf(hit("开黑", "组队打游戏")), now = 1L)
        val first = dao.find(SessionTermEntity.SCOPE_SESSION, "s-1", "开黑")!!
        val second = dao.find(SessionTermEntity.SCOPE_SESSION, "s-2", "开黑")!!

        assertTrue(store.promoteToAssistant(first.id))
        assertTrue(store.promoteToAssistant(second.id))

        val assistantRows = dao.listForScope(SessionTermEntity.SCOPE_ASSISTANT, null)
        assertEquals("两个会话提升同一个词,助手级只能有一行", 1, assistantRows.size)
        assertEquals("先立的那条保留它的解释(可能被用户改过)", "一起打排位", assistantRows.single().meaning)
    }

    @Test
    fun `等待确认的计数用同一个阈值`() = runTest {
        store.recordObserved("s-1", "default", listOf(hit("开黑")), now = 1L)
        store.recordObserved("s-1", "default", listOf(hit("开黑")), now = 1L)
        assertEquals("两次还够不上打扰用户", 0, store.countAwaitingConfirmation())

        store.recordObserved("s-1", "default", listOf(hit("开黑")), now = 1L)
        assertEquals(1, store.countAwaitingConfirmation())

        val row = dao.find(SessionTermEntity.SCOPE_SESSION, "s-1", "开黑")!!
        store.confirm(row.id)
        assertEquals("确认过就该从待办里消失", 0, store.countAwaitingConfirmation())
    }

    @Test
    fun `删除条目与会话清理`() = runTest {
        store.recordObserved("s-1", "default", listOf(hit("开黑"), hit("双推")), now = 1L)
        val row = dao.find(SessionTermEntity.SCOPE_SESSION, "s-1", "开黑")!!

        assertTrue(store.remove(row.id))
        assertNull(dao.find(SessionTermEntity.SCOPE_SESSION, "s-1", "开黑"))
        assertFalse("删不存在的条目要说 false,而不是假装成功", store.remove(row.id))

        assertEquals(1, store.deleteForSession("s-1"))
        assertTrue(dao.listForScope(SessionTermEntity.SCOPE_SESSION, "s-1").isEmpty())
    }
}
