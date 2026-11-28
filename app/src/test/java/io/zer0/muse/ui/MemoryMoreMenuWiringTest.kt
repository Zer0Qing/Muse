package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class MemoryMoreMenuWiringTest {

    @Test
    fun `memory center more menu reuses chat action sheet rows`() {
        val source = locateMemoryScreenSource()

        assertTrue(source.contains("MuseActionSheetRow("))
        assertFalse(source.contains("MuseListItem("))
    }

    private fun locateMemoryScreenSource(): String {
        // 实现已拆到 MemoryFactComponents.kt，两处拼接后再断言。
        val candidates =
            listOf(
                // 2026-11-22: MemoryScreen 已归档到 ui/memory 子包（W6-1）。
                Path.of("src/main/java/io/zer0/muse/ui/memory/MemoryScreen.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/memory/MemoryScreen.kt"),
            )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate MemoryScreen.kt from ${System.getProperty("user.dir")}")
        val base = Files.newBufferedReader(path).use { it.readText() }
        val sibling = path.resolveSibling("MemoryFactComponents.kt")
        val extra = if (Files.exists(sibling)) Files.newBufferedReader(sibling).use { it.readText() } else ""
        return base + "\n" + extra
    }
}
