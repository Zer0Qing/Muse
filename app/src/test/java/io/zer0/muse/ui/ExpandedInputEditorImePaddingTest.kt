package io.zer0.muse.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-UI-F14: 全屏输入编辑页必须有 imePadding,否则键盘遮住输入框/发送按钮。
 *
 * 该页由 InputBar 内直接组合(非 Dialog 独立窗口),MainActivity 开启 edge-to-edge
 * 后软键盘 inset 不会自动顶起内容 —— 仅 systemBarsPadding 不够。
 */
class ExpandedInputEditorImePaddingTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/ui/MuseExpandedInputEditor.kt").readText()

    @Test
    fun `expanded editor root applies ime padding`() {
        val src = source()
        assertTrue(
            "根布局必须应用 imePadding",
            src.contains(".imePadding()"),
        )
        assertTrue(
            "imePadding 需导入",
            src.contains("import androidx.compose.foundation.layout.imePadding"),
        )
    }
}
