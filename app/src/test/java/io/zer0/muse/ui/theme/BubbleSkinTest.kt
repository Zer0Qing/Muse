package io.zer0.muse.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleSkinTest {

    @Test
    fun `validator accepts inclusive style range boundaries`() {
        val minimums = BubbleRoleStyle(
            surfaceArgb = 0xFF101010L,
            contentArgb = 0xFFFFFFFFL,
            outlineWidthDp = 0f,
            radiusDp = 0f,
            paddingHorizontalDp = 0f,
            paddingVerticalDp = 0f,
            maxWidthFraction = 0.35f,
            fontScale = 0.75f,
        )
        val maximums = BubbleRoleStyle(
            surfaceArgb = 0xFF202020L,
            contentArgb = 0xFFFFFFFFL,
            outlineWidthDp = 8f,
            radiusDp = 40f,
            paddingHorizontalDp = 48f,
            paddingVerticalDp = 48f,
            maxWidthFraction = 1f,
            fontScale = 1.5f,
        )
        val skin = BubbleSkin(
            id = "range-boundaries",
            name = "Range boundaries",
            light = mapOf(BubbleRole.USER to minimums),
            dark = mapOf(BubbleRole.ASSISTANT to maximums),
        )

        assertTrue(BubbleSkinValidator.isValid(skin))
        assertTrue(BubbleSkinValidator.validate(skin).isEmpty())
    }

    @Test
    fun `validator rejects every style range outside its bounds`() {
        val base = BubbleRoleStyle(surfaceArgb = 0xFF101010L, contentArgb = 0xFFFFFFFFL)
        val invalidStyles = listOf(
            "light/USER radius out of range" to base.copy(radiusDp = -0.01f),
            "light/USER horizontal padding out of range" to base.copy(paddingHorizontalDp = 48.01f),
            "light/USER vertical padding out of range" to base.copy(paddingVerticalDp = -0.01f),
            "light/USER width out of range" to base.copy(maxWidthFraction = 0.349f),
            "light/USER font scale out of range" to base.copy(fontScale = 1.501f),
            "light/USER outline width out of range" to base.copy(outlineWidthDp = 8.01f),
        )

        invalidStyles.forEach { (expectedError, style) ->
            val skin = BubbleSkin(
                id = "invalid-range",
                name = "Invalid range",
                light = mapOf(BubbleRole.USER to style),
            )

            assertFalse("$expectedError should be invalid", BubbleSkinValidator.isValid(skin))
            assertEquals(listOf(expectedError), BubbleSkinValidator.validate(skin))
        }
    }

    @Test
    fun `unsupported schema is invalid and resolver falls back to default skin`() {
        val customStyle = BubbleRoleStyle(
            surfaceArgb = 0xFF00FF00L,
            contentArgb = 0xFF000000L,
            tail = BubbleTailMode.ASYMMETRIC,
        )
        val unsupported = BubbleSkin(
            schemaVersion = BubbleSkinValidator.CURRENT_SCHEMA + 1,
            id = "future-schema",
            name = "Future schema",
            light = mapOf(BubbleRole.USER to customStyle),
            dark = mapOf(BubbleRole.USER to customStyle),
        )

        assertEquals(listOf("unsupported schemaVersion"), BubbleSkinValidator.validate(unsupported))

        val resolved = BubbleSkinResolver.resolve(unsupported, BubbleRole.USER, darkTheme = false)
        assertEquals("builtin-default", resolved.sourceSkinId)
        assertEquals(
            BubbleSkinResolver.defaultSkin(darkTheme = false).light.getValue(BubbleRole.USER),
            resolved.style,
        )
    }

    @Test
    fun `resolver falls back to assistant style when requested role is absent`() {
        val assistant = BubbleRoleStyle(
            surfaceArgb = 0xFF123456L,
            contentArgb = 0xFFFFFFFFL,
            radiusDp = 11f,
        )
        val skin = BubbleSkin(
            id = "assistant-fallback",
            name = "Assistant fallback",
            light = mapOf(BubbleRole.ASSISTANT to assistant),
            dark = mapOf(BubbleRole.ASSISTANT to assistant),
        )

        val resolved = BubbleSkinResolver.resolve(skin, BubbleRole.TOOL, darkTheme = false)

        assertEquals(BubbleRole.TOOL, resolved.role)
        assertEquals(assistant, resolved.style)
        assertEquals("assistant-fallback", resolved.sourceSkinId)
    }

    @Test
    fun `resolver selects the light and dark role styles independently`() {
        val lightUser = BubbleRoleStyle(
            surfaceArgb = 0xFFF0F0F0L,
            contentArgb = 0xFF111111L,
            radiusDp = 9f,
        )
        val darkUser = BubbleRoleStyle(
            surfaceArgb = 0xFF202020L,
            contentArgb = 0xFFF5F5F5L,
            radiusDp = 23f,
        )
        val skin = BubbleSkin(
            id = "theme-specific",
            name = "Theme specific",
            light = mapOf(BubbleRole.USER to lightUser),
            dark = mapOf(BubbleRole.USER to darkUser),
        )

        val light = BubbleSkinResolver.resolve(skin, BubbleRole.USER, darkTheme = false)
        val dark = BubbleSkinResolver.resolve(skin, BubbleRole.USER, darkTheme = true)

        assertEquals(lightUser, light.style)
        assertEquals(darkUser, dark.style)
        assertEquals("theme-specific", light.sourceSkinId)
        assertEquals("theme-specific", dark.sourceSkinId)
    }

    @Test
    fun `null resolver input uses distinct light and dark defaults`() {
        val light = BubbleSkinResolver.resolve(null, BubbleRole.USER, darkTheme = false)
        val dark = BubbleSkinResolver.resolve(null, BubbleRole.USER, darkTheme = true)

        assertEquals(0xFFF0F0ECL, light.style.surfaceArgb)
        assertEquals(0xFF2E2E2EL, dark.style.surfaceArgb)
        assertEquals("builtin-default", light.sourceSkinId)
        assertEquals("builtin-default", dark.sourceSkinId)
    }

    @Test
    fun `default skins satisfy the contrast requirement`() {
        // 内置 default 必须永远通过校验,否则 resolver 回退会失去意义。
        assertTrue(BubbleSkinValidator.isValid(BubbleSkinResolver.defaultSkin(darkTheme = false)))
        assertTrue(BubbleSkinValidator.isValid(BubbleSkinResolver.defaultSkin(darkTheme = true)))
    }

    @Test
    fun `contrast helper matches WCAG extremes`() {
        assertEquals(21.0, BubbleSkinValidator.contrastRatio(0xFFFFFFFFL, 0xFF000000L), 0.01)
        assertEquals(1.0, BubbleSkinValidator.contrastRatio(0xFF777777L, 0xFF777777L), 0.01)
    }

    @Test
    fun `low contrast style is invalid and resolver falls back to builtin default`() {
        val washedOut = BubbleRoleStyle(
            surfaceArgb = 0xFFF2F2F2L,
            contentArgb = 0xFFE8E8E8L,
        )
        val skin = BubbleSkin(
            id = "low-contrast",
            name = "Low contrast",
            light = mapOf(BubbleRole.USER to washedOut),
            dark = mapOf(BubbleRole.USER to washedOut),
        )

        assertEquals(
            listOf("light/USER contrast out of range", "dark/USER contrast out of range"),
            BubbleSkinValidator.validate(skin),
        )
        val resolved = BubbleSkinResolver.resolve(skin, BubbleRole.USER, darkTheme = false)
        assertEquals("builtin-default", resolved.sourceSkinId)
        assertEquals(
            BubbleSkinResolver.defaultSkin(darkTheme = false).light.getValue(BubbleRole.USER),
            resolved.style,
        )
    }
}
