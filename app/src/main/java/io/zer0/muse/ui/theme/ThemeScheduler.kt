package io.zer0.muse.ui.theme

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.zer0.common.Logger
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.ThemeLocationCache
import io.zer0.muse.data.ThemeScheduleConfig
import io.zer0.muse.data.ThemeSolarUnavailableReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

data class ThemeSettingsSnapshot(
    val baseMode: BaseThemeMode,
    val schedule: ThemeScheduleConfig,
)

interface ThemeScheduleStore {
    suspend fun read(): ThemeSettingsSnapshot
    suspend fun saveSchedule(config: ThemeScheduleConfig)
}

interface ThemeTriggerBackend {
    fun scheduleAlarm(atMillis: Long)
    fun cancelAlarm()
    fun scheduleCatchUp(atMillis: Long, append: Boolean)
    fun cancelCatchUp()
}

interface ThemeLocationProvider {
    fun hasCoarsePermission(): Boolean
    fun lastKnownLocation(): ThemeLocationCache?
}

enum class ThemeReconcileReason(val wireValue: String) {
    SETTINGS_CHANGED("settings_changed"),
    APP_START("app_start"),
    ALARM("alarm"),
    WORKER("worker"),
    DATE_CHANGED("date_changed"),
    TIME_CHANGED("time_changed"),
    TIME_ZONE_CHANGED("time_zone_changed"),
    BOOT_COMPLETED("boot_completed"),
    PACKAGE_REPLACED("package_replaced"),
    ;

    companion object {
        fun fromWire(value: String?): ThemeReconcileReason = entries.firstOrNull { it.wireValue == value } ?: WORKER
    }
}

class ThemeScheduler(
    private val settings: ThemeScheduleStore,
    private val triggers: ThemeTriggerBackend,
    private val locationProvider: ThemeLocationProvider,
    private val clock: () -> Instant = Instant::now,
    private val zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
    private val systemIsDark: () -> Boolean,
) {
    private val _decisionFlow = MutableStateFlow<ThemeDecision?>(null)
    val decisionFlow: StateFlow<ThemeDecision?> = _decisionFlow.asStateFlow()

    suspend fun reconcile(reason: ThemeReconcileReason, appendCatchUp: Boolean = false): ThemeDecision {
        refreshSolarCacheIfNeeded()
        val snapshot = settings.read()
        val now = clock()
        val zoneId = zoneIdProvider()
        val solar = snapshot.schedule.solarCache?.toSolarScheduleOrNull()
        val decision = ThemeDecisionCalculator.evaluate(
            baseMode = snapshot.baseMode,
            autoPolicy = snapshot.schedule.autoPolicy,
            schedule = snapshot.schedule,
            now = now,
            systemIsDark = systemIsDark(),
            solar = solar,
            zoneId = zoneId,
        )
        _decisionFlow.value = decision
        Logger.i(TAG, "reconcile reason=${reason.wireValue}, policy=${snapshot.schedule.autoPolicy}, dark=${decision.isDark}")

        if (snapshot.schedule.autoPolicy == ThemeAutoPolicy.OFF) {
            triggers.cancelAlarm()
            triggers.cancelCatchUp()
        } else {
            val nextAt = decision.nextChangeAt ?: nextLocalMidnight(now, zoneId)
            triggers.scheduleAlarm(nextAt)
            triggers.scheduleCatchUp(nextAt, appendCatchUp)
        }
        return decision
    }

    suspend fun refreshSolarCacheIfNeeded() {
        val snapshot = settings.read()
        if (snapshot.schedule.autoPolicy != ThemeAutoPolicy.SUNRISE_SUNSET) return

        val now = clock()
        val zoneId = zoneIdProvider()
        val today = now.atZone(zoneId).toLocalDate()
        val currentSolar = snapshot.schedule.solarCache?.toSolarScheduleOrNull()
        if (currentSolar?.date == today && currentSolar.zoneId == zoneId) return

        val permitted = locationProvider.hasCoarsePermission()
        val lastKnown = if (permitted) {
            runCatching { locationProvider.lastKnownLocation() }
                .onFailure { Logger.w(TAG, "Last-known location read failed", it) }
                .getOrNull()
        } else {
            null
        }
        val cachedLocation = listOfNotNull(snapshot.schedule.locationCache, lastKnown)
            .maxByOrNull(ThemeLocationCache::updatedAt)
        if (cachedLocation == null) {
            val reason = if (permitted) {
                ThemeSolarUnavailableReason.LOCATION_UNAVAILABLE
            } else {
                ThemeSolarUnavailableReason.LOCATION_PERMISSION_REQUIRED
            }
            settings.saveSchedule(snapshot.schedule.copy(solarUnavailableReason = reason))
            return
        }

        val solar = SolarCalculator.calculate(cachedLocation.latitude, cachedLocation.longitude, today, zoneId)
        val unavailableReason = when (solar.condition) {
            SolarCondition.NORMAL, SolarCondition.POLAR_DAY, SolarCondition.POLAR_NIGHT -> null
            SolarCondition.INVALID_COORDINATES -> ThemeSolarUnavailableReason.SOLAR_EVENTS_UNAVAILABLE
        }
        settings.saveSchedule(
            snapshot.schedule.copy(
                locationCache = cachedLocation,
                solarCache = solar.toCache(),
                solarUnavailableReason = unavailableReason,
            ),
        )
    }

    companion object {
        private const val TAG = "ThemeScheduler"
        const val CATCH_UP_WORK_NAME = "muse_theme_reconcile"

        fun create(context: Context, settings: SettingsRepository, systemIsDark: (() -> Boolean)? = null): ThemeScheduler {
            val appContext = context.applicationContext
            val settingsAdapter = object : ThemeScheduleStore {
                override suspend fun read(): ThemeSettingsSnapshot = ThemeSettingsSnapshot(
                    baseMode = settings.baseThemeModeFlow.first(),
                    schedule = settings.themeScheduleFlow.first(),
                )

                override suspend fun saveSchedule(config: ThemeScheduleConfig) {
                    settings.saveThemeSchedule(config)
                }
            }
            val resolvedSystemIsDark = systemIsDark ?: {
                val nightMask = appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightMask == Configuration.UI_MODE_NIGHT_YES
            }
            return ThemeScheduler(
                settings = settingsAdapter,
                triggers = AndroidThemeTriggerBackend(appContext),
                locationProvider = AndroidThemeLocationProvider(appContext),
                systemIsDark = resolvedSystemIsDark,
            )
        }

        private fun nextLocalMidnight(now: Instant, zoneId: ZoneId): Long =
            now.atZone(zoneId).toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    }
}

