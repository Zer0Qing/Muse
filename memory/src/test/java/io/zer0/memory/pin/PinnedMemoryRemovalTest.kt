package io.zer0.memory.pin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 按内容取消置顶的两条路径。
 *
 * 这两条路径的差别不是风格问题：
 *  - 按关键词取消（用户/模型给的是一段关键词），匹配不到精确内容时可以退化为包含匹配；
 *  - 按事实取消（事实被取消置顶），只能按原文精确删 —— 模糊匹配会连带删掉
 *    内容里含同一段文字的其它独立置顶，那是用户另一条记忆。
 */
class PinnedMemoryRemovalTest {

    private fun <T> withStore(block: suspend (PinnedMemoryStore) -> T): T = runBlocking {
        val directory = Files.createTempDirectory("pinned-removal-test").toFile()
        try {
            block(PinnedMemoryStore(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `flexible removal matches a later entry inside the scope`() = withStore { store ->
        // 旧实现只看“scope 内第一条”：第一条不匹配就直接返回 false，
        // 同一个助手置顶了不止一条时，取消置顶会静默失败。
        store.add("第一条置顶", assistantId = "assistant-a")
        store.add("第二条置顶", assistantId = "assistant-a")

        val removed = store.removeByContentFlexibleForAssistant(
            content = "第二条置顶",
            assistantId = "assistant-a",
            includeGlobal = false,
        )

        assertTrue("scope 内后续条目也必须能命中", removed)
        assertEquals(
            listOf("第一条置顶"),
            store.getForAssistant("assistant-a", includeGlobal = false).map { it.content },
        )
    }

    @Test
    fun `exact removal never deletes a merely containing entry`() = withStore { store ->
        store.add("用户养了一只猫", assistantId = "default")
        store.add("用户养了一只猫,还养了一条狗", assistantId = "default")

        val removed = store.removeByContentExactForAssistant(
            content = "用户养了一只猫",
            assistantId = "default",
            includeGlobal = true,
        )

        assertTrue(removed)
        assertEquals(
            listOf("用户养了一只猫,还养了一条狗"),
            store.getForAssistant("default", includeGlobal = true).map { it.content },
        )
    }

    @Test
    fun `exact removal stays inside the assistant scope`() = withStore { store ->
        store.add("同名内容", assistantId = "assistant-a")
        store.add("同名内容", assistantId = "assistant-b")

        store.removeByContentExactForAssistant(
            content = "同名内容",
            assistantId = "assistant-a",
            includeGlobal = false,
        )

        assertTrue(store.getForAssistant("assistant-a", includeGlobal = false).isEmpty())
        assertEquals(
            "另一个助手的同名置顶不得被删",
            1,
            store.getForAssistant("assistant-b", includeGlobal = false).size,
        )
    }

    @Test
    fun `exact removal reports false when nothing matches`() = withStore { store ->
        store.add("用户喜欢美式", assistantId = "default")

        val removed = store.removeByContentExactForAssistant(
            content = "用户喜欢拿铁",
            assistantId = "default",
            includeGlobal = true,
        )

        assertFalse("没有精确匹配时不该删任何条目", removed)
        assertEquals(1, store.getForAssistant("default", includeGlobal = true).size)
    }
}