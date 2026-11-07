package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话列表预览列的显示口径（走查 §十六）。
 *
 * 真机实测到的缺陷：英文界面的会话副标题显示「[已中断]」。根因是预览列是**落库快照**，
 * 而快照里写的是当时语言的那句话。正确分工是：库里只存与语言无关的记号，
 * 界面渲染时才换成当前语言——所以这条判据必须能在纯函数层面钉住。
 */
class SessionPreviewTextTest {
    private val token = io.zer0.muse.data.session.MessageMarkers.INTERRUPTED

    @Test
    fun rendersTheNeutralMarkerInTheCurrentLanguage() {
        assertEquals(
            "英文界面要看到英文那句",
            "[Interrupted]",
            sessionPreviewText(token, "[Interrupted]", "(truncated)"),
        )
        assertEquals(
            "中文界面要看到中文那句",
            "已中断",
            sessionPreviewText(token, "已中断", "（被截断）"),
        )
    }

    @Test
    fun rendersLegacyStoredChineseInTheCurrentLanguage() {
        // 修复前落库的快照就是中文原句，读出来也必须跟着界面语言走，不能永远卡在中文。
        assertEquals("[Interrupted]", sessionPreviewText("[已中断]", "[Interrupted]", "(truncated)"))
    }

    @Test
    fun keepsOrdinaryPreviewTextAndCollapsesWhitespace() {
        assertEquals(
            "普通正文原样，只是收敛空白",
            "好，30分钟后 提醒你",
            sessionPreviewText("好，30分钟后\n提醒你", "[Interrupted]", "(truncated)"),
        )
    }

    @Test
    fun dropsArtifactMarkersFromPreview() {
        val id = "7b7a65c4-1111-2222-3333-444455556666"
        assertEquals("正文", sessionPreviewText("正文 [artifact:$id]", "[Interrupted]", "(truncated)"))
    }
}
