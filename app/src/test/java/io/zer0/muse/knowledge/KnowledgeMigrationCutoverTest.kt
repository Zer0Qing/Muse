package io.zer0.muse.knowledge

import io.zer0.muse.knowledge.integration.KnowledgeReadMode
import io.zer0.muse.knowledge.integration.KnowledgeReadModeResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R9 步骤「影子读比对候选/范围/证据后再开启新引擎读；所有写入已统一后小范围切换。
 * 切换前必须有可验证恢复点」与「验证同新 schema 的兼容读开关回退」。
 *
 * 合同:
 *  - 默认 LEGACY(未切换);
 *  - SHADOW 只执行比对,不改变用户可见结果;
 *  - V2_ACTIVE 必须同时具备「切换批准 + V2 可用」,否则降级 LEGACY 并说明原因。
 */
class KnowledgeMigrationCutoverTest {

    @Test
    fun `default is legacy read`() {
        val decision = KnowledgeReadModeResolver.decide(
            requested = KnowledgeReadMode.LEGACY,
            v2Available = true,
            cutoverApproved = false,
        )
        assertEquals(KnowledgeReadMode.LEGACY, decision.servingEngine)
        assertFalse(decision.runShadowRead)
        assertNull(decision.fallbackReason)
    }

    @Test
    fun `shadow read keeps legacy serving and records comparison`() {
        val decision = KnowledgeReadModeResolver.decide(
            requested = KnowledgeReadMode.V2_SHADOW,
            v2Available = true,
            cutoverApproved = false,
        )
        assertEquals("影子读不得改变用户可见结果", KnowledgeReadMode.LEGACY, decision.servingEngine)
        assertTrue(decision.runShadowRead)
    }

    @Test
    fun `shadow without v2 skips comparison with reason`() {
        val decision = KnowledgeReadModeResolver.decide(
            requested = KnowledgeReadMode.V2_SHADOW,
            v2Available = false,
            cutoverApproved = false,
        )
        assertEquals(KnowledgeReadMode.LEGACY, decision.servingEngine)
        assertFalse(decision.runShadowRead)
        assertNotNull(decision.fallbackReason)
    }

    @Test
    fun `active switch refused without approval`() {
        val decision = KnowledgeReadModeResolver.decide(
            requested = KnowledgeReadMode.V2_ACTIVE,
            v2Available = true,
            cutoverApproved = false,
        )
        assertEquals(KnowledgeReadMode.LEGACY, decision.servingEngine)
        assertTrue(decision.fallbackReason!!.contains("未获"))
    }

    @Test
    fun `active switch refused when v2 unavailable`() {
        val decision = KnowledgeReadModeResolver.decide(
            requested = KnowledgeReadMode.V2_ACTIVE,
            v2Available = false,
            cutoverApproved = true,
        )
        assertEquals(KnowledgeReadMode.LEGACY, decision.servingEngine)
        assertTrue(decision.fallbackReason!!.contains("不可用"))
    }

    @Test
    fun `active switch allowed only with approval and availability`() {
        val decision = KnowledgeReadModeResolver.decide(
            requested = KnowledgeReadMode.V2_ACTIVE,
            v2Available = true,
            cutoverApproved = true,
        )
        assertEquals(KnowledgeReadMode.V2_ACTIVE, decision.servingEngine)
        assertNull(decision.fallbackReason)
    }
}
