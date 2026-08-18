package io.zer0.muse.ui

import io.zer0.muse.data.session.SearchResult
import io.zer0.muse.data.session.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchScreenSearchDataTest {

    @Test
    fun `duplicate session ids are collapsed before rendering`() {
        val sessions = listOf(
            SessionEntity("s_same", "第一条", 1L, 3L, lastMessagePreview = "needle"),
            SessionEntity("s_same", "重复条", 2L, 4L, lastMessagePreview = "needle"),
            SessionEntity("s_other", "第二条", 1L, 2L, lastMessagePreview = "needle"),
        )

        val result = filterSearchSessions(sessions, "needle")

        assertEquals(listOf("s_same", "s_other"), result.map { it.id })
    }

    @Test
    fun `duplicate message ids are collapsed before rendering`() {
        val results = listOf(
            SearchResult("m_same", "s1", "会话", "a", "user", 1L),
            SearchResult("m_same", "s1", "会话", "b", "assistant", 2L),
            SearchResult("m_other", "s1", "会话", "c", "assistant", 3L),
        )

        val unique = uniqueSearchResults(results)

        assertEquals(listOf("m_same", "m_other"), unique.map { it.messageId })
    }
}
