package io.zer0.muse.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.zer0.common.AppJson
import io.zer0.muse.ui.theme.CustomTheme
import io.zer0.muse.data.ThemeScheduleConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer


/**
 * P2-2 拆分：外观/主题子仓库。
 *
 * 与 SettingsRepository 共用同一个 `muse_settings` DataStore 文件，
 * 只承载主题模式、主题 id、深色主题、定时切换、动态取色与自定义主题。
 * 后续 SettingsRepository 将逐步委托到本类，最终只保留聚合门面。
 */
class AppearanceSettingsStore(private val context: Context) {

    private val store get() = context.museSettingsDataStore

    val themeModeFlow: Flow<String> = store.data.map { prefs -> prefs[KEY_THEME_MODE] ?: "system" }
    val themeIdFlow: Flow<String> = store.data.map { prefs -> prefs[KEY_THEME_ID] ?: "mono" }
    val darkThemeIdFlow: Flow<String> = store.data.map { prefs -> prefs[KEY_DARK_THEME_ID] ?: "" }
    val themeScheduleFlow: Flow<ThemeScheduleConfig> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_THEME_SCHEDULE], ThemeScheduleConfig.serializer(), "ThemeSchedule") ?: ThemeScheduleConfig()
    }
    val dynamicColorFlow: Flow<Boolean> = store.data.map { prefs -> prefs[KEY_DYNAMIC_COLOR] ?: false }
    val fontSizeScaleFlow: Flow<String> = store.data.map { prefs -> prefs[KEY_FONT_SIZE_SCALE] ?: "medium" }
    val defaultHomePageFlow: Flow<Int> = store.data.map { prefs -> prefs[KEY_DEFAULT_HOME_PAGE] ?: 0 }
    val onboardingShownFlow: Flow<Boolean> = store.data.map { prefs -> prefs[KEY_ONBOARDING_SHOWN] == true }
    val asrTipShownFlow: Flow<Boolean> = store.data.map { prefs -> prefs[KEY_ASR_TIP_SHOWN] ?: false }
    val customThemesFlow: Flow<List<CustomTheme>> = store.data.map { prefs ->
        decodePrefsOrNull(prefs[KEY_CUSTOM_THEMES], ListSerializer(CustomTheme.serializer()), "CustomThemes") ?: emptyList()
    }
    /** E2: 自定义正文字体文件绝对路径(filesDir/fonts/ 下);null 表示使用系统默认字体。 */
    val customFontPathFlow: Flow<String?> = store.data.map { prefs -> prefs[KEY_CUSTOM_FONT_PATH] }

    suspend fun saveThemeMode(mode: String) { store.edit { it[KEY_THEME_MODE] = mode } }
    suspend fun saveThemeId(id: String) { store.edit { it[KEY_THEME_ID] = id } }
    suspend fun saveDarkThemeId(id: String) { store.edit { it[KEY_DARK_THEME_ID] = id } }
    suspend fun saveThemeSchedule(config: ThemeScheduleConfig) {
        store.edit { it[KEY_THEME_SCHEDULE] = AppJson.encodeToString(ThemeScheduleConfig.serializer(), config) }
    }
    suspend fun saveDynamicColor(enabled: Boolean) { store.edit { it[KEY_DYNAMIC_COLOR] = enabled } }
    suspend fun saveFontSizeScale(scale: String) { store.edit { it[KEY_FONT_SIZE_SCALE] = scale } }
    suspend fun saveDefaultHomePage(page: Int) { store.edit { it[KEY_DEFAULT_HOME_PAGE] = page.coerceIn(0, 2) } }
    suspend fun markOnboardingShown() { store.edit { it[KEY_ONBOARDING_SHOWN] = true } }
    suspend fun saveAsrTipShown(shown: Boolean) { store.edit { it[KEY_ASR_TIP_SHOWN] = shown } }
    suspend fun saveCustomThemes(themes: List<CustomTheme>) {
        store.edit { it[KEY_CUSTOM_THEMES] = AppJson.encodeToString(ListSerializer(CustomTheme.serializer()), themes) }
    }
    suspend fun saveCustomFontPath(path: String?) {
        store.edit { prefs ->
            if (path == null) prefs.remove(KEY_CUSTOM_FONT_PATH) else prefs[KEY_CUSTOM_FONT_PATH] = path
        }
    }

    suspend fun upsertCustomTheme(theme: CustomTheme) {
        store.edit { prefs ->
            val current = decodePrefsOrNull(prefs[KEY_CUSTOM_THEMES], ListSerializer(CustomTheme.serializer()), "CustomThemes(upsert)") ?: emptyList()
            val updated = current.filterNot { it.id == theme.id } + theme
            prefs[KEY_CUSTOM_THEMES] = AppJson.encodeToString(ListSerializer(CustomTheme.serializer()), updated)
        }
    }

    suspend fun deleteCustomTheme(id: String) {
        store.edit { prefs ->
            val current = decodePrefsOrNull(prefs[KEY_CUSTOM_THEMES], ListSerializer(CustomTheme.serializer()), "CustomThemes(delete)") ?: emptyList()
            prefs[KEY_CUSTOM_THEMES] = AppJson.encodeToString(ListSerializer(CustomTheme.serializer()), current.filterNot { it.id == id })
        }
    }

    private fun <T> decodePrefsOrNull(value: String?, serializer: kotlinx.serialization.KSerializer<T>, label: String): T? {
        if (value.isNullOrBlank()) return null
        return runCatching { AppJson.decodeFromString(serializer, value) }
            .onFailure { e -> android.util.Log.w("AppearanceStore", "$label 解析失败", e) }
            .getOrNull()
    }

    companion object {
        /** 默认主题 id — 与 MainActivity/ThemeSection 保持一致(R-UI-03)。 */
        const val DEFAULT_THEME_ID = "mono"

        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_THEME_ID = stringPreferencesKey("theme_id")
        private val KEY_DARK_THEME_ID = stringPreferencesKey("dark_theme_id")
        private val KEY_THEME_SCHEDULE = stringPreferencesKey("theme_schedule_json")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_FONT_SIZE_SCALE = stringPreferencesKey("font_size_scale")
        private val KEY_DEFAULT_HOME_PAGE = intPreferencesKey("default_home_page")
        private val KEY_ONBOARDING_SHOWN = booleanPreferencesKey("onboarding_shown")
        private val KEY_ASR_TIP_SHOWN = booleanPreferencesKey("asr_tip_shown")
        private val KEY_CUSTOM_THEMES = stringPreferencesKey("custom_themes_json")
        private val KEY_CUSTOM_FONT_PATH = stringPreferencesKey("custom_font_path")
    }
}
