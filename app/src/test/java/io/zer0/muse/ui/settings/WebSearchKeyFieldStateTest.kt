package io.zer0.muse.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联网搜索「Key 池」输入框的显示/保存语义。
 *
 * 输入框默认不回填已保存 Key（防一开屏就泄露），于是最容易引入的新坑是：
 * **框看起来是空的，保存就被当成清空**。所以「没动过」必须与「动过但清空」区分开，
 * 这两条各有断言。指示行只报个数，也要守住不回显内容。
 */
class WebSearchKeyFieldStateTest {

    @Test
    fun `什么都没动就保存不得删掉已保存的 Key 池`() {
        // 这条是不回填改造最大的回归风险：输入框空着 ≠ 用户想清空
        assertEquals(
            "未编辑时保持原池",
            listOf("key-a", "key-b"),
            WebSearchKeyFieldState.poolToSave(listOf("key-a", "key-b"), draft = "", edited = false),
        )
    }

    @Test
    fun `未编辑时草稿里有什么都不影响保存结果`() {
        assertEquals(
            "没动过就不该读草稿",
            listOf("saved"),
            WebSearchKeyFieldState.poolToSave(listOf("saved"), draft = "stray-draft", edited = false),
        )
    }

    @Test
    fun `编辑后清空才是删除`() {
        assertTrue(
            "动过且留空＝用户要清掉整个池",
            WebSearchKeyFieldState.poolToSave(listOf("key-a"), draft = "   ", edited = true).isEmpty(),
        )
    }

    @Test
    fun `编辑后批量粘贴按分隔符解析成多把并去首尾空白`() {
        assertEquals(
            listOf("key-a", "key-b", "key-c"),
            WebSearchKeyFieldState.poolToSave(
                listOf("old"),
                draft = " key-a , key-b\nkey-c ",
                edited = true,
            ),
        )
    }

    @Test
    fun `编辑后中文标点与换行同样当分隔符且去重保序`() {
        assertEquals(
            listOf("a", "b"),
            WebSearchKeyFieldState.poolToSave(emptyList(), draft = "a，b；a\nb", edited = true),
        )
    }

    @Test
    fun `原本就没有 Key 时保存空值不会凭空造出内容`() {
        assertTrue(
            WebSearchKeyFieldState.poolToSave(emptyList(), draft = "", edited = false).isEmpty(),
        )
    }

    @Test
    fun `指示行只数非空 Key 不泄露内容`() {
        assertEquals(2, WebSearchKeyFieldState.savedCount(listOf("key-a", " ", "key-b", "")))
        assertEquals(0, WebSearchKeyFieldState.savedCount(emptyList()))
        assertEquals(0, WebSearchKeyFieldState.savedCount(listOf("", "   ")))
    }

    // ---- 逐条删除语义（对齐模型供应商侧 Key 池的「每行一个 Key + 删除按钮」） ----

    @Test
    fun `按索引删除一把 Key 其余保持原序`() {
        assertEquals(
            listOf("key-a", "key-c"),
            WebSearchKeyFieldState.removeAt(listOf("key-a", "key-b", "key-c"), index = 1),
        )
    }

    @Test
    fun `删除唯一一把后得到空池`() {
        assertTrue(WebSearchKeyFieldState.removeAt(listOf("only"), index = 0).isEmpty())
    }

    @Test
    fun `索引越界时原池原样返回不抛异常`() {
        assertEquals(
            listOf("key-a"),
            WebSearchKeyFieldState.removeAt(listOf("key-a"), index = 5),
        )
        assertEquals(
            emptyList<String>(),
            WebSearchKeyFieldState.removeAt(emptyList(), index = -1),
        )
    }

    // ---- 指示行尾号（掩码展示，不回显完整 Key） ----

    @Test
    fun `长 Key 掩码只露头尾各 3 位`() {
        val masked = WebSearchKeyFieldState.masked("sk-abcdefghijklmnopqrstuvwxyz0123456789")
        assertTrue("应只露头尾", masked.contains("…"))
        assertEquals(
            "sk-…789",
            masked,
        )
        assertTrue(
            "中段不得出现在掩码里",
            !masked.contains("mnopqrstu"),
        )
    }

    @Test
    fun `短 Key 全掩码避免泄露长度信息`() {
        assertEquals("****", WebSearchKeyFieldState.masked("ab12"))
        assertEquals("***", WebSearchKeyFieldState.masked("abc"))
    }

    @Test
    fun `空串不参与尾号指示`() {
        assertEquals("", WebSearchKeyFieldState.masked(""))
    }
}
