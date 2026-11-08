package io.zer0.muse.ui

import io.zer0.muse.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 内置目录中文映射（用户反馈 02①）。
 *
 * 条目行、下钻选择器、顶栏标题共用这一份映射；未知目录必须保持原名，
 * 不能把用户自建的目录猜成某个内置名。
 */
class WorkspaceDirectoryLabelTest {

    @Test
    fun `built-in directories map to localized labels`() {
        assertEquals(R.string.workspace_tool_outputs, workspaceDirectoryLabelRes("tool_outputs"))
        assertEquals(R.string.workspace_attachments, workspaceDirectoryLabelRes("attachments"))
        assertEquals(R.string.workspace_download, workspaceDirectoryLabelRes("download"))
        assertEquals(R.string.workspace_download, workspaceDirectoryLabelRes("downloads"))
        assertEquals(R.string.workspace_resume, workspaceDirectoryLabelRes("resume"))
    }

    @Test
    fun `mapping ignores case`() {
        assertEquals(R.string.workspace_tool_outputs, workspaceDirectoryLabelRes("Tool_Outputs"))
    }

    @Test
    fun `unknown directories keep their own name`() {
        assertNull(workspaceDirectoryLabelRes("my-folder"))
        assertNull(workspaceDirectoryLabelRes(""))
    }
}
