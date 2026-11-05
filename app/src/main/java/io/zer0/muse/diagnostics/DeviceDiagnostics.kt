package io.zer0.muse.diagnostics

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.memory.fact.FACT_DB_VERSION
import io.zer0.memory.summary.MEMORY_DB_VERSION
import io.zer0.muse.BuildConfig
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.schedule.BackgroundWakeDiagnostics
import io.zer0.muse.schedule.WakeDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * v2.6.23(S4): 诊断快照的聚合与导出。
 *
 * 定位(与「以为能用结果用不了」同源的问题意识):用户报「记忆页变慢/后台没跑」时,
 * 过去只能靠猜;这块把可以确定回答的问题一次收集齐,并且**以能安全粘贴给开发者为前提**。
 *
 * 脱敏规则是硬性契约,不是建议:
 *  - 只输出:字节数、计数、时长、版本号、枚举名、库文件名;
 *  - 绝不输出:消息内容、会话 id、渠道对端 id、密钥/密码/PIN、绝对路径、账号信息;
 *  - `sanitizedJson` 的字段集合固定,新增字段必须先过这轮审查。
 */
data class DiagnosticsSnapshot(
    val collectedAt: String,
    val app: AppSection,
    val storage: StorageSection,
    val mainThread: MainThreadSection,
    val webHost: WebHostSection,
    val background: WakeDiagnostics,
) {
    data class AppSection(
        val versionName: String,
        val versionCode: Int,
        /** 各数据库当前 schema 版本(app 层只能静态取到注册值)。 */
        val museDbVersion: Int,
        val factDbVersion: Int,
        val memoryDbVersion: Int,
    )

    data class StorageSection(
        val databases: List<DatabaseFootprintEntry>,
        val logBytes: Long,
        val totalBytes: Long,
    )

    data class MainThreadSection(
        val jankCount: Int,
        val slowCount: Int,
        val blockCount: Int,
        val worstMs: Long,
        val windowSeconds: Long,
    )

    data class WebHostSection(
        val latencySamples: Int,
        val p95Ms: Long?,
        /** 样本不足时 UI 与导出都要标出来,不给假的精确数字。 */
        val p95Reliable: Boolean,
    )

    /**
     * 固定字段的脱敏 JSON。手写映射而不是整对象 encode:
     * 以后往 Snapshot 里塞新字段时,必须**显式**决定是否进导出 —— 默认不外泄。
     */
    fun sanitizedJson(): String = AppJson.encodeToString(
        ExportedDiagnostics.serializer(),
        ExportedDiagnostics(
            collectedAt = collectedAt,
            app = ExportedApp(app.versionName, app.versionCode, app.museDbVersion, app.factDbVersion, app.memoryDbVersion),
            databases = storage.databases.map { ExportedDb(it.name, it.mainBytes, it.walBytes, it.shmBytes, it.known) },
            logBytes = storage.logBytes,
            jank = ExportedJank(mainThread.jankCount, mainThread.slowCount, mainThread.blockCount, mainThread.worstMs),
            web = ExportedWeb(webHost.latencySamples, webHost.p95Ms, webHost.p95Reliable),
            background = ExportedBackground(
                background.entries.map { ExportedWake(it.kind.name, it.state.name, it.stopReason.name) },
            ),
        ),
    )

    /** 写入 cacheDir/diagnostics.json 并返回文件(cacheDir 随应用卸载清理,不留敏感残留)。 */
    suspend fun exportToCache(context: Context): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "diagnostics.json")
        runCatching {
            file.writeText(sanitizedJson())
        }.onFailure { Logger.w(TAG, "诊断导出写盘失败", it) }
        file
    }

    companion object {
        private const val TAG = "DeviceDiagnostics"

        /**
         * 收集一次快照。所有读取都是只读且容错:
         * 单个来源失败表现为该段为空/零,而不是整卡打不开 —— 诊断工具自己崩溃
         * 是最讽刺的失效形态。
         */
        suspend fun collect(context: Context): DiagnosticsSnapshot = withContext(Dispatchers.IO) {
            val jank = MainThreadJankTracker.shared.snapshot()
            val webSamples = WebHostLatencyStats.shared.snapshot()
            val databases = DatabaseFootprint.collect(context)
            DiagnosticsSnapshot(
                collectedAt = Instant.now().toString(),
                app = AppSection(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    museDbVersion = MuseDb.LATEST_SCHEMA_VERSION,
                    factDbVersion = FACT_DB_VERSION,
                    memoryDbVersion = MEMORY_DB_VERSION,
                ),
                storage = StorageSection(
                    databases = databases,
                    logBytes = Logger.getLogFile()?.length() ?: 0L,
                    totalBytes = databases.sumOf { it.mainBytes + it.walBytes + it.shmBytes },
                ),
                mainThread = MainThreadSection(
                    jankCount = jank.jankCount,
                    slowCount = jank.slowCount,
                    blockCount = jank.blockCount,
                    worstMs = jank.worstMs,
                    windowSeconds = ((jank.nowMs - jank.windowStartedMs) / 1000L).coerceAtLeast(0L),
                ),
                webHost = WebHostSection(
                    latencySamples = webSamples.first,
                    p95Ms = webSamples.second,
                    p95Reliable = webSamples.first >= WebHostLatencyStats.MIN_RELIABLE_SAMPLES,
                ),
                background = runCatching {
                    BackgroundWakeDiagnostics.collectWithSettings(context)
                }.getOrElse {
                    Logger.w(TAG, "后台唤醒快照采集失败: ${it.message}")
                    // 失败也要出整卡:以 error 字段呈现,其余为空
                    WakeDiagnostics(
                        entries = emptyList(),
                        backgroundWorkEnabled = null,
                        batteryOptimizationExempt = false,
                        exportedAtMillis = 0L,
                        appVersion = "",
                        androidVersion = "",
                        device = "",
                        error = it.message ?: "collect failed",
                    )
                },
            )
        }
    }
}

// ── 导出 schema(固定字段,见 sanitizedJson 的审查约束) ────────────────

@kotlinx.serialization.Serializable
internal data class ExportedDiagnostics(
    val collectedAt: String,
    val app: ExportedApp,
    val databases: List<ExportedDb>,
    val logBytes: Long,
    val jank: ExportedJank,
    val web: ExportedWeb,
    val background: ExportedBackground,
)

@kotlinx.serialization.Serializable
internal data class ExportedApp(
    val versionName: String,
    val versionCode: Int,
    val museDbVersion: Int,
    val factDbVersion: Int,
    val memoryDbVersion: Int,
)

@kotlinx.serialization.Serializable
internal data class ExportedDb(
    val name: String,
    val mainBytes: Long,
    val walBytes: Long,
    val shmBytes: Long,
    val known: Boolean,
)

@kotlinx.serialization.Serializable
internal data class ExportedJank(
    val jankCount: Int,
    val slowCount: Int,
    val blockCount: Int,
    val worstMs: Long,
)

@kotlinx.serialization.Serializable
internal data class ExportedWeb(
    val latencySamples: Int,
    val p95Ms: Long?,
    val p95Reliable: Boolean,
)

@kotlinx.serialization.Serializable
internal data class ExportedBackground(
    val works: List<ExportedWake>,
)

@kotlinx.serialization.Serializable
internal data class ExportedWake(
    val kind: String,
    val state: String,
    val stopReason: String,
)
