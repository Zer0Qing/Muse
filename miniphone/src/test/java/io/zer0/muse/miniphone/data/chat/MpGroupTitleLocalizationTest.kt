package io.zer0.muse.miniphone.data.chat

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
 * 群名的分隔符与收口词要跟着界面语言走。
 *
 * 群名在建群那一刻写进 `mp_thread.title`，之后切语言它不会变 —— 所以拼的那一刻就必须用当前语言，
 * 否则中文建的群在英文界面上仍叫「阿远、小满等 5 人」。
 *
 * 断言读真实资源、不写死中文：测试环境的 locale 不是中文，写死只会把「英文界面拿到中文群名」这个真缺陷盖回去。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpGroupTitleLocalizationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `分隔符与收口词取自当前语言`() {
        val separator = context.getString(R.string.mp_group_title_separator)
        val names = listOf("阿远", "小满", "阿吾")
        assertEquals(names.joinToString(separator), groupTitleOf(names, separator = separator))
    }

    @Test
    fun `超过三个人时收口词也按当前语言`() {
        val separator = context.getString(R.string.mp_group_title_separator)
        val names = listOf("阿远", "小满", "阿吾", "老陈", "小北")
        val title = groupTitleOf(
            names,
            separator = separator,
            fold = { count -> context.getString(R.string.mp_group_title_fold, count) },
        )
        val expectedTail = context.getString(R.string.mp_group_title_fold, names.size)
        assertTrue("收口词要用资源里的那句: $title", title.endsWith(expectedTail))
        assertFalse("不能留下没被替换的占位符: $title", title.contains("%"))
        // N 是全部人数，不是省略掉的人数
        assertTrue("人数要写对: $title", title.contains(names.size.toString()))
    }

    @Test
    fun `不给参数时退回既有默认 行为不变`() {
        assertEquals("阿远、小满", groupTitleOf(listOf("阿远", "小满")))
        assertTrue("默认收口仍是「等 N 人」", groupTitleOf(listOf("a", "b", "c", "d", "e")).endsWith("等 5 人"))
    }
}
