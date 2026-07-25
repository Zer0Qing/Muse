package io.zer0.muse.update

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 应用更新检查器 — 通过 GitHub Releases API 拉取最新发布版本。
 *
 * 参考实现:kelivo 项目的 update_provider.dart。
 *
 * 端点:GET https://api.github.com/repos/{repo}/releases/latest
 *  - GitHub API 强制要求 User-Agent header,否则 403
 *  - 网络请求在 IO 线程执行,协程可取消(suspendCancellableCoroutine + enqueue)
 *  - 不引入新依赖,复用项目已有的 OkHttp + kotlinx.serialization
 *
 * @param client OkHttpClient(由调用方注入,通常复用 named("chat") 单例)
 * @param repo GitHub 仓库标识,格式 "owner/name"
 */
class UpdateChecker(
    private val client: OkHttpClient,
    private val repo: String = DEFAULT_REPO,
) {

    /** GitHub Releases API 端点。 */
    private val apiUrl: String
        get() = "https://api.github.com/repos/$repo/releases/latest"

    /**
     * GitHub Release 资源(APK 文件)。
     *
     * @param name 文件名(如 "muse-v1.133.apk")
     * @param downloadUrl 浏览器下载直链(browser_download_url)
     * @param size 文件字节数
     */
    @Serializable
    data class ApkAsset(
        val name: String,
        val downloadUrl: String,
        val size: Long,
    )

    /**
     * GitHub Release 信息。
     *
     * @param tagName 版本标签(如 "v1.132")
     * @param name release 标题
     * @param body release notes(markdown 正文)
     * @param htmlUrl release 页面 URL(用于"查看详情")
     * @param publishedAt 发布时间戳(毫秒)
     * @param apkAssets 以 .apk 结尾的资源列表(已过滤)
     */
    @Serializable
    data class ReleaseInfo(
        val tagName: String,
        val name: String,
        val body: String,
        val htmlUrl: String,
        val publishedAt: Long,
        val apkAssets: List<ApkAsset>,
    )

    /**
     * 异步查询最新 Release。
     *
     * @return 成功返回 [Result.Success] 携带 [ReleaseInfo];
     *         失败(网络/解析错误)返回 [Result.Error]
     */
    suspend fun checkLatestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        resultOf {
            exec(req).use { resp ->
                if (!resp.isSuccessful) {
                    Logger.w(TAG, "GitHub API failed: HTTP ${resp.code}")
                    return@use Result.Error("HTTP ${resp.code}")
                }
                val bodyText = resp.body.string()
                if (bodyText.isBlank()) {
                    return@use Result.Error("response is empty")
                }
                val json = AppJson.parseToJsonElement(bodyText).jsonObject
                val tagName = json["tag_name"]?.jsonPrimitive?.contentOrNull
                    ?: return@use Result.Error("failed to parse tag_name")
                val releaseName = json["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val releaseBody = json["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val htmlUrl = json["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                // published_at 形如 "2025-01-01T00:00:00Z",GitHub API 返回 ISO8601 字符串
                val publishedAt = parseIso8601ToMillis(
                    json["published_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
                val assets = json["assets"]?.jsonArray
                    ?.mapNotNull { it.jsonObject }
                    ?.mapNotNull { obj ->
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val url = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull
                            ?: return@mapNotNull null
                        val size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
                        ApkAsset(name = name, downloadUrl = url, size = size)
                    }
                    ?.filter { it.name.endsWith(".apk", ignoreCase = true) }
                    ?: emptyList()
                Result.Success(
                    ReleaseInfo(
                        tagName = tagName,
                        name = releaseName,
                        body = releaseBody,
                        htmlUrl = htmlUrl,
                        publishedAt = publishedAt,
                        apkAssets = assets,
                    ),
                )
            }
        }.onError { msg, t ->
            Logger.e(TAG, "检查更新失败: ${t?.message ?: msg}", t)
        }.getOrNull() ?: Result.Error("network error")
    }

    /**
     * 协程可取消的 HTTP 执行 — 参照 ai/ 模块 ProviderHttpSupport / ImageService 的实现。
     *
     * 用 suspendCancellableCoroutine + enqueue 替代同步 [Call.execute]:
     *  - 协程取消时调用 [Call.cancel],中断阻塞的网络调用
     *  - 不占用线程等待响应(enqueue 由 OkHttp 调度器线程回调)
     */
    private suspend fun exec(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
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
     * 解析 ISO8601 字符串(如 "2025-01-01T00:00:00Z")为毫秒时间戳。
     * 解析失败返回 0。
     */
    private fun parseIso8601ToMillis(value: String): Long {
        if (value.isBlank()) return 0L
        return resultOf {
            // 用 java.time 解析(项目 minSdk=26 时已支持 java.time,API 26+ 内置)
            // 兼容形如 "2025-01-01T00:00:00Z" 与 "2025-01-01T00:00:00.000Z"
            java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
        }.getOrNull() ?: 0L
    }

    companion object {
        private const val TAG = "UpdateChecker"
        /** User-Agent(GitHub API 强制要求,否则 403)。 */
        private const val USER_AGENT = "muse-android"
        /** 默认 GitHub 仓库(owner/name)。 */
        const val DEFAULT_REPO = "zer0/muse"

        /**
         * 语义版本比较(支持 "v1.132" / "1.132" / "1.13.2" 等格式)。
         *
         * @param current 当前版本(可带 "v" 前缀)
         * @param latest 待比较版本(可带 "v" 前缀)
         * @return 负数表示 current < latest;0 表示相等;正数表示 current > latest
         */
        fun compareVersions(current: String, latest: String): Int {
            val a = parseVersion(current)
            val b = parseVersion(latest)
            val maxLen = maxOf(a.size, b.size)
            for (i in 0 until maxLen) {
                val ai = a.getOrElse(i) { 0 }
                val bi = b.getOrElse(i) { 0 }
                if (ai != bi) return ai.compareTo(bi)
            }
            return 0
        }

        /** 解析版本号为整数列表(如 "v1.132" → [1, 132]);非数字片段忽略。 */
        private fun parseVersion(version: String): List<Int> {
            val trimmed = version.trim().trimStart('v', 'V')
            return trimmed.split('.').mapNotNull { it.toIntOrNull() }
        }
    }
}
