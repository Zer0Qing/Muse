package io.zer0.muse.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ConversationSearchRegistrarWiringTest {

    @Test
    fun `bootstrapper eagerly instantiates conversation search registrar`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/tools/ToolRegistrarBootstrapper.kt"),
                Path.of("app/src/main/java/io/zer0/muse/tools/ToolRegistrarBootstrapper.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("ToolRegistrarBootstrapper.kt not found")

        assertTrue(source.contains("conversationSearchToolsRegistrar: ConversationSearchToolsRegistrar"))
    }
}
