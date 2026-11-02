package io.zer0.muse.data

import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.datastore.preferences.core.emptyPreferences
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

private val settingsStoreLock = Any()

/**
 * 按 `filesDir` 分片缓存。
 *
 * **为什么不能"换目录时取消旧 scope"**（这里以前就是这么做的）：
 * 旧实例可能正被**别处**骑着读流 —— 生产里不会发生（只有一个目录），
 * 但测试 JVM 是同一个进程跑很多用例，A 用例的订阅者还挂在流上，B 用例一取 store 就触发换目录，
 * 取消会顺着 flow 打到 A 的收集协程上，症状是"完全无关的第三个用例红，堆栈里却指着别人的测试"。
 * 实测被打到的有 `DiagnosticExporterZipTest`（JobCancellationException）。
 *
 * 现在只做一件事：一个目录一个实例，谁都不杀。旧目录被 Robolectric 删掉后，
 * 残留订阅者若再写盘会抛 IO 异常 —— 那由作用域上的
 * [io.zer0.muse.util.GlobalCoroutineExceptionHandler] 就地记录，不再冒泡到线程默认处理器。
 * 生产进程只有一个目录，因此这个 Map 恒为 1 项，不存在累积成本。
 */
private val settingsStores = LinkedHashMap<File, DataStore<Preferences>>()

/**
 * 所有 Settings 子仓库共用的 `muse_settings` DataStore。
 *
 * **为什么不用 `by preferencesDataStore(...)`**：
 * 那个委托是**全局静态**的 —— 同一个进程里第一次取用后就把 DataStore 钉死在当时 `filesDir` 上。
 * 生产环境 `filesDir` 不变，所以没问题；但 Robolectric 每个测试会给一个新的 `filesDir`，
 * 静态委托仍往**已被删除的旧目录**写，`Files.move` 抛 `AccessDeniedException`
 * （表现为「随便哪个用到设置的测试偶发红」，与代码逻辑无关）。
 *
 * 这里改为**按 filesDir 缓存**：目录不变就复用同一个实例（满足 DataStore「同一文件只能有一个实例」），
 * 目录变了（只发生在测试）就为新目录建新实例。
 */
internal val Context.museSettingsDataStore: DataStore<Preferences>
    get() {
        val filesDir = applicationContext.filesDir
        synchronized(settingsStoreLock) {
            settingsStores[filesDir]?.let { return it }
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + GlobalCoroutineExceptionHandler)
            val created = PreferenceDataStoreFactory.create(
                scope = scope,
                corruptionHandler = ReplaceFileCorruptionHandler { ex ->
                    Logger.w("MuseSettingsDataStore", "muse_settings 数据损坏，尝试备份并重建: ${ex.message}", ex)
                    SettingsStoreContextHolder.applicationContext?.let { ctx ->
                        runCatching { backupCorruptFile(ctx) }
                            .onFailure { Logger.w("MuseSettingsDataStore", "备份损坏文件失败: ${it.message}", it) }
                    }
                    emptyPreferences()
                },
                produceFile = { File(filesDir, "datastore/$SETTINGS_STORE_NAME.preferences_pb") },
            )
            settingsStores[filesDir] = created
            return created
        }
    }
