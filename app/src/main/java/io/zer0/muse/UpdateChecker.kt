package io.zer0.muse

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * P3-15: 应用更新检查器。
 *
 * 通过 GitHub Releases API 查询最新发布版本,与本地 versionName 比较:
 *  - 请求 https://api.github.com/repos/5352124/Muse/releases/latest
 *  - 解析 tag_name(如 "v1.118")与 html_url(下载页)
 *  - 与当前 versionName(如 "1.118")做语义化版本比较
 *
 * 约束:
 *  - GitHub API 要求 User-Agent header,否则返回 403
 *  - 网络请求在 IO 线程执行,结果回调到调用方
 *  - 不引入新依赖,复用项目已有的 OkHttp + kotlinx.serialization
 *
 * @param client OkHttpClient(默认自带短超时客户端,避免更新检查阻塞过久)
 */
class UpdateChecker(
    private val client: OkHttpClient = defaultClient(),
) {

    /** GitHub Releases API 端点(v1.119: 修正为真实仓库 5352124/Muse)。 */
    private val apiUrl = "https://api.github.com/repos/5352124/Muse/releases/latest"

    /**
     * 检查更新结果。
     */
    sealed class Result {
        /** 发现新版本:携带 tag_name 与 release html_url。 */
        data class NewVersion(val tagName: String, val htmlUrl: String) : Result()
        /** 已是最新版本。 */
        data object UpToDate : Result()
        /** 检查失败(网络/解析错误)。 */
        data class Error(val message: String) : Result()
    }

    /**
     * 异步检查更新。
     *
     * @param currentVersionName 当前 versionName(如 "0.28",可带 "v" 前缀)
     * @return 检查结果(发现新版本 / 已是最新 / 失败)
     *
     * Result.Error 使用英文消息,调用方 SettingsSubPages 已用 context.getString() 本地化。
     */
    suspend fun check(currentVersionName: String): Result = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(apiUrl)
            // GitHub API 强制要求 User-Agent,否则 403 拒绝
            .header("User-Agent", "muse-android")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        // M8: 用 resultOf 替代 runCatching(避免吞 CancellationException);
        // 网络请求改为 suspendCancellableCoroutine + enqueue,协程取消时调用 call.cancel()
        resultOf {
            exec(req).use { resp ->
                if (!resp.isSuccessful) {
                    Logger.w("UpdateChecker", "GitHub API failed: HTTP ${resp.code}")
                    return@use Result.Error("HTTP ${resp.code}")
                }
                val body = resp.body.string()
                if (body.isBlank()) {
                    return@use Result.Error("response is empty")
                }
                val json = AppJson.parseToJsonElement(body).jsonObject
                val tagName = json["tag_name"]?.jsonPrimitive?.contentOrNull
                    ?: return@use Result.Error("failed to parse tag_name")
                val htmlUrl = json["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (isNewer(tagName, currentVersionName)) {
                    Result.NewVersion(tagName, htmlUrl)
                } else {
                    Result.UpToDate
                }
            }
        }.onError { msg, t ->
            Logger.e("UpdateChecker", "检查更新失败: ${t?.message ?: msg}", t)
        }.getOrNull() ?: Result.Error("network error")
    }

    /**
     * M8: 协程可取消的 HTTP 执行 — 参照 ai/ 模块 ProviderHttpSupport / ImageService 的实现。
     *
     * 用 suspendCancellableCoroutine + enqueue 替代同步 [Call.execute]:
     *  - 协程取消时调用 [Call.cancel],中断阻塞的网络调用
     *  - 不占用线程等待响应(enqueue 由 OkHttp 调度器线程回调)
     */
    private suspend fun exec(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            // M1: 统一用 resultOf{} 替代 runCatching{}(项目 Result 约定)
            cont.invokeOnCancellation { resultOf { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }

    /**
     * 比较版本号(支持 "v0.29" / "0.28" 格式)。
     *
     * @return true 表示 remote 比 current 新
     */
    private fun isNewer(remote: String, current: String): Boolean {
        val r = parseVersion(remote)
        val c = parseVersion(current)
        return compareVersions(r, c) > 0
    }

    /** 解析版本号为整数列表(如 "v0.29" → [0, 29])。非数字片段忽略。 */
    private fun parseVersion(version: String): List<Int> {
        val trimmed = version.trim().trimStart('v', 'V')
        return trimmed.split('.').mapNotNull { it.toIntOrNull() }
    }

    /** 逐段比较版本号列表(长度不一致时缺失段视为 0)。 */
    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        val maxLen = maxOf(a.size, b.size)
        for (i in 0 until maxLen) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }

    companion object {
        /** 默认 OkHttpClient:更新检查用短超时,避免长时间阻塞。 */
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
