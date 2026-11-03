package io.zer0.muse.ui

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class MessageBubbleShellTest {
    @Test
    fun `bodyless shell does not use the assistant body container`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, content = "body")

        val result =
            isBodylessMessageShell(
                message = message,
                body = "",
                rendersBody = false,
                taskCard = null,
            )

        assertTrue(result)
    }

    @Test
    fun `header and footer rows do not own body-only interaction state`() {
        assertTrue(
            "the body owns text selection and media interactions",
            messageBubbleNeedsBodyInteraction(rendersBody = true),
        )
        assertFalse("header and footer must not own body interactions", messageBubbleNeedsBodyInteraction(false))
        assertTrue("complete rows keep the full interaction surface", messageBubbleNeedsBodyInteraction(true))
    }

    @Test
    fun `bodyless shell does not attach message gesture modifier`() {
        val source = readMessageBubbleSource()
        val alwaysBubbleModifier = Regex("""=\s*Modifier\s*\.then\(bubbleClickModifier\)""")
        assertFalse(
            "a header/footer shell must not attach the body-level gesture modifier",
            alwaysBubbleModifier.containsMatchIn(source),
        )
        assertTrue(source.contains("messageBubbleNeedsBodyInteraction(rendersBody)"))
    }

    private fun readMessageBubbleSource(): String {
        val candidates =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/MessageBubble.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/MessageBubble.kt"),
            )
        val path =
            candidates.firstOrNull(Files::exists)
                ?: error("Unable to locate MessageBubble.kt from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
