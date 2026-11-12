package io.zer0.muse.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「选择文本」入口合同。
 *
 * 钉住的行为：
 *  1. 点「选择文本」直接打开全屏面板，不再走「切可选状态 + 弹提示 + 再长按」那条旧路；
 *  2. 全屏面板用 [androidx.compose.foundation.text.selection.SelectionContainer] 承载正文，
 *     把划选与上下文菜单交给平台，而不是自绘菜单；
 *  3. 旧的选择模式状态已彻底移除（残留会让长按手势被提前 return 吃掉）。
 *
 * 用源码契约方式断言：这类 UI 行为在 Robolectric 下难以稳定驱动手势，
 * 而回归风险恰恰在「有人把旧分支加回来」。
 */
class TextSelectionEntryContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("无法定位 $relative")
    }

    private val messageBubble: String by lazy { source("src/main/java/io/zer0/muse/ui/MessageBubble.kt") }
    private val panel: String by lazy { source("src/main/java/io/zer0/muse/ui/chat/TextSelectionPanel.kt") }

    @Test
    fun `select text action opens full screen panel`() {
        val index = messageBubble.indexOf("onSelectText = {")
        assertTrue("必须存在 onSelectText 入口", index >= 0)
        val block = messageBubble.substring(index, (index + 400).coerceAtMost(messageBubble.length))
        assertTrue("必须打开全屏面板", block.contains("showTextSelectionPanel = true"))
        assertTrue(
            "不得再弹「长按文字即可划选复制」这类把操作推回用户的提示",
            !block.contains("chat_select_text_hint"),
        )
    }

    @Test
    fun `legacy inline selection mode is gone`() {
        assertTrue(
            "旧选择模式状态已移除：残留会让长按被提前 return 吃掉",
            !messageBubble.contains("textSelectMode"),
        )
        assertTrue(
            "气泡内不应再包 SelectionContainer（全屏面板才是选择入口）",
            !messageBubble.contains("SelectionContainer { markdownContent() }"),
        )
    }

    @Test
    fun `panel delegates selection to platform`() {
        assertTrue(
            "正文必须交给平台选择容器",
            panel.contains("import androidx.compose.foundation.text.selection.SelectionContainer"),
        )
        assertTrue("必须实际使用 SelectionContainer", panel.contains("SelectionContainer {"))
        assertTrue(
            "面板必须是整屏而非受高度比例限制的面板",
            panel.contains("usePlatformDefaultWidth = false"),
        )
        assertTrue("必须有关闭入口", panel.contains("MuseIcons.x"))
    }

    @Test
    fun `panel renders message body not raw markup`() {
        // 传入的是清洗后的 body（导出内容），不是 msg.content 原文。
        val index = messageBubble.indexOf("TextSelectionPanel(")
        assertTrue(index >= 0)
        val block = messageBubble.substring(index, (index + 200).coerceAtMost(messageBubble.length))
        assertTrue("必须传清洗后的正文", block.contains("text = body"))
        assertTrue("不得直接把原始 content 塞进面板", !block.contains("text = msg.content"))
    }
}
