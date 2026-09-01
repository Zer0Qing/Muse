package io.zer0.muse.ui.moment

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v1.0.74: 天气页 — Open-Meteo 免费接口(无需 API Key)。
 * - 有定位权限:自动获取当前位置天气
 * - 无定位权限/失败:手动输入城市名(geocoding)
 * 展示: 当前温度 + 天气描述 + 7 天预报。
 */
@Composable
fun MiniWeatherScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var city by remember { mutableStateOf("") }
    var cityInput by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var current: WeatherNow? by remember { mutableStateOf(null) }
    var daily: List<WeatherDay> by remember { mutableStateOf(emptyList()) }
    val context = LocalContext.current
    // 审计修复 (3.3): 统一使用 rememberCoroutineScope 的 scope 启动网络协程,
    // 随组合销毁自动取消。不再每次调用新建 CoroutineScope(Dispatchers.Main),
    // 避免退出页面后协程继续运行并写已销毁组件。
    val scope = rememberCoroutineScope()

    fun loadWeather(lat: Double, lon: Double) {
        loading = true
        error = null
        // 审计修复 (3.3): 复用组合作用域
        scope.launch {
            val w = withContext(Dispatchers.IO) { fetchWeather(lat, lon, context) }
            if (w != null) {
                current = w.first
                daily = w.second
                loading = false
            } else {
                loading = false
                error = context.getString(R.string.weather_service_unavailable)
            }
        }
    }

    fun searchCity(name: String) {
        if (name.isBlank()) return
        loading = true
        error = null
        // 审计修复 (3.3): 复用组合作用域
        scope.launch {
            val result = withContext(Dispatchers.IO) { geocode(name) }
            if (result != null) {
                city = result.first
                loadWeather(result.second.first, result.second.second)
            } else {
                loading = false
                error = context.getString(R.string.weather_city_not_found, name)
            }
        }
    }

    fun loadWeatherByLocation() {
        loading = true
        error = null
        // 审计修复 (3.3): 复用组合作用域
        scope.launch {
            val located = withContext(Dispatchers.IO) { tryLocate(context) }
            if (located != null) {
                city = located.first
                loadWeather(located.second.first, located.second.second)
            } else {
                loading = false
                error = context.getString(R.string.weather_location_failed)
            }
        }
    }

    // 定位权限由用户主动触发,首次进入优先展示城市搜索。
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            loadWeatherByLocation()
        } else {
            loading = false
            error = context.getString(R.string.weather_permission_denied)
        }
    }

    LaunchedEffect(Unit) {
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            loadWeatherByLocation()
        } else {
            loading = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.weather_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        loadWeatherByLocation()
                    } else {
                        permissionLauncher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = stringResource(R.string.weather_current_location),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // 城市搜索
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MuseTextField(
                value = cityInput,
                onValueChange = { cityInput = it },
                placeholder = { Text(stringResource(R.string.weather_search_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.weather_search_action),
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .clickable { searchCity(cityInput.trim()) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        when {
            loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = error ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.weather_permission_guide),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            current != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(MusePaddings.screen),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 当前天气
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = city,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "${current!!.temp.toInt()}°",
                        style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = current!!.desc,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.weather_feels_like_wind,
                            current!!.feelsLike.toInt(),
                            current!!.windSpeed,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )

                    Spacer(Modifier.height(24.dp))

                    // 7 天预报
                    daily.forEach { day ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = day.date,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(72.dp),
                            )
                            Text(
                                text = day.desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${day.min.toInt()}° / ${day.max.toInt()}°",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            else -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.weather_search_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.weather_permission_guide),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/** 当前天气数据。 */
data class WeatherNow(
    val temp: Double,
    val feelsLike: Double,
    val windSpeed: Double,
    val desc: String,
)

/** 单日预报。 */
data class WeatherDay(
    val date: String,
    val min: Double,
    val max: Double,
    val desc: String,
)

/** 尝试定位,返回 (城市名, 经纬度)。无权限/失败返回 null。 */
private suspend fun tryLocate(context: android.content.Context): Pair<String, Pair<Double, Double>>? {
    return try {
        // 检查定位权限
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            ?: return null
        val lat = loc.latitude
        val lon = loc.longitude
        // 反查城市名(Open-Meteo geocoding 反向不支持,用正向搜索最近城市近似,或直接显示"当前位置")
        val name = reverseGeocode(lat, lon) ?: context.getString(R.string.weather_current_location)
        name to (lat to lon)
    } catch (e: Exception) {
        null
    }
}

/** Open-Meteo 城市搜索 → (名称, 经纬度)。 */
private suspend fun geocode(name: String): Pair<String, Pair<Double, Double>>? {
    return try {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=${java.net.URLEncoder.encode(name, "UTF-8")}&count=1&language=zh&format=json"
        val json = httpGet(url) ?: return null
        val root = io.zer0.common.AppJson.parseToJsonElement(json).jsonObject
        val results = root["results"]?.jsonArray ?: return null
        val first = results.firstOrNull()?.jsonObject ?: return null
        val cityName = first["name"]?.jsonPrimitive?.contentOrNull ?: name
        val lat = first["latitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
        val lon = first["longitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
        cityName to (lat to lon)
    } catch (e: Exception) {
        null
    }
}

/** 反查城市名(用 Open-Meteo geocoding 的坐标参数,失败返回 null 显示"当前位置")。 */
private suspend fun reverseGeocode(lat: Double, lon: Double): String? {
    return try {
        val url = "https://geocoding-api.open-meteo.com/v1/search?latitude=$lat&longitude=$lon&count=1&language=zh&format=json"
        val json = httpGet(url) ?: return null
        val root = io.zer0.common.AppJson.parseToJsonElement(json).jsonObject
        root["results"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("name")?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) {
        null
    }
}

/** Open-Meteo 获取天气。返回 (当前, 7 天)。 */
private suspend fun fetchWeather(lat: Double, lon: Double, context: Context): Pair<WeatherNow, List<WeatherDay>>? {
    val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
        "&current=temperature_2m,apparent_temperature,wind_speed_10m,weather_code" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=7"
    val json = httpGet(url) ?: return null
    return try {
        val root = io.zer0.common.AppJson.parseToJsonElement(json).jsonObject
        val current = root["current"]?.jsonObject
        val dailyObj = root["daily"]?.jsonObject
        if (current == null || dailyObj == null) {
            null
        } else {
            val now = WeatherNow(
                temp = current["temperature_2m"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                feelsLike = current["apparent_temperature"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                windSpeed = current["wind_speed_10m"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                desc = weatherCodeText(
                    current["weather_code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    context,
                ),
            )

            val dates = dailyObj["time"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val codes = dailyObj["weather_code"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() } ?: emptyList()
            val maxs = dailyObj["temperature_2m_max"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.toDoubleOrNull() } ?: emptyList()
            val mins = dailyObj["temperature_2m_min"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.toDoubleOrNull() } ?: emptyList()

            val days = dates.mapIndexedNotNull { i, d ->
                if (i >= codes.size) return@mapIndexedNotNull null
                WeatherDay(
                    date = formatDayLabel(d, context),
                    min = mins.getOrElse(i) { 0.0 },
                    max = maxs.getOrElse(i) { 0.0 },
                    desc = weatherCodeText(codes[i], context),
                )
            }
            now to days
        }
    } catch (e: Exception) {
        null
    }
}

/** WMO weather code → 天气描述资源 id。 */
private val weatherCodeStrings: Map<Int, Int> = mapOf(
    0 to R.string.weather_condition_clear,
    1 to R.string.weather_condition_partly_cloudy,
    2 to R.string.weather_condition_partly_cloudy,
    3 to R.string.weather_condition_overcast,
    45 to R.string.weather_condition_fog,
    48 to R.string.weather_condition_fog,
    51 to R.string.weather_condition_drizzle,
    53 to R.string.weather_condition_drizzle,
    55 to R.string.weather_condition_drizzle,
    56 to R.string.weather_condition_freezing_drizzle,
    57 to R.string.weather_condition_freezing_drizzle,
    61 to R.string.weather_condition_rain,
    63 to R.string.weather_condition_rain,
    65 to R.string.weather_condition_rain,
    66 to R.string.weather_condition_freezing_drizzle,
    67 to R.string.weather_condition_freezing_drizzle,
    71 to R.string.weather_condition_snow,
    73 to R.string.weather_condition_snow,
    75 to R.string.weather_condition_snow,
    77 to R.string.weather_condition_sleet,
    80 to R.string.weather_condition_showers,
    81 to R.string.weather_condition_showers,
    82 to R.string.weather_condition_showers,
    85 to R.string.weather_condition_snow_showers,
    86 to R.string.weather_condition_snow_showers,
    95 to R.string.weather_condition_thunderstorm,
    96 to R.string.weather_condition_thunderstorm_hail,
    99 to R.string.weather_condition_thunderstorm_hail,
)

/** WMO weather code → 天气描述(按当前语言)。 */
private fun weatherCodeText(code: Int, context: Context): String =
    context.getString(weatherCodeStrings[code] ?: R.string.weather_condition_unknown)

/** "2026-08-11" → "周一 08-11" 或 "今天"。 */
private fun formatDayLabel(date: String, context: Context): String {
    return try {
        val parsed = java.time.LocalDate.parse(date)
        val today = java.time.LocalDate.now()
        val weekDays = context.resources.getStringArray(R.array.weather_weekdays)
        when (parsed) {
            today -> context.getString(R.string.weather_day_today)
            today.plusDays(1) -> context.getString(R.string.weather_day_tomorrow)
            else -> "${weekDays[parsed.dayOfWeek.value % 7]} ${parsed.monthValue}-${parsed.dayOfMonth}"
        }
    } catch (e: Exception) {
        date
    }
}

/** 简单 HTTP GET(同步)。 */
private fun httpGet(url: String): String? = try {
    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 8000
    conn.readTimeout = 8000
    conn.requestMethod = "GET"
    if (conn.responseCode == 200) {
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } else {
        null
    }
} catch (e: Exception) {
    null
}
