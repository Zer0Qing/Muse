package io.zer0.muse.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渐进渲染快照的档位计算。
 *
 * 这组用例锁的是「整页重载次数」而不是观感：卡片侧真正贵的是 WebView 整页重载，
 * 次数必须从 O(L/step) 降到 O(log)，否则一次「顺手调小步长」就能把几十次重载放回来，
 * 而没有任何别的门禁会红。
 */
class ProgressiveCardTest {

    @Test
    fun `content_shorter_than_step_is_returned_as_is`() {
        assertEquals(0, ProgressiveCard.snapshotLength(0))
        assertEquals(24, ProgressiveCard.snapshotLength(24))
        assertEquals("abc", ProgressiveCard.snapshot("abc"))
    }

    @Test
    fun `snapshot_length_only_moves_on_doubling_steps`() {
        // 步长 24：档位是 24 / 48 / 96 / 192 / 384 / 768 / 1536 / 3072 / 6144 ...
        assertEquals(24, ProgressiveCard.snapshotLength(25))
        assertEquals(24, ProgressiveCard.snapshotLength(47))
        assertEquals(48, ProgressiveCard.snapshotLength(48))
        assertEquals(48, ProgressiveCard.snapshotLength(95))
        assertEquals(96, ProgressiveCard.snapshotLength(96))
        assertEquals(96, ProgressiveCard.snapshotLength(191))
        assertEquals(192, ProgressiveCard.snapshotLength(192))
        assertEquals(6144, ProgressiveCard.snapshotLength(12_287))
    }

    @Test
    fun `snapshot_is_a_prefix_and_never_longer_than_content`() {
        val content = "x".repeat(5000)
        val snap = ProgressiveCard.snapshot(content)
        assertEquals(3072, snap.length)
        assertTrue(content.startsWith(snap))
        assertEquals(content, ProgressiveCard.snapshot(content, step = 100_000))
    }

    @Test
    fun `snapshot_count_drops_from_linear_to_logarithmic`() {
        // 8KB 卡片：按 120 固定步长是 68 次整页重载，几何档位是 9 次。
        assertEquals(9, ProgressiveCard.snapshotCount(8192))
        assertEquals(68, 8192 / 120)
        assertTrue(ProgressiveCard.snapshotCount(8192) * 7 <= 68)
    }

    @Test
    fun `streaming_growth_only_triggers_on_boundaries`() {
        // 模拟一次真实流式：每次追加 40 字符，统计真正变过的快照有几个。
        val total = StringBuilder()
        val seen = LinkedHashSet<Int>()
        repeat(300) {
            total.append("0123456789".repeat(4))
            seen.add(ProgressiveCard.snapshotLength(total.length))
        }
        assertEquals(12_000, total.length)
        // 固定步长的旧口径：24…12000 之间每个 120 的倍数都换一次 ≈ 99 次；
        // 几何档位只有 24/48/…/6144 共 9 个。
        assertEquals(9, seen.size)
        assertTrue(seen.size * 10 < 12_000 / 120)
    }

    @Test
    fun `custom_step_is_honoured`() {
        assertEquals(10, ProgressiveCard.snapshotLength(15, step = 10))
        assertEquals(20, ProgressiveCard.snapshotLength(35, step = 10))
        assertEquals(40, ProgressiveCard.snapshotLength(51, step = 10))
        assertEquals(2, ProgressiveCard.snapshotCount(35, step = 10))
    }

    @Test
    fun `snapshot_never_splits_a_surrogate_pair`() {
        // 23 个 ascii 之后是 emoji（UTF-16 两个 char）：快照长度算出来正好是 24，
        // 恰好切在 emoji 的高位代理上，必须退一格到 23。
        val content = "a".repeat(23) + "\uD83D\uDE00" + "尾"
        assertEquals(24, ProgressiveCard.snapshotLength(content.length))
        val snap = ProgressiveCard.snapshot(content)
        assertEquals(23, snap.length)
        assertTrue(snap.none { Character.isSurrogate(it) })
    }
}
