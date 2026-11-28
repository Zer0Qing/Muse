package io.zer0.muse.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import io.zer0.muse.ui.common.GreetingHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DrawerGreetingSummaryTest {
    @Test
    fun `a fallback in the previous language is omitted`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun localized(locale: Locale): Context = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(locale) },
        )
        val chinese = localized(Locale.SIMPLIFIED_CHINESE)
        val englishFallback = localized(Locale.US).getString(R.string.daily_summary_light_fallback)
        val today = LocalDate.of(2026, 11, 28)

        assertNull(GreetingHelper.getDrawerSummaryHint(chinese, englishFallback, today.toString(), today))
        assertEquals(
            "明天有部门例会。",
            GreetingHelper.getDrawerSummaryHint(chinese, "明天有部门例会。", today.toString(), today),
        )
    }
}
