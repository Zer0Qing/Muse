package io.zer0.muse.workspace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 工作区挂载层回归测试。
 *
 * 覆盖：默认挂载可用、路径前缀解析、只读挂载拒绝写、越界路径拒绝、
 * 会话可见范围限制、标识校验与移除。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkspaceMountTest {

    private lateinit var manager: WorkspaceManager

    private lateinit var context: Context

    /** 一个外部目录，用来模拟「用户自己选的一块地方」。 */
    private lateinit var externalDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = WorkspaceManager(context)
        externalDir = File(context.filesDir, "external-doc").apply { mkdirs() }
    }

    /** 挂一块只读的外部目录。 */
    private suspend fun mountReadOnly(id: String = "docs"): WorkspaceMount {
        val mount = WorkspaceMount(
            id = id,
            label = "文档",
            provider = MountProvider.LOCAL,
            rootPath = externalDir.absolutePath,
            capabilities = WorkspaceMount.READ_ONLY_CAPABILITIES,
            createdAt = System.currentTimeMillis(),
        )
        val result = manager.upsertMount(mount)
        assertTrue("挂载应成功: $result", result is WorkspaceMountStore.MountStoreResult.Success)
        return mount
    }

    @Test
    fun defaultMountIsAlwaysPresent() = runTest {
        val mounts = manager.mounts()
        assertEquals(2, mounts.size)
        assertEquals(WorkspaceMount.MAIN_ID, mounts.first().id)
        assertTrue(mounts.first().canWrite)
    }

    @Test
    fun builtInToolOutputsMountIsListedReadOnly() = runTest {
        val mounts = manager.mounts()
        val toolOutputs = mounts.first { it.id == WorkspaceMountStore.TOOL_OUTPUTS_MOUNT_ID }
        assertTrue("内置工具输出挂载应可列出", toolOutputs.canList)
        assertTrue("内置工具输出挂载应可读取", toolOutputs.canRead)
        assertTrue("内置工具输出挂载不可写", toolOutputs.isReadOnly)
        assertTrue("内置挂载应标记为系统项", toolOutputs.system)
    }

    @Test
    fun defaultRootViewListsToolOutputsMount() = runTest {
        val result = manager.listDir("")
        assertTrue("列默认根目录应成功: $result", result is WorkspaceManager.ListResult.Success)
        val paths = (result as WorkspaceManager.ListResult.Success).entries.map { it.relativePath }
        assertTrue("默认根视图应包含工具输出挂载: $paths", paths.contains("tool_outputs:/"))
    }

    @Test
    fun toolOutputsMountRejectsWrite() = runTest {
        val result = manager.writeFile("tool_outputs:/a.txt", "x")
        assertTrue("内置工具输出挂载必须拒绝写入: $result", result is WorkspaceManager.OpResult.Error)
        assertTrue("提示里应说明只读: $result", (result as WorkspaceManager.OpResult.Error).message.contains("只读"))
    }

    @Test
    fun toolOutputFilesAreReadableThroughTheMount() = runTest {
        val dir = File(context.filesDir, WorkspaceMountStore.TOOL_OUTPUTS_MOUNT_ID).apply { mkdirs() }
        File(dir, "capture.txt").writeText("工具长输出", Charsets.UTF_8)
        val result = manager.readFile("tool_outputs:/capture.txt")
        assertTrue("工具输出挂载应可读: $result", result is WorkspaceManager.ReadResult.Success)
        assertEquals("工具长输出", (result as WorkspaceManager.ReadResult.Success).content)
    }

    @Test
    fun builtInToolOutputsMountCannotBeRemovedOrRenamed() = runTest {
        val id = WorkspaceMountStore.TOOL_OUTPUTS_MOUNT_ID
        assertTrue("内置挂载不可删除", !manager.removeMount(id))
        val renamed = WorkspaceMount(
            id = id,
            label = "改名试试",
            provider = MountProvider.LOCAL,
            rootPath = externalDir.absolutePath,
        )
        val upsert = manager.upsertMount(renamed)
        assertTrue("内置挂载不可改名: $upsert", upsert is WorkspaceMountStore.MountStoreResult.Error)
        assertTrue("内置挂载应仍在列表里", manager.mounts().any { it.id == id })
    }

    @Test
    fun pathWithoutPrefixResolvesToDefaultMount() = runTest {
        val write = manager.writeFile("note.txt", "hello")
        assertTrue("写入应成功: $write", write is WorkspaceManager.OpResult.Success)
        val direct = manager.readFile("note.txt")
        val prefixed = manager.readFile("main:/note.txt")
        assertEquals(
            (direct as WorkspaceManager.ReadResult.Success).content,
            (prefixed as WorkspaceManager.ReadResult.Success).content,
        )
    }

    @Test
    fun readOnlyMountRejectsWrite() = runTest {
        mountReadOnly()
        val result = manager.writeFile("docs:/a.txt", "x")
        assertTrue("只读挂载必须拒绝写入: $result", result is WorkspaceManager.OpResult.Error)
        val message = (result as WorkspaceManager.OpResult.Error).message
        assertTrue("提示里应说明只读: $message", message.contains("只读"))
    }

    @Test
    fun readOnlyMountStillAllowsRead() = runTest {
        mountReadOnly()
        File(externalDir, "a.txt").writeText("来自外部目录", Charsets.UTF_8)
        val result = manager.readFile("docs:/a.txt")
        assertTrue("只读挂载应可读: $result", result is WorkspaceManager.ReadResult.Success)
        assertEquals("来自外部目录", (result as WorkspaceManager.ReadResult.Success).content)
    }

    @Test
    fun parentSegmentIsRejected() = runTest {
        mountReadOnly()
        val result = manager.readFile("docs:/../escape.txt")
        assertTrue("含 .. 的路径必须拒绝: $result", result is WorkspaceManager.ReadResult.Error)
    }

    @Test
    fun listDirShowsMountPrefixedPaths() = runTest {
        mountReadOnly()
        File(externalDir, "inner").mkdirs()
        val result = manager.listDir("docs:/")
        assertTrue("列目录应成功: $result", result is WorkspaceManager.ListResult.Success)
        val names = (result as WorkspaceManager.ListResult.Success).entries.map { it.relativePath }
        assertTrue("子项路径应带挂载前缀: $names", names.any { it == "docs:/inner" })
    }

    @Test
    fun sessionScopeBlocksUnlistedMount() = runTest {
        mountReadOnly()
        manager.setSessionMounts("session-a", setOf(WorkspaceMount.MAIN_ID))
        val scope = manager.sessionMountIds("session-a")
        assertNotNull(scope)
        val blocked = manager.listDir("docs:/", scope)
        assertTrue("范围外的挂载应被拒绝: $blocked", blocked is WorkspaceManager.ListResult.Error)
        val allowed = manager.listDir("", scope)
        assertTrue("范围内的默认挂载应可用: $allowed", allowed is WorkspaceManager.ListResult.Success)
    }

    @Test
    fun invalidMountIdIsRejected() = runTest {
        val bad = WorkspaceMount(
            id = "Docs!",
            label = "坏标识",
            provider = MountProvider.LOCAL,
            rootPath = externalDir.absolutePath,
        )
        val result = manager.upsertMount(bad)
        assertTrue("非法标识应被拒绝: $result", result is WorkspaceMountStore.MountStoreResult.Error)
    }

    @Test
    fun removingMountDropsItFromRegistry() = runTest {
        mountReadOnly()
        assertTrue(manager.removeMount("docs"))
        assertEquals(2, manager.mounts().size)
        val result = manager.listDir("docs:/")
        assertTrue("移除后的挂载不应再可用: $result", result is WorkspaceManager.ListResult.Error)
    }

    @Test
    fun defaultMountCannotBeRemoved() = runTest {
        assertTrue(!manager.removeMount(WorkspaceMount.MAIN_ID))
        assertEquals(2, manager.mounts().size)
    }

    @Test
    fun grepRejectsPathWithParentSegment() = runTest {
        mountReadOnly()
        val result = manager.grep("needle", "docs:/../escape.md")
        assertTrue("含 .. 的路径要给出错误而不是抛异常: $result", result is WorkspaceManager.GrepResult.Error)
    }

    @Test
    fun grepOnMountOutsideScopeReturnsError() = runTest {
        mountReadOnly()
        File(externalDir, "a.md").writeText("needle", Charsets.UTF_8)
        val result = manager.grep("needle", "docs:/", scope = setOf(WorkspaceMount.MAIN_ID))
        assertTrue("范围外的挂载要给出错误而不是抛异常: $result", result is WorkspaceManager.GrepResult.Error)
    }

    @Test
    fun grepOnReadOnlyMountStillFindsMatches() = runTest {
        mountReadOnly()
        File(externalDir, "a.md").writeText("needle\nother", Charsets.UTF_8)
        val result = manager.grep("needle", "docs:/")
        assertTrue("只读挂载应可搜: $result", result is WorkspaceManager.GrepResult.Success)
        assertEquals(1, (result as WorkspaceManager.GrepResult.Success).matches.size)
    }

    @Test
    fun grepWithoutPathSpansAllVisibleMounts() = runTest {
        mountReadOnly()
        File(externalDir, "a.md").writeText("needle", Charsets.UTF_8)
        manager.writeFile("b.txt", "needle here")
        val result = manager.grep("needle") as WorkspaceManager.GrepResult.Success
        assertEquals("两处命中都要收进来: ${result.matches}", 2, result.matches.size)
        assertTrue(
            "命中的路径要带挂载前缀: ${result.matches.map { it.path }}",
            result.matches.any { it.path.startsWith("docs:/") },
        )
    }

    @Test
    fun copyBetweenMountsCarriesContent() = runTest {
        mountReadOnly()
        File(externalDir, "src.txt").writeText("跨挂载内容", Charsets.UTF_8)
        val result = manager.copy("docs:/src.txt", "copied.txt")
        assertTrue("跨挂载复制应成功: $result", result is WorkspaceManager.OpResult.Success)
        val back = manager.readFile("copied.txt")
        assertEquals("跨挂载内容", (back as WorkspaceManager.ReadResult.Success).content)
    }
}
