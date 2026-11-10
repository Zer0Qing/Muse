package io.zer0.muse.data.preset

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 引导页预设：自定义兼容端点必须首屏可见，不能埋在 9 个预设之后。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OnboardingPresetOrderTest {

    private val presets by lazy {
        val context: Context = ApplicationProvider.getApplicationContext()
        PresetProviders(context).onboardingPresets
    }

    @Test
    fun `自定义兼容端点排第一`() {
        assertEquals("preset_custom_openai", presets.first().id)
    }

    @Test
    fun `预设总数不变 且不重复`() {
        assertEquals(10, presets.size)
        assertEquals(presets.size, presets.map { it.id }.toSet().size)
    }

    @Test
    fun `每个预设 specId 去掉 preset 前缀`() {
        presets.forEach { preset ->
            assertEquals(preset.id.removePrefix("preset_").ifBlank { null }, preset.specId)
        }
    }
}
