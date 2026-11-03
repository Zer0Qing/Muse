package io.zer0.muse.automation.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Pure request construction keeps the approval gate deterministic and easy to test. */
object AutonomousAccessibilityState {
    fun minuteOfDay(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Int {
        val time = Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalTime()
        return time.hour * 60 + time.minute
    }

    fun dateKey(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String = Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate().toString()

    /** Whole calendar days, not rolling 24-hour windows. */
    fun isSameDate(
        storedDateKey: String?,
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (storedDateKey.isNullOrBlank()) return false
        val parsed = runCatching { LocalDate.parse(storedDateKey) }.getOrNull() ?: return false
        return parsed == Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    }
}
