package io.zer0.muse.diagnostic

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.BuildConfig
import io.zer0.muse.R
import io.zer0.muse.debug.DebugLogStore
import io.zer0.muse.schedule.BackgroundWakeDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v2.x（诊断导出项目 A）：把诊断信息打包成 zip 并调起系统分享。
 *
 * 包含内容（全部脱敏）：
 *  - generation-trace.jsonl：最近 20 轮生成链路轨迹（统计量，无正文）
 *  - device-info.txt：设备/Android 版本/ABI/app 版本
 *  - debug-log.txt：DebugLogStore 的现有日志缓冲（若有）
 *  - background-work.txt：WorkManager 队列里在册的后台任务状态（定时任务「没跑」时唯一能回答
 *    「系统到底有没有唤醒」的证据，见 [BackgroundWakeDiagnostics]）
 *
 * **红线**：不含消息正文、prompt、API key 原文。
 */
object DiagnosticExporter {

    private val exportDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * 打包诊断 zip 到 cacheDir，返回 FileProvider URI（供分享 Intent 使用）。
     * 失败返回 null。
     *
     * 全程在 IO 线程：zip 写入、日志文件读取和 WorkManager 查询都不应该压在调用方线程上。
     */
    suspend fun exportZip(context: Context): android.net.Uri? = withContext(Dispatchers.IO) {
        val zipFile = buildDiagnosticZip(context) ?: return@withContext null
        runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
        }.onFailure { Logger.w("DiagnosticExporter", "导出失败: ${it.message}") }.getOrNull()
    }

    /**
     * 只负责把内容写进 zip，不碰 FileProvider。
     *
     * 拆成独立函数是为了能直接对 zip 内容做断言（不依赖 provider 声明与分享 Intent）。
     * 任一环节抛异常时整包失败，返回 null。
     */
    internal suspend fun buildDiagnosticZip(context: Context): File? = withContext(Dispatchers.IO) {
        // 唤醒状态采集失败不影响整包导出:其它诊断信息仍然有价值。
        val backgroundWork = resultOf {
            BackgroundWakeDiagnostics.formatSnapshot(BackgroundWakeDiagnostics.collectWithSettings(context))
        }.onError { message, throwable ->
            Logger.w("DiagnosticExporter", "后台唤醒状态采集失败: $message", throwable)
        }.getOrNull()
        runCatching {
            val zipFile = File(context.cacheDir, "muse_diagnostic_${exportDateFormat.format(Date())}.zip")
            ZipOutputStream(BufferedOutputStream(zipFile.outputStream())).use { zos ->
                // 1. 生成链路轨迹
                GenerationTrace.traceFileOrNull()?.let { traceFile ->
                    zos.putNextEntry(ZipEntry("generation-trace.jsonl"))
                    traceFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                } ?: run {
                    // 无文件时导内存缓冲（进程刚启动还没落盘的轮次）
                    val records = GenerationTrace.snapshot()
                    if (records.isNotEmpty()) {
                        zos.putNextEntry(ZipEntry("generation-trace.jsonl"))
                        records.forEach { rec ->
                            val line = AppJson.encodeToString(GenerationTrace.TraceRecord.serializer(), rec)
                            zos.write((line + "\n").toByteArray())
                        }
                        zos.closeEntry()
                    }
                }

                // 2. 设备信息
                zos.putNextEntry(ZipEntry("device-info.txt"))
                zos.write(buildDeviceInfo().toByteArray())
                zos.closeEntry()

                // 3. 现有调试日志（内存环形缓冲，只有最近 1000 条）
                DebugLogStore.exportToFile()?.let { debugFile ->
                    zos.putNextEntry(ZipEntry("debug-log.txt"))
                    debugFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }

                // 4. 文件日志（cacheDir/logs/muse.log，1MB 滚动）
                // 渠道链路的问题经常发生在几千条日志之前，内存缓冲里早被冲掉了。
                Logger.getLogFile()?.let { logFile ->
                    zos.putNextEntry(ZipEntry("muse.log"))
                    logFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }

                // 5. 后台唤醒状态（WorkManager 队列的在册任务与状态）
                if (backgroundWork != null) {
                    zos.putNextEntry(ZipEntry("background-work.txt"))
                    zos.write(backgroundWork.toByteArray())
                    zos.closeEntry()
                }
            }
            zipFile
        }.onFailure { Logger.w("DiagnosticExporter", "导出失败: ${it.message}") }.getOrNull()
    }

    /** 调起系统分享。 */
    suspend fun share(context: Context) {
        val uri = exportZip(context) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostic_export_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.diagnostic_export_title)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private fun buildDeviceInfo(): String = buildString {
        appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("locale: ${Locale.getDefault()}")
        appendLine("exported_at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
    }
}
