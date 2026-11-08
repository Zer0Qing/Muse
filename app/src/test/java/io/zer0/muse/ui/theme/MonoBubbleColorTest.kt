package io.zer0.muse.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 黑白主题气泡配色回归。
 *
 * 用户反馈：黑白主题暗色模式下气泡颜色「非常奇怪」—— 根因是用户气泡用
 * `primary.copy(alpha = 0.20f)`，而 mono 的 primary 在暗色是近白（#E9E9ED），
 * 叠到近黑背景上变成一层发浊的乳白；开了液态玻璃还会再叠一层竖向渐变。
 *
 * 现在黑白主题改成**扁平实色灰阶 + 不做任何渐变**，这条用例钉住四个值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class MonoBubbleColorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun colorsFor(themeId: String, dark: Boolean): Pair<Color, Color> {
        var user: Color? = null
        var assistant: Color? = null
        composeTestRule.setContent {
            MuseTheme(themeId = themeId, darkTheme = dark) {
                user = MuseBubbleStyles.userSurfaceColor()
                assistant = MuseBubbleStyles.assistantSurfaceColor()
            }
        }
        composeTestRule.waitForIdle()
        return requireNotNull(user) to requireNotNull(assistant)
    }

    @Test
    fun `mono dark bubbles are flat grayscale`() {
        val (user, assistant) = colorsFor("mono", dark = true)
        assertEquals("暗色用户气泡应为中灰实色", 0xFF2E2E34.toInt(), user.toArgb())
        assertEquals("暗色助手气泡应为近背景深灰", 0xFF1C1C21.toInt(), assistant.toArgb())
        assertTrue("必须是实色（不能带透明度）", user.alpha == 1f)
        assertTrue("必须是实色（不能带透明度）", assistant.alpha == 1f)
    }

    @Test
    fun `mono light bubbles are flat grayscale`() {
        val (user, assistant) = colorsFor("mono", dark = false)
        assertEquals(0xFFECECF0.toInt(), user.toArgb())
        assertEquals(0xFFF4F4F6.toInt(), assistant.toArgb())
        assertTrue(user.alpha == 1f)
        assertTrue(assistant.alpha == 1f)
    }

    @Test
    fun `non mono themes use a solid theme container`() {
        val (user, _) = colorsFor("sakura", dark = false)
        // 2.6.24: 20% 主色透明底压在壁纸/渐变上像「糊了一层」；改为实色 primaryContainer。
        assertTrue("非黑白主题也必须是不透明实色", user.alpha == 1f)
    }
}
