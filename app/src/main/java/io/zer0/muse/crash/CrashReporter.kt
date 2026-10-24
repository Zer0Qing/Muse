package io.zer0.muse.crash

import android.content.Context
import android.content.Intent
import android.os.Build
import io.zer0.common.Logger
import io.zer0.common.resultOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 崩溃上报的固定出口。
 *
 * ## 为什么不再让用户选
 *
 * 旧实现把「发到哪个邮箱 / 哪个 webhook」做成设置项，让终端用户自己填 —— 这件事本身是错的：
 * 用户既不知道该发给谁，也没有义务为开发者的崩溃收集做配置。设置页上是两个空输入框加一个
 * 「保存」按钮，绝大多数人点开只有困惑。
 *
 * 现在的模型是**一个按钮**：点「立即上报」，App 自己把日志送到开发者的服务器；
 * 服务器不可达时才退回邮件（邮件地址也写死在代码里，用户只看到一句「会发给开发者」）。
 *
 * ## 隐私边界（与旧实现一致，没有放宽）
 *
 * - 上报内容只含：崩溃堆栈（已脱敏）、设备型号 / 品牌 / Android 版本 / ABI、App 版本、时间戳
 * - 不含 IMEI / Android ID / MAC / 位置 / 账号等任何唯一标识
 * - 仍然只在用户点按钮时发生，没有任何后台静默上报
 */
object CrashReporterFactory {

    /** 崩溃报告的唯一收件地址（开发者邮箱）。用户不需要、也不该填写这个。 */
    const val REPORT_EMAIL = "2119280571@qq.com"

    /**
     * 崩溃报告的服务端接收端点（museai.ltd 自建）。
     *
     * 这是开发者自己的服务器，与用户配置的 AI 中转代理无关，**必须直连**。
     * 详见 [directClient]。
     */
    const val REPORT_ENDPOINT = "https://museai.ltd/api/crash-report"

    /**
     * 为崩溃上报准备一个**不带代理**的 client。
     *
     * 为什么必须单独建：调用方传进来的往往是 `named("chat")` 那个共享 client，
     * 它装了动态 ProxySelector，会按用户配置的代理转发所有请求。用户为了连 AI 中转
     * 而配的代理，很可能根本到不了开发者的服务器（甚至把它整个封掉）——
     * 于是上报永远失败，而这跟“服务器挂了”从外部看一模一样。
     *
     * 这里显式把 proxy 设为 NO_PROXY：崩溃上报这种基建链路不该受用户代理配置影响。
     */
    internal fun directClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .proxy(Proxy.NO_PROXY)
        .proxyAuthenticator { _, _ -> null }
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** 服务端单次提交超时（秒）。崩溃后不该为了上报把用户卡在这里。 */
    internal const val CONNECT_TIMEOUT_SECONDS = 10L
    internal const val WRITE_TIMEOUT_SECONDS = 15L
    internal const val READ_TIMEOUT_SECONDS = 10L

    /**
     * 上报一次崩溃批次。
     *
     * 顺序：先打服务端（一次点击、静默完成）；服务端不可达时退回邮件 Intent，
     * 保证「有网但服务没起来」和「没网」这两种情况下用户的点击都不白点。
     *
     * @return 实际上走了哪条通道，供界面显示准确的结果文案
     */
    suspend fun submit(
        context: Context,
        client: OkHttpClient,
        crashLogs: List<String>,
        metadata: Map<String, String> = buildStandardMetadata(context),
    ): SubmitOutcome {
        if (crashLogs.isEmpty()) return SubmitOutcome.NothingToSend
        val uploaded = runCatching {
            ServerCrashReporter(REPORT_ENDPOINT, directClient(client)).send(crashLogs, metadata)
        }.onFailure { Logger.w(TAG, "服务端上报异常: ${describeForLog(it)}") }.getOrDefault(false)
        return if (uploaded) {
            SubmitOutcome.Uploaded(crashLogs.size)
        } else {
            // 服务端不通 → 邮件兜底。收件人写死，用户只需要在邮件应用里点发送。
            val opened = runCatching {
                EmailCrashReporter(context, REPORT_EMAIL).report(crashLogs.joinToString("\n\n---\n\n"), metadata)
            }.onFailure { Logger.w(TAG, "邮件兜底失败: ${it.message}") }.isSuccess
            if (opened) SubmitOutcome.MailFallback(crashLogs.size) else SubmitOutcome.Failed
        }
    }

    private const val TAG = "CrashReporterFactory"

    /**
     * 把异常转成可诊断的一行。
     *
     * 旧写法只记 `it.message`，而网络异常经常 message 为 null（例如 OkHttp 内部的
     * `IOException` 子类），日志里就只剩「服务端上报异常: null」—— 等于没有信息。
     * 这里补上类型名与 cause 链，下次再失败能直接看出是 DNS、拒绝连接还是超时。
     */
    internal fun describeForLog(t: Throwable): String {
        val parts = mutableListOf<String>()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 4) {
            val msg = cur.message?.takeIf { it.isNotBlank() }
            parts += if (msg != null) "${cur.javaClass.simpleName}: $msg" else cur.javaClass.simpleName
            cur = cur.cause
            depth++
        }
        return parts.joinToString(" <- ")
    }
}

