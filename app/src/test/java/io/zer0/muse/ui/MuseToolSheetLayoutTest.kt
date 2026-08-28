package io.zer0.muse.ui

import io.zer0.muse.ui.common.form.calculateBottomPopupPosition
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class MuseToolSheetLayoutTest {

    @Test
    fun popup_bottom_is_anchored_above_gesture_navigation_area() {
        val position = calculateBottomPopupPosition(
            windowSize = IntSize(width = 1_080, height = 1_920),
            popupContentSize = IntSize(width = 1_000, height = 640),
            bottomInsetPx = 72,
            gapPx = 16,
        )

        assertEquals(40, position.x)
        assertEquals(1_192, position.y)
    }

    @Test
    fun popup_reserves_larger_three_button_navigation_area() {
        val position = calculateBottomPopupPosition(
            windowSize = IntSize(width = 1_080, height = 1_920),
            popupContentSize = IntSize(width = 1_000, height = 640),
            bottomInsetPx = 144,
            gapPx = 16,
        )

        assertEquals(40, position.x)
        assertEquals(1_120, position.y)
    }
}
