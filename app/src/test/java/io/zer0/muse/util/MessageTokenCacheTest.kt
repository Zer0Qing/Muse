package io.zer0.muse.util

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息级 token 计数缓存。
 *
 * 背景：一次请求组装里同一批历史会被估算好几遍（总预算、历史预算、超限判断、
 * 压缩后收益），每遍都对每条消息跑一次 cl100k BPE。缓存按**实例身份**复用，
 * 这里锁三件事：数值与原实现一致、同一实例只算一次、换了实例必须重算。
 */
class MessageTokenCacheTest {

    private fun msg(content: String, role: MessageRole = MessageRole.USER) =
        UIMessage(role = role, content = content)

    @Test
    fun `numbers_match_uncached_estimate`() {
        val messages = listOf(
            msg("你好，帮我总结一下这份文档"),
            msg("好的，我从三个角度来说明……", MessageRole.ASSISTANT),
            msg("继续"),
        )
        val uncached = TokenEstimator.estimate(messages)
        val cached = TokenEstimator.estimate(messages, cache = MessageTokenCache())
        assertEquals("缓存不得改变估算结果", uncached, cached)
    }

    @Test
    fun `each_message_is_counted_once_per_cache`() {
        val messages = (1..20).map { msg("第 $it 条历史内容，长度适中用于触发编码") }
        val cache = MessageTokenCache()

        TokenEstimator.estimate(messages, cache = cache)
        assertEquals("第一遍应全部未命中", 20, cache.misses)
        assertEquals(0, cache.hits)

        // 第二遍：同一批实例（历史预算、超限判断走的就是这条）
        TokenEstimator.estimate(messages, cache = cache)
        assertEquals("第二遍不得再跑 BPE", 20, cache.misses)
        assertEquals("第二遍应全部命中", 20, cache.hits)
    }

    @Test
    fun `sub_list_reuses_parent_counts`() {
        // dropWhile / drop 产出的子列表元素与父列表是同一批实例，必须命中缓存。
        val system = msg("系统提示", MessageRole.SYSTEM)
        val messages = listOf(system) + (1..5).map { msg("历史 $it") }
        val cache = MessageTokenCache()

        TokenEstimator.estimate(messages, cache = cache)
        val afterFirst = cache.misses
        TokenEstimator.estimate(messages.dropWhile { it.role == MessageRole.SYSTEM }, cache = cache)

        assertEquals("子列表不应产生新的未命中", afterFirst, cache.misses)
    }

    @Test
    fun `different_instances_are_counted_separately`() {
        val cache = MessageTokenCache()
        val first = msg("相同内容")
        val second = msg("相同内容")

        val a = TokenEstimator.estimate(listOf(first), cache = cache)
        val b = TokenEstimator.estimate(listOf(second), cache = cache)

        assertEquals("内容相同、实例不同也必须得到同一数值", a, b)
        assertEquals("实例不同应各自计数", 2, cache.misses)
    }

    @Test
    fun `counted_value_covers_reasoning_tool_calls_and_images`() {
        val plain = msg("内容")
        val rich = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "内容",
            reasoning = "思考过程".repeat(10),
            toolCalls = listOf(
                io.zer0.ai.core.ToolCall(
                    id = "call-1",
                    name = "show_card",
                    arguments = "{\"language\":\"svg\",\"code\":\"<svg/>\"}",
                ),
            ),
            imageUrls = listOf("https://example.com/a.png"),
        )
        assertTrue(
            "含思考/工具/图片的消息必须显著多于纯文本",
            TokenEstimator.countMessage(rich) > TokenEstimator.countMessage(plain) + 900,
        )
    }

    @Test
    fun `concurrent use does not corrupt the map`() {
        // P25 把这个缓存提升成进程级共享实例，占用率刷新与提示词组装会从不同线程同时进来。
        // IdentityHashMap 并发 put 会让结构损坏（可能死循环），这里用多线程压一遍。
        val cache = MessageTokenCache()
        val messages = (1..50).map { i ->
            UIMessage(role = MessageRole.USER, content = "并发测试消息 $i".repeat(20))
        }
        val expected = messages.map { TokenEstimator.countMessage(it) }
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = (1..8).map {
            Thread {
                try {
                    repeat(200) { round ->
                        val index = (round + Thread.currentThread().hashCode()) % messages.size
                        check(cache.count(messages[index]) == expected[index]) { "计数不一致" }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(20_000) }
        assertTrue("并发访问不应抛异常: ${errors.firstOrNull()}", errors.isEmpty())
    }
}
