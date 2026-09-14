package io.zer0.memory.compile

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MemoryFileWriterIsolationTest {

    @Test
    fun `daily files are isolated by assistant and space`() {
        val root = Files.createTempDirectory("muse-memory-writer").toFile()
        try {
            val writer = MemoryFileWriter(root)
            val first = MemoryCompileTarget(assistantId = "assistant-a", scope = "assistant-a", spaceId = "work")
            val second = MemoryCompileTarget(assistantId = "assistant-b", scope = "assistant-b", spaceId = "work")

            writer.writeDailyMd("2026-09-14", "first", first)
            writer.writeDailyMd("2026-09-14", "second", second)

            assertEquals("first", writer.readDailyEntryBody("2026-09-14", first))
            assertEquals("second", writer.readDailyEntryBody("2026-09-14", second))
            assertNotEquals(
                writer.listDailyEntries(first).map { it.file.absolutePath },
                writer.listDailyEntries(second).map { it.file.absolutePath },
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
