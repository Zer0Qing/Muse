package io.zer0.ai.openai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.0.74: DeepSeek 官方 API 模型名归一化测试。
 * listModels 返回带日期/大小写变体(如 DeepSeek-V4-Flash-0731),但 /chat/completions
 * 只接受规范名(deepseek-v4-pro / deepseek-v4-flash),直接发原 id 会 400(真实用户实测)。
 */
class DeepSeekModelNameTest {

    private val deepseekBase = "https://api.deepseek.com/v1"
    private val thirdPartyBase = "https://api.thirdparty.com/v1"

    @Test
    fun `flash with date suffix normalized`() {
        assertEquals("deepseek-v4-flash", normalizeDeepSeekModelId("DeepSeek-V4-Flash-0731", deepseekBase))
    }

    @Test
    fun `flash lowercase with date normalized`() {
        assertEquals("deepseek-v4-flash", normalizeDeepSeekModelId("deepseek-v4-flash-0731", deepseekBase))
    }

    @Test
    fun `flash canonical unchanged`() {
        assertEquals("deepseek-v4-flash", normalizeDeepSeekModelId("deepseek-v4-flash", deepseekBase))
    }

    @Test
    fun `pro with date suffix normalized`() {
        assertEquals("deepseek-v4-pro", normalizeDeepSeekModelId("DeepSeek-V4-Pro-0731", deepseekBase))
    }

    @Test
    fun `pro canonical unchanged`() {
        assertEquals("deepseek-v4-pro", normalizeDeepSeekModelId("deepseek-v4-pro", deepseekBase))
    }

    @Test
    fun `bare v4 maps to pro`() {
        assertEquals("deepseek-v4-pro", normalizeDeepSeekModelId("deepseek-v4", deepseekBase))
    }

    @Test
    fun `non-v4 deepseek models unchanged`() {
        assertEquals("deepseek-chat", normalizeDeepSeekModelId("deepseek-chat", deepseekBase))
        assertEquals("deepseek-reasoner", normalizeDeepSeekModelId("deepseek-reasoner", deepseekBase))
    }

    @Test
    fun `third party baseUrl not normalized`() {
        // 中转站可能有自定义命名,不做归一化(避免破坏自定义模型)
        assertEquals("DeepSeek-V4-Flash-0731", normalizeDeepSeekModelId("DeepSeek-V4-Flash-0731", thirdPartyBase))
    }

    // ── v2.6.1: 中转站前导分组标签剥离 ────────────────────────────

    @Test
    fun `channel group label stripped`() {
        assertEquals("gemini-3.8-flash", stripChannelGroupLabelsImpl("[ais]gemini-3.8-flash"))
    }

    @Test
    fun `multiple group labels stripped`() {
        assertEquals("claude-3", stripChannelGroupLabelsImpl("[A][vip]claude-3"))
    }

    @Test
    fun `clean model name untouched`() {
        assertEquals("deepseek-v4-flash", stripChannelGroupLabelsImpl("deepseek-v4-flash"))
    }

    @Test
    fun `provider slash prefix preserved`() {
        // 斜杠前缀是中转站路由必需,不是分组标签 —— 不能被误剥。
        assertEquals("openai/gpt-4o", stripChannelGroupLabelsImpl("openai/gpt-4o"))
    }

    @Test
    fun `pure label without model name falls back`() {
        // 纯标签(无真实模型名)不应剥成空串,回退原值避免发空 model。
        assertEquals("[ais]", stripChannelGroupLabelsImpl("[ais]"))
    }

    @Test
    fun `brackets inside model name are not touched`() {
        // 模型名中间的方括号不属于前导标签,不剥离。
        assertEquals("model[1]", stripChannelGroupLabelsImpl("model[1]"))
    }
}
