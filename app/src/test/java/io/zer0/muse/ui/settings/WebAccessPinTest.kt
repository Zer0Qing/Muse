package io.zer0.muse.ui.settings

import io.zer0.muse.web.WebServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 网页版 PIN 的形态与「重新生成」行为。
 *
 * PIN 是 Web 端首次访问的凭据，设置页把它明文显示给用户，所以必须固定 6 位纯数字；
 * 「重新生成」要在旧 PIN 立刻失效的意义上真的换一个值 —— 这里用固定种子注入随机源，
 * 避免依赖真实随机的极低概率碰撞。
 */
class WebAccessPinTest {

    private val pinRegex = Regex("^[1-9]\\d{5}$")

    @Test
    fun `pin is six digits without leading zero`() {
        val random = Random(20261030L)
        repeat(50) {
            val pin = WebServerConfig.generateRandomPin(random)
            assertEquals(WebServerConfig.PIN_LENGTH, pin.length)
            assertTrue("PIN 形态不符: $pin", pinRegex.matches(pin))
        }
    }

    @Test
    fun `regenerating twice with the same source yields a different pin`() {
        val random = Random(42L)
        val first = WebServerConfig.generateRandomPin(random)
        val second = WebServerConfig.generateRandomPin(random)
        assertNotEquals("固定种子下连续两次生成不应相同", first, second)
        assertTrue(second, pinRegex.matches(second))
    }

    /** 默认无参调用走 SecureRandom，形态与种子注入版一致。 */
    @Test
    fun `default source also produces six digits`() {
        val pin = WebServerConfig.generateRandomPin()
        assertTrue("PIN 形态不符: $pin", pinRegex.matches(pin))
    }
}
