package io.zer0.muse.data.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导入候选的取舍（表情包重做 P1）。 */
class StickerCandidateFiltersTest {

    @Test
    fun `normal sized images pass`() {
        assertTrue(StickerCandidateFilters.plausible(width = 500, height = 500, bytes = 40_000))
        assertTrue("120px 的合法小表情要留下", StickerCandidateFilters.plausible(120, 120, 8_000))
    }

    @Test
    fun `tiny and extreme shapes are dropped`() {
        assertFalse(StickerCandidateFilters.plausible(40, 40, 8_000))
        assertFalse(StickerCandidateFilters.plausible(1000, 100, 8_000))
        assertFalse(StickerCandidateFilters.plausible(500, 500, 0))
        assertFalse(StickerCandidateFilters.plausible(500, 500, StickerCandidateFilters.MAX_BYTES + 1))
    }

    @Test
    fun `identical bytes are deduped regardless of order`() {
        val items = listOf(
            byteArrayOf(1, 2, 3),
            byteArrayOf(1, 2, 3),
            byteArrayOf(9, 9, 9),
        )

        val kept = StickerCandidateFilters.filter(items, { 500 }, { 500 }, { it })

        // ByteArray 的 equals 比的是引用，所以先转成 List<Byte> 再比内容
        assertTrue(
            "只该留下两张不同的图，且保持原顺序: " + kept.map { it.toList() },
            kept.map { bytes -> bytes.toList().map { it.toInt() } } ==
                listOf(listOf(1, 2, 3), listOf(9, 9, 9)),
        )
    }

    @Test
    fun `the batch is capped`() {
        val items = (0..(StickerCandidateFilters.MAX_ITEMS + 20)).map { byteArrayOf(it.toByte(), 2, 3) }

        val kept = StickerCandidateFilters.filter(items, { 500 }, { 500 }, { it })

        assertEquals(StickerCandidateFilters.MAX_ITEMS, kept.size)
    }

    @Test
    fun `the hash is stable and content-addressed`() {
        assertEquals(StickerCandidateFilters.sha256(byteArrayOf(1, 2)), StickerCandidateFilters.sha256(byteArrayOf(1, 2)))
        assertFalse(StickerCandidateFilters.sha256(byteArrayOf(1, 2)) == StickerCandidateFilters.sha256(byteArrayOf(1, 3)))
    }
}
