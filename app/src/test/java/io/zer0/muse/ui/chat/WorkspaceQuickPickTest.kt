package io.zer0.muse.ui.chat

import io.zer0.muse.workspace.WorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话顶栏「更多 → 工作区」下钻选择器的纯逻辑（整改计划 第 5 条）。
 *
 * 界面本身要真机看，但"层级怎么走、什么该出现在列表里"是能钉住的：
 * 这些判据错了，用户在菜单里点到的就不是他以为的那个目录。
 */
class WorkspaceQuickPickTest {

    private fun dir(name: String, path: String) =
        WorkspaceManager.WorkspaceEntry(
            name = name,
            relativePath = path,
            isDirectory = true,
            size = 0L,
            lastModified = 0L,
        )

    private fun file(name: String, path: String) =
        WorkspaceManager.WorkspaceEntry(
            name = name,
            relativePath = path,
            isDirectory = false,
            size = 12L,
            lastModified = 0L,
        )

    @Test
    fun `根层级没有上一级可回`() {
        val stack = WorkspacePickStack()
        assertEquals(WorkspacePickStack.ROOT, stack.current)
        assertFalse("到底了就不该给「返回」", stack.canGoBack)
        assertFalse(stack.back())
    }

    @Test
    fun `逐层进入再逐层退出`() {
        val stack = WorkspacePickStack()
        stack.enter("notes")
        stack.enter("notes/2026")
        assertEquals("notes/2026", stack.current)
        assertTrue(stack.back())
        assertEquals("退回上一层应是 notes", "notes", stack.current)
        assertTrue(stack.back())
        assertEquals(WorkspacePickStack.ROOT, stack.current)
        assertFalse("回到根就不再多弹一帧", stack.back())
    }

    @Test
    fun `重复进入同一目录不会在返回链上留空台阶`() {
        val stack = WorkspacePickStack()
        stack.enter("notes")
        stack.enter("/notes/")  // 斜杠噪声要被吃掉，仍是同一个目录
        stack.enter("notes")
        assertTrue(stack.canGoBack)
        stack.back()
        assertFalse("只该有一帧真实层级", stack.canGoBack)
    }

    @Test
    fun `只列目录并且跳过隐藏项`() {
        val entries = listOf(
            dir("notes", "notes"),
            file("readme.md", "readme.md"),
            dir(".secrets", ".secrets"),
        )
        val picked = workspacePickFolders(entries)
        assertEquals("文件与隐藏目录都不该出现", listOf("notes"), picked.map { it.name })
    }

    @Test
    fun `路径归一化吃掉首尾斜杠与空白`() {
        assertEquals("a/b", WorkspacePickStack.normalize("  /a/b/  "))
        assertEquals(WorkspacePickStack.ROOT, WorkspacePickStack.normalize("/"))
    }
}
