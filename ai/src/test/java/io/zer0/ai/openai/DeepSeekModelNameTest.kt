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
}
