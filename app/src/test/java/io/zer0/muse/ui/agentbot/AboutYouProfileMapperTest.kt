package io.zer0.muse.ui.agentbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutYouProfileMapperTest {

    @Test
    fun `maps facts into profile sections and keeps pinned separate`() {
        val profile = AboutYouProfileMapper.map(
            listOf(
                fact(1, "我叫小林", "identity", pinned = true),
                fact(2, "喜欢喝咖啡", "preference"),
                fact(3, "正在学习日语", "goal"),
                fact(4, "最近在准备旅行", "event"),
            ),
        )

        assertEquals(listOf(1L), profile.pinned.map { it.id })
        assertEquals(listOf(2L, 3L), profile.preferences.map { it.id })
        assertTrue(profile.basics.isEmpty())
        assertEquals(listOf(4L), profile.recent.map { it.id })
    }

    @Test
    fun `sorts within a section by importance then newest`() {
        val profile = AboutYouProfileMapper.map(
            listOf(
                fact(1, "普通", "identity", importance = 0, createdAt = "2026-01-01"),
                fact(2, "重要", "identity", importance = 1, createdAt = "2026-01-02"),
                fact(3, "关键", "identity", importance = 2, createdAt = "2026-01-03"),
            ),
        )

        assertEquals(listOf(3L, 2L, 1L), profile.basics.map { it.id })
    }

    @Test
    fun `empty fact list produces empty profile`() {
        assertTrue(AboutYouProfileMapper.map(emptyList()).isEmpty)
    }

    private fun fact(
        id: Long,
        content: String,
        category: String,
        importance: Int = 0,
        createdAt: String? = "2026-01-01",
        pinned: Boolean = false,
    ) = AboutYouFact(
        id = id,
        content = content,
        category = category,
        importance = importance,
        createdAt = createdAt,
        pinned = pinned,
    )
}
