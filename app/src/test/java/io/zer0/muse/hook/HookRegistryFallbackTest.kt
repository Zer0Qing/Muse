package io.zer0.muse.hook

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 未登记接口的派发兜底测试。
 *
 * [HookRegistry] 按 [HookRegistry.HOOK_TYPES] 分桶，登记漏一行就会让某个接口的派发点
 * 永远取到空表，而且不报错、不留日志。这里用一个故意不登记的接口验证兜底路径：
 * 只要实例实现了该接口，派发就必须能取到它。
 */
class HookRegistryFallbackTest {

    /** 测试专用接口，不在 HOOK_TYPES 里。 */
    private interface UnregisteredHook : SkillHook {
        fun tag(): String
    }

    private class SampleHook(override val id: String = "sample") : UnregisteredHook {
        override fun tag(): String = "hit:$id"
    }

    @Test
    fun `未登记在 HOOK_TYPES 的接口也能派发到实例`() = runTest {
        val registry = HookRegistry()
        registry.register(SampleHook())

        val result = registry.execute(UnregisteredHook::class, initial = "") { hook, acc -> acc + hook.tag() }

        assertEquals("hit:sample", result)
    }

    @Test
    fun `注销后不再派发`() = runTest {
        val registry = HookRegistry()
        val hook = SampleHook()
        registry.register(hook)
        registry.unregister(hook)

        val result = registry.execute(UnregisteredHook::class, initial = "") { h, acc -> acc + h.tag() }

        assertEquals("", result)
        assertEquals(0, registry.size())
    }

    @Test
    fun `已登记接口仍按 priority 降序执行`() = runTest {
        val registry = HookRegistry()
        registry.register(SampleHook("low"))
        registry.register(
            object : UnregisteredHook {
                override val id = "high"
                override val priority = 90
                override fun tag(): String = "hit:$id"
            },
        )

        val result = registry.execute(UnregisteredHook::class, initial = "") { hook, acc -> acc + hook.tag() }

        assertEquals("hit:highhit:low", result)
    }
}
