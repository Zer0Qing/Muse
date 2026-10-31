package io.zer0.muse.data.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动等级的单测。
 *
 * 这套档位控制两件不同的事：一天能说几条（floor/cap），以及**能说什么**（allowedKinds）。
 * 后者长期没有消费方 —— 枚举上写着「安静档只说该说的事」，实际调度层从不过滤，
 * 于是选了安静的用户照样收到分享与里程碑。这里把语义钉死，避免再退回那个状态。
 */
class ProactiveIntensityTest {

    @Test
    fun `安静档只允许说具体的事`() {
        assertTrue(ProactiveIntensity.QUIET.allows(ThoughtKind.REMINDER))
        assertFalse("安静档不该主动分享", ProactiveIntensity.QUIET.allows(ThoughtKind.SHARE))
        assertFalse("安静档不该报关系节点", ProactiveIntensity.QUIET.allows(ThoughtKind.MILESTONE))
        assertFalse("安静档不该主动关心寒暄", ProactiveIntensity.QUIET.allows(ThoughtKind.CARE))
    }

    @Test
    fun `偶尔被想到档允许接话与关心但不容分享`() {
        assertTrue(ProactiveIntensity.RESERVED.allows(ThoughtKind.FOLLOW_UP))
        assertTrue(ProactiveIntensity.RESERVED.allows(ThoughtKind.CARE))
        assertFalse(ProactiveIntensity.RESERVED.allows(ThoughtKind.SHARE))
        assertFalse(ProactiveIntensity.RESERVED.allows(ThoughtKind.MILESTONE))
    }

    @Test
    fun `标准及以上不限制类型`() {
        listOf(
            ProactiveIntensity.STANDARD,
            ProactiveIntensity.ACTIVE,
            ProactiveIntensity.CLINGY,
        ).forEach { level ->
            ThoughtKind.entries.forEach { kind ->
                assertTrue("${level.name} 应当允许 ${kind.name}", level.allows(kind))
            }
        }
    }

    @Test
    fun `每日条数上限按档位分别是 3 8 15 30 与不限`() {
        assertEquals(3, ProactiveIntensity.QUIET.cap)
        assertEquals(8, ProactiveIntensity.RESERVED.cap)
        assertEquals(15, ProactiveIntensity.STANDARD.cap)
        assertEquals(30, ProactiveIntensity.ACTIVE.cap)
        assertEquals(Int.MAX_VALUE, ProactiveIntensity.CLINGY.cap)
        assertTrue(ProactiveIntensity.CLINGY.unlimited)
        assertFalse(ProactiveIntensity.ACTIVE.unlimited)
    }

    @Test
    fun `只有安静档要求在有事时才开口`() {
        assertTrue(ProactiveIntensity.QUIET.onlyWhenNeeded)
        assertFalse(ProactiveIntensity.RESERVED.onlyWhenNeeded)
        assertFalse(ProactiveIntensity.STANDARD.onlyWhenNeeded)
        assertFalse(ProactiveIntensity.ACTIVE.onlyWhenNeeded)
        assertFalse(ProactiveIntensity.CLINGY.onlyWhenNeeded)
    }

    @Test
    fun `按档位过滤候选能砍掉该档位不该说的类型`() {
        val ledger = listOf(
            thought(id = "t1", kind = ThoughtKind.REMINDER),
            thought(id = "t2", kind = ThoughtKind.SHARE),
            thought(id = "t3", kind = ThoughtKind.MILESTONE),
            thought(id = "t4", kind = ThoughtKind.CARE),
        )

        val quiet = ledger.filter { ProactiveIntensity.QUIET.allows(it.kindEnum()) }
        assertEquals(listOf("t1"), quiet.map { it.id })

        val reserved = ledger.filter { ProactiveIntensity.RESERVED.allows(it.kindEnum()) }
        assertEquals(listOf("t1", "t4"), reserved.map { it.id })

        val standard = ledger.filter { ProactiveIntensity.STANDARD.allows(it.kindEnum()) }
        assertEquals(ledger.map { it.id }, standard.map { it.id })
    }

    @Test
    fun `非法档位名回落到默认`() {
        assertEquals(ProactiveIntensity.DEFAULT, ProactiveIntensity.fromName("NOT_A_LEVEL"))
        assertEquals(ProactiveIntensity.DEFAULT, ProactiveIntensity.fromName(null))
        assertEquals(ProactiveIntensity.QUIET, ProactiveIntensity.fromName("QUIET"))
    }

    private fun thought(id: String, kind: ThoughtKind) = PendingThoughtEntity(
        id = id,
        assistantId = "a1",
        kind = kind.name,
        seed = "测试用",
        weight = 0.5f,
    )
}
