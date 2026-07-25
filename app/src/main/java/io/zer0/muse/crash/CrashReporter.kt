package io.zer0.muse.crash

import android.content.Context
import android.content.Intent
import android.os.Build
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 崩溃上报抽象接口。
 *
 * 设计为"非阻塞"语义:实现方负责把上报动作推到后台线程/外部 Intent,
 * [report] 本身不阻塞调用线程(尤其不能阻塞崩溃处理器所在的失败线程)。
 *
 * 隐私约束(项目级):
 *  - 上报前必须由调用方确认用户已授权(默认关闭,见 [CrashReportSettings])
 *  - 崩溃日志需经 [MuseCrashHandler.redactSensitive] 脱敏(去掉 API key / Bearer token)
 *  - 设备信息只收集型号 / Android 版本 / App 版本 / 时间戳,不收集 IMEI / Android ID 等唯一标识
 */
interface CrashReporter {
    /**
     * 上报一条崩溃日志。
     *
     * @param crashLog 脱敏后的崩溃堆栈文本(已含设备信息头,由 [MuseCrashHandler.writeCrashLog] 生成)
     * @param metadata 附加元数据(型号 / Android 版本 / App 版本 / 时间戳等,见 [buildStandardMetadata])
     */
    fun report(crashLog: String, metadata: Map<String, String>)
}

/**
 * 崩溃上报器工厂 — 根据用户配置选择具体实现。
 *
 * 调用方传入 [method]("email" / "webhook")与对应参数,返回具体 [CrashReporter] 实例。
 * 未知方法返回 [NoopCrashReporter],保证调用方无需处理 null。
 */
object CrashReporterFactory {
    /** 邮件上报方式标识。 */
    const val METHOD_EMAIL = "email"
    /** Webhook 上报方式标识。 */
    const val METHOD_WEBHOOK = "webhook"

    /**
     * 创建 [CrashReporter] 实例。
     *
     * @param context 应用上下文(邮件 Intent 启动需要)
     * @param method 上报方式([METHOD_EMAIL] / [METHOD_WEBHOOK])
     * @param emailRecipient 邮件收件人(method=email 时使用)
     * @param webhookUrl Webhook URL(method=webhook 时使用)
     * @param client Webhook 上报使用的 OkHttpClient(共享 chat client 即可)
     */
    fun create(
        context: Context,
        method: String,
        emailRecipient: String,
        webhookUrl: String,
        client: OkHttpClient,
    ): CrashReporter = when (method) {
        METHOD_EMAIL -> EmailCrashReporter(context, emailRecipient)
        METHOD_WEBHOOK -> WebhookCrashReporter(webhookUrl, client)
        else -> NoopCrashReporter
    }
}

/**
 * 空实现 — 用户未配置或未授权时返回此实例,所有调用为 no-op。
 *
 * 避免调用方处理 null,简化上游逻辑。
 */
object NoopCrashReporter : CrashReporter {
    override fun report(crashLog: String, metadata: Map<String, String>) {
        // 默认不上报,隐私优先
    }
}

/**
 * 邮件崩溃上报器 — 用 [Intent.ACTION_SEND] 调起邮件应用,预填收件人/主题/正文。
 *
 * 注意:本实现不阻塞调用线程,直接 fire-and-forget 启动 Intent(使用 [Intent.FLAG_ACTIVITY_NEW_TASK]
 * 因为是从非 Activity 上下文启动)。实际邮件是否发出由用户在邮件应用中确认,
 * 符合"上报前必须用户授权"的隐私约束。
 *
 * 限制:无邮件应用时 Intent 会启动失败(安静失败,不影响主流程)。
 *      后续可考虑 fallback 到剪贴板复制(参考 SettingsSubPages.sendFeedback)。
 */
class EmailCrashReporter(
    private val context: Context,
    private val recipient: String,
) : CrashReporter {

    override fun report(crashLog: String, metadata: Map<String, String>) {
        if (recipient.isBlank()) {
            Logger.w(TAG, "邮件收件人为空,跳过上报")
            return
        }
        // 把元数据拼接到正文头部,便于收到邮件后快速定位设备/版本
        val body = buildString {
            appendLine("===== Muse 崩溃报告 =====")
            metadata.forEach { (k, v) -> appendLine("$k: $v") }
            appendLine()
            appendLine("----- 崩溃堆栈 -----")
            appendLine(crashLog)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, "Muse 崩溃报告 ${metadata["timestamp"] ?: ""}")
            putExtra(Intent.EXTRA_TEXT, body)
            // 从 Application Context 启动 Activity 必须加 NEW_TASK
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // 用 createChooser 让用户选择邮件应用,失败时安静降级(不抛异常避免二次崩溃)
        resultOf {
            context.startActivity(Intent.createChooser(intent, "选择邮件应用发送崩溃报告"))
        }.onError { msg, t ->
            Logger.w(TAG, "调起邮件应用失败: ${t?.message ?: msg}")
        }
    }

    private companion object {
        private const val TAG = "EmailCrashReporter"
    }
}

