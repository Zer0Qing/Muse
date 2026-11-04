package io.zer0.memory.space

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.memory.fact.FactDao
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactEntity
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
 * 记忆空间仓库（`:memory` 体检第二处）。
 *
 * 这里管的是用户看得见的那份"空间"列表：切换器、记忆页的标签、
 * 以及「助手 · 空间」那行元数据 —— **全都只显示名字**。
 * 所以两条规矩必须有：
 *
 *  - 不许有两个同名空间（否则用户只能靠逐个改名去猜哪个是哪个）；
 *  - 重排之后不许出现重复 `sort_index`（列表按 `sort_index, created_at` 排，
 *    撞车时顺序取决于建得早晚 —— 用户看到的就是"拖好了，一刷新又弹回原位"）。
 *
 * 用真内存库跑：这两条都是数据库层的不变式，用假 DAO 测等于测我自己的假 DAO。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemorySpaceRepositoryTest {

    private lateinit var db: FactDb
    private lateinit var repository: MemorySpaceRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MemorySpaceRepository(db.memorySpaceDao())
        repository.ensureDefaultSpaceExists()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun names(): List<String> = repository.listSpaces().map { it.name }

    @Test
    fun `默认空间存在且不可删除`() = runBlocking {
        assertEquals(listOf(MemorySpaceEntity.DEFAULT_SPACE_NAME), names())

        assertFalse("默认空间删不得，否则没有可写入的空间", repository.deleteSpace(MemorySpaceEntity.DEFAULT_SPACE_ID))
        assertEquals(1, repository.listSpaces().size)
    }

    @Test
    fun `兜底是幂等的 重复调用不会造出第二个默认空间`() = runBlocking {
        repository.ensureDefaultSpaceExists()
        repository.ensureDefaultSpaceExists()

        assertEquals(1, repository.listSpaces().size)
    }

    @Test
    fun `同名空间被拒绝 第二次创建拿不到 id`() = runBlocking {
        val first = repository.createSpace("工作")
        val second = repository.createSpace("工作")

        assertNotNull("第一次该成", first)
        assertNull("同名必须拒绝，不能造出两个都叫「工作」的空间", second)
        assertEquals(listOf(MemorySpaceEntity.DEFAULT_SPACE_NAME, "工作"), names())
    }

    @Test
    fun `只差大小写和空格的同名也算同名`() = runBlocking {
        repository.createSpace("Work")

        assertNull(repository.createSpace("  work  "))
        assertNull(repository.createSpace("WORK"))
        assertEquals(2, repository.listSpaces().size)
    }

    @Test
    fun `改不成别人已经占着的名字 但改回自己不算撞名`() = runBlocking {
        repository.createSpace("学习")
        val work = repository.createSpace("工作")!!

        assertFalse("改名撞车要拒绝", repository.renameSpace(work, "学习"))
        assertTrue("只是去掉首尾空白，等于没改名字，该允许", repository.renameSpace(work, "工作 "))
        assertEquals("工作", repository.getSpace(work)?.name)
        assertFalse("空名字不能写进去", repository.renameSpace(work, "   "))
    }

    @Test
    fun `只重排一部分时不许留下重复序号`() = runBlocking {
        val a = repository.createSpace("A")!!
        val b = repository.createSpace("B")!!
        val c = repository.createSpace("C")!!

        // 只点名 C：以前会把 C 放到 0，而 A 原本就是 0 —— 两个 0，顺序看谁建得早
        repository.reorderSpaces(listOf(c))

        val spaces = repository.listSpaces()
        assertEquals("序号不许重复", spaces.size, spaces.map { it.sortIndex }.distinct().size)
        assertEquals("没被点名的空间要留在后面，不能消失", c, spaces.first().id)
        assertTrue(spaces.map { it.id }.containsAll(listOf(a, b)))
    }

    @Test
    fun `全是无效 id 的重排不该改变可见顺序`() = runBlocking {
        repository.createSpace("A")
        val before = repository.listSpaces().map { it.id }

        repository.reorderSpaces(listOf("nope", "missing"))

        assertEquals(before, repository.listSpaces().map { it.id })
    }

    @Test
    fun `全量重排就按给定的顺序`() = runBlocking {
        val a = repository.createSpace("A")!!
        val b = repository.createSpace("B")!!
        val c = repository.createSpace("C")!!

        assertTrue(repository.reorderSpaces(listOf(b, a, c)))

        val ids = repository.listSpaces().map { it.id }
        assertEquals(
            listOf(b, a, c, MemorySpaceEntity.DEFAULT_SPACE_ID),
            ids,
        )
    }

    @Test
    fun `空的重排请求直接拒绝`() = runBlocking {
        repository.createSpace("A")

        assertFalse(repository.reorderSpaces(emptyList()))
        assertEquals(2, repository.listSpaces().size)
    }

    @Test
    fun `删除空间时里面的事实迁回默认空间而不是被清掉`() = runBlocking {
        val spaceId = repository.createSpace("临时")!!
        val dao: FactDao = db.factDao()
        val keep = FactEntity(
            fact = "用户习惯周五下午开会",
            createdAt = "2026-11-04T00:00:00Z",
            spaceId = spaceId,
        )
        val id = dao.insert(keep)
        assertTrue(id > 0)
        assertEquals(1, repository.countFactsInSpace(spaceId))

        assertTrue(repository.deleteSpace(spaceId))

        assertEquals("空间没了，事实必须还在默认空间里", 1, repository.countFactsInSpace(MemorySpaceEntity.DEFAULT_SPACE_ID))
        assertEquals("不能两头都算数", 0, repository.countFactsInSpace(spaceId))
        assertNull(repository.getSpace(spaceId))
    }

    @Test
    fun `删除不存在的空间返回 false 而不是假装成功`() = runBlocking {
        assertFalse(repository.deleteSpace("no-such-space"))
    }
}
