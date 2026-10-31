package io.zer0.muse.tools

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.StringReader

class ToolOutputCaptureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `command output keeps the tail and drops the head`() {
        val output = "progress line\n".repeat(4_000) + "FAILED: assertion at BuildTest.kt:42\nexit=1"
        val directory = File(tempFolder.root, TOOL_OUTPUTS_DIR)
        val capture = ToolOutputCapture(directory, "terminal_exec")
        output.chunked(1_024).forEach(capture::append)

        val result = capture.finish(header = "exit=1", emptyMessage = "(无输出)")
        val savedFile = directory.listFiles()?.single()

        assertTrue(result.contains("[完整输出已保存到:"))
        assertTrue(result.contains("path=tool_outputs:/"))
        assertTrue(result.contains("read_file"))
        assertTrue(result.contains("offset_chars=0"))
        // 命令的结论在末尾：尾部必须在，开头那 4KB 进度日志不该占位置。
        assertTrue("尾部结论必须保留", result.contains("FAILED: assertion at BuildTest.kt:42"))
        assertFalse("头部进度日志不该出现在预览里", result.contains(output.take(TOOL_RESULT_PREVIEW_CHARS)))
        assertFalse(result.contains(output))
        assertEquals(output, savedFile?.readText())
    }

    @Test
    fun `pagination notice reports the total length of the captured output`() {
        val output = "progress line\n".repeat(4_000)
        val directory = File(tempFolder.root, TOOL_OUTPUTS_DIR)
        val capture = ToolOutputCapture(directory, "execute_shell")
        capture.append(output)

        val result = capture.finish(header = "exit=0", emptyMessage = "(无输出)")

        assertTrue("分页提示必须给出原文总量", result.contains("原文共 ${output.length} 字"))
        assertTrue("分页提示必须说明预览取自末尾", result.contains("输出末尾"))
        assertTrue(result.contains("offset_chars=0"))
    }

    @Test
    fun `non command output keeps the head`() {
        val output = "header line\n".repeat(4_000) + "END OF FILE"
        val directory = File(tempFolder.root, TOOL_OUTPUTS_DIR)
        val capture = ToolOutputCapture(directory, "read_public_file")
        output.chunked(1_024).forEach(capture::append)

        val result = capture.finish(header = "", emptyMessage = "(空)")

        assertTrue("文件类输出开头更有用", result.contains(output.take(TOOL_RESULT_PREVIEW_CHARS)))
        assertTrue(result.contains("输出开头"))
        assertTrue(result.contains("原文共 ${output.length} 字"))
    }

    @Test
    fun `tail preview never splits a surrogate pair`() {
        // 让 4KB 边界正好落在一个 emoji 中间：前面一个半角字符把配对下标整体挪一位。
        val output = "a" + "\uD83D\uDE00".repeat(3_000) + "\nDONE exit=0"
        val directory = File(tempFolder.root, TOOL_OUTPUTS_DIR)
        // 内联上限压到 1_000 字，逼它走落盘 + 尾部预览这条路径。
        val capture = ToolOutputCapture(directory, "terminal_exec", inlineLimitChars = 1_000)
        output.chunked(977).forEach(capture::append)

        val result = capture.finish(header = "", emptyMessage = "(无输出)")

        assertFalse("预览不能包含孤立代理字符", hasUnpairedSurrogate(result))
        assertTrue(result.contains("DONE exit=0"))
        // 丢掉半个代理之后，提示里的字数必须仍然自洽：显示的 + 未显示的 = 原文总量。
        val notice = Regex("""末尾 (\d+) 字的预览；原文共 (\d+) 字，开头 (\d+) 字未显示""").find(result)
        assertTrue("分页提示必须给出三个数", notice != null)
        val shown = notice!!.groupValues[1].toInt()
        val total = notice.groupValues[2].toInt()
        val omitted = notice.groupValues[3].toInt()
        assertEquals(output.length, total)
        assertEquals("已显示 + 未显示必须等于原文总量", total, shown + omitted)
    }

    @Test
    fun `small streamed output stays inline without creating a file`() {
        val directory = File(tempFolder.root, TOOL_OUTPUTS_DIR)
        val capture = ToolOutputCapture(directory, "terminal_exec")
        capture.append("short result")

        assertEquals("exit=0\nshort result", capture.finish(header = "exit=0", emptyMessage = "(无输出)"))
        assertFalse(directory.exists())
    }

    @Test
    fun `captureTextStream keeps a full file while limiting only its parse preview`() {
        val source = "page text ".repeat(120_000)
        val directory = File(tempFolder.root, TOOL_OUTPUTS_DIR)
        val captured = captureTextStream(
            reader = StringReader(source),
            outputDirectory = directory,
            filePrefix = "parse_link",
            previewLimitChars = 1_000_000,
        )

        assertEquals(source.take(1_000_000), captured.preview)
        assertTrue(captured.reference.contains("offset_chars=0"))
        assertEquals(source, captured.savedFile?.readText())
    }

    @Test
    fun `large alternate-path tool results use the same complete file delivery`() = runBlocking {
        val context = mockk<Context>()
        every { context.filesDir } returns tempFolder.root
        val output = "large alternate tool result\n".repeat(2_000)

        val result = captureLargeToolOutput(context, "recovery_tool", output)
        val savedPath = Regex("""\[完整输出已保存到: ([^\]]+)]""")
            .find(result)
            ?.groupValues
            ?.get(1)

        assertTrue(savedPath != null)
        assertEquals(output, File(savedPath!!).readText())
    }

    /** 高/低代理字符必须成对出现，孤立的一个会在下游 JSON 序列化时被打断。 */
    private fun hasUnpairedSurrogate(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return true
                i += 2
            } else if (Character.isLowSurrogate(c)) {
                return true
            } else {
                i++
            }
        }
        return false
    }
}
