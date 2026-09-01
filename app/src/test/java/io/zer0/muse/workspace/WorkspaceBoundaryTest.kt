package io.zer0.muse.workspace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M5.1: 工作区路径边界回归测试。
 *
 * 覆盖验收:越界路径被拒绝 —— `..` 穿越、绝对路径、越权前缀、
 * 正常相对路径仍可用;所有 IO 走公共 API(与生产路径一致)。
 */
@RunWith(RobolectricTestRunner::class)
class WorkspaceBoundaryTest {

    private lateinit var manager: WorkspaceManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = WorkspaceManager(context)
    }

    @Test
    fun `normal relative paths work inside the workspace`() = runTest {
        val write = manager.writeFile("docs/notes.txt", "hello boundary")
        assertTrue("正常写入应成功: ${write}", write is WorkspaceManager.OpResult.Success)

        val read = manager.readFile("docs/notes.txt")
        assertTrue(read is WorkspaceManager.ReadResult.Success)
        assertTrue((read as WorkspaceManager.ReadResult.Success).content == "hello boundary")

        val list = manager.listDir("docs")
        assertTrue(list is WorkspaceManager.ListResult.Success)
    }

    @Test
    fun `dot dot traversal is rejected`() = runTest {
        val write = manager.writeFile("../escape.txt", "should not exist")
        assertTrue(write is WorkspaceManager.OpResult.Error)

        val read = manager.readFile("a/../../escape.txt")
        assertTrue(read is WorkspaceManager.ReadResult.Error)

        // 不应产生工作区外的文件
        val escaped = java.io.File(manager.rootDir.parentFile, "escape.txt")
        assertFalse("越权文件不得存在", escaped.exists())
    }

    @Test
    fun `absolute style paths never escape the root`() = runTest {
        // 前导 '/' 被 trim 后等价于相对路径,落点必须仍在工作区内(containment 语义)
        val write = manager.writeFile("/etc/hosts", "contained")
        assertTrue(write is WorkspaceManager.OpResult.Success)
        assertTrue(
            "落点必须在工作区根内",
            java.io.File(manager.rootDir, "etc/hosts").exists(),
        )
        // 带根前缀的完整路径:文件不存在 → 必须返回错误,不得静默成功
        val read = manager.readFile("/data/data/io.zer0.muse/files/workspace/docs/missing.txt")
        assertTrue(read is WorkspaceManager.ReadResult.Error)
    }

    @Test
    fun `deletion stays inside the root`() = runTest {
        manager.writeFile("keep/a.txt", "x")
        val delete = manager.delete("../outside-target")
        assertTrue(delete is WorkspaceManager.OpResult.Error)
        assertTrue("正常文件仍存在", manager.readFile("keep/a.txt") is WorkspaceManager.ReadResult.Success)
    }
}
