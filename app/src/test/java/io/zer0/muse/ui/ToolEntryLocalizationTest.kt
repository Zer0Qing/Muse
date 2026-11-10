package io.zer0.muse.ui

import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ToolEntryLocalizationTest {
    @Test
    fun tool_selection_does_not_depend_on_translated_titles() {
        for (title in listOf("联网搜索", "Buscar en Internet", "Web search")) {
            val search = ToolEntry(MuseIcons.globe, title, onClick = {}, titleRes = R.string.chat_web_search_cd)
            val thinking = ToolEntry(
                MuseIcons.brain,
                "Razonamiento profundo · Alto",
                onClick = {},
                titleRes = R.string.chat_deep_thinking_cd,
            )
            val unclassified = ToolEntry(MuseIcons.book, title, onClick = {})
            val entries = listOf(unclassified, thinking, search)

            assertSame(search, entries.findToolEntry(R.string.chat_web_search_cd))
            assertSame(thinking, entries.findToolEntry(R.string.chat_deep_thinking_cd))
            assertNull(entries.findToolEntry(R.string.chat_tool_attachment))
        }
    }
}
