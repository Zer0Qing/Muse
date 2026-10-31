package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * workspace_edit 判定核心测试：三类前置拒绝的文案、重叠计数、行级 diff 的行号。
 *
 * 本类只跑纯逻辑，不碰文件系统，也不起 Robolectric；落盘行为见 WorkspaceEditToolTest。
 */
class WorkspaceEditPlanTest {

    private fun plan(original: String, oldString: String, newString: String): EditPlan =
        WorkspaceTool.planEdit(original, oldString, newString, "note.txt")

    private fun rejectionMessage(plan: EditPlan): String {
        assertTrue("应当被拒绝，实际: $plan", plan is EditPlan.Rejected)
        return (plan as EditPlan.Rejected).message
    }

    @Test
    fun `片段出现 0 次时拒绝并说明找不到`() {
        val message = rejectionMessage(plan("第一行\n第二行\n", "不存在的片段", "新内容"))
        assertTrue(message, message.contains("找不到该片段"))
        assertTrue(message, message.contains("文件未改动"))
    }

    @Test
    fun `片段出现多次时拒绝并报出现次数`() {
        val message = rejectionMessage(plan("x\ntarget\ny\ntarget\n", "target", "ok"))
        assertTrue(message, message.contains("出现 2 次"))
        assertTrue(message, message.contains("唯一"))
        // 明确拒绝「只替换第一处」这种静默改错
        assertTrue(message, message.contains("不会只替换第一处"))
    }

    @Test
    fun `新旧内容相同时拒绝`() {
        val message = rejectionMessage(plan("a\nb\n", "b", "b"))
        assertTrue(message, message.contains("新旧内容相同"))
    }

    @Test
    fun `空 old_string 直接拒绝而不是当成插入`() {
        val message = rejectionMessage(plan("abc", "", "x"))
        assertTrue(message, message.contains("old_string 不能为空"))
    }

    @Test
    fun `CRLF 文件里用 LF 写法找不到时点明换行差异`() {
        val message = rejectionMessage(plan("a\r\nb\r\nc\r\n", "a\nb", "x"))
        assertTrue(message, message.contains("CRLF"))
    }

    @Test
    fun `重叠出现也按多次计数`() {
        assertEquals(2, WorkspaceTool.countOccurrences("aaa", "aa"))
        assertEquals(1, WorkspaceTool.countOccurrences("abc", "abc"))
        assertEquals(0, WorkspaceTool.countOccurrences("abc", "z"))
        assertEquals(0, WorkspaceTool.countOccurrences("abc", ""))
    }

    @Test
    fun `唯一片段替换只改目标位置`() {
        val applied = plan("a\ntarget\nz\n", "target", "done") as EditPlan.Applied
        assertEquals("a\ndone\nz\n", applied.updated)
    }

    @Test
    fun `diff 报首个改动行与被替换的行数`() {
        val original = (1..10).joinToString("\n") { "line$it" } + "\n"
        val updated = original.replace("line5", "LINE5\n追加")
        val diff = WorkspaceTool.diffLines(original, updated)

        assertEquals(5, diff.firstChangedLine)
        // 原文 1 行被换成 2 行
        assertEquals(1, diff.oldLineCount)
        assertEquals(2, diff.newLineCount)
        // 上下文 3 行：原文第 2-8 行，新文第 2-9 行
        assertTrue(diff.hunk, diff.hunk.startsWith("@@ -2,7 +2,8 @@"))
        assertTrue(diff.hunk, diff.hunk.contains("\n-line5\n"))
        assertTrue(diff.hunk, diff.hunk.contains("\n+LINE5\n+追加\n"))
        // 未改动的上下文行带前导空格
        assertTrue(diff.hunk, diff.hunk.contains("\n line4\n"))
    }

    @Test
    fun `文件头的改动从第 1 行开始且没有前置上下文`() {
        val diff = WorkspaceTool.diffLines("head\nb\nc\n", "HEAD\nb\nc\n")
        assertEquals(1, diff.firstChangedLine)
        assertEquals(1, diff.oldLineCount)
        assertEquals(1, diff.newLineCount)
        assertTrue(diff.hunk, diff.hunk.startsWith("@@ -1,4 +1,4 @@"))
        assertTrue(diff.hunk, diff.hunk.startsWith("@@ -1,4 +1,4 @@\n-head\n+HEAD\n b\n"))
    }

    @Test
    fun `文件尾的改动不留越界上下文`() {
        val diff = WorkspaceTool.diffLines("a\nb\ntail\n", "a\nb\nTAIL\n")
        assertEquals(3, diff.firstChangedLine)
        assertTrue(diff.hunk, diff.hunk.startsWith("@@ -1,4 +1,4 @@"))
        assertTrue(diff.hunk, diff.hunk.endsWith("-tail\n+TAIL"))
    }
}
