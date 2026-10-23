package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class McpTransportSelectorWiringTest {

    @Test
    fun `transport options wrap instead of squeezing labels into ellipsis`() {
        val source = locateMcpSectionSource()
        val selector = source
            .substringAfter("Text(stringResource(R.string.settings_mcp_transport_type)")
            .substringBefore("// Phase 11.1.1")

        assertTrue("Transport selector should use a wrapping layout", selector.contains("FlowRow("))
        assertFalse(
            "A fixed Row squeezes StreamableHTTP and STDIO on narrow dialogs",
            selector.contains("Row(horizontalArrangement = Arrangement.spacedBy(8.dp))"),
        )
        assertTrue("STDIO must remain an explicit option", selector.contains("text = \"STDIO\""))
    }

    private fun locateMcpSectionSource(): String {
        val candidates = listOf(
            Path.of("src/main/java/io/zer0/muse/ui/settings/McpSection.kt"),
            Path.of("app/src/main/java/io/zer0/muse/ui/settings/McpSection.kt"),
        )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate McpSection.kt from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
