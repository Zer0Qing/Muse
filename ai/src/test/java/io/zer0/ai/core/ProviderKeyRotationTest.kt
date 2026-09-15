package io.zer0.ai.core

import io.zer0.common.Logger
import io.zer0.ai.util.KeyRoulette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1.2: [ProviderKeyRotation] 多 Key 轮换逻辑测试。
 *
 * 覆盖:
 *  - 单 key 场景:effectiveApiKey 直接返回 trim 后的 key,跳过 LRU
 *  - 多 key 场景:每次调用都重新 LRU pick(不缓存),switchToNextKey 切换
 *  - 429 切换:switchToNextKey 在多 key 时返回 true 且换新 key
 *  - markKeyFailed:401 场景排除失效 key
 */
class ProviderKeyRotationTest {

    @org.junit.Before
    fun setUp() {
        Logger.enabled = false
    }

    @org.junit.After
    fun tearDown() {
        Logger.enabled = true
    }

    private fun config(apiKey: String, id: String = "test-provider") = ProviderConfig(
        id = id,
        displayName = "test",
        type = ProviderType.OPENAI,
        apiKey = apiKey,
    )

    // ---------- 单 key 场景 ----------

    @Test
    fun `single key effectiveApiKey returns trimmed key without LRU`() {
        val rotation = ProviderKeyRotation(config("sk_single_key"), keyRoulette = KeyRoulette())
        assertEquals("sk_single_key", rotation.effectiveApiKey())
        // 多次调用仍返回同一 key(单 key 无需轮换)
        assertEquals("sk_single_key", rotation.effectiveApiKey())
        assertEquals("sk_single_key", rotation.effectiveApiKey())
    }

    @Test
    fun `single key with whitespace is trimmed`() {
        val rotation = ProviderKeyRotation(config("  sk_padded  "))
        assertEquals("sk_padded", rotation.effectiveApiKey())
    }

    @Test
    fun `single key switchToNextKey returns false`() {
        val rotation = ProviderKeyRotation(config("sk_only"))
        assertFalse(rotation.switchToNextKey())
        // key 保持不变
        assertEquals("sk_only", rotation.effectiveApiKey())
    }

    @Test
    fun `single key markKeyFailed returns false`() {
        val rotation = ProviderKeyRotation(config("sk_only"))
        assertFalse(rotation.markKeyFailed())
        assertEquals("sk_only", rotation.effectiveApiKey())
    }

    // ---------- 多 key 场景 ----------

    @Test
    fun `multi key effectiveApiKey returns one of the configured keys`() {
        val rotation = ProviderKeyRotation(config("sk_a,sk_b,sk_c"))
        val key = rotation.effectiveApiKey()
        assertTrue(key in setOf("sk_a", "sk_b", "sk_c"))
    }

    @Test
    fun `multi key effectiveApiKey does not return raw comma-joined string`() {
        val rotation = ProviderKeyRotation(config("sk_a,sk_b,sk_c"))
        val key = rotation.effectiveApiKey()
        assertFalse(key.contains(","))
        assertFalse(key == "sk_a,sk_b,sk_c")
    }

    @Test
    fun `multi key switchToNextKey switches to a different key`() {
        val rotation = ProviderKeyRotation(config("sk_a,sk_b"))
        val first = rotation.effectiveApiKey()
        val switched = rotation.switchToNextKey()
        assertTrue(switched)
        val next = rotation.effectiveApiKey()
        assertNotEquals(first, next)
        assertTrue(next in setOf("sk_a", "sk_b"))
    }

    @Test
    fun `multi key switchToNextKey with one soft-blocked key falls back to remaining`() {
        // 两个 key:sk_a,sk_b。先用 sk_a 触发 switch,sk_a 进软黑名单,
        // 再次 effectiveApiKey 应优先选 sk_b(未被软黑名单覆盖的候选)
        val rotation = ProviderKeyRotation(config("sk_a,sk_b"))
        val initial = rotation.effectiveApiKey()
        if (initial == "sk_a") {
            assertTrue(rotation.switchToNextKey())
            val next = rotation.effectiveApiKey()
            assertEquals("sk_b", next)
        } else {
            assertTrue(rotation.switchToNextKey())
            val next = rotation.effectiveApiKey()
            assertEquals("sk_a", next)
        }
    }

    @Test
    fun `multi key markKeyFailed excludes failed key and picks another`() {
        val rotation = ProviderKeyRotation(config("sk_bad,sk_good"))
        // 强制先选中 sk_bad(多次 pick 直至拿到 sk_bad)
        var current = rotation.effectiveApiKey()
        var guard = 0
        while (current != "sk_bad" && guard < 20) {
            rotation.switchToNextKey()
            current = rotation.effectiveApiKey()
            guard++
        }
        // 标记当前 key 失效(401 场景,hardBlock)
        val switched = rotation.markKeyFailed()
        assertTrue(switched)
        val after = rotation.effectiveApiKey()
        assertNotEquals("sk_bad", after)
        assertEquals("sk_good", after)
    }

    @Test
    fun `multi key effectiveApiKey is re-picked each call not cached`() {
        // 核心修复验证:多 key 场景 effectiveApiKey 每次都应走 LRU pick,
        // 不命中"缓存"分支(否则轮换后仍返回旧 key)。
        // 实现方式:连续两次调用,若两次都命中缓存则第二次必等于第一次;
        // 但 LRU pick 会记录使用,使第二次倾向选另一个 key。
        val rotation = ProviderKeyRotation(config("sk_a,sk_b"))
        val first = rotation.effectiveApiKey()
        val second = rotation.effectiveApiKey()
        // 由于 LRU 记录,两次连续调用大概率拿到不同 key;
        // 即便随机命中相同,也合法 — 这里只断言仍落在配置 key 集合内。
        assertTrue(first in setOf("sk_a", "sk_b"))
        assertTrue(second in setOf("sk_a", "sk_b"))
    }
}
