package io.zer0.muse.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-09: 校验每套预设主题的浅色/深色色板都显式覆盖了关键 surface 字段，
 * 防止拆分后某套主题回落 Material 默认紫色导致暗色白块。
 */
class PresetThemeTest {

    private val defaultLight = lightColorScheme()
    private val defaultDark = darkColorScheme()

    @Test
    fun `preset themes are unique and complete`() {
        assertEquals(12, PresetThemes.size)
        assertEquals(12, PresetThemes.map { it.id }.toSet().size)
    }

    @Test
    fun `every theme overrides key surface fields in both schemes`() {
        PresetThemes.forEach { theme ->
            listOf(
                theme.lightScheme to defaultLight,
                theme.darkScheme to defaultDark,
            ).forEach { (scheme, defaultScheme) ->
                assertNotEquals("${theme.id} surfaceContainer", defaultScheme.surfaceContainer, scheme.surfaceContainer)
                assertNotEquals("${theme.id} surface", defaultScheme.surface, scheme.surface)
                assertNotEquals("${theme.id} surfaceVariant", defaultScheme.surfaceVariant, scheme.surfaceVariant)
                assertNotEquals("${theme.id} background", defaultScheme.background, scheme.background)
                assertTrue("${theme.id} onBackground", scheme.onBackground != defaultScheme.onBackground)
            }
        }
    }

    @Test
    fun `findPresetTheme falls back to warm paper`() {
        assertEquals("warm_paper", findPresetTheme("missing").id)
        assertEquals("mono", findPresetTheme("mono").id)
    }
}
