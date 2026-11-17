package io.zer0.muse.runtime

import android.content.Context
import android.os.Build
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 运行环境清单的来源。
 *
 * 顺序是「远程优先、内置兜底」：
 *  1. 先请求我们自己的 `/api/runtime/manifest-<abi>.json`；
 *  2. 失败（断网、服务异常、格式不对）就用 APK 里那份内置清单。
 *
 * 为什么清单不直接打进包就算完：清单里写着下载地址，而下载地址要能随时换源
 * （比如今天走清华镜像、明天换成对象存储）。把清单放在自己服务器上，换源改服务端一处即可，
 * 不需要发新版。内置那份的作用是保证断网时界面仍有版本与体积可显示。
 */
class RuntimeManifestSource(
    private val context: Context,
    private val remoteBaseUrl: String = DEFAULT_REMOTE_BASE,
) {

    /** 当前 ABI 对应清单。远程拿不到时用内置的；两者都没有返回 null。 */
    suspend fun load(id: RuntimeId = RuntimeId.UBUNTU_NODE): RuntimeManifest? {
        val abi = primaryAbi()
        fetchRemote(abi, id)?.let { return it }
        return loadBundled(abi, id)
    }

    private suspend fun fetchRemote(abi: String, id: RuntimeId): RuntimeManifest? =
        withContext(Dispatchers.IO) {
            val url = "$remoteBaseUrl/manifest-$abi.json"
            runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = REMOTE_TIMEOUT_MS
                    readTimeout = REMOTE_TIMEOUT_MS
                    instanceFollowRedirects = true
                }
                try {
                    if (conn.responseCode !in 200..299) {
                        Logger.i(TAG, "远程清单不可用（HTTP ${conn.responseCode}），改用内置")
                        return@withContext null
                    }
                    val raw = conn.inputStream.bufferedReader().use { it.readText() }
                    val manifest = RuntimeManifest.parse(raw)
                    if (manifest == null || manifest.runtimeId != id.manifestKey) {
                        Logger.w(TAG, "远程清单格式不对，改用内置")
                        null
                    } else {
                        Logger.i(TAG, "使用远程清单: $url (rev=${manifest.revision})")
                        manifest
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrElse { e ->
                Logger.i(TAG, "远程清单请求失败: ${e.message}")
                null
            }
        }

    private fun loadBundled(abi: String, id: RuntimeId): RuntimeManifest? {
        val candidates = buildList {
            add("runtime/manifest-$abi.json")
            Build.SUPPORTED_ABIS.forEach { add("runtime/manifest-$it.json") }
        }
        for (path in candidates.distinct()) {
            val raw = runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }
                .getOrNull() ?: continue
            val manifest = RuntimeManifest.parse(raw) ?: continue
            if (manifest.runtimeId != id.manifestKey) continue
            Logger.i(TAG, "使用内置清单: $path (rev=${manifest.revision})")
            return manifest
        }
        Logger.w(TAG, "没有找到 ${id.manifestKey} 的清单（ABI=$abi）")
        return null
    }

    private fun primaryAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    private companion object {
        const val TAG = "RuntimeManifestSource"

        /** 清单接口。换域名只改这一处。 */
        const val DEFAULT_REMOTE_BASE = "https://museai.ltd/api/runtime"
        const val REMOTE_TIMEOUT_MS = 6_000
    }
}
