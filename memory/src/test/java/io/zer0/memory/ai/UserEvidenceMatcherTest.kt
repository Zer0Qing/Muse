package io.zer0.memory.ai

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v2.6.23(M1): 用户原话证据匹配的单测。
 *
 * 钉住四条判定:
 *  1. 只从 USER 消息找,助手消息永远不算出处;
 *  2. 归一化(标点/空白剥离)后子串命中才算,措辞不同不硬配;
 *  3. 短事实不配证据("喜欢咖啡"级别的事实必然误配);
 *  4. 找不到出处返回 null(宁缺毋滥)。
 */
class UserEvidenceMatcherTest {

    private fun user(text: String) = UIMessage(role = MessageRole.USER, content = text)

    private fun assistant(text: String) = UIMessage(role = MessageRole.ASSISTANT, content = text)

    @Test
    fun `用户消息含事实时命中并带回消息 id`() {
        val message = user("我下周三下午要去杭州出差,住西湖边")
        val hit = UserEvidenceMatcher.find(listOf(message), "用户下周三下午要去杭州出差", minFactChars = 6)

        assertNotNull(hit)
        assertEquals(message.content, hit?.first)
        assertEquals(message.id.toString(), hit?.second)
    }

    @Test
    fun `标点与空白差异不阻挡命中`() {
        val message = user("我 养了一只,叫「豆豆」的金毛犬")
        val hit = UserEvidenceMatcher.find(listOf(message), "用户养了一只叫豆豆的金毛犬", minFactChars = 6)

        assertNotNull(hit)
    }

    @Test
    fun `助手消息永远不算出处`() {
        val history = listOf(assistant("您之前说过每周三要去杭州出差"))

        assertNull(UserEvidenceMatcher.find(history, "每周三要去杭州出差", minFactChars = 6))
    }

    @Test
    fun `过短的事实不配证据宁缺毋滥`() {
        val history = listOf(user("我喜欢咖啡,少糖"))

        // "喜欢咖啡" 归一化后 4 字,低于下限 → 即便子串命中也不写
        assertNull(UserEvidenceMatcher.find(history, "喜欢咖啡", minFactChars = 6))
    }

    @Test
    fun `历史为空或 null 时返回 null`() {
        assertNull(UserEvidenceMatcher.find(null, "用户在深圳南山区科技园上班", minFactChars = 6))
        assertNull(UserEvidenceMatcher.find(emptyList(), "用户在深圳南山区科技园上班", minFactChars = 6))
    }

    @Test
    fun `多条命中时取最近一条用户消息`() {
        val older = user("我在学弹吉他,入门阶段")
        val newer = user("我最近开始练钢琴,每天半小时")

        val hit = UserEvidenceMatcher.find(listOf(older, newer, user("好的")), "我最近开始练钢琴", minFactChars = 6)

        assertEquals(newer.id.toString(), hit?.second)
    }
}
