package io.zer0.muse.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话标题展示口径（实机走查 实-W3-02/03）。
 *
 * 同一会话在首页、聊天顶栏、搜索与备份导出里必须叫同一个名字；
 * 空白、纯符号、以及历史上按当时语言落库的默认标题，都算"没有名字"。
 */
class SessionTitleDisplayTest {

    private val fallback = "Nuevo chat"

    @Test
    fun `空白与纯符号标题回落到兜底名`() {
        assertEquals(fallback, displaySessionTitle(null, fallback))
        assertEquals(fallback, displaySessionTitle("", fallback))
        assertEquals(fallback, displaySessionTitle("   ", fallback))
        assertEquals(fallback, displaySessionTitle("…", fallback))
        assertEquals(fallback, displaySessionTitle("。。。", fallback))
    }

    @Test
    fun `各语言的旧默认标题也回落到当前语言的兜底名`() {
        legacyDefaultSessionTitles.forEach { literal ->
            assertEquals(
                "「$literal」是写死的默认标题,不该在西语界面里原样挂着",
                fallback,
                displaySessionTitle(literal, fallback),
            )
            assertTrue("写入层也要能把「$literal」改名", isMeaninglessSessionTitle(literal))
        }
    }

    @Test
    fun `用户自己起的名字原样保留`() {
        assertEquals("周报草稿", displaySessionTitle("周报草稿", fallback))
        assertEquals("会议记录", displaySessionTitle("  会议记录  ", fallback))
    }
}
