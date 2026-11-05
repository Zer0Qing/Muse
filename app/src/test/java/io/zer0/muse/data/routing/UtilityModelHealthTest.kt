package io.zer0.muse.data.routing

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 辅助模型失败账本的读写边界。
 *
 * 它只负责「让用户看见」，不参与回退决策；所以测试只钉四件事：
 * 记录能被读回、空模型不记、过期自动失效、换绑定时旧记录不会挂到新模型上。
 */
class UtilityModelHealthTest {

    private val now = 1_800_000_000_000L
    private val provider = "test-provider"
    private val touched = mutableListOf<String>()

    @After
    fun clearTouched() {
        touched.forEach { UtilityModelHealth.clear(provider, it) }
    }

    @Test
    fun `记录后能按绑定读回`() {
        record("model-a", "HTTP 401")

        val failure = UtilityModelHealth.lastFor(provider, "model-a", now)

        assertNotNull(failure)
        assertEquals("model-a", failure?.modelId)
        assertEquals("HTTP 401", failure?.message)
    }

    @Test
    fun `模型 id 为空不记录`() {
        UtilityModelHealth.record(provider, "", "boom", now)

        assertNull(UtilityModelHealth.lastFor(provider, "", now))
    }

    @Test
    fun `超过一天不再返回旧失败`() {
        record("model-b", "timeout")

        assertNotNull(UtilityModelHealth.lastFor(provider, "model-b", now))
        assertNull(UtilityModelHealth.lastFor(provider, "model-b", now + 25 * 60 * 60 * 1000L))
    }

    @Test
    fun `同一绑定只保留最近一条`() {
        record("model-c", "first", now)
        record("model-c", "second", now + 1_000L)

        assertEquals("second", UtilityModelHealth.lastFor(provider, "model-c", now + 1_000L)?.message)
    }

    @Test
    fun `清除只影响指定绑定`() {
        record("model-d", "boom")
        record("model-e", "boom")
        UtilityModelHealth.clear(provider, "model-d")

        assertNull(UtilityModelHealth.lastFor(provider, "model-d", now))
        assertNotNull(UtilityModelHealth.lastFor(provider, "model-e", now))
    }

    @Test
    fun `过长的错误只保留前 200 字`() {
        record("model-f", "x".repeat(300))

        assertEquals(200, UtilityModelHealth.lastFor(provider, "model-f", now)?.message?.length)
    }

    private fun record(modelId: String, message: String, at: Long = now) {
        if (modelId !in touched) touched += modelId
        UtilityModelHealth.record(provider, modelId, message, at)
    }
}
