package io.zer0.muse.miniphone.ui.moments

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 动态下回复行的文案拼法（小手机 P2b 第四批）。
 *
 * 两种形态差一个词：直接回评论 vs 回复某条评论。
 * 少打「回复 谁」，一串回复里就看不出谁在跟谁说话；
 * 反过来给所有回复都加前缀，又会让简单的一来一回变得啰嗦。
 */
class MomentReplyTextTest {

    private fun reply(
        sender: String,
        content: String,
        to: String?,
        id: String = "r1",
    ) = MomentReplyUi(
        id = id,
        senderName = sender,
        replyToName = to,
        content = content,
        createdAt = 1L,
    )

    @Test
    fun `直接回评论时只写谁说的`() {
        assertEquals("阿远：写得好", reply("阿远", "写得好", to = null).let { replyText(it) })
    }

    @Test
    fun `回复某条评论时补一句回复谁`() {
        val text = reply("小满", "同意", to = "阿远").let { replyText(it) }

        assertEquals("小满 回复 阿远：同意", text)
    }

    @Test
    fun `正文自己带冒号时不额外加工`() {
        // 不做"聪明"的转义或截断：正文原样出现在最后，用户看到的就是他发的内容
        val text = reply("大毛", "时间：明天下午", to = null).let { replyText(it) }

        assertEquals("大毛：时间：明天下午", text)
    }

    @Test
    fun `空正文也照常拼出不带内容的行 不抛异常`() {
        // 界面另有过滤，但这里不能因为内容为空就崩
        assertEquals("阿远：", reply("阿远", "", to = null).let { replyText(it) })
    }
}
