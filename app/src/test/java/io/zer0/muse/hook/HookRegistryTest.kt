package io.zer0.muse.hook

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HookRegistry 分桶口径回归测试。
 *
 * 背景:注册侧过去按实现类入桶(`hook::class`),派发侧按接口查询,
 * 两侧口径不一致导致所有 Hook 链永远取到空表,且不抛任何异常
 * —— 世界书注入、楼层裁剪、插件 prompt_finalize、设备能力档案
 * 四套功能因此静默失效过一段时间。这类错误没有异常可抓,只能靠断言盯着。
 */
class HookRegistryTest {

    private class ComposeHook(override val id: String, override val priority: Int = 50) :
        SystemPromptComposeHook

    /** 同时实现两个接口的实现类,用于验证「一个实例进多个桶」。 */
    private class DualHook(override val id: String) : SystemPromptComposeHook, PromptFinalizeHook

    private class DisabledHook(override val id: String) : PromptFinalizeHook {
        override val enabled: Boolean = false
    }

    @Test
    fun `register by implementation class is queryable through interface`() {
        val registry = HookRegistry()
        registry.register(ComposeHook("compose-1"))

        val hooks = registry.getHooks(SystemPromptComposeHook::class)

        assertEquals(1, hooks.size)
        assertEquals("compose-1", hooks.first().id)
    }

    @Test
    fun `one hook implementing two interfaces lands in both buckets`() {
        val registry = HookRegistry()
        registry.register(DualHook("dual-1"))

        assertEquals(1, registry.getHooks(SystemPromptComposeHook::class).size)
        assertEquals(1, registry.getHooks(PromptFinalizeHook::class).size)
        assertEquals(1, registry.size())
    }

    @Test
    fun `unregister removes the hook from every bucket`() {
        val registry = HookRegistry()
        val hook = DualHook("dual-2")
        registry.register(hook)

        registry.unregister(hook)

        assertTrue(registry.getHooks(SystemPromptComposeHook::class).isEmpty())
        assertTrue(registry.getHooks(PromptFinalizeHook::class).isEmpty())
        assertEquals(0, registry.size())
    }

    @Test
    fun `unregister by type and id only touches that bucket`() {
        val registry = HookRegistry()
        registry.register(DualHook("dual-3"))

        registry.unregister(PromptFinalizeHook::class, "dual-3")

        assertTrue(registry.getHooks(PromptFinalizeHook::class).isEmpty())
        assertEquals(1, registry.getHooks(SystemPromptComposeHook::class).size)
    }

    @Test
    fun `disabled hook is filtered out and priority orders descending`() {
        val registry = HookRegistry()
        registry.register(ComposeHook("low", priority = 10))
        registry.register(ComposeHook("high", priority = 90))
        registry.register(DisabledHook("off"))

        val ids = registry.getHooks(SystemPromptComposeHook::class).map { it.id }

        assertEquals(listOf("high", "low"), ids)
        assertTrue(registry.getHooks(PromptFinalizeHook::class).isEmpty())
    }

    @Test
    fun `execute walks the chain in priority order`() = runBlocking {
        val registry = HookRegistry()
        registry.register(ComposeHook("a", priority = 10))
        registry.register(ComposeHook("b", priority = 90))

        val visited = mutableListOf<String>()
        val result = registry.execute(SystemPromptComposeHook::class, initial = "") { hook, acc ->
            visited += hook.id
            acc + hook.id
        }

        assertEquals(listOf("b", "a"), visited)
        assertEquals("ba", result)
    }
}
