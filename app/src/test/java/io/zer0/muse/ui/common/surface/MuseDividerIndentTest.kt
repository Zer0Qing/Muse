package io.zer0.muse.ui.common.surface

import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分隔线起点缩进的纯函数不变量（回归测试）。
 *
 * 目标：分隔线跟随内容左边线，而非无条件 56dp。
 *  - 无 leading 图标：落到行内边距 [MusePaddings.screen]，与无图标卡的文字起点对齐。
 *  - 有 leading 图标：缩到图标右侧文字列 = 行内边距 + 图标 + 图标-文字间距，
 *    三项与 MuseListItem 的 leading 布局同源。
 *
 * 旧实现无条件 56dp，会让无图标卡的分隔线比内容线多缩一个图标宽；这里把两档钉成断言。
 */
class MuseDividerIndentTest {

    @Test
    fun `无 leading 图标时缩进等于行内边距`() {
        assertEquals(MusePaddings.screen, museDividerStartIndent(hasLeading = false))
        assertEquals(16.dp, museDividerStartIndent(hasLeading = false))
    }

    @Test
    fun `有 leading 图标时缩进等于边距加图标加间距`() {
        val expected = MusePaddings.screen + MuseIconSizes.icon + MusePaddings.screen
        assertEquals(expected, museDividerStartIndent(hasLeading = true))
        assertEquals(56.dp, museDividerStartIndent(hasLeading = true))
    }
}
