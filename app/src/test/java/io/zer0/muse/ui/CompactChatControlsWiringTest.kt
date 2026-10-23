package io.zer0.muse.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class CompactChatControlsWiringTest {

    @Test
    fun `shared floating menu uses compact width without shrinking row touch targets`() {
        val source = locate("src/main/java/io/zer0/muse/ui/common/MuseFloatingActionMenu.kt")
        assertTrue(source.contains("widthIn(min = 160.dp, max = 192.dp)"))
        assertTrue(source.contains(".heightIn(min = 40.dp)"))
    }

    @Test
    fun `single and group chat input islands share compact spacing tokens`() {
        val inputBar = locate("src/main/java/io/zer0/muse/ui/InputBar.kt")
        val groupInputBar = locate("src/main/java/io/zer0/muse/ui/groupchat/GroupChatDetailComponents.kt")
        assertTrue(inputBar.contains("MusePaddings.inputIslandHorizontal"))
        assertTrue(inputBar.contains("MusePaddings.inputIslandRowVertical"))
        assertTrue(groupInputBar.contains("MusePaddings.inputIslandHorizontal"))
        assertTrue(groupInputBar.contains("MusePaddings.inputIslandRowVertical"))
    }

    @Test
    fun `compact input controls preserve 48dp touch target token`() {
        val inputBar = locate("src/main/java/io/zer0/muse/ui/InputBar.kt")
        val groupInputBar = locate("src/main/java/io/zer0/muse/ui/groupchat/GroupChatDetailComponents.kt")
        assertTrue(inputBar.contains("size(MuseIconSizes.touchTarget)"))
        assertTrue(groupInputBar.contains("size = MuseIconSizes.touchTarget"))
    }

    private fun locate(relativePath: String): String {
        val candidates =
            listOf(Path.of(relativePath), Path.of("app").resolve(relativePath.removePrefix("src/")))
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
