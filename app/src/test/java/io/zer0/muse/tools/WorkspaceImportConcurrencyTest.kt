package io.zer0.muse.tools

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 并发导入的命名正确性。
 *
 * 重名时自动追加序号，序号判定与落盘必须在同一次持锁内完成；
 * 只锁写入的话，两个并发导入会各自算出同一个序号，后写的那次把先写的那次覆盖掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkspaceImportConcurrencyTest {

    private lateinit var context: Context

    private lateinit var manager: WorkspaceManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = WorkspaceManager(context)
    }

    /** 造一个待导入的源文件，返回它的 URI。 */
    private fun sourceFile(name: String, content: String): Uri {
        val file = File(context.cacheDir, name)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return Uri.fromFile(file)
    }

    private fun importedContent(relativePath: String): String =
        runBlocking {
            val result = manager.readFile(relativePath)
            assertTrue("导入结果读不回来: $relativePath -> $result", result is WorkspaceManager.ReadResult.Success)
            (result as WorkspaceManager.ReadResult.Success).content
        }

    @Test
    fun `并发导入同名文件时得到两个不同文件名且两份内容都在`() {
        val first = sourceFile("import-a.txt", "内容 A：第一份\n")
        val second = sourceFile("import-b.txt", "内容 B：第二份\n")

        val results = runBlocking {
            coroutineScope {
                listOf(first, second).map { uri ->
                    async(Dispatchers.IO) {
                        manager.importFile(uri, targetDirectory = "", displayName = "note.txt")
                    }
                }.awaitAll()
            }
        }

        val paths = results.map { result ->
            assertTrue("导入失败: $result", result is WorkspaceManager.FileImportResult.Success)
            (result as WorkspaceManager.FileImportResult.Success).relativePath
        }

        assertEquals("同名的两次导入必须落成两个文件: $paths", 2, paths.toSet().size)
        assertEquals(
            "两份内容都要在: ${paths.map { importedContent(it) }}",
            setOf("内容 A：第一份\n", "内容 B：第二份\n"),
            paths.map { importedContent(it) }.toSet(),
        )
    }

    @Test
    fun `并发导入之后继续导入第三个同名文件仍会顺延序号`() {
        val first = sourceFile("import-c.txt", "c1")
        val second = sourceFile("import-d.txt", "c2")
        val third = sourceFile("import-e.txt", "c3")

        val paths = runBlocking {
            coroutineScope {
                listOf(first, second).map { uri ->
                    async(Dispatchers.IO) { manager.importFile(uri, targetDirectory = "", displayName = "same.txt") }
                }.awaitAll()
            }.map { (it as WorkspaceManager.FileImportResult.Success).relativePath }
        }
        val thirdPath = runBlocking { manager.importFile(third, targetDirectory = "", displayName = "same.txt") }

        assertTrue("第三次导入失败: $thirdPath", thirdPath is WorkspaceManager.FileImportResult.Success)
        val allPaths = paths + (thirdPath as WorkspaceManager.FileImportResult.Success).relativePath
        assertEquals("三次导入必须落成三个文件: $allPaths", 3, allPaths.toSet().size)
    }
}
