package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ChatCompressionDialogWiringTest {

    @Test
    fun `more menu starts background compression without a parameter dialog`() {
        val source = locateChatScreenSource()
        assertTrue(source.contains("viewModel.manualCompress(updateMemoryFirst = true)"))
        assertFalse(source.contains("showCompressDialog"))
        assertFalse(source.contains("CompressContextDialog"))
    }

    @Test
    fun `compression action remains guarded while another compression is running`() {
        val source = locateChatScreenSource()
        assertTrue(source.contains("!state.isCompressing"))
    }

    private fun locateChatScreenSource(): String {
        val candidates =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/ChatScreen.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/ChatScreen.kt"),
            )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate ChatScreen.kt from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
