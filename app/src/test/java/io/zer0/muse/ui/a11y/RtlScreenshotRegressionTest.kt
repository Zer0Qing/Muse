package io.zer0.muse.ui.a11y

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A11Y-07: RTL 回归（`supportsRtl="true"` 但此前无任何验证）。
 *
 * **覆盖范围（诚实说明，A11Y-07 复核）**：本文件是 Robolectric/JVM 测试，
 * **只做布局方向断言，不做像素级截图回归**（无 Bitmap 采样、无基准图比对）。
 * 类名中的 "Screenshot" 是历史命名，实际能力如下：
 * 1. `ar-rSA` locale 下根布局方向为 [LayoutDirection.Rtl]（属性层断言）；
 * 2. RTL 下文本按像素坐标真实落位于父容器右侧，且具备实际渲染尺寸
 *    （证明方向不只属性正确，还真实作用于排版）。
 *
 * **未覆盖**：真实设备/模拟器上的像素级截图对比（字体、间距、镜像图标、
 * 双向混排断行等）。此前注释引用的 `RtlScreenshotInstrumentedTest`
 * 在 `app/src/androidTest/` 中并不存在；若后续要补，需新增 instrumented
 * 截图测试并在 AVD/真机上运行，届时本节应同步更新。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "ar-rSA")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RtlScreenshotRegressionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** 采样文案:混排阿拉伯语/中日/拉丁,防止纯拉丁文本掩盖 RTL 方向错误。 */
    private val rtlSample = "مرحبا Muse · 設置 · Settings"

    @Test
    fun `ar locale root layout direction is RTL`() {
        var captured by mutableStateOf(LayoutDirection.Ltr)
        composeTestRule.setContent {
            MaterialTheme {
                captured = LocalLayoutDirection.current
                Text(text = rtlSample)
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(LayoutDirection.Rtl, captured)
    }

    @Test
    fun `ar locale text is laid out right aligned with real size`() {
        var textX by mutableStateOf(-1f)
        var widthPx by mutableStateOf(-1f)
        var heightPx by mutableStateOf(-1f)
        composeTestRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = rtlSample,
                        modifier = Modifier.onGloballyPositioned {
                            textX = it.positionInParent().x
                            widthPx = it.size.width.toFloat()
                            heightPx = it.size.height.toFloat()
                        },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        assertTrue("RTL 下文本应落位于父容器右侧而非左缘(textX=$textX)", textX > 0f)
        assertTrue("RTL 文本应具备实际渲染宽度(width=$widthPx)", widthPx > 0f)
        assertTrue("RTL 文本应具备实际渲染高度(height=$heightPx)", heightPx > 0f)
    }
}
