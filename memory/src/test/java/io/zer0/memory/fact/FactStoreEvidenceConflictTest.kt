package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.6.23(M1/M2): 证据溯源与矛盾裁决回路的单测。
 *
 * 钉住的关键行为:
 *  - add/addBatch 只在调用方给出处时写证据;合并时**已有证据不被覆盖**;
 *  - markDetectedContradictions 幂等,双方各挂一次 pending;
 *  - resolveContradiction 让落选方 supersedes 指向保留方,双方退出 pending,
 *    行不删(记忆页仍可见、可回退),并留下 reason='conflict_resolved' 的修订;
 *  - dismissPendingConflict 只清标记,不动内容。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FactStoreEvidenceConflictTest {

    private lateinit var db: FactDb
    private lateinit var dao: FactDao
    private lateinit var store: FactStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.factDao()
        store = FactStore(dao, db, revisionDao = db.factRevisionDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── M1: 证据 ────────────────────────────────────────────────

    @Test
    fun add_withEvidence_storesQuoteAndMessageId() = runTest {
        val id = store.add(
            FactStore.Fact(
                fact = "用户每年十月去日本滑雪",
                source = "inferred",
                evidenceQuote = "我每年十月都去日本滑雪,已经五年了",
                evidenceMessageId = "msg-42",
            ),
        )

        val stored = store.getById(id)!!
        assertEquals("我每年十月都去日本滑雪,已经五年了", stored.evidenceQuote)
        assertEquals("msg-42", stored.evidenceMessageId)
    }

    @Test
    fun add_withoutEvidence_keepsEmpty() = runTest {
        val id = store.add(FactStore.Fact(fact = "用户习惯早上六点起床"))

        assertNull(store.getById(id)!!.evidenceQuote)
    }

    @Test
    fun merge_intoExisting_backfillsEvidenceOnlyWhenMissing() = runTest {
        val firstId = store.add(FactStore.Fact(fact = "用户养了一只金毛犬,名字叫豆豆"))
        // 前缀通道命中 → 合并进首行;第二行带来出处,应回填
        store.add(
            FactStore.Fact(
                fact = "用户养了一只金毛犬",
                evidenceQuote = "我家里养了一只金毛犬,名字叫豆豆",
                evidenceMessageId = "msg-later",
            ),
        )

        val merged = store.getById(firstId)!!
        assertEquals(
            "已有行原本没有证据时,回填这次带来的出处",
            "我家里养了一只金毛犬,名字叫豆豆",
            merged.evidenceQuote,
        )
        assertEquals("msg-later", merged.evidenceMessageId)
    }

    @Test
    fun evidence_isTruncatedToBudgetAndScrubbed() = runTest {
        val long = "我住在" + "上海" + "浦东新区" + "张江路100号" + "，隔壁有一个很大的公园".repeat(30)
        val id = store.add(
            FactStore.Fact(fact = "用户住在上海浦东新区张江路100号", evidenceQuote = long),
        )

        val quote = store.getById(id)!!.evidenceQuote!!
        assertTrue("证据必须截断到预算内", quote.length <= FactStore.EVIDENCE_MAX_CHARS)
    }

    // ── M2: 矛盾落库与裁决 ──────────────────────────────────────

    private suspend fun seedContradictionPair(): Pair<Long, Long> {
        val likes = store.add(FactStore.Fact(fact = "用户喜欢香菜", entityKey = "香菜"))
        val hates = store.add(FactStore.Fact(fact = "用户讨厌香菜", entityKey = "香菜"))
        // addBatch 的取代仲裁要求 user_explicit 才自动让位;这里双方都是推断,
        // 两条都在场 —— 正是矛盾登记要处理的情形。
        val facts = listOf(likes, hates).mapNotNull { store.getById(it) }
        store.markDetectedContradictions(listOf(facts[0] to facts[1]))
        return likes to hates
    }

    @Test
    fun markDetectedContradictions_setsPendingOnBothSides() = runTest {
        val (a, b) = seedContradictionPair()

        val pending = store.listPendingContradictions("main", "default")
        assertEquals(2, pending.size)
        assertEquals(b, pending.first { it.id == a }.conflictWithId)
        assertEquals(a, pending.first { it.id == b }.conflictWithId)
    }

    @Test
    fun markDetectedContradictions_isIdempotent() = runTest {
        val (a, b) = seedContradictionPair()

        val second = store.markDetectedContradictions(
            listOf(
                store.getById(a)!! to store.getById(b)!!,
            ),
        )

        assertEquals("已 pending 的行不重复登记", 0, second)
        assertEquals(2, store.listPendingContradictions("main", "default").size)
    }

    @Test
    fun resolveContradiction_makesLoserYieldWithoutDeleting() = runTest {
        val (a, b) = seedContradictionPair()

        val ok = store.resolveContradiction(keepId = a)
        assertTrue(ok)

        val kept = store.getById(a)!!
        val loser = dao.getById(b)!!
        assertEquals("none", kept.conflictStatus)
        assertEquals("保留方不取代任何东西", null, kept.supersedesId)
        assertEquals("落选方按 v18 让位", a, loser.supersedesId)
        assertEquals("none", loser.conflictStatus)
        // 行还在:记忆页可见、可回退
        assertNotNull(loser)
        val revisions = store.getRevisions(b)
        assertTrue(
            "裁决必须留下可审计的修订记录",
            revisions.any { it.reason == "conflict_resolved" },
        )
    }

    @Test
    fun resolveContradiction_requiresPendingState() = runTest {
        val id = store.add(FactStore.Fact(fact = "用户周末一般去爬山"))

        assertEquals(false, store.resolveContradiction(id))
    }

    @Test
    fun dismissPendingConflict_clearsMarkersButKeepsContent() = runTest {
        val (a, b) = seedContradictionPair()

        store.dismissPendingConflict(a, b)

        assertEquals(emptyList<FactStore.Fact>(), store.listPendingContradictions("main", "default"))
        assertNotNull(dao.getById(a))
        assertNotNull(dao.getById(b))
        assertNull(dao.getById(a)!!.supersedesId)
    }
}
