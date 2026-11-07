package io.zer0.muse.miniphone.ui.moments

import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 发布动态页的门控与回传（小手机 P2b 第三批）。
 *
 * 这里最容易做错又最难看出的两件事：
 *
 *  - **空草稿能不能点"发布"**：能点就会写出一条空动态（列表里一个空气泡，删都找不到）；
 *    反过来该能点时点不动，用户以为按钮坏了。
 *  - **"仅自己可见"的默认值**：默认应是公开。把默认写反，用户发出去才发现
 *    朋友看不见 —— 这是最难追回的一种错。
 *
 * 断言走 `testTag`（比中文标签稳：改文案不该让测试红）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MomentPublishUiTest {

    @get:Rule
    val compose = createComposeRule()

    private class Captured {
        var calls = 0
        var text: String? = null
        var images: List<Uri> = emptyList()
        var onlySelf: Boolean? = null
        var closes = 0
    }

    private fun render(captured: Captured) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MomentPublishScreen(
                    onClose = { captured.closes += 1 },
                    onPublish = { text, images, onlySelf ->
                        captured.calls += 1
                        captured.text = text
                        captured.images = images
                        captured.onlySelf = onlySelf
                    },
                )
            }
        }
    }

    private fun res(@androidx.annotation.StringRes id: Int): String =
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    @Test
    fun `empty draft cannot be published`() {
        val captured = Captured()
        render(captured)

        compose.onNodeWithText(res(R.string.mp_publish_title)).assertExists()
        compose.onNodeWithTag(MiniPhoneMomentsTags.PUBLISH_CONFIRM).assertIsNotEnabled()
        assertTrue("没点成就该一次都不回调", captured.calls == 0)
    }

    @Test
    fun `只有空白字符也不算写了东西`() {
        val captured = Captured()
        render(captured)

        compose.onNode(hasSetTextAction()).performTextInput("   ")

        compose.onNodeWithTag(MiniPhoneMomentsTags.PUBLISH_CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun `写下想法后能发布 并把正文与默认可见性交出去`() {
        val captured = Captured()
        render(captured)

        compose.onNode(hasSetTextAction()).performTextInput("今天下雨了")
        compose.onNodeWithTag(MiniPhoneMomentsTags.PUBLISH_CONFIRM).assertIsEnabled()
        compose.onNodeWithTag(MiniPhoneMomentsTags.PUBLISH_CONFIRM).performClick()

        assertEquals(1, captured.calls)
        assertEquals("今天下雨了", captured.text)
        assertEquals("默认不带图", emptyList<Uri>(), captured.images)
        assertEquals("默认是公开：仅自己可见必须为 false", false, captured.onlySelf)
    }

    @Test
    fun `打开仅自己可见后回传的是开过的状态`() {
        val captured = Captured()
        render(captured)

        compose.onNode(hasSetTextAction()).performTextInput("只给自己看")
        compose.onNodeWithTag(MiniPhoneMomentsTags.PUBLISH_VISIBLE_SELF).performClick()
        compose.onNodeWithTag(MiniPhoneMomentsTags.PUBLISH_CONFIRM).performClick()

        assertEquals(1, captured.calls)
        assertEquals(true, captured.onlySelf)
    }

    @Test
    fun `取消把关闭意图交回调用方`() {
        val captured = Captured()
        render(captured)

        compose.onNodeWithTag(MiniPhoneMomentsTags.PUBLISH_CLOSE).performClick()

        assertEquals(1, captured.closes)
        assertEquals("取消不该顺手发布", 0, captured.calls)
    }
}
