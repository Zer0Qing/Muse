package io.zer0.muse.ui.settings

import io.zer0.muse.rag.EmbeddingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「当前实际在用哪个 embedding 后端」摘要的规则锁定。
 *
 * 设置页状态卡只渲染 [resolveActiveBackendSummary] 的结果，因此这里的每条断言都对应
 * 用户能看见或看不见的一段文案；降级时 warning 必须非空，否则降级会重新变静默。
 */
class ActiveBackendSummaryTest {

    private val fallback = "已降级到其它后端，具体原因未记录"

    private fun state(
        displayName: String = "Local Keyword (Offline)",
        modelName: String = "local-keyword-v1",
        degraded: Boolean = false,
        reason: String? = null,
    ) = EmbeddingService.BackendState(
        backendId = "local-keyword",
        displayName = displayName,
        modelName = modelName,
        degraded = degraded,
        reason = reason,
    )

    /** 规则 1：还没跑过检索（state 为 null）时返回 null，调用方整块不渲染。 */
    @Test
    fun `null state yields no summary`() {
        assertNull(resolveActiveBackendSummary(null, fallback))
    }

    /** 规则 2：未降级时只有主行，warning 必须为 null（不显示告警行）。 */
    @Test
    fun `healthy backend yields primary without warning`() {
        val summary = resolveActiveBackendSummary(state(), fallback)
        assertEquals("Local Keyword (Offline) · local-keyword-v1", summary?.primary)
        assertNull(summary?.warning)
    }

    /** 规则 3：降级且带原因时，warning 原样使用该原因。 */
    @Test
    fun `degraded backend exposes reason as warning`() {
        val reason = "Provider OpenAI 的 API Key 为空"
        val summary = resolveActiveBackendSummary(
            state(degraded = true, reason = reason),
            fallback,
        )
        assertEquals("Local Keyword (Offline) · local-keyword-v1", summary?.primary)
        assertEquals(reason, summary?.warning)
    }

    /** 规则 4：降级但原因为空时用兜底文案，不允许 warning 变回 null。 */
    @Test
    fun `degraded backend without reason falls back to default text`() {
        val empty = resolveActiveBackendSummary(state(degraded = true, reason = null), fallback)
        assertEquals(fallback, empty?.warning)

        val blank = resolveActiveBackendSummary(state(degraded = true, reason = "   "), fallback)
        assertEquals(fallback, blank?.warning)
    }

    /** 规则 5：模型名为空时主行只显示后端名，不留孤立分隔符。 */
    @Test
    fun `blank model name yields display name only`() {
        val summary = resolveActiveBackendSummary(state(modelName = ""), fallback)
        assertEquals("Local Keyword (Offline)", summary?.primary)
    }
}
