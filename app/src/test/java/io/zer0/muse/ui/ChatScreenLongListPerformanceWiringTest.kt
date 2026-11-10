package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ChatScreenLongListPerformanceWiringTest {
    @Test
    fun `translation source lookup uses precomputed id index`() {
        val source = readUi("ChatScreen.kt")
        val lookup = source.substringAfter("translationSourceContent =")
            .substringBefore("translations = msg.translations")

        assertTrue(
            "translation lookup should use the message-id index built outside the lazy item",
            lookup.contains("messagesById[srcId]"),
        )
        assertFalse(
            "a per-item full message scan makes long translated conversations O(n^2)",
            lookup.contains("messages.find"),
        )
    }

    @Test
    fun `artifacts are observed once per visible window`() {
        val source = readUi("ChatScreen.kt")

        assertTrue(source.contains("observeArtifactsByMessages("))
        assertFalse(
            "per-item artifact flows create a DB subscription for every card scrolled into view",
            source.contains("viewModel.observeArtifactsByMessage(msg.id.toString())"),
        )
    }

    @Test
    fun `per-message copy and delegate callbacks are remembered`() {
        val source = readUi("ChatScreen.kt")

        assertTrue(source.contains("val onCopyMessage: (String) -> Unit ="))
        assertTrue(source.contains("onCopyMessage = onCopyMessage"))
        assertTrue(source.contains("val onDelegate ="))
        assertTrue(source.contains("onDelegate = onDelegate"))
    }

    @Test
    fun `scroll persistence waits until scrolling stops`() {
        val source = readUi("ChatScreen.kt")
        val scrollPersistence =
            source.substringAfter("LaunchedEffect(listState) {")
                .substringBefore("// v2.6.7 fix")

        assertTrue(
            "position writes during a fling force the whole ChatScreen state to recompose",
            scrollPersistence.contains(".filter { !listState.isScrollInProgress }"),
        )
        assertFalse(
            "sampling every 100ms while scrolling is not a frame-safe persistence strategy",
            scrollPersistence.contains(".sample(100L)"),
        )
    }

    @Test
    fun `message rows do not run placement animations during flings`() {
        val source = readUi("ChatScreen.kt")
        val nextMessageItem =
            source.substringAfter("itemsIndexed(", missingDelimiterValue = "")
                .indexOf("itemsIndexed(")
                .let { next ->
                    if (next < 0) source.length else source.indexOf("itemsIndexed(") + next
                }
        val messageRow =
            source.substring(
                source.indexOf("itemsIndexed("),
                nextMessageItem,
            )

        assertFalse(
            "item placement animation keeps every visible message animated while the list scrolls",
            messageRow.contains("museAnimateItem"),
        )
    }

    @Test
    fun `long completed assistant bodies are flattened into lazy rows`() {
        val source = readUi("ChatScreen.kt")

        assertTrue(source.contains("ChatMessageRenderRows.project("))
        assertTrue(source.contains("ChatRenderContinuationRow("))
        assertTrue(
            "the interaction bubble must not re-measure the projected body",
            source.contains("rendersBody = row.rendersMessageBody"),
        )
        assertFalse(
            "one giant MessageBubble item forces all markdown and rich content through a single measure pass",
            source.contains("visibleMessages,\n                                            key = { _, it -> it.id }"),
        )
    }

    @Test
    fun `footer decision is gated to messages that can split`() {
        val source = readUi("ChatScreen.kt")

        assertTrue(
            "short and rich messages must not parse the quick-action footer decision",
            source.contains("ChatMessageRenderRows.needsFooterDecision("),
        )
        assertTrue(source.contains("!ChatMessageRenderRows.needsFooterDecision("))
    }

    private fun readUi(relative: String): String {
        val candidates = listOf(
            Path.of("src/main/java/io/zer0/muse/ui/$relative"),
            Path.of("app/src/main/java/io/zer0/muse/ui/$relative"),
        )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate ui/$relative from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
