package io.zer0.muse.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import io.zer0.common.Logger
import java.io.File

/**
 * P2-2：所有 Settings 子仓库共用的 `muse_settings` DataStore。
 *
 * 必须只有一个委托，否则同一文件会出现多个 DataStore 实例，
 * 在测试或并发写入时触发 IllegalStateException。
 *
 * v2.5.3 (P1-2)：损坏文件先备份再重建。
 * 默认的 ReplaceFileCorruptionHandler 会直接用空偏好替换损坏文件，等于静默清空用户全部设置，
 * 且原始损坏内容不可找回。这里在替换前把损坏文件另存为 `*.corrupt-<时间戳>.preferences_pb`，
 * 便于事后排查与人工恢复。
 */
private const val SETTINGS_STORE_NAME = "muse_settings"

/**
 * 尽力备份损坏的数据文件；失败不影响重建（不能让备份异常阻断启动）。
 *
 * 该 Handler 拿不到当前被损坏文件路径，但 muse_settings 只有一个固定文件名，
 * 因此按确定路径定位（datastore 子目录与根目录都探测一次，兼容不同 DataStore 版本布局）。
 */
private fun backupCorruptFile(context: Context) {
    val candidates =
        listOf(
            File(context.filesDir, "datastore/$SETTINGS_STORE_NAME.preferences_pb"),
            File(context.filesDir, "$SETTINGS_STORE_NAME.preferences_pb"),
        )
    val source = candidates.firstOrNull { it.exists() }
    if (source == null) {
        Logger.w("MuseSettingsDataStore", "未找到损坏文件，跳过备份")
        return
    }
    val backup = File(source.parentFile, "$SETTINGS_STORE_NAME.corrupt-${System.currentTimeMillis()}.preferences_pb")
    source.copyTo(backup, overwrite = true)
    Logger.w("MuseSettingsDataStore", "损坏文件已备份: ${backup.absolutePath}")
}

/**
 * v2.5.3：供损坏备份逻辑拿到 Application Context。
 *
 * 由 MuseApp.onCreate 初始化；未初始化时备份跳过，不影响 DataStore 重建。
 */
internal object SettingsStoreContextHolder {
    @Volatile
    var applicationContext: Context? = null
}

internal val Context.museSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SETTINGS_STORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { ex ->
        Logger.w("MuseSettingsDataStore", "muse_settings 数据损坏，尝试备份并重建: ${ex.message}", ex)
        SettingsStoreContextHolder.applicationContext?.let { ctx ->
            runCatching { backupCorruptFile(ctx) }
                .onFailure { Logger.w("MuseSettingsDataStore", "备份损坏文件失败: ${it.message}", it) }
        }
        emptyPreferences()
    },
)
