package io.zer0.muse.transformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 经验打分的小写文本缓存。
 *
 * 经验库段每轮都要按当前问题给全部经验打分，做法是把 `title + content` 整段小写后做子串扫描。
 * 条目是用户积累出来的、内容可能很长 —— 每轮为每条经验重新分配一份完整小写副本，是纯重复劳动。
 *
 * 这里锁两件事：
 *  1. 同一 `id + updatedAt` 命中同一份小写文本，条目被编辑（updatedAt 变）后必须重算；
 *  2. 容量有上限，不会变成无界缓存。
 */
class ExperienceTextCacheTest {

    @Test
    fun `same_version_hits_the_same_lowered_text`() {
        val cache = ExperienceTextCache()
        val key = cache.key("exp-1", 1000L)

        assertNull("首次必须未命中", cache.get(key))
        cache.put(key, "标题 正文")
        assertEquals("标题 正文", cache.get(key))
        assertEquals(1, cache.hits)
        assertEquals(1, cache.misses)
    }

    @Test
    fun `editing_an_entry_invalidates_by_updated_at`() {
        val cache = ExperienceTextCache()
        val before = cache.key("exp-1", 1000L)
        cache.put(before, "旧内容")

        val after = cache.key("exp-1", 2000L)
        assertNull("updatedAt 变了必须视为新条目", cache.get(after))
        assertEquals("旧版本的副本仍然可读（不影响其它调用）", "旧内容", cache.get(before))
    }

    @Test
    fun `cache_is_bounded`() {
        val cache = ExperienceTextCache(maxEntries = 3)
        repeat(6) { index -> cache.put(cache.key("exp-$index", 1L), "内容 $index") }
        assertEquals(3, cache.size())
        assertNull("最久未用的条目应被淘汰", cache.get(cache.key("exp-0", 1L)))
        assertEquals("内容 5", cache.get(cache.key("exp-5", 1L)))
    }

    // ── 接线：打分路径必须真的走缓存 ────────────────────────────

    private fun assemblerSource(): String = File("src/main/java/io/zer0/muse/transformer/SystemPromptAssembler.kt").readText()

    @Test
    fun `experience_relevance_uses_the_cache`() {
        val source = assemblerSource()
        val start = source.indexOf("private fun experienceRelevance(")
        require(start >= 0) { "找不到 experienceRelevance" }
        val end = source.indexOf("private fun", start + 10).let { if (it < 0) source.length else it }
        val body = source.substring(start, end)

        assertTrue(
            "打分必须走缓存取小写文本",
            body.contains("experienceTextCache.get("),
        )
        assertTrue(
            "未命中时必须写回缓存",
            body.contains("experienceTextCache.put("),
        )
        assertTrue(
            "打分函数里不应再直接整段 lowercase（那样缓存等于没用）",
            !body.contains("(exp.title + \" \" + exp.content).lowercase()\n"),
        )
    }

    @Test
    fun `experience_tokenizer_regex_is_a_constant`() {
        val source = assemblerSource()
        val tokenizeStart = source.indexOf("private fun tokenizeExperience(")
        require(tokenizeStart >= 0) { "找不到 tokenizeExperience" }
        val tokenizeEnd = source.indexOf("}", tokenizeStart).let { if (it < 0) source.length else it }
        val tokenizeBody = source.substring(tokenizeStart, tokenizeEnd)

        assertTrue(
            "分词必须复用实例级正则，不得每轮现场编译",
            tokenizeBody.contains("experienceTokenRegex"),
        )
        assertTrue(
            "分词函数里不应出现现场 Regex(...) 构造",
            !tokenizeBody.contains("Regex("),
        )
    }
}
