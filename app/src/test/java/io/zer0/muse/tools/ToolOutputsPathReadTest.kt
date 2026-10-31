package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * read_file 对工具输出目录的路径口径。
 *
 * 回执同时给了挂载形式 `tool_outputs:/x.txt` 与相对形式 `tool_outputs/x.txt`，
 * 两种写法都必须读到同一份文件；相对形式曾经因为多拼一层前缀而永远撞不上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolOutputsPathReadTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun prepareFile(): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, TOOL_OUTPUTS_DIR).apply { mkdirs() }
        return File(dir, "capture.txt").apply { writeText("完整输出内容", Charsets.UTF_8) }
    }

    @Test
    fun `read_file opens tool outputs by relative path`() = runBlocking {
        prepareFile()
        val result = FileTools.execute(
            FileTools.NAME_READ_FILE,
            mapOf("path" to "$TOOL_OUTPUTS_DIR/capture.txt"),
            ApplicationProvider.getApplicationContext(),
            tempFolder.newFolder("workspace"),
        )
        assertEquals("完整输出内容", result)
    }

    @Test
    fun `read_file accepts the mount form used in receipts`() = runBlocking {
        prepareFile()
        val result = FileTools.execute(
            FileTools.NAME_READ_FILE,
            mapOf("path" to "$TOOL_OUTPUTS_DIR:/capture.txt"),
            ApplicationProvider.getApplicationContext(),
            tempFolder.newFolder("workspace"),
        )
        assertEquals("完整输出内容", result)
        assertTrue("不该报路径不存在: $result", !result.contains("文件不存在"))
    }
}
