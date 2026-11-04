package io.zer0.memory.reflection

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 矛盾记忆清单的持久化（`:memory` 模块第一处修复）。
 *
 * 这清单是给**用户**看的：记忆中心展示"它记得相反的两句"，让用户逐条确认或清除。
 * 所以最不能接受的是**静默抹掉**：坏文件被当成空表，任何一次保存就会用
 * 「只含本次 key」的内容覆盖整份文件，别的助手 / 空间的清单一起没了，还不留痕迹。
 *
 * 这里既钉正常路径的隔离（scope/space 互不覆盖），也钉那条损坏路径的三种行为：
 * 写之前归档留证、归不了档就拒绝写、只读绝不改状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryContradictionStoreTest {

    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var store: MemoryContradictionStore

    @Before
    fun setUp() {
        dir = createTempDir("mp-contradiction")
        file = File(dir, "contradictions.json")
        store = MemoryContradictionStore(file)
    }

    private fun createTempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").apply { mkdirs() }

    private fun pairs(vararg texts: String) = texts.toList().chunked(2).map { (a, b) -> a to b }

    @Test
    fun `两个 scope 各存各的 后写不能抹掉先写`() = runBlocking {
        store.save("assistant_main", "default", pairs("我住杭州", "我住上海"))
        store.save("assistant_writer", "default", pairs("deadline 周五", "deadline 周一"))

        assertEquals(
            listOf("我住杭州" to "我住上海"),
            store.list("assistant_main", "default").map { it.a to it.b },
        )
        assertEquals(
            listOf("deadline 周五" to "deadline 周一"),
            store.list("assistant_writer", "default").map { it.a to it.b },
        )
    }

    @Test
    fun `同一 scope 的不同空间互不覆盖`() = runBlocking {
        store.save("assistant_main", "work", pairs("加班到十点", "不加班"))
        store.save("assistant_main", "home", pairs("养了一只猫", "没有宠物"))

        assertEquals(1, store.list("assistant_main", "work").size)
        assertEquals("加班到十点", store.list("assistant_main", "work").single().a)
        assertEquals("养了一只猫", store.list("assistant_main", "home").single().a)
    }

    @Test
    fun `空间留空时落到 default 而不是造出一个空 key`() = runBlocking {
        store.save("assistant_main", "", pairs("A", "B"))

        assertEquals("A", store.list("assistant_main", "").single().a)
        assertEquals("A", store.list("assistant_main", "default").single().a)
    }

    @Test
    fun `坏文件被写之前先归档留证 而不是当空表覆盖`() = runBlocking {
        val broken = "{这段是半截的 JSO"
        file.writeText(broken)

        store.save("assistant_main", "default", pairs("新 detecting", "新的相反一句"))

        val archived = dir.listFiles()?.filter { it.name.contains(".corrupt-") }.orEmpty()
        assertEquals("必须留下一份归档，否则用户无从追溯", 1, archived.size)
        assertEquals("归档里就是原来的坏内容，一个字节都不能改", broken, archived.single().readText())
        assertEquals("新写的那条要读得回来", "新 detecting", store.list("assistant_main", "default").single().a)
    }

    @Test
    fun `只读遇到坏文件时不该改动任何状态`() = runBlocking {
        file.writeText("{这段是半截的 JSO")

        val listed = store.list("assistant_main", "default")

        assertEquals("读不出来就按空表展示", emptyList<MemoryContradictionStore.ContradictionPair>(), listed)
        assertTrue("只读不许生成归档", dir.listFiles()?.none { it.name.contains(".corrupt-") } == true)
        assertTrue("原文件必须还在原位", file.exists())
    }

    @Test
    fun `清掉一个 scope 不会误删名字前缀相同的另一个`() = runBlocking {
        store.save("ab", "default", pairs("x", "y"))
        store.save("abc", "default", pairs("p", "q"))

        val removed = store.clearScope("ab")

        assertEquals("只该删 ab|default 那一条", 1, removed)
        assertTrue(store.list("ab", "default").isEmpty())
        assertEquals("同前缀的 abc 必须留着", "p", store.list("abc", "default").single().a)
    }

    @Test
    fun `空 scope 不清任何东西`() = runBlocking {
        store.save("assistant_main", "default", pairs("A", "B"))

        assertEquals(0, store.clearScope("   "))
        assertEquals("内容不能被顺手清掉", 1, store.list("assistant_main", "default").size)
    }

    @Test
    fun `确认掉最后一对后不留空壳条目`() = runBlocking {
        val pair = MemoryContradictionStore.ContradictionPair("我住杭州", "我住上海")
        store.save("assistant_main", "default", listOf(pair.a to pair.b))

        store.removePair("assistant_main", "default", pair)

        assertTrue(store.list("assistant_main", "default").isEmpty())
        // 空壳条目如果留着，下一次覆盖写会把它一起带回去（用户看到"已确认"又冒出来）
        val text = file.readText()
        assertNotNull(text)
        assertTrue("不该留下 pairs 为空的条目: $text", !text.contains("\"pairs\":[]"))
    }

    @Test
    fun `未知 scope 的确认动作不该报错`() = runBlocking {
        val pair = MemoryContradictionStore.ContradictionPair("x", "y")

        store.removePair("nobody", "default", pair)

        assertTrue(store.list("nobody", "default").isEmpty())
    }
}
