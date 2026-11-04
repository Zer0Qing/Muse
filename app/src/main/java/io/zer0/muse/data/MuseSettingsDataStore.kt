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
import kotlinx.coroutines.cancel
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
 * 所以这里的规矩是：**目录还在的实例谁都不杀**；只回收"目录已被删除"的僵尸实例
 * （见 [evictDeletedStoreDirsLocked]，那条区别是关键）。
 */
private class SettingsStoreEntry(
    val store: DataStore<Preferences>,
    val scope: CoroutineScope,
)

private val settingsStores = LinkedHashMap<File, SettingsStoreEntry>()

/**
 * 回收**目录已经消失**的旧实例。
 *
 * 这跟被否证的「换目录就取消旧 scope」不是一回事，区别就在"只碰目录已消失的那些"：
 * 目录还在，说明那个实例可能正被别处的读流骑着，取消会顺着 flow 打到别人的收集协程上
 * （历史症状：无关的第三个用例红、堆栈指着别人的测试，实测打到过 `DiagnosticExporterZipTest`）。
 * 而目录已经被删（Robolectric 在测试结束时删掉临时 `filesDir`），就意味着那个用例已经走完 ——
 * 留着它的害处有两层：
 *
 *  1. 它的写盘协程还挂在已删除的路径上，再触发一次就是
 *     `另一个程序正在使用此文件` / `AccessDeniedException` —— 这正是全量测试里那族偶发红；
 *  2. 每个用例一个新目录，实例只增不减。
 *
 * 生产进程只有一个目录、也不会被删，所以这段在生产里恒为 no-op，不改变任何行为。
 */
private fun evictDeletedStoreDirsLocked(current: File) {
    // 先挑出要摘的（得到的是快照），再逐个移除：
    // 既不用在循环里写跳转，也不会边遍历边改同一个 map
    settingsStores.filterKeys { key -> key != current && !key.exists() }
        .forEach { (dir, entry) ->
            settingsStores.remove(dir)
            entry.scope.cancel("设置目录已被删除，回收该实例")
        }
}

/**
 * 测试可见：当前缓存里持有着哪些 `filesDir` 的实例。
 *
 * 必须是"看得见具体目录"而不是只给个数 —— 只给个数的话，
 * 断言很容易写成永远成立的形状（我第一版就是这么错的）。
 */
internal fun museSettingsStoreCachedDirsForTests(): Set<File> =
    synchronized(settingsStoreLock) { settingsStores.keys.toSet() }

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
 * 目录变了（只发生在测试）就为新目录建新实例，并顺手回收目录已消失的旧实例。
 */
internal val Context.museSettingsDataStore: DataStore<Preferences>
    get() {
        val filesDir = applicationContext.filesDir
        synchronized(settingsStoreLock) {
            settingsStores[filesDir]?.let { return it.store }
            evictDeletedStoreDirsLocked(current = filesDir)
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
            settingsStores[filesDir] = SettingsStoreEntry(created, scope)
            return created
        }
    }
