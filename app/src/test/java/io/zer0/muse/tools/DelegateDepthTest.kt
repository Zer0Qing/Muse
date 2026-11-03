package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 委派深度规则（协作语义显式化 · P4）。
 *
 * 这里测的不是"常量等于 3"这一件事，而是**两条判定之间的关系**——
 * 它们此前是两处内联比较（入口 `depth > 3`、下发工具 `depth < 3`），
 * 各写一个 3 迟早漂移，而漂移不会编译报错，只会得到
 * "递归到第 5 层还没停"这种把本地模型压穿的现场。
 */
class DelegateDepthTest {

    @Test
    fun `最深允许到第三层`() {
        assertEquals(3, DelegateDepth.MAX)
    }

    @Test
    fun `每一层能不能进来与还能不能再给委派工具是两条不同的线`() {
        // 1、2 层：可以进来，也可以再往下委派
        (1..2).forEach { depth ->
            assertTrue("第 $depth 层应可进入", DelegateDepth.allowsEntry(depth))
            assertTrue("第 $depth 层的子 agent 应还能拿到 delegate_agent", DelegateDepth.shouldOfferDelegateTool(depth))
        }

        // 第 3 层：还能进来，但**不再拿到** delegate_agent —— 这就是递归的自然止境
        assertTrue(DelegateDepth.allowsEntry(3))
        assertFalse("最深那一层不能再拿到委派工具，否则递归停不下来", DelegateDepth.shouldOfferDelegateTool(3))

        // 第 4 层：连进都进不来（只有 ThreadLocal 没归零这种异常路径才会到这儿）
        assertFalse(DelegateDepth.allowsEntry(4))
        assertFalse(DelegateDepth.shouldOfferDelegateTool(4))
    }

    @Test
    fun `入口放行过的深度里至少有一层是收不到委派工具的`() {
        // 关系断言：入口上限与工具上限不能是同一个数，
        // 否则"最后一层还能委派"→ 下一次委派深度 = MAX+1，只能靠入口硬拒，
        // 而入口硬拒是异常兜底，不该是常规停止手段。
        val entryAllowed = (1..10).filter(DelegateDepth::allowsEntry)
        val toolAllowed = (1..10).filter(DelegateDepth::shouldOfferDelegateTool)

        assertTrue(entryAllowed.size == toolAllowed.size + 1)
        assertEquals(entryAllowed.maxOf { it }, toolAllowed.maxOf { it } + 1)
    }
}
