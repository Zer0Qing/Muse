package io.zer0.muse.ai

import io.zer0.ai.util.KeyRoulette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * KeyRoulette API key 轮换单元测试。
 *
 * 测试 LRU 选 key 策略 + 黑名单(软/硬)逻辑:
 *  - [KeyRoulette.pick]:LRU 策略选取 key(优先未使用过的)
 *  - [KeyRoulette.pickNext]:429 限流时切换到下一个 key(当前 key 加入软黑名单)
 *  - [KeyRoulette.markFailed]:标记 key 失败(hardBlock 完全排除)
 *  - [KeyRoulette.clearBlacklist]:清除指定 Provider 的黑名单
 *  - [KeyRoulette.clear]:清除全部缓存与黑名单
 *
 * 每个测试方法使用独立的 KeyRoulette 实例,避免状态污染。
 */
class KeyRouletteTest {

    private lateinit var roulette: KeyRoulette

    private val providerId = "test-provider"
    private val key1 = "sk-key1-aaaaaaaa"
    private val key2 = "sk-key2-bbbbbbbb"
    private val key3 = "sk-key3-cccccccc"
    private val multiKeys = "$key1,$key2,$key3"

    @Before
    fun setUp() {
        roulette = KeyRoulette()
    }

    // ── pick:LRU 选 key ────────────────────────────────────────────────

    @Test
    fun `单 key 时 pick 返回原 key`() {
        val result = roulette.pick(providerId, key1)
        assertEquals(key1, result)
    }

    @Test
    fun `多 key 时 pick 返回其中一个候选 key`() {
        val result = roulette.pick(providerId, multiKeys)
        assertTrue("pick 结果应是候选 key 之一: $result", result in listOf(key1, key2, key3))
    }

    @Test
    fun `pick 后再次 pick 不会返回刚用过的 key(LRU)`() {
        // 第一次 pick
        val first = roulette.pick(providerId, multiKeys)
        // 第二次 pick:first 刚用过,应选其他 key
        val second = roulette.pick(providerId, multiKeys)
        assertFalse("LRU 策略下第二次 pick 不应返回刚用过的 key: first=$first, second=$second", first == second)
    }

    @Test
    fun `三次 pick 覆盖全部三个 key`() {
        val picked = mutableSetOf<String>()
        repeat(3) {
            picked.add(roulette.pick(providerId, multiKeys))
        }
        // 三次 pick 应覆盖全部三个 key(LRU 无重复)
        assertEquals("三次 pick 应覆盖全部 key: $picked", 3, picked.size)
        assertTrue(key1 in picked)
        assertTrue(key2 in picked)
        assertTrue(key3 in picked)
    }

    @Test
    fun `换行分隔的 key 也能正确解析`() {
        val newlineKeys = "$key1\n$key2\n$key3"
        val result = roulette.pick(providerId, newlineKeys)
        assertTrue("换行分隔的 key 应能正确解析: $result", result in listOf(key1, key2, key3))
    }

    @Test
    fun `含空值的 key 列表自动过滤`() {
        // "key1,,key2" 中间有空值,应被过滤
        val keysWithEmpty = "$key1,,$key2"
        val result = roulette.pick(providerId, keysWithEmpty)
        assertTrue("空值 key 应被过滤: $result", result in listOf(key1, key2))
    }

    @Test
    fun `不同 providerId 的 LRU 状态隔离`() {
        // provider-A pick key1
        val a1 = roulette.pick("provider-A", multiKeys)
        // provider-B pick 不受 provider-A 的 LRU 影响
        val b1 = roulette.pick("provider-B", multiKeys)
        // provider-A 第二次 pick 应避开 a1
        val a2 = roulette.pick("provider-A", multiKeys)
        assertFalse("provider-A 的 LRU 不应影响 provider-B", a1 == a2)
    }

    // ── pickNext:429 限流切换 ──────────────────────────────────────────

    @Test
    fun `pickNext 切换到不同的 key`() {
        val first = roulette.pick(providerId, multiKeys)
        val next = roulette.pickNext(providerId, multiKeys, first)
        assertFalse("pickNext 应切换到不同的 key: first=$first, next=$next", first == next)
        assertTrue("切换后的 key 应在候选列表中: $next", next in listOf(key1, key2, key3))
    }

    @Test
    fun `单 key 时 pickNext 返回原 key`() {
        val result = roulette.pickNext(providerId, key1, key1)
        assertEquals("单 key 时 pickNext 应返回原 key", key1, result)
    }

