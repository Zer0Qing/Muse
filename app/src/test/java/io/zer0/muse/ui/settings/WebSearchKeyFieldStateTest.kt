package io.zer0.muse.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「多引擎 API Key」一行的显示/保存语义。
 *
 * 改成默认掩码时最容易引入的新坑是：**输入框看起来是空的，保存就被当成清空**。
 * 所以「没动过」必须与「动过但清空」区分开，这两条各有断言。
 */
class WebSearchKeyFieldStateTest {

    @Test
    fun `已保存的 Key 默认必须掩码，揭示后才明文`() {
        assertTrue("有已保存密钥时必须默认遮住", WebSearchKeyFieldState.shouldMask("tvly-secret", revealed = false))
        assertFalse("用户点了显示之后不再遮", WebSearchKeyFieldState.shouldMask("tvly-secret", revealed = true))
        assertFalse("还没有密钥时无事可遮", WebSearchKeyFieldState.shouldMask("", revealed = false))
    }

    @Test
    fun `没有已保存密钥时不提供显示切换`() {
        assertFalse(WebSearchKeyFieldState.canToggleVisibility(""))
        assertTrue(WebSearchKeyFieldState.canToggleVisibility("tvly-secret"))
    }

    @Test
    fun `未编辑时显示的是已保存值而不是空串`() {
        assertEquals("tvly-secret", WebSearchKeyFieldState.displayValue("tvly-secret", draft = "", edited = false))
        assertEquals("新草稿", WebSearchKeyFieldState.displayValue("tvly-secret", draft = "新草稿", edited = true))
    }

    @Test
    fun `什么都没动就保存不得删掉密钥`() {
        // 这条是掩码改造最大的回归风险：输入框空着 ≠ 用户想清空
        assertEquals(
            "未编辑时保持原值",
            "tvly-secret",
            WebSearchKeyFieldState.valueToSave("tvly-secret", draft = "", edited = false),
        )
    }

    @Test
    fun `编辑后清空才是删除`() {
        assertNull(
            "动过且留空＝用户要清掉这把 Key",
            WebSearchKeyFieldState.valueToSave("tvly-secret", draft = "   ", edited = true),
        )
    }

    @Test
    fun `编辑后写入新值并去掉首尾空白`() {
        assertEquals(
            "保存前应去掉首尾空白",
            "new-key",
            WebSearchKeyFieldState.valueToSave("old", draft = "  new-key  ", edited = true),
        )
    }

    @Test
    fun `原本就没有密钥时保存空值不会凭空造出删除动作`() {
        assertNull(WebSearchKeyFieldState.valueToSave("", draft = "", edited = false))
    }
}
