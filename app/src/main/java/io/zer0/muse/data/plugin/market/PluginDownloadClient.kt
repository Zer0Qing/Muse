package io.zer0.muse.data.plugin.market

import io.zer0.common.Logger
import io.zer0.muse.tools.script.SkillBridgeHttpClient
import java.io.File
import java.security.MessageDigest

/** Result of a verified artifact download; the file is app-private staging, not the active plugin dir. */
data class DownloadedArtifact(
    val file: File,
    val sha256: String,
    val bytes: Long,
)

data class DownloadResult(
    val artifact: DownloadedArtifact? = null,
    val error: String? = null,
) {
    val success: Boolean get() = artifact != null
}

/**
 * Downloads catalog artifacts into app-private staging.
 *
 * Transport, redirect handling, DNS pinning, and route validation are delegated to
 * [SkillBridgeHttpClient], the project's controlled HTTP egress. This client adds the
 * catalog-specific policy: size limit, declared SHA-256, and never writing into the
 * active plugin directory.
 */
internal class PluginDownloadClient(
    private val httpClient: SkillBridgeHttpClient = SkillBridgeHttpClient(),
    private val stagingDir: File,
    private val maxArtifactBytes: Int = MAX_ARTIFACT_BYTES,
) {
    fun download(entry: PluginCatalogEntry): DownloadResult {
        val expectedSha = entry.artifactSha256.lowercase()
        return try {
            if (!stagingDir.exists() && !stagingDir.mkdirs()) {
                return DownloadResult(error = "无法创建插件缓存目录")
            }
            val response = httpClient.getBytes(entry.artifactUrl, maxArtifactBytes)
            if (response.status !in 200..299) {
                return DownloadResult(error = "插件下载失败: HTTP ${response.status}")
            }
            val bytes = response.body
            if (bytes.size.toLong() > maxArtifactBytes) {
                return DownloadResult(error = "插件包超过大小限制")
            }
            val actual = sha256Hex(bytes)
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                return DownloadResult(error = "插件包摘要与目录不一致")
            }
            val target = File(stagingDir, stagedFileName(entry))
            target.writeBytes(bytes)
            DownloadResult(artifact = DownloadedArtifact(target, actual, bytes.size.toLong()))
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Logger.w("PluginDownloadClient", "插件下载失败: ${entry.id}", error)
            DownloadResult(error = error.message ?: "插件下载失败")
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** Matches the plugin package size limit enforced by PluginManager. */
        internal const val MAX_ARTIFACT_BYTES = 20 * 1024 * 1024

        /** staging 文件的唯一命名规则；安装计划据此确认可删除的文件。 */
        internal fun stagedFileName(entry: PluginCatalogEntry): String =
            "${entry.id}-${entry.version}.muse-plugin"
    }
}
