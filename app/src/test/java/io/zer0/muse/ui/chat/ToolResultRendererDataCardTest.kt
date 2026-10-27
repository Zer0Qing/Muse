package io.zer0.muse.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultRendererDataCardTest {

    @Test
    fun cardFenceIsDetectedAsDataCardResult() {
        val method = Class.forName("io.zer0.muse.ui.chat.ToolResultRendererKt")
            .getDeclaredMethod("detectResultKind", String::class.java)
            .apply { isAccessible = true }

        val kind = (
            method.invoke(
                null,
                """
            ```card
            {"type":"bar","title":"Messages","labels":["Mon"],"values":[1]}
            ```
                """.trimIndent(),
            ) as? Enum<*>
            )?.name

        assertEquals("DATA_CARD", kind)
    }

    @Test
    fun largeTableKeepsRowsAndLongCellsForTheFullTextRenderer() {
        val rows = buildString {
            append("| id | payload |\n| --- | --- |\n")
            repeat(350) { index ->
                append("| row-$index | ")
                append("detail-".repeat(80))
                append(" |\n")
            }
        }

        val parsed = parseTableRows(rows)

        assertEquals(351, parsed.size)
        assertEquals("row-349", parsed.last().first())
        assertTrue(parsed.last().last().length > 500)
    }
}
