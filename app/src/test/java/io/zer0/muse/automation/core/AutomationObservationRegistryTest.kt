package io.zer0.muse.automation.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 观察有效期台账（对比清单 P1-06 的 observation_id 失效校验）。
 *
 * 要防的是一个很具体的失误：模型读屏 → 想两秒 → 点下去，而这两秒里界面已经变了。
 * 轻则白点一次，重则点到「删除/付款」。
 */
class AutomationObservationRegistryTest {

    @Before
    fun setUp() = AutomationObservationRegistry.resetForTests()

    @After
    fun tearDown() = AutomationObservationRegistry.resetForTests()

    private fun record(scope: String = "main", now: Long = 1_000, ttl: Long = 10_000) =
        AutomationObservationRegistry.record(scope, "fp", now, ttl)

    @Test
    fun `a fresh observation is accepted`() {
        val observation = record()

        assertTrue(
            AutomationObservationRegistry.isFresh("main", observation.id, now = 1_500),
        )
    }

    @Test
    fun `a newer observation invalidates the previous one`() {
        val first = record(now = 1_000)
        val second = record(now = 1_100)

        assertFalse(
            "模型拿着旧画面去点，必须被拒绝",
            AutomationObservationRegistry.isFresh("main", first.id, now = 1_200),
        )
        assertTrue(AutomationObservationRegistry.isFresh("main", second.id, now = 1_200))
    }

    @Test
    fun `an expired observation is rejected even if nothing changed`() {
        val observation = record(now = 1_000, ttl = 500)

        assertNull(AutomationObservationRegistry.current("main", now = 2_000))
        assertFalse(AutomationObservationRegistry.isFresh("main", observation.id, now = 2_000))
    }

    @Test
    fun `a blank observation id is never accepted`() {
        record()

        assertFalse(AutomationObservationRegistry.isFresh("main", null, now = 1_100))
        assertFalse("空串不能当成合法 id", AutomationObservationRegistry.isFresh("main", "   ", now = 1_100))
    }

    @Test
    fun `ids are not reused and not guessable by incrementing`() {
        val first = record(now = 1_000)
        record(now = 1_100)
        val third = record(now = 1_200)

        assertNotEquals(first.id, third.id)
        // 旧 id 不能因为"看起来像下一个"就被接受
        assertFalse(AutomationObservationRegistry.isFresh("main", first.id, now = 1_300))
    }

    @Test
    fun `scopes are independent so the virtual display cannot invalidate the main screen`() {
        val main = record(scope = "main", now = 1_000)
        record(scope = "display-7", now = 1_100)

        assertTrue(AutomationObservationRegistry.isFresh("main", main.id, now = 1_200))
    }

    @Test
    fun `fingerprint changes when the visible text changes`() {
        val a = AutomationObservationRegistry.fingerprintOf("com.shop", "MainActivity", listOf("购物车", "结算"))
        val b = AutomationObservationRegistry.fingerprintOf("com.shop", "MainActivity", listOf("购物车", "结算", "已下单"))
        val c = AutomationObservationRegistry.fingerprintOf("com.shop", "MainActivity", listOf("购物车", "结算"))

        assertEquals("同样的界面要得到同样的指纹", a, c)
        assertNotEquals("界面内容变了指纹就要变", a, b)
    }

    @Test
    fun `fingerprint tolerates missing package and activity`() {
        val fingerprint = AutomationObservationRegistry.fingerprintOf(null, null, emptyList())

        assertTrue("空界面也要有确定的指纹", fingerprint.isNotEmpty())
    }
}
