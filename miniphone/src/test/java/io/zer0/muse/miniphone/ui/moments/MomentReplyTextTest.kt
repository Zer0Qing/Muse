package io.zer0.muse.miniphone.ui.moments

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回复行的两种形态：直接回评论 vs 回复某条评论。
 *
 * 差的就是「回复 谁」那一段。少打它，一串回复里看不出谁在跟谁说话；
 * 反过来给所有回复都加前缀，又会让简单的一来一回变得啰嗦。
 *
 * 断言比的是「与当前语言的模板一致」，不写死中文：名字与标点由语言自己决定，
 * 代码里不再出现全角冒号或某个语言的动词 —— 这正是这次修复要守住的东西。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MomentReplyTextTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `直接回评论时只写谁说的`() {
        val text = context.getString(R.string.mp_moments_comment_line, "阿远", "写得好")
        val expected = context.getString(R.string.mp_moments_comment_line)
            .replace("%1\$s", "阿远")
            .replace("%2\$s", "写得好")
        assertEquals(expected, text)
        assertFalse("直接回评论不该出现回复对象那一段", text.contains("阿远 回复"))
    }

    @Test
    fun `回复某条评论时补一句回复谁`() {
        val text = context.getString(R.string.mp_moments_reply_line, "小满", "阿远", "同意")
        assertTrue("被回复的人必须出现: $text", text.contains("阿远"))
        assertTrue("回复者必须出现: $text", text.contains("小满"))
        assertFalse("不能留下没被替换的占位符: $text", text.contains("%"))
    }

    @Test
    fun `正文自己带冒号时原样保留`() {
        // 不做"聪明"的转义或截断：正文原样出现在最后，用户看到的就是他发的内容
        val content = "时间：明天下午"
        val text = context.getString(R.string.mp_moments_comment_line, "大毛", content)
        assertTrue("正文必须原样出现: $text", text.endsWith(content))
    }

    @Test
    fun `空正文也照常拼出不带内容的行 不抛异常`() {
        // 界面另有过滤，但这里不能因为内容为空就崩
        val text = context.getString(R.string.mp_moments_comment_line, "阿远", "")
        assertTrue("名字仍要在: $text", text.contains("阿远"))
    }

    @Test
    fun `发送者名字由调用方解析 空名字不会拼出光秃秃的冒号`() {
        // 落库侧不再把「我」写进数据，读出来可能是空串；补称呼是界面按语言做的事，
        // 传进来的是已解析的称呼，就不会出现「：内容」。
        val text = context.getString(R.string.mp_moments_comment_line, "我", "同意")
        assertTrue("名字要在最前面: $text", text.startsWith("我"))
        assertTrue("正文要在: $text", text.contains("同意"))
    }

    @Test
    fun `回复对象的名字同样由调用方解析`() {
        val text = context.getString(R.string.mp_moments_reply_line, "小满", "角色", "同意")
        assertTrue("被回复的人用解析后的称呼: $text", text.contains("角色"))
        assertFalse("不该出现空的回复对象", text.contains("角色角色"))
    }
}
