package io.zer0.muse.data.plugin

import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.muse.hook.PluginSystemPromptHook
import io.zer0.muse.hook.PromptContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件往系统提示词追加内容时的来源标记（插件计划 P3b）。
 *
 * 这是最敏感的一处：系统提示词决定模型怎么理解自己的角色。
 * 标记的作用是给模型一个"这段来自插件、不是用户指令"的锚点，
 * 即使插件正文里写"忽略以上所有要求"，前面那句也还在。
 */
class PluginPromptInjectionTest {

    @Test
    fun `the marker names the plugin and says it is reference material`() {
        val wrapped = PluginPromptInjection.wrap("翻译助手", "遇到英文时先给中文")

        assertTrue("要点名来源: $wrapped", wrapped.contains("插件「翻译助手」"))
        assertTrue("要说明按参考资料对待: $wrapped", wrapped.contains("参考资料"))
        assertTrue("要说明不是用户指令: $wrapped", wrapped.contains("不是用户指令"))
        assertTrue(wrapped.contains("遇到英文时先给中文"))
    }

    @Test
    fun `blank content produces nothing`() {
        assertEquals("", PluginPromptInjection.wrap("某插件", "   "))
        assertEquals("", PluginPromptInjection.wrap("某插件", ""))
    }

    @Test
    fun `long content is capped so one plugin cannot flood the prompt`() {
        val long = "字".repeat(PluginPromptInjection.MAX_APPEND_CHARS + 500)

        val wrapped = PluginPromptInjection.wrap("某插件", long)

        val body = wrapped.substringAfter("---\n")
        assertEquals(PluginPromptInjection.MAX_APPEND_CHARS, body.length)
    }

    @Test
    fun `a nameless plugin still gets a readable marker`() {
        val wrapped = PluginPromptInjection.wrap("   ", "内容")

        assertTrue("不能出现空的名字: $wrapped", wrapped.contains("未命名插件"))
        assertFalse(wrapped.contains("「」"))
    }

    // ── 钩子 ────────────────────────────────────────────────────────────

    @Test
    fun `the hook returns what the bridge collected`() = runBlocking {
        val bridge: PluginActivationBridge = mockk(relaxed = true)
        coEvery { bridge.composeSystemPromptAppend(any()) } returns "--- 来自插件 ---\n内容"
        val hook = PluginSystemPromptHook(bridge)

        val appended = hook.afterComposeSystemPrompt(context())

        assertEquals("--- 来自插件 ---\n内容", appended)
    }

    @Test
    fun `no plugin means no append at all`() = runBlocking {
        val bridge: PluginActivationBridge = mockk(relaxed = true)
        coEvery { bridge.composeSystemPromptAppend(any()) } returns null

        assertEquals("", PluginSystemPromptHook(bridge).afterComposeSystemPrompt(context()))
    }

    @Test
    fun `the hook runs late so built-in prompt sections stay in front`() {
        val hook = PluginSystemPromptHook(mockk(relaxed = true))

        assertTrue("优先级要低: ${hook.priority}", hook.priority <= 10)
        assertEquals("plugin_system_prompt", hook.id)
    }

    private fun context() = PromptContext(
        assistantId = "a1",
        sessionId = "s1",
        locale = "zh",
        forSubagent = false,
    )
}
