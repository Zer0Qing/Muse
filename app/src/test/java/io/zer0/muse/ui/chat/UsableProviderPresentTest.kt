package io.zer0.muse.ui.chat

import io.zer0.ai.core.ProviderConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「有没有可用模型服务」的判定口径。
 *
 * 这个布尔值决定发消息是被闸门拦下并告知用户,还是让"正在思考"挂到超时后什么都不说。
 * 判错两个方向都是伤害用户:误拦 = 配好了也发不出;漏判 = 静默空回合。
 */
class UsableProviderPresentTest {

    private fun provider(key: String = "", enabled: Boolean = true, allowMissing: Boolean = false) = ProviderConfig(
        id = "p-${key.ifBlank { "empty" }}-$enabled-$allowMissing",
        displayName = "测试供应商",
        apiKey = key,
        enabled = enabled,
        allowMissingApiKey = allowMissing,
    )

    @Test
    fun `一个供应商都没有时不可用`() {
        assertFalse(usableProviderPresent(emptyList()))
    }

    @Test
    fun `启用且填了密钥时可用`() {
        assertTrue(usableProviderPresent(listOf(provider(key = "sk-test"))))
    }

    @Test
    fun `启用但没密钥且必须密钥时不可用`() {
        assertFalse(usableProviderPresent(listOf(provider(key = ""))))
    }

    @Test
    fun `只有空白的密钥不算填了`() {
        assertFalse(usableProviderPresent(listOf(provider(key = "   "))))
    }

    @Test
    fun `免密钥供应商不填也能用`() {
        assertTrue(usableProviderPresent(listOf(provider(key = "", allowMissing = true))))
    }

    @Test
    fun `被停用的供应商有密钥也不算可用`() {
        assertFalse(usableProviderPresent(listOf(provider(key = "sk-test", enabled = false))))
    }

    @Test
    fun `列表里只要有一个可用就算可用`() {
        val list = listOf(
            provider(key = "", enabled = false),
            provider(key = ""),
            provider(key = "sk-ok"),
        )
        assertTrue(usableProviderPresent(list))
    }
}
