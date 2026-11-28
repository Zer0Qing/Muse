package io.zer0.muse.ui.artifact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LongFormArtifactsTest {

    @Test
    fun `structured full response is stored with complete content`() {
        val content = buildString {
            appendLine("# Release report")
            appendLine()
            appendLine("## Summary")
            appendLine("A detailed report. ".repeat(50))
            appendLine()
            appendLine("## Findings")
            append("Final paragraph with complete findings.")
        }

        val artifact = LongFormArtifacts.extract("session-1", "message-1", content)

        assertEquals("doc_message-1", artifact?.id)
        assertEquals("Release report", artifact?.title)
        assertEquals(content.trim(), artifact?.content)
        assertTrue((artifact?.content?.length ?: 0) >= 800)
    }

    @Test
    fun `short response claiming a file write is not stored`() {
        val content = "已写入沙盒文件 report.md，请查看。"

        assertNull(LongFormArtifacts.extract("session-1", "message-2", content))
    }

    @Test
    fun `explicit artifact placeholder is not stored again`() {
        val content = """# Existing report

## Details
${"Long details. ".repeat(80)}
[artifact:report.md]
"""

        assertNull(LongFormArtifacts.extract("session-1", "message-3", content))
    }
}
