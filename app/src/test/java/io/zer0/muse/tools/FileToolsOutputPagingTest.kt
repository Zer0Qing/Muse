package io.zer0.muse.tools

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FileToolsOutputPagingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `read_file returns consecutive pages for output larger than two megabytes`() = runBlocking {
        val context = mockk<Context>()
        val filesDir = File(tempFolder.root, "files").apply { mkdirs() }
        val cacheDir = File(tempFolder.root, "cache").apply { mkdirs() }
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns cacheDir

        val content = "output line\n".repeat(220_000)
        File(filesDir, "$TOOL_OUTPUTS_DIR/large-result.txt").apply {
            parentFile?.mkdirs()
            writeText(content)
        }
        val workspace = File(tempFolder.root, "workspace").apply { mkdirs() }

        val firstPage = FileTools.execute(
            FileTools.NAME_READ_FILE,
            mapOf("path" to "$TOOL_OUTPUTS_DIR/large-result.txt"),
            context,
            workspace,
        )
        val secondPage = FileTools.execute(
            FileTools.NAME_READ_FILE,
            mapOf(
                "path" to "$TOOL_OUTPUTS_DIR/large-result.txt",
                "offset_chars" to TOOL_OUTPUT_READ_PAGE_CHARS.toString(),
                "length_chars" to TOOL_OUTPUT_READ_PAGE_CHARS.toString(),
            ),
            context,
            workspace,
        )

        assertTrue(firstPage.startsWith(content.take(TOOL_OUTPUT_READ_PAGE_CHARS)))
        assertTrue(firstPage.contains("offset_chars=$TOOL_OUTPUT_READ_PAGE_CHARS"))
        assertTrue(
            secondPage.startsWith(
                content.substring(TOOL_OUTPUT_READ_PAGE_CHARS, TOOL_OUTPUT_READ_PAGE_CHARS * 2),
            ),
        )
    }
}
