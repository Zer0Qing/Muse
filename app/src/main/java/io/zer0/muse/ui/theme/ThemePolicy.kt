package io.zer0.muse.ui.theme

import kotlinx.serialization.Serializable

@Serializable
enum class BaseThemeMode(val wireValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromWire(value: String): BaseThemeMode = entries.firstOrNull { it.wireValue == value } ?: SYSTEM
    }
}

@Serializable
enum class ThemeAutoPolicy {
    OFF,
    CUSTOM_TIME,
    SUNRISE_SUNSET,
}

enum class ThemeDecisionSource {
    BASE_MODE,
    CUSTOM_TIME,
    SUNRISE_SUNSET,
}

enum class ThemeDecisionFallback {
    INVALID_CUSTOM_TIME,
    SOLAR_CACHE_MISSING,
    SOLAR_CACHE_STALE,
    SOLAR_EVENTS_UNAVAILABLE,
}

data class ThemeDecision(
    val isDark: Boolean,
    val nextChangeAt: Long?,
    val source: ThemeDecisionSource,
    val fallback: ThemeDecisionFallback? = null,
)
