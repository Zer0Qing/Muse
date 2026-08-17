package io.zer0.muse.ui.markdown

import io.zer0.muse.ui.markdown.CodeHighlighter.DiffLineKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E6: CodeHighlighter diff 行分类单元测试。
 *
 * classifyDiffLine 为 internal 纯函数,同模块测试可直接访问;
 * highlight/highlightDiff 为 @Composable/私有,由渲染层覆盖。
 */
class CodeHighlighterTest {

    @Test
    fun `hunk header lines classify as HUNK_HEADER`() {
        assertEquals(DiffLineKind.HUNK_HEADER, CodeHighlighter.classifyDiffLine("@@ -1,3 +1,4 @@"))
        assertEquals(DiffLineKind.HUNK_HEADER, CodeHighlighter.classifyDiffLine("+++ b/src/Main.kt"))
        assertEquals(DiffLineKind.HUNK_HEADER, CodeHighlighter.classifyDiffLine("--- a/src/Main.kt"))
        // 无行号段的简化 hunk 头
        assertEquals(DiffLineKind.HUNK_HEADER, CodeHighlighter.classifyDiffLine("@@"))
    }

    @Test
    fun `added and removed lines classify correctly`() {
        assertEquals(DiffLineKind.ADDED, CodeHighlighter.classifyDiffLine("+val x = 1"))
        assertEquals(DiffLineKind.REMOVED, CodeHighlighter.classifyDiffLine("-val x = 1"))
    }

    @Test
    fun `double plus prefix is ADDED not HUNK_HEADER`() {
        // "++ foo" 以 + 开头但不是 +++,必须归为 ADDED(+++ 判定先于 + 判定)
        assertEquals(DiffLineKind.ADDED, CodeHighlighter.classifyDiffLine("++ foo"))
        assertEquals(DiffLineKind.REMOVED, CodeHighlighter.classifyDiffLine("-- foo"))
    }

    @Test
    fun `context and empty lines classify correctly`() {
        assertEquals(DiffLineKind.CONTEXT, CodeHighlighter.classifyDiffLine(" context line"))
        assertEquals(DiffLineKind.CONTEXT, CodeHighlighter.classifyDiffLine("val keep = true"))
        assertEquals(DiffLineKind.EMPTY, CodeHighlighter.classifyDiffLine(""))
    }

    @Test
    fun `no diff marker in middle of line is CONTEXT`() {
        assertEquals(DiffLineKind.CONTEXT, CodeHighlighter.classifyDiffLine("val s = \"-not-a-diff\""))
    }
}
