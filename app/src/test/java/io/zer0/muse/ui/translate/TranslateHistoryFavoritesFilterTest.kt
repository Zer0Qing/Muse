package io.zer0.muse.ui.translate

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §11-6: 翻译收藏筛选。
 *
 * 收藏能点、能落库、能进 state.favorites，但历史上从来没有出口 —— UI 只渲染 history。
 * 这里钉住"开关切到收藏视图时展示的是 favorites，切回来还是完整历史"。
 */
class TranslateHistoryFavoritesFilterTest {

    private fun item(id: String, favorite: Boolean) = TranslateViewModel.TranslateHistoryItem(
        id = id,
        sourceText = "source-$id",
        translatedText = "translated-$id",
        sourceLanguage = "中文",
        targetLanguage = "English",
        timestamp = 1_000L,
        favorite = favorite,
    )

    @Test
    fun `favorites only shows the favorite list`() {
        val history = listOf(item("a", true), item("b", false))

        val shown = TranslateViewModel.historyForDisplay(
            history = history,
            favorites = history.filter { it.favorite },
            favoritesOnly = true,
        )

        assertEquals(listOf("a"), shown.map { it.id })
    }

    @Test
    fun `turning the filter off restores the full history`() {
        val history = listOf(item("a", true), item("b", false))

        val shown = TranslateViewModel.historyForDisplay(
            history = history,
            favorites = history.filter { it.favorite },
            favoritesOnly = false,
        )

        assertEquals(listOf("a", "b"), shown.map { it.id })
    }

    @Test
    fun `no favorites yields an empty list rather than falling back to history`() {
        val history = listOf(item("a", false))

        val shown = TranslateViewModel.historyForDisplay(
            history = history,
            favorites = emptyList(),
            favoritesOnly = true,
        )

        assertEquals(emptyList<String>(), shown.map { it.id })
    }
}
