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
 * 评论行与回复行的模板：参数个数要对得上，标点与词序跟着语言走。
 *
 * 这两个模板直接读真实资源，不做替身 —— 多传或少传一个参数在运行时只会显示成 `%3$s`
 * 或前后错位，不会崩，所以必须在这里把「模板里有几个占位符」钉死。
 *
 * 断言不写死任何一种语言：测试环境的 locale 不是中文，写死中文只会把「英文界面拿到中文模板」
 * 这个真缺陷又盖回去。要比的是「成品与当前语言的模板一致」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MomentLineTemplateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `评论行模板吃两个参数 拼出名字与正文`() {
        val template = context.getString(R.string.mp_moments_comment_line)
        val text = context.getString(R.string.mp_moments_comment_line, "阿远", "写得好")
        assertEquals(template.replace("%1\$s", "阿远").replace("%2\$s", "写得好"), text)
        assertFalse("不能留下没被替换的占位符: $text", text.contains("%"))
        assertTrue("名字与正文都要出现在成品里: $text", text.contains("阿远") && text.contains("写得好"))
    }

    @Test
    fun `回复行模板吃三个参数 拼出谁回复谁与正文`() {
        val text = context.getString(R.string.mp_moments_reply_line, "小满", "阿远", "同意")
        assertFalse("不能留下没被替换的占位符: $text", text.contains("%"))
        listOf("小满", "阿远", "同意").forEach { part ->
            assertTrue("「$part」必须出现在回复行里: $text", text.contains(part))
        }
    }

    @Test
    fun `两个模板的占位符顺序都是先人后话`() {
        // 位置重排（%2$s 出现在 %1$s 前面）会让「谁回复谁」读反，这里不依赖那种写法。
        assertEquals("%1\$s", Regex("%\\d\\\$s").find(context.getString(R.string.mp_moments_comment_line))?.value)
        assertEquals(
            listOf("%1\$s", "%2\$s", "%3\$s"),
            Regex("%\\d\\\$s").findAll(context.getString(R.string.mp_moments_reply_line)).map { it.value }.toList(),
        )
    }
}