/**
 * Webhook 崩溃上报器 — HTTP POST 到用户配置的 URL,JSON 格式。
 *
 * 异步执行:在内部 [scope] 协程中发起请求,不阻塞调用线程(崩溃处理线程)。
 * 超时设置:连接 15s / 读 30s(避免 webhook 服务异常时挂起后台协程)。
 *
 * 请求体 JSON 结构:
 * ```
 * {
 *   "timestamp": "2026-07-23 12:34:56",
 *   "device": { "model": "...", "brand": "...", "android": "...", "abi": "..." },
 *   "app_version": "1.0.11",
 *   "crash_log": "..."
 * }
 * ```
 *
 * 失败处理:网络/HTTP 错误只记录日志,不重试(避免在崩溃后产生异常循环)。
 */
class WebhookCrashReporter(
    private val webhookUrl: String,
    /** 复用上游 OkHttpClient(用 newBuilder() 覆盖超时,不影响其他模块)。 */
    client: OkHttpClient,
) : CrashReporter {

    // 独立 scope:Webhook 上报失败不能影响调用方协程,SupervisorJob 保证子协程异常不传播
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 覆盖超时:webhook 服务响应时间不可控,设短一些避免后台协程长期挂起
    private val httpclient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun report(crashLog: String, metadata: Map<String, String>) {
        if (webhookUrl.isBlank() || !webhookUrl.startsWith("http")) {
            Logger.w(TAG, "Webhook URL 非法或为空,跳过上报: $webhookUrl")
            return
        }
        // 异步发起请求,不阻塞崩溃处理线程
        scope.launch {
            resultOf {
                val payload = buildJsonPayload(crashLog, metadata)
                val request = Request.Builder()
                    .url(webhookUrl)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                httpclient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Logger.w(TAG, "Webhook 上报失败: HTTP ${response.code}")
                    } else {
                        Logger.i(TAG, "Webhook 上报成功")
                    }
                }
            }.onError { msg, t ->
                Logger.w(TAG, "Webhook 上报异常: ${t?.message ?: msg}")
            }
        }
    }

    /** 构建 JSON 请求体 — 把 metadata 拆为 device / app_version 顶层字段便于服务端解析。 */
    private fun buildJsonPayload(crashLog: String, metadata: Map<String, String>): String {
        val json = JSONObject()
        json.put("timestamp", metadata["timestamp"] ?: "")
        // 设备信息子对象
        val device = JSONObject()
        device.put("brand", metadata["device_brand"] ?: "")
        device.put("model", metadata["device_model"] ?: "")
        device.put("android", metadata["android_version"] ?: "")
        device.put("abi", metadata["device_abi"] ?: "")
        json.put("device", device)
        json.put("app_version", metadata["app_version"] ?: "")
        json.put("crash_log", crashLog)
        return json.toString()
    }

    private companion object {
        private const val TAG = "WebhookCrashReporter"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * 构建标准崩溃元数据 — 型号 / Android 版本 / App 版本 / 时间戳。
 *
 * 供 [CrashReporter.report] 的 metadata 参数使用,保证两种实现上报内容一致。
 * App 版本通过 PackageManager 读取(无 BuildConfig 依赖,避免 release 构建混淆时丢失)。
 */
fun buildStandardMetadata(context: Context): Map<String, String> {
    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    // 读取 App 版本名(兼容 Android 13+ 的 PackageInfoFlags API)
    val appVersion = resultOf {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0),
            ).versionName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }
    }.getOrNull() ?: "unknown"
    return mapOf(
        "timestamp" to timestamp,
        "device_brand" to Build.BRAND,
        "device_model" to Build.MODEL,
        "android_version" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        "device_abi" to Build.SUPPORTED_ABIS.joinToString(","),
        "app_version" to appVersion,
    )
}