    @Test
    fun `pickNext 把当前 key 加入软黑名单后不再优先选`() {
        val first = roulette.pick(providerId, multiKeys)
        roulette.pickNext(providerId, multiKeys, first)
        // 再次 pick,first 在软黑名单中,应优先选其他 key
        val third = roulette.pick(providerId, multiKeys)
        // 软黑名单中的 key 降优先级,有其他候选时不选它
        val remaining = listOf(key1, key2, key3) - first
        assertTrue("软黑名单中的 key 应被降优先级: $third", third in remaining)
    }

    @Test
    fun `连续 pickNext 最终轮换所有 key`() {
        val first = roulette.pick(providerId, multiKeys)
        val second = roulette.pickNext(providerId, multiKeys, first)
        val third = roulette.pickNext(providerId, multiKeys, second)
        // 三个 key 都应被选到
        val all = setOf(first, second, third)
        assertEquals("连续 pickNext 应轮换全部 key: $all", 3, all.size)
    }

    // ── markFailed:硬黑名单 ────────────────────────────────────────────

    @Test
    fun `markFailed 后该 key 被完全排除`() {
        roulette.markFailed(providerId, key1, hardBlock = true)
        // 多次 pick 都不应返回 key1
        repeat(5) {
            val picked = roulette.pick(providerId, multiKeys)
            assertFalse("markFailed 后 key1 应被排除: 第 ${it + 1} 次 pick=$picked", picked == key1)
        }
    }

    @Test
    fun `markFailed 所有 key 后退化为返回第一个 key`() {
        // 把三个 key 都标记为失败
        roulette.markFailed(providerId, key1, hardBlock = true)
        roulette.markFailed(providerId, key2, hardBlock = true)
        roulette.markFailed(providerId, key3, hardBlock = true)
        // 全部 hard-blocked → 返回第一个 key(让 Provider 自己报错)
        val result = roulette.pick(providerId, multiKeys)
        assertEquals("全部 key 被拉黑应返回第一个 key", key1, result)
    }

    @Test
    fun `markFailed softBlock 时 key 降优先级但不排除`() {
        // hardBlock=false:软黑名单,有其他候选时不选,无其他候选时仍可选
        roulette.markFailed(providerId, key1, hardBlock = false)
        // 有 key2/key3 可用,不应选 key1
        repeat(3) {
            val picked = roulette.pick(providerId, multiKeys)
            assertFalse("软黑名单下有其他候选时不选 key1: $picked", picked == key1)
        }
    }

    @Test
    fun `markFailed 只影响指定 provider`() {
        roulette.markFailed("provider-A", key1, hardBlock = true)
        // provider-B 不受影响
        val result = roulette.pick("provider-B", multiKeys)
        assertTrue("markFailed 不应影响其他 provider: $result", result in listOf(key1, key2, key3))
    }

    // ── clearBlacklist / clear ─────────────────────────────────────────

    @Test
    fun `clearBlacklist 恢复被拉黑的 key`() {
        roulette.markFailed(providerId, key1, hardBlock = true)
        // 清除黑名单
        roulette.clearBlacklist(providerId)
        // key1 恢复候选
        val picked = mutableSetOf<String>()
        repeat(5) { picked.add(roulette.pick(providerId, multiKeys)) }
        assertTrue("clearBlacklist 后 key1 应恢复候选: $picked", key1 in picked)
    }

    @Test
    fun `clear 清除全部状态`() {
        roulette.pick(providerId, multiKeys)
        roulette.markFailed(providerId, key1, hardBlock = true)
        roulette.clear()
        // clear 后 key1 应恢复候选
        val picked = mutableSetOf<String>()
        repeat(5) { picked.add(roulette.pick(providerId, multiKeys)) }
        assertTrue("clear 后 key1 应恢复候选: $picked", key1 in picked)
    }

    @Test
    fun `clearBlacklist 只影响指定 provider`() {
        roulette.markFailed("provider-A", key1, hardBlock = true)
        roulette.markFailed("provider-B", key1, hardBlock = true)
        // 只清除 provider-A 的黑名单
        roulette.clearBlacklist("provider-A")
        // provider-B 的 key1 仍被拉黑
        repeat(3) {
            val picked = roulette.pick("provider-B", multiKeys)
            assertFalse("clearBlacklist 不应影响 provider-B: $picked", picked == key1)
        }
    }
}
