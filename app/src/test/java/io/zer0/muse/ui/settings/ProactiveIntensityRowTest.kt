package io.zer0.muse.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.proactive.ProactiveIntensity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.zer0.muse.R

/**
 * 强度控件（整改计划 第 4 条差异①）的渲染结构。
 *
 * 换算逻辑已由 `ProactiveIntensityTrackTest` 钉住；这里管的是**画出来的东西**：
 *  - 五个档位名必须**各出现一次**。本仓出过"同一标题渲染两行"的缺陷
 *    （见同目录 `AutonomousAccessibilityCardRowTest` 的说明），那种问题人眼在真机上
 *    最容易忽略，结构断言比截图更早发现；
 *  - 只有当前档位那一格带把手文字（其余格是刻度点），所以文字总数恰好等于档位数 ——
 *    多出来的就是重复渲染。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProactiveIntensityRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun labels(): List<String> =
        ProactiveIntensity.entries.map { context.getString(labelResOf(it)) }

    /** 与控件同一份映射；测试里复刻是为了断言"五个名字都在场"，不是抄第二份业务表。 */
    private fun labelResOf(level: ProactiveIntensity): Int = when (level) {
        ProactiveIntensity.QUIET -> R.string.proactive_intensity_quiet
        ProactiveIntensity.RESERVED -> R.string.proactive_intensity_reserved
        ProactiveIntensity.STANDARD -> R.string.proactive_intensity_standard
        ProactiveIntensity.ACTIVE -> R.string.proactive_intensity_active
        ProactiveIntensity.CLINGY -> R.string.proactive_intensity_clingy
    }

    private fun show(current: ProactiveIntensity) {
        composeTestRule.setContent {
            MaterialTheme {
                IntensityRow(current = current, onSelect = {})
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun countOf(text: String): Int =
        composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun `五个档位名各出现一次不重复`() {
        show(ProactiveIntensity.STANDARD)
        val counts = labels().associateWith { countOf(it) }
        assertEquals(
            "每个档位名都必须且只能出现一次（重复=同一行画了两遍）：$counts",
            ProactiveIntensity.entries.size,
            counts.values.sum(),
        )
        counts.forEach { (label, n) -> assertEquals("档位「$label」出现了 $n 次", 1, n) }
    }

    @Test
    fun `换到最热情档也不该多出第二个把手文字`() {
        show(ProactiveIntensity.CLINGY)
        val all = labels()
        assertEquals("总文字数应恰等于档位数", ProactiveIntensity.entries.size, all.sumOf { countOf(it) })
        assertEquals("当前档也必须只有一次", 1, countOf(context.getString(labelResOf(ProactiveIntensity.CLINGY))))
    }

    /**
     * 两端各测一次。**每个用例只能 setContent 一次** ——
     * 我第一版在同一个测试里循环渲染了两档，直接撞
     * `ComponentActivity has already set content`：那是测试自己的毛病，不是被测对象的。
     */
    @Test
    fun `最静一档下文字总数等于档位数`() {
        assertLabelTotal(ProactiveIntensity.QUIET)
    }

    @Test
    fun `较热一档下文字总数仍等于档位数`() {
        assertLabelTotal(ProactiveIntensity.ACTIVE)
    }

    private fun assertLabelTotal(level: ProactiveIntensity) {
        show(level)
        val total = labels().sumOf { countOf(it) }
        assertEquals(
            "$level 下文字总数不对（每档各一次，把手不该额外多出一份文字）",
            ProactiveIntensity.entries.size,
            total,
        )
    }
}
