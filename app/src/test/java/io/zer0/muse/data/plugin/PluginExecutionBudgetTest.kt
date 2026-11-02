package io.zer0.muse.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 插件执行超时预算（`runtime.long`）。
 *
 * 这条闸的意义全在"没授权时收窄"：如果调用方传一个很大的超时就能绕过去，
 * 那 `runtime.long` 只是一句装饰。
 */
class PluginExecutionBudgetTest {

    @Test
    fun `without the capability a big request is clamped to the default`() {
        assertEquals(
            PluginExecutionBudget.DEFAULT_TIMEOUT_MS,
            PluginExecutionBudget.effectiveTimeoutMs(requested = 600_000L, longTaskAllowed = false),
        )
    }

    @Test
    fun `without the capability a small request is kept`() {
        assertEquals(
            2_000L,
            PluginExecutionBudget.effectiveTimeoutMs(requested = 2_000L, longTaskAllowed = false),
        )
    }

    @Test
    fun `with the capability a big request is honoured`() {
        assertEquals(
            600_000L,
            PluginExecutionBudget.effectiveTimeoutMs(requested = 600_000L, longTaskAllowed = true),
        )
    }

    @Test
    fun `even with the capability the timeout is capped`() {
        assertEquals(
            PluginExecutionBudget.LONG_TASK_TIMEOUT_MS,
            PluginExecutionBudget.effectiveTimeoutMs(requested = 10 * 60 * 60 * 1000L, longTaskAllowed = true),
        )
    }

    @Test
    fun `a nonsensical request still yields a usable timeout`() {
        assertEquals(1L, PluginExecutionBudget.effectiveTimeoutMs(requested = 0L, longTaskAllowed = true))
        assertEquals(1L, PluginExecutionBudget.effectiveTimeoutMs(requested = -5L, longTaskAllowed = false))
    }

    @Test
    fun `the long budget is ten minutes and strictly larger than the default`() {
        assertEquals(10 * 60 * 1000L, PluginExecutionBudget.LONG_TASK_TIMEOUT_MS)
        assertEquals(10_000L, PluginExecutionBudget.DEFAULT_TIMEOUT_MS)
    }
}
