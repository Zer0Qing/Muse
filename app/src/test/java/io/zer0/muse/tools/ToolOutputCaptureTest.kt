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
    fun `large streamed output is fully saved and has a paginated read reference`() {
        val output = "terminal result\n".repeat(4_000)
        val directory = File(tempFolder.root, TOOL_OUTPUTS_DIR)
        val capture = ToolOutputCapture(directory, "terminal_exec")
        output.chunked(1_024).forEach(capture::append)

        val result = capture.finish(header = "exit=0", emptyMessage = "(无输出)")
        val savedFile = directory.listFiles()?.single()

        assertTrue(result.contains("[完整输出已保存到:"))
        assertTrue(result.contains("path=tool_outputs/"))
        assertTrue(result.contains("offset_chars=0"))
        assertTrue(result.contains(output.take(TOOL_RESULT_PREVIEW_CHARS)))
        assertFalse(result.contains(output))
        assertEquals(output, savedFile?.readText())
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
}
