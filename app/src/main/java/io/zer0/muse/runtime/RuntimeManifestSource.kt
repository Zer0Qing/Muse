package io.zer0.muse.runtime

import android.content.Context
import android.os.Build
import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class RuntimeManifestSource(
    private val context: Context,
    private val remoteBaseUrl: String = DEFAULT_REMOTE_BASE,
) {

    suspend fun loadCandidates(id: RuntimeId = RuntimeId.UBUNTU_NODE): List<ManifestCandidate> = withContext(Dispatchers.IO) {
        val abi = primaryAbi()
        val remote = fetchRemote(abi, id)
        val bundled = loadBundled(abi, id)
        orderCandidates(listOfNotNull(remote) + listOfNotNull(bundled))
    }

    suspend fun load(id: RuntimeId = RuntimeId.UBUNTU_NODE): RuntimeManifest? {
        val document = loadCandidates(id).firstOrNull()?.document ?: return null
        val version = document.versionsOrLegacy().firstOrNull() ?: return null
        return document.toManifest(version)
    }

    private suspend fun fetchRemote(abi: String, id: RuntimeId): ManifestCandidate? = withContext(Dispatchers.IO) {
        val url = "$remoteBaseUrl/manifest-$abi.json"
        try {
            val connection = (URL(url).openConnection() as? HttpURLConnection)
                ?: throw IllegalArgumentException("远程清单必须使用 HTTP(S) 连接")
            connection.apply {
                connectTimeout = REMOTE_TIMEOUT_MS
                readTimeout = REMOTE_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    Logger.i(TAG, "远程清单不可用（HTTP $status），改用内置")
                    return@withContext null
                }
                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                validateCandidate(raw, "remote:$url", abi, id)
            } finally {
                connection.disconnect()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            Logger.i(TAG, "远程清单请求失败: ${error.message}")
            null
        } catch (error: IllegalArgumentException) {
            Logger.i(TAG, "远程清单请求失败: ${error.message}")
            null
        }
    }

    private fun loadBundled(abi: String, id: RuntimeId): ManifestCandidate? {
        val path = "runtime/manifest-$abi.json"
        val raw = try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (error: IOException) {
            Logger.w(TAG, "读取内置清单失败: $path (${error.message})")
            return null
        } catch (error: SecurityException) {
            Logger.w(TAG, "读取内置清单失败: $path (${error.message})")
            return null
        }
        return validateCandidate(raw, "bundled:$path", abi, id)
    }

    private fun validateCandidate(raw: String, source: String, abi: String, id: RuntimeId): ManifestCandidate? {
        val document = RuntimeManifestDocument.parse(raw)
        if (document == null || document.runtimeId != id.manifestKey) {
            Logger.w(TAG, "清单格式错误或 runtimeId 不匹配: $source")
            return null
        }
        return when (val validation = document.validateFor(abi)) {
            ManifestValidation.Valid -> ManifestCandidate(document, source)
            is ManifestValidation.Invalid -> {
                Logger.w(TAG, "清单校验失败: $source: ${validation.reasons.joinToString()}")
                null
            }
        }
    }

    private fun primaryAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    companion object {
        fun orderCandidates(candidates: List<ManifestCandidate>): List<ManifestCandidate> {
            val seen = mutableSetOf<String>()
            return candidates.filter { seen.add(it.fingerprint) }
        }

        private const val TAG = "RuntimeManifestSource"
        private const val DEFAULT_REMOTE_BASE = "https://museai.ltd/api/runtime"
        private const val REMOTE_TIMEOUT_MS = 6_000
    }
}
