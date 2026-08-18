package io.zer0.muse.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class MusePopoverPositionProviderTest {

    private val windowSize = IntSize(width = 1080, height = 1920)
    private val popupSize = IntSize(width = 220, height = 300)

    @Test
    fun `press point with enough space is placed above`() {
        val provider = MusePopoverPositionProvider(
            fallbackAnchorBounds = Rect.Zero,
            anchorPointInWindow = Offset(500f, 1000f),
            gapPx = 8,
        )

        val position = provider.calculatePosition(
            anchorBounds = IntRect.Zero,
            windowSize = windowSize,
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = popupSize,
        )

        assertEquals(390, position.x)
        assertEquals(692, position.y)
    }

    @Test
    fun `press point near top is placed below`() {
        val provider = MusePopoverPositionProvider(
            fallbackAnchorBounds = Rect.Zero,
            anchorPointInWindow = Offset(100f, 100f),
            gapPx = 8,
        )

        val position = provider.calculatePosition(
            anchorBounds = IntRect.Zero,
            windowSize = windowSize,
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = popupSize,
        )

        assertEquals(8, position.x)
        assertEquals(108, position.y)
    }

    @Test
    fun `press point is clamped to the bottom and right edges`() {
        val provider = MusePopoverPositionProvider(
            fallbackAnchorBounds = Rect.Zero,
            anchorPointInWindow = Offset(1060f, 1920f),
            gapPx = 8,
        )

        val position = provider.calculatePosition(
            anchorBounds = IntRect.Zero,
            windowSize = windowSize,
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = popupSize,
        )

        assertEquals(852, position.x)
        assertEquals(1612, position.y)
    }
}
