package io.zer0.muse.ui.common.form

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class BottomPopupPositionTest {

    @Test
    fun popup_is_bottom_aligned_to_the_safe_drawing_edge() {
        val position = calculateBottomPopupPosition(
            windowSize = IntSize(width = 1_080, height = 1_920),
            popupContentSize = IntSize(width = 700, height = 600),
            bottomInsetPx = 96,
            gapPx = 12,
        )

        assertEquals(190, position.x)
        assertEquals(1_212, position.y)
    }

    @Test
    fun popup_is_not_moved_below_the_window_when_it_is_taller_than_available_space() {
        val position = calculateBottomPopupPosition(
            windowSize = IntSize(width = 1_080, height = 1_920),
            popupContentSize = IntSize(width = 700, height = 2_000),
            bottomInsetPx = 96,
            gapPx = 12,
        )

        assertEquals(190, position.x)
        assertEquals(0, position.y)
    }
}