/** 崩溃上报的落地结果。 */
sealed interface SubmitOutcome {
    /** 没有待上报记录。 */
    data object NothingToSend : SubmitOutcome

    /** 已提交到开发者服务器。 */
    data class Uploaded(val count: Int) : SubmitOutcome

    /** 服务器不可达，已改为打开邮件（用户在邮件应用里确认发送）。 */
    data class MailFallback(val count: Int) : SubmitOutcome

    /** 两条通道都失败。 */
    data object Failed : SubmitOutcome
}

/**
 * 邮件崩溃上报器 — 用 [Intent.ACTION_SEND] 调起邮件应用，预填收件人/主题/正文。
 *
 * 只在服务端上报不可达时使用。收件人由 [CrashReporterFactory.REPORT_EMAIL] 固定，
 * 不读任何用户设置。
 */
class EmailCrashReporter(
    private val context: Context,
    private val recipient: String,
) {
    fun report(crashLog: String, metadata: Map<String, String>) {
        if (recipient.isBlank()) {
            Logger.w(TAG, "邮件收件人为空,跳过上报")
            return
        }
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
        // createChooser 返回新 Intent，原始 intent 上的 NEW_TASK 不会自动传递，
        // 统一走 ShareIntentHelper 在非 Activity context 下补 flag。
        resultOf {
            io.zer0.muse.util.ShareIntentHelper.startChooserSafely(context, intent, "选择邮件应用发送崩溃报告")
        }.onError { msg, t ->
            Logger.w(TAG, "调起邮件应用失败: ${t?.message ?: msg}")
        }
    }

    private companion object {
        private const val TAG = "EmailCrashReporter"
    }
}

/**
 * 服务端崩溃上报器 — POST 到 museai.ltd 的接收端点，JSON 格式。
 *
 * 与旧 webhook 实现的差别：URL 固定（不再读用户配置）、**同步返回结果**
 * （调用方要据此决定要不要退回邮件），超时也更短。
 *
 * 请求体：
 * ```
 * {
 *   "timestamp": "2026-07-23 12:34:56",
 *   "device": { "brand": "...", "model": "...", "android": "...", "abi": "..." },
 *   "app_version": "1.0.11",
 *   "count": 2,
 *   "crash_logs": ["...", "..."]
 * }
 * ```
 */
class ServerCrashReporter(
    private val endpoint: String,
    client: OkHttpClient,
) {
    private val httpClient = client.newBuilder()
        .connectTimeout(CrashReporterFactory.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(CrashReporterFactory.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(CrashReporterFactory.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * 上传一批崩溃日志。
     *
     * @return true 表示服务端接受（HTTP 2xx）；false 表示不可达或被拒 —— 调用方据此回退。
     */
    suspend fun send(crashLogs: List<String>, metadata: Map<String, String>): Boolean {
        if (endpoint.isBlank() || !endpoint.startsWith("http")) {
            Logger.w(TAG, "上报端点非法: $endpoint")
            return false
        }
        return runCatching {
            val request = Request.Builder()
                .url(endpoint)
                .post(buildJsonPayload(crashLogs, metadata).toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Logger.i(TAG, "崩溃已上报到服务端 (${crashLogs.size} 条)")
                    true
                } else {
                    Logger.w(TAG, "服务端拒绝崩溃上报: HTTP ${response.code}")
                    false
                }
            }
        }.onFailure { Logger.w(TAG, "服务端上报异常: ${CrashReporterFactory.describeForLog(it)}") }.getOrDefault(false)
    }

    /** 构建 JSON 请求体。批次内多条日志用数组，服务端按条归档。 */
    private fun buildJsonPayload(crashLogs: List<String>, metadata: Map<String, String>): String {
        val json = JSONObject()
        json.put("timestamp", metadata["timestamp"] ?: "")
        val device = JSONObject()
        device.put("brand", metadata["device_brand"] ?: "")
        device.put("model", metadata["device_model"] ?: "")
        device.put("android", metadata["android_version"] ?: "")
        device.put("abi", metadata["device_abi"] ?: "")
        json.put("device", device)
        json.put("app_version", metadata["app_version"] ?: "")
        json.put("count", crashLogs.size)
        json.put("crash_logs", JSONArray(crashLogs))
        return json.toString()
    }

    private companion object {
        private const val TAG = "ServerCrashReporter"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * 构建标准崩溃元数据 — 型号 / Android 版本 / App 版本 / 时间戳。
 *
 * App 版本通过 PackageManager 读取（无 BuildConfig 依赖，避免 release 构建混淆时丢失）。
 */
fun buildStandardMetadata(context: Context): Map<String, String> {
    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
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
