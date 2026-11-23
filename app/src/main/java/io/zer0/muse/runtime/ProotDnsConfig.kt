package io.zer0.muse.runtime

import android.content.Context
import android.net.ConnectivityManager
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import java.io.File
import java.io.IOException

internal object ProotDnsConfig {
    private const val TAG = "ProotDnsConfig"
    private const val FALLBACK_DNS = "1.1.1.1"
    private val writeLock = Any()

    fun ensureFile(context: Context): File {
        val properties = runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            connectivity?.activeNetwork?.let(connectivity::getLinkProperties)
        }.onFailure { error ->
            Logger.w(TAG, "读取 Android DNS 配置失败，使用备用 DNS", error)
        }.getOrNull()
        val content = render(
            dnsServers = properties?.dnsServers.orEmpty().mapNotNull { it.hostAddress },
            searchDomains = properties?.domains,
        )
        val file = File(context.filesDir, "runtime/proot/resolv.conf")
        synchronized(writeLock) {
            val current = if (file.isFile) {
                try {
                    file.readText()
                } catch (error: IOException) {
                    Logger.w(TAG, "读取 proot DNS 配置失败，将重新写入", error)
                    null
                }
            } else {
                null
            }
            if (current != content) {
                AtomicFileStore.writeText(file, content)
            }
        }
        return file
    }

    internal fun render(dnsServers: List<String>, searchDomains: String?): String {
        val servers = dnsServers.map { it.trim().substringBefore('%').trim('[', ']') }
            .filter(String::isNotBlank)
            .distinct()
            .ifEmpty { listOf(FALLBACK_DNS) }
        return buildString {
            servers.forEach { append("nameserver ").append(it).append('\n') }
            searchDomains?.split(Regex("\\s+"))
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                ?.distinct()
                ?.takeIf { it.isNotEmpty() }
                ?.let { domains -> append("search ").append(domains.joinToString(" ")).append('\n') }
            append("options ndots:1 timeout:2 attempts:2\n")
        }
    }
}