private class AndroidThemeLocationProvider(private val context: Context) : ThemeLocationProvider {
    override fun hasCoarsePermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    override fun lastKnownLocation(): ThemeLocationCache? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = runCatching { manager.getProviders(true) }
            .onFailure { Logger.w(TAG, "Location providers unavailable", it) }
            .getOrDefault(emptyList())
        return providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider)?.toThemeLocationCache() }
                .onFailure { Logger.w(TAG, "Unable to read last-known location from $provider", it) }
                .getOrNull()
        }.maxByOrNull(ThemeLocationCache::updatedAt)
    }

    private fun Location.toThemeLocationCache(): ThemeLocationCache = ThemeLocationCache(
        latitude = latitude,
        longitude = longitude,
        updatedAt = time,
    )

    companion object {
        private const val TAG = "ThemeLocationProvider"
    }
}

private class AndroidThemeTriggerBackend(private val context: Context) : ThemeTriggerBackend {
    private val alarmManager: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)

    override fun scheduleAlarm(atMillis: Long) {
        val manager = alarmManager ?: return Logger.w(TAG, "AlarmManager unavailable; WorkManager remains the fallback")
        runCatching {
            val pendingIntent = alarmPendingIntent(context)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
                } else {
                    manager.setExact(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
            } else {
                manager.set(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
            }
        }.onFailure { Logger.w(TAG, "Theme alarm scheduling failed; WorkManager remains the fallback", it) }
    }

    override fun cancelAlarm() {
        val manager = alarmManager ?: return
        val pendingIntent = alarmPendingIntent(context)
        manager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    override fun scheduleCatchUp(atMillis: Long, append: Boolean) {
        val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ThemeReconcileWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ThemeReconcileWorker.KEY_REASON to ThemeReconcileReason.WORKER.wireValue))
            .build()
        val policy = if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(CATCH_UP_WORK_NAME, policy, request)
        }.onFailure { Logger.w(TAG, "Theme WorkManager catch-up scheduling failed", it) }
    }

    override fun cancelCatchUp() {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(CATCH_UP_WORK_NAME) }
            .onFailure { Logger.w(TAG, "Theme WorkManager catch-up cancellation failed", it) }
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ThemeAlarmReceiver::class.java).setAction(ThemeAlarmReceiver.ACTION_RECONCILE)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    companion object {
        private const val TAG = "ThemeTriggerBackend"
        private const val REQUEST_CODE = 0x5448
        private const val CATCH_UP_WORK_NAME = ThemeScheduler.CATCH_UP_WORK_NAME
    }
}
