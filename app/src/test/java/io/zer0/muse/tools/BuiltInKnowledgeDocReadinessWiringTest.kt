package io.zer0.muse.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class BuiltInKnowledgeDocReadinessWiringTest {

    @Test
    fun `internal knowledge search waits for built in docs seeding`() {
        val searchSource =
            listOf(
                Path.of("src/main/java/io/zer0/muse/tools/SkillSearchToolsImpl.kt"),
                Path.of("app/src/main/java/io/zer0/muse/tools/SkillSearchToolsImpl.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("SkillSearchToolsImpl.kt not found")
        val appSource =
            listOf(
                Path.of("src/main/java/io/zer0/muse/MuseApp.kt"),
                Path.of("app/src/main/java/io/zer0/muse/MuseApp.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("MuseApp.kt not found")

        assertTrue(searchSource.contains("ensureSeeded"))
        assertTrue(appSource.contains("BuiltInKnowledgeDocSeeder"))
    }
}
