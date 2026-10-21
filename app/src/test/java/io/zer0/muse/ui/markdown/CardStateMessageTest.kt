package io.zer0.muse.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.7: 卡片闭环回传的状态尾巴 —— 拼装/剥离必须可逆。
 *
 * 这条测试守的是两个方向：
 *  - 模型侧：`appendCardStateToMessage` 必须把状态带进消息正文（否则下一轮读不到）；
 *  - 用户侧：`stripCardStateFromMessage` 必须把状态剔干净（否则气泡里出现 JSON）。
 * 两边都成立，"卡片在卡片里结束"才算真的补上。
 */
class CardStateMessageTest {

    @Test
    fun `appends card state as machine readable tail`() {
        val text = "把睡眠调到 6 小时"
        val merged = appendCardStateToMessage(
            text = text,
            cardId = "msg-123",
            data = """{"hours":6,"from":8}""",
        )

        assertTrue("正文必须保留", merged.startsWith(text))
        assertTrue("必须带卡片身份", merged.contains("cardId=msg-123"))
        assertTrue("必须带结构化状态", merged.contains("\"hours\""))
        assertTrue("必须有起止标记", merged.contains(CARD_STATE_OPEN) && merged.contains(CARD_STATE_CLOSE))
    }

    @Test
    fun `strip removes state and keeps the sentence`() {
        val text = "把睡眠调到 6 小时"
        val merged = appendCardStateToMessage(text, "msg-123", """{"hours":6}""")

        val displayed = stripCardStateFromMessage(merged)

        assertEquals("气泡只显示那句话", text, displayed)
        assertFalse("不能残留标记", displayed.contains(CARD_STATE_OPEN))
        assertFalse("不能残留 JSON", displayed.contains("hours"))
    }

    @Test
    fun `null or blank data leaves text untouched`() {
        val text = "普通消息"
        assertEquals(text, appendCardStateToMessage(text, "msg-1", null))
        assertEquals(text, appendCardStateToMessage(text, "msg-1", "   "))
    }

    @Test
    fun `plain text without state is unchanged by strip`() {
        val text = "我没有卡片状态"
        assertEquals(text, stripCardStateFromMessage(text))
    }

    @Test
    fun `invalid json is still delivered verbatim`() {
        // 卡片脚本可能拼错 JSON。这时候宁可原样透传，也不能丢状态 ——
        // 模型看到坏 JSON 能自行判断，看到空状态只会以为卡片没回传任何内容。
        val merged = appendCardStateToMessage("摘要", "msg-9", "{not-json")
        assertTrue(merged.contains("{not-json"))
        assertEquals("摘要", stripCardStateFromMessage(merged))
    }

    @Test
    fun `missing card id degrades to unknown instead of dropping state`() {
        val merged = appendCardStateToMessage("摘要", null, """{"a":1}""")
        assertTrue(merged.contains("cardId=unknown"))
    }
}
