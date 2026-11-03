package io.zer0.muse.miniphone.ui.chat

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowToast
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 聊天输入框的门控（小手机 P2b 第五批）。
 *
 * 挑这几条是因为它们都是"用户按下去才知道不对"的东西：
 *
 *  - 空内容能点发送 → 库里落一条空气泡，还得手动删；
 *  - 发完不清空 → 再点一次就把同一句话发两遍（聊天里最尴尬的重复）；
 *  - 识别引擎明明不支持还让麦克风亮着 → 用户按了半天录完什么也没有，
 *    只能以为是自己没说完。
 *
 * 只测空闲态：**不驱动真实录音/权限**，那需要设备侧的假件，
 * 而"转写只补进输入框、不自动发送"这条已经由 `appendTranscript` 的纯函数测试钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneChatInputBarUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<String>()

    private fun render(voiceInputEnabled: Boolean = true, onPickImages: () -> Unit = {}) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                ChatInputBar(
                    onSend = { sent += it },
                    onPickImages = onPickImages,
                    voiceInputEnabled = voiceInputEnabled,
                )
            }
        }
    }

    private fun input() = compose.onNode(hasSetTextAction())

    @Test
    fun `空输入时发送键点不动`() {
        render()

        compose.onNodeWithText("说点什么…").assertExists()
        compose.onNodeWithTag(MiniPhoneChatTags.SEND_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun `只有空白字符也不算内容`() {
        render()

        input().performTextInput("   \n ")

        compose.onNodeWithTag(MiniPhoneChatTags.SEND_BUTTON).assertIsNotEnabled()
        assertEquals("不该有任何发送发生", 0, sent.size)
    }

    @Test
    fun `发完清空输入框 不让人再点一次发重复`() {
        render()

        input().performTextInput("下午三点开会")
        compose.onNodeWithTag(MiniPhoneChatTags.SEND_BUTTON).assertIsEnabled()
        compose.onNodeWithTag(MiniPhoneChatTags.SEND_BUTTON).performClick()

        assertEquals(listOf("下午三点开会"), sent)
        // 断"清空了"用行为而不是内部文本：发送键重新变灰就说明框里没内容了，
        // 而 assertTextEquals("") 绑的是 EditableText 语义，BasicTextField 空值时不提供它
        compose.onNodeWithTag(MiniPhoneChatTags.SEND_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun `连续两句各自只发一次`() {
        render()

        input().performTextInput("第一句")
        compose.onNodeWithTag(MiniPhoneChatTags.SEND_BUTTON).performClick()
        input().performTextInput("第二句")
        compose.onNodeWithTag(MiniPhoneChatTags.SEND_BUTTON).performClick()

        assertEquals(listOf("第一句", "第二句"), sent)
    }

    @Test
    fun `引擎不支持时点麦克风不会静默无反应`() {
        render(voiceInputEnabled = false)

        // 钉住既有的产品决定（实现里就写着"仍旧可点：点下去给一句解释"）：
        // 灰掉又点不动会让人反复按、或以为麦克风坏了；点一下告诉他在哪儿能开才有下一步。
        compose.onNodeWithTag(MiniPhoneChatTags.MIC_BUTTON).assertIsEnabled()
        compose.onNodeWithTag(MiniPhoneChatTags.MIC_BUTTON).performClick()

        // 不能真的开始录音：录音条不出现
        compose.onNodeWithTag(MiniPhoneChatTags.RECORDING_SECONDS).assertDoesNotExist()
        // 但必须给出一句解释
        val notice = ShadowToast.getTextOfLatestToast()?.toString().orEmpty()
        assertTrue("该说明为什么不能用，实际：$notice", notice.contains("不支持录音输入"))
    }

    @Test
    fun `引擎支持时点麦克风不该冒出那句解释`() {
        render(voiceInputEnabled = true)

        compose.onNodeWithTag(MiniPhoneChatTags.MIC_BUTTON).performClick()

        val notice = ShadowToast.getTextOfLatestToast()?.toString().orEmpty()
        assertTrue("没被引擎挡住就不该说这话：$notice", !notice.contains("不支持录音输入"))
    }

    @Test
    fun `图片入口一直可点 与输入内容无关`() {
        var picks = 0
        render(onPickImages = { picks++ })

        compose.onNodeWithTag(MiniPhoneChatTags.PICK_IMAGE).assertIsEnabled()
        compose.onNodeWithTag(MiniPhoneChatTags.PICK_IMAGE).performClick()

        assertEquals("空输入也能先选图", 1, picks)
    }
}
