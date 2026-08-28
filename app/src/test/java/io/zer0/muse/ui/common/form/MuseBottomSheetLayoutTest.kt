package io.zer0.muse.ui.common.form

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MuseBottomSheetLayoutTest {

    @Test
    fun `sheet max height leaves navigation bar safe area`() {
        val height = calculateBottomSheetHeight(800.dp, 0.85f, 48.dp)

        assertEquals(639.2f, height.value, 0.01f)
        assertTrue(height <= 752.dp)
    }
}
