package io.zer0.muse.ui.chat

import io.zer0.muse.tools.APPROVAL_WAIT_LABEL
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 折叠态摘要行的取舍（P4 协作可见性）。
 *
 * 缺陷原样：编排器特意把「等待审批…」写进步骤，好让"在等人"与"正在跑"区分开；
 * 但折叠态读的是 `title.ifBlank { progressText }` —— 工具名永远赢，
 * 于是**折叠时看起来仍在跑工具**，而它其实卡在用户身上，30 秒后还会静默变成拒绝。
 * 折叠态恰恰是用户最常停留的样子，这条区分等于没做。
 */
class ProcessCardSummaryPriorityTest {

    private val fallback = "执行中…"

    @Test
    fun `审批等待要盖过工具名`() {
        val text = pickRunningStepText(
            title = "shell",
            stepProgress = APPROVAL_WAIT_LABEL,
            runningFallback = fallback,
        )

        assertEquals("在等人就必须写在等人", APPROVAL_WAIT_LABEL, text)
    }

    @Test
    fun `没有审批等待时仍显示步骤标题`() {
        val text = pickRunningStepText(title = "读取文件", stepProgress = null, runningFallback = fallback)

        assertEquals("读取文件", text)
    }

    @Test
    fun `普通进度不该把工具名顶掉`() {
        // 只提审批这一种：其余 progressText（OCR、视觉分析）沿用既有优先级，
        // 不然这次修复会顺手改掉所有步骤的显示口径
        val text = pickRunningStepText(title = "shell", stepProgress = "正在分析图片 2/4…", runningFallback = fallback)

        assertEquals("shell", text)
    }

    @Test
    fun `标题为空时退回步骤进度`() {
        val text = pickRunningStepText(title = "  ", stepProgress = "正在分析图片 2/4…", runningFallback = fallback)

        assertEquals("正在分析图片 2/4…", text)
    }

    @Test
    fun `什么都没有时用兜底文案 不能交出空行`() {
        assertEquals(fallback, pickRunningStepText(title = "", stepProgress = null, runningFallback = fallback))
        assertEquals(fallback, pickRunningStepText(title = "", stepProgress = "   ", runningFallback = fallback))
    }
}
