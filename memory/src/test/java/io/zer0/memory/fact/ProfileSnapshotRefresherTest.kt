package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/**
 * v2.6.23(M3): 画像快照刷新器的单测。
 *
 * 钉住四条设计:
 *  1. 段落归属是确定性规则 —— category 映射优先,低置信一律进「不确定笔记」,
 *     时间兜底只在无明确分类时生效(临时状态绝不进稳定段);
 *  2. 每段条数有上限,画像不是对话转述;
 *  3. 指纹短路:来源没变只续期,不重写正文;
 *  4. 用户覆写跨刷新保留(manual_content 独立列)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProfileSnapshotRefresherTest {

    private lateinit var db: FactDb
    private lateinit var dao: ProfileSnapshotDao
    private lateinit var refresher: ProfileSnapshotRefresher

    private val now: Instant = Instant.parse("2026-11-01T08:00:00Z")

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.profileSnapshotDao()
        refresher = ProfileSnapshotRefresher(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun fact(
        id: Long,
        text: String,
        category: String = "general",
        confidence: Float = 1.0f,
        createdAt: String = "2026-01-01T00:00:00Z",
    ) = FactStore.Fact(id = id, fact = text, category = category, confidence = confidence, createdAt = createdAt)

    @Test
    fun assignSections_mapsCategoriesAndConfidence() {
        val facts = listOf(
            fact(1, "用户叫小王", category = "identity"),
            fact(2, "用户和助手是老朋友", category = "relationship"),
            fact(3, "用户偏好简洁回答", category = "preference"),
            fact(4, "用户下周三交报告", category = "event"),
            fact(5, "用户可能换工作了", category = "goal", confidence = 0.4f),
            fact(6, "用户住在杭州", category = "general"),
        )

        val sections = refresher.assignSections(facts, now)

        assertTrue(sections.getValue(ProfileSection.IDENTITY_SETTINGS).any { it.first.contains("小王") })
        assertTrue(sections.getValue(ProfileSection.RELATIONSHIP_SETTINGS).any { it.first.contains("老朋友") })
        assertTrue(sections.getValue(ProfileSection.INTERACTION_PREFERENCES).any { it.first.contains("简洁") })
        assertTrue(sections.getValue(ProfileSection.RECENT_INTERACTIONS).any { it.first.contains("交报告") })
        // 低置信的 goal 也必须进 uncertain,不许留在稳定段
        assertTrue(sections.getValue(ProfileSection.UNCERTAIN_NOTES).any { it.first.contains("换工作") })
        assertTrue(
            "低置信不得进稳定段",
            sections.getValue(ProfileSection.STABLE_FACTS).none { it.first.contains("换工作") },
        )
        assertTrue(sections.getValue(ProfileSection.STABLE_FACTS).any { it.first.contains("杭州") })
    }

    @Test
    fun assignSections_capsItemsPerSection() {
        val many = (1..30L).map { fact(it, "用户习惯第${it}条", category = "preference") }

        val sections = refresher.assignSections(many, now)

        assertTrue(
            "每段最多 ${ProfileSnapshotRefresher.MAX_ITEMS_PER_SECTION} 条",
            sections.getValue(ProfileSection.INTERACTION_PREFERENCES).size <= ProfileSnapshotRefresher.MAX_ITEMS_PER_SECTION,
        )
    }

    @Test
    fun refresh_firstRun_writesRows() = runTest {
        val outcome = refresher.refresh(
            scope = "main",
            spaceId = "default",
            facts = listOf(fact(1, "用户是产品经理", category = "identity")),
            now = now,
        )

        assertEquals(ProfileSnapshotRefresher.Outcome.REFRESHED, outcome)
        val row = dao.getOne("main", "default", ProfileSection.IDENTITY_SETTINGS)
        assertNotNull(row)
        assertTrue(row!!.autoContent.contains("产品经理"))
    }

    @Test
    fun refresh_fingerprintUnchanged_onlyTouchesClock() = runTest {
        val facts = listOf(fact(1, "用户是产品经理", category = "identity"))
        refresher.refresh("main", "default", facts, now = now)
        val first = dao.getOne("main", "default", ProfileSection.IDENTITY_SETTINGS)!!

        val outcome = refresher.refresh("main", "default", facts, now = now.plus(Duration.ofHours(7)))
        val after = dao.getOne("main", "default", ProfileSection.IDENTITY_SETTINGS)!!

        assertEquals(ProfileSnapshotRefresher.Outcome.UNCHANGED, outcome)
        assertEquals("指纹不变不得重写正文", first.updatedAt, after.updatedAt)
        assertEquals("只续 last_refresh_at", now.plus(Duration.ofHours(7)).toString(), after.lastRefreshAt)
    }

    @Test
    fun refresh_withinTtl_skipsEntirely() = runTest {
        refresher.refresh("main", "default", listOf(fact(1, "用户是产品经理", category = "identity")), now = now)

        val outcome = refresher.refresh(
            "main",
            "default",
            listOf(fact(1, "用户是产品经理", category = "identity"), fact(2, "用户住在杭州", category = "identity")),
            now = now.plus(Duration.ofHours(1)),
        )

        assertEquals("TTL 内不重算(新事实等下一个窗口)", ProfileSnapshotRefresher.Outcome.UNCHANGED, outcome)
        val row = dao.getOne("main", "default", ProfileSection.IDENTITY_SETTINGS)!!
        assertTrue(!row.autoContent.contains("杭州"))
    }

    @Test
    fun refresh_preservesManualOverride() = runTest {
        refresher.refresh("main", "default", listOf(fact(1, "用户是产品经理", category = "identity")), now = now)
        dao.setManualContent("main", "default", ProfileSection.IDENTITY_SETTINGS, "我叫王小明,做设计")

        // 内容变化触发重写(加一条新事实 + 越过 TTL)
        refresher.refresh(
            "main",
            "default",
            listOf(fact(1, "用户是产品经理", category = "identity"), fact(2, "用户每周跑步", category = "identity")),
            now = now.plus(Duration.ofHours(7)),
        )

        val row = dao.getOne("main", "default", ProfileSection.IDENTITY_SETTINGS)!!
        assertEquals("用户覆写必须跨刷新保留", "我叫王小明,做设计", row.manualContent)
        assertTrue(row.autoContent.contains("跑步"))
    }
}
