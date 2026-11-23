package io.zer0.muse.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.ui.theme.BaseThemeMode
import io.zer0.muse.ui.theme.ThemeAutoPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SettingsRepositoryTestApplication::class)
class ThemeScheduleMigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scheduleKey = stringPreferencesKey("theme_schedule_json")
    private val themeModeKey = stringPreferencesKey("theme_mode")

    @Before
    fun clearThemePreferences() = runTest {
        context.museSettingsDataStore.edit { prefs ->
            prefs.remove(scheduleKey)
            prefs.remove(themeModeKey)
        }
    }

    @After
    fun clearThemePreferencesAfterTest() = runTest {
        context.museSettingsDataStore.edit { prefs ->
            prefs.remove(scheduleKey)
            prefs.remove(themeModeKey)
        }
    }

    @Test
    fun `legacy enabled schedule migrates to custom policy without rewriting stored JSON`() = runTest {
        val legacyJson = """{"enabled":true,"wakeUpHour":6,"wakeUpMinute":30,"sleepHour":21,"sleepMinute":15}"""
        context.museSettingsDataStore.edit { it[scheduleKey] = legacyJson }

        val schedule = AppearanceSettingsStore(context).themeScheduleFlow.first()
        val storedJson = context.museSettingsDataStore.data.first()[scheduleKey]

        assertEquals(ThemeAutoPolicy.CUSTOM_TIME, schedule.autoPolicy)
        assertEquals(6, schedule.wakeUpHour)
        assertEquals(30, schedule.wakeUpMinute)
        assertEquals(ThemeScheduleConfig.CURRENT_SCHEMA_VERSION, schedule.schemaVersion)
        assertEquals(legacyJson, storedJson)
        assertNull(AppearanceSettingsStore(context).themeScheduleDiagnosticFlow.first())
    }

    @Test
    fun `explicit off policy takes precedence over the legacy enabled flag`() = runTest {
        context.museSettingsDataStore.edit {
            it[scheduleKey] = """{"enabled":true,"autoPolicy":"OFF"}"""
        }

        assertEquals(ThemeAutoPolicy.OFF, AppearanceSettingsStore(context).themeScheduleFlow.first().autoPolicy)
    }

    @Test
    fun `malformed schedule exposes diagnostic and preserves the base mode`() = runTest {
        context.museSettingsDataStore.edit { prefs ->
            prefs[themeModeKey] = "dark"
            prefs[scheduleKey] = "{ malformed schedule"
        }
        val store = AppearanceSettingsStore(context)

        assertEquals(BaseThemeMode.DARK, store.baseThemeModeFlow.first())
        assertEquals(ThemeAutoPolicy.OFF, store.themeScheduleFlow.first().autoPolicy)
        assertNotNull(store.themeScheduleDiagnosticFlow.first())
        assertEquals("{ malformed schedule", context.museSettingsDataStore.data.first()[scheduleKey])
        assertEquals("dark", store.themeModeFlow.first())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `saving a policy writes current schema without changing the base theme`() = runTest {
        val store = AppearanceSettingsStore(context)
        store.saveThemeMode("system")
        store.saveThemeSchedule(ThemeScheduleConfig(autoPolicy = ThemeAutoPolicy.SUNRISE_SUNSET, enabled = true))

        val persisted = context.museSettingsDataStore.data.first()[scheduleKey]
        val savedSchedule = store.themeScheduleFlow.first()

        assertEquals(BaseThemeMode.SYSTEM, store.baseThemeModeFlow.first())
        assertEquals(ThemeAutoPolicy.SUNRISE_SUNSET, savedSchedule.autoPolicy)
        assertFalse(savedSchedule.enabled)
        assertEquals(ThemeScheduleConfig.CURRENT_SCHEMA_VERSION, savedSchedule.schemaVersion)
        assertNotNull(persisted)
    }
}
