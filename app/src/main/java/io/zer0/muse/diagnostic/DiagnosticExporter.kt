package io.zer0.muse.diagnostic

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.BuildConfig
import io.zer0.muse.R
import io.zer0.muse.debug.DebugLogStore
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
 *
 * **红线**：不含消息正文、prompt、API key 原文。
 */
object DiagnosticExporter {

    private val exportDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * 打包诊断 zip 到 cacheDir，返回 FileProvider URI（供分享 Intent 使用）。
     * 失败返回 null。
     */
    fun exportZip(context: Context): android.net.Uri? {
        return runCatching {
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

                // 3. 现有调试日志
                DebugLogStore.exportToFile()?.let { debugFile ->
                    zos.putNextEntry(ZipEntry("debug-log.txt"))
                    debugFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
        }.onFailure { Logger.w("DiagnosticExporter", "导出失败: ${it.message}") }.getOrNull()
    }

    /** 调起系统分享。 */
    fun share(context: Context) {
        val uri = exportZip(context) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostic_export_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.diagnostic_export_title)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
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
