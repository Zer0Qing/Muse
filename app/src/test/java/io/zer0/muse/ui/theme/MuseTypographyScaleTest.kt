package io.zer0.muse.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R-TEST-21: MuseTypography fontSizeScale 枚举解析。
 */
class MuseTypographyScaleTest {

    private val baseBodySize = MuseTypography.bodyLarge.fontSize.value

    @Test
    fun `known scales map to expected factors`() {
        assertEquals(0.85f, MuseTypography.scaled("small").bodyLarge.fontSize.value / baseBodySize, 0.0001f)
        assertEquals(1.0f, MuseTypography.scaled("medium").bodyLarge.fontSize.value / baseBodySize, 0.0001f)
        assertEquals(1.15f, MuseTypography.scaled("large").bodyLarge.fontSize.value / baseBodySize, 0.0001f)
        assertEquals(1.3f, MuseTypography.scaled("xlarge").bodyLarge.fontSize.value / baseBodySize, 0.0001f)
    }

    @Test
    fun `unknown scale falls back to medium`() {
        assertEquals(1.0f, MuseTypography.scaled("huge").bodyLarge.fontSize.value / baseBodySize, 0.0001f)
        assertEquals(1.0f, MuseTypography.scaled("SMALL").bodyLarge.fontSize.value / baseBodySize, 0.0001f)
    }
}
