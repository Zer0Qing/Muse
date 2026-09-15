package io.zer0.muse.ui

import io.zer0.ai.RefImageUrlValidator
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * SSRF 防护 — 判定链接目标是否为内网/回环/链路本地等非公网地址。
 *
 * 从 LinkPreviewCard 抽出以便复用,并避免单个文件函数过多(detekt TooManyFunctions)。
 * 聊天链接预览(以及 og:image 的 Coil 二次抓取)由模型输出或用户粘贴的 URL 驱动,
 * 若不加校验可被诱导反连 127.0.0.1(内嵌 WebServer)/局域网主机。
 *
 * C-6: DNS Rebinding 防护 — [isPrivateHost] 在每次请求(导航/重定向/资源加载)时
 * 都重新解析域名,逐事件校验;TTL 极短的 A 记录攻击(解析时公网、连接后切内网)
 * 因每次导航/资源事件都重新触发 isBlocked 判定,亦会被后续事件拦下,无需
 * 连接后二次验证机制。
 */
internal object SsrfGuard {

    /**
     * G4: 参考图 URL SSRF 校验器 — http/https URL 经 [isBlocked] 判定(命中内网/保留
     * 地址返回 false 需拒绝),其余 scheme(data:/file:) 恒 true(不需网络,放行)。
     * 类型 [RefImageUrlValidator] 定义在 ai 模块(供 Koin 注入声明),
     * 由 app 层 SsrfBridgeModule 注册进 Koin 供 [io.zer0.ai.image.AgnesImageProvider] 使用。
     */
    val refImageUrlValidator: RefImageUrlValidator = object : RefImageUrlValidator {
        override fun isAllowed(url: String): Boolean {
            val trimmed = url.trim()
            if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
                return !isBlocked(trimmed)
            }
            return true
        }
    }

    /** 返回 true 表示应拒绝抓取(非 http(s)/无法解析/内网地址)。 */
    fun isBlocked(url: String): Boolean {
        val host = parseHttpUri(url)?.host?.takeIf { it.isNotBlank() } ?: return true
        return isPrivateHost(host)
    }

    /**
     * B-31: 从自由文本中提取 http(s) URL,逐个经 [isBlocked] 判定;
     * 任一命中(内网/回环/链路本地/无法解析等)即返回 true,调用方应整体拒绝该文本。
     * 仅负责定位 http(s) 片段,私网/链路本地/IPv6/整数 IP 等判定统一收敛到 [isPrivateAddress]。
     */
    fun hasBlockedUrlInText(text: String): Boolean =
        HTTP_URL_IN_TEXT.findAll(text).any { isBlocked(it.value) }

    private fun parseHttpUri(url: String): URI? = try {
        URI(url).takeIf { it.scheme?.lowercase() in setOf("http", "https") }
    } catch (_: Exception) {
        null
    }

    private fun isPrivateHost(host: String): Boolean = try {
        InetAddress.getAllByName(host).any { isPrivateAddress(it) }
    } catch (_: Exception) {
        true // 解析失败(不存在/遭劫持)时保守拒绝,不发抓取请求
    }

    @Suppress("ReturnCount") // 多层 early-return fail-fast,可读性优于强行收敛到单出口
    private fun isPrivateAddress(addr: InetAddress): Boolean {
        val v6 = addr as? Inet6Address
        if (v6 != null) {
            val bytes = v6.address
            val isV4Mapped = isV4Mapped(bytes)
            if (isV4Mapped) {
                return isPrivateIpv4(bytes[12].toInt() and 0xff, bytes[13].toInt() and 0xff)
            }
            // fc00::/7 ULA(Java isSiteLocalAddress 只覆盖 fec0::/10,不覆盖 fc00::/7)
            if (isUla(bytes)) return true
        }
        return addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
    }

    /** IPv4-mapped IPv6 (::ffff:a.b.c.d) 判定,Java 的 isLoopbackAddress 等对映射地址返回 false。 */
    private fun isV4Mapped(bytes: ByteArray): Boolean =
        bytes.size == 16 &&
            bytes.copyOfRange(0, 10).all { it == 0.toByte() } &&
            bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()

    private fun isUla(bytes: ByteArray): Boolean =
        bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC

    private fun isPrivateIpv4(a: Int, b: Int): Boolean = when {
        a == 0 -> true                  // 0.0.0.0/8
        a == 10 -> true                 // 10.0.0.0/8
        a == 127 -> true                // 127.0.0.0/8
        a == 169 && b == 254 -> true    // 169.254.0.0/16 link-local
        a == 172 && b in 16..31 -> true // 172.16.0.0/12
        a == 192 && b == 168 -> true    // 192.168.0.0/16
        a >= 224 -> true                // 224.0.0.0/4 multicast 及保留段
        else -> false
    }

    /** B-31: 在自由文本中定位 http(s) URL 片段(仅定位,私网判定交给 [isPrivateAddress])。 */
    private val HTTP_URL_IN_TEXT: Regex = Regex("""https?://[^\s"'`<>]+""", RegexOption.IGNORE_CASE)
}
