package io.zer0.muse.data

import io.zer0.muse.ui.theme.ThemeAutoPolicy
import kotlinx.serialization.Serializable

/** Persistent inputs for automatic theme selection. */
@Serializable
data class ThemeScheduleConfig(
    /** Kept to read older stored configurations; new code uses [autoPolicy]. */
    @Deprecated("Use autoPolicy")
    val enabled: Boolean = false,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val autoPolicy: ThemeAutoPolicy = ThemeAutoPolicy.OFF,
    val wakeUpHour: Int = 7,
    val wakeUpMinute: Int = 0,
    val sleepHour: Int = 22,
    val sleepMinute: Int = 0,
    val locationCache: ThemeLocationCache? = null,
    val solarCache: ThemeSolarCache? = null,
    val solarUnavailableReason: ThemeSolarUnavailableReason? = null,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

@Serializable
data class ThemeLocationCache(
    val latitude: Double,
    val longitude: Double,
    val updatedAt: Long,
)

@Serializable
data class ThemeSolarCache(
    val solarDate: String,
    val sunriseAt: Long? = null,
    val sunsetAt: Long? = null,
    val zoneId: String,
    val condition: ThemeSolarCondition,
)

@Serializable
enum class ThemeSolarCondition {
    NORMAL,
    POLAR_DAY,
    POLAR_NIGHT,
    INVALID_COORDINATES,
}

@Serializable
enum class ThemeSolarUnavailableReason {
    LOCATION_PERMISSION_REQUIRED,
    LOCATION_UNAVAILABLE,
    SOLAR_EVENTS_UNAVAILABLE,
}
