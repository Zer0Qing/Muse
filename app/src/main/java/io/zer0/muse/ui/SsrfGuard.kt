package io.zer0.muse.ui

import io.zer0.ai.RefImageUrlValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * C-6: DNS Rebinding 防护 — [isPrivateHost] 对每个新域名做一次解析并短 TTL 缓存;
 * TTL 极短的 A 记录攻击(解析时公网、连接后切内网)由 [HOST_VERDICT_TTL_MS] 缓存窗口
 * 兜底,缓存过期后按事件重新解析。P2-19: 短 TTL + 有界缓存避免 WebView 逐资源
 * 主线程阻塞 DNS(ANR 风险),同一主机在缓存窗口内只解析一次。
 *
 * P2-33(ANR 根除): 三条调用路径显式分离,主线程永不发起 DNS —
 *  - [isBlockedAsync]: 解析在 [Dispatchers.IO] 执行,主线程只等待/消费结果;
 *  - [cachedVerdictOrNull]: 纯缓存读,主线程可调(未命中返回 null,由调用方 fail-closed);
 *  - [isBlocked]: 同步阻塞版本,仅限已在后台线程的调用方(网络抓取/HTTP 工具等)。
 * 首次解析的结果同样写入缓存,因此「冷域名首解析」之后的同主机查询不再触发 DNS。
 */
internal object SsrfGuard {

    /** 主机判定结果缓存 TTL(毫秒):30s 窗口内重复资源不再触发 DNS。 */
    private const val HOST_VERDICT_TTL_MS = 30_000L

    /** 缓存条目上限:超过时整体清空(避免逐条目淘汰复杂度)。 */
    private const val HOST_VERDICT_CACHE_MAX = 512

    /** 主机 → 判定结果缓存(带过期时间)。 */
    private val hostVerdictCache = java.util.concurrent.ConcurrentHashMap<String, HostVerdict>()

    private data class HostVerdict(val blocked: Boolean, val expiresAt: Long)

    /**
     * P2-33: 可注入的主机解析器。
     *
     * 生产实现即系统 DNS([InetAddress.getAllByName]);单测注入慢解析器/固定地址解析器,
     * 以确定性断言「解析发生在 IO 线程而不是调用线程」以及缓存覆盖首次解析。
     */
    internal fun interface HostResolver {
        /** 在**调用线程**同步解析主机(调用方负责保证不在主线程执行)。 */
        fun resolve(host: String): Array<InetAddress>
    }

    /** 默认解析器:系统 DNS。 */
    private val systemResolver = HostResolver { host -> InetAddress.getAllByName(host) }

    @Volatile
    private var resolver: HostResolver = systemResolver

    /** 仅供测试注入解析器(注入方应在 finally 中调用 [resetForTest] 还原)。 */
    internal fun setResolverForTest(hostResolver: HostResolver) {
        resolver = hostResolver
    }

    /** 仅供测试:还原默认解析器并清空主机判定缓存。 */
    internal fun resetForTest() {
        resolver = systemResolver
        hostVerdictCache.clear()
    }

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
     * P2-33: 异步判定 — 解析在 [Dispatchers.IO] 执行,主线程调用不阻塞。
     *
     * 语义与 [isBlocked] 完全一致(非 http(s)/解析失败/内网 → true),区别只在解析发生在
     * 后台线程;解析结果照常写入 [hostVerdictCache],后续 [cachedVerdictOrNull] 与
     * [isBlocked] 都能命中,即「首次解析」这条路径也被缓存覆盖。
     */
    suspend fun isBlockedAsync(url: String): Boolean {
        val host = parseHttpUri(url)?.host?.takeIf { it.isNotBlank() } ?: return true
        cachedVerdict(host)?.let { return it }
        return withContext(Dispatchers.IO) { resolveAndCache(host) }
    }

    /**
     * P2-33: 后台预热 — 仅补齐缓存,不关心返回值(供 WebView 主线程回调 fail-closed 后自我修复)。
     */
    suspend fun prewarm(url: String) {
        isBlockedAsync(url)
    }

    /**
     * P2-33: 只消费缓存的非阻塞判定(主线程安全,绝不发起 DNS)。
     *
     * @return 缓存命中返回判定结果;未命中返回 null,调用方必须 fail-closed 处理。
     *         非 http(s)/无法解析的 URL 恒返回 true(保守拒绝)。
     */
    fun cachedVerdictOrNull(url: String): Boolean? {
        val host = parseHttpUri(url)?.host?.takeIf { it.isNotBlank() } ?: return true
        return cachedVerdict(host)
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

    /**
     * 同步判定(可能发起 DNS):仅限已在后台线程的调用方使用。
     *
     * 主线程调用方改用 [isBlockedAsync](挂起)或 [cachedVerdictOrNull](纯缓存),
     * 否则冷域名首次解析会阻塞主线程(ANR)。
     */
    private fun isPrivateHost(host: String): Boolean {
        // P2-19: 短 TTL 缓存命中则跳过 DNS;未命中/过期才解析,同一主机在窗口内只解析一次
        cachedVerdict(host)?.let { return it }
        return resolveAndCache(host)
    }

    /** 缓存命中(未过期)返回判定,否则 null(不发起 DNS)。 */
    private fun cachedVerdict(host: String): Boolean? {
        val cached = hostVerdictCache[host] ?: return null
        return if (System.currentTimeMillis() < cached.expiresAt) cached.blocked else null
    }

    /** 解析主机并写入缓存(解析失败保守判为 blocked)。 */
    private fun resolveAndCache(host: String): Boolean {
        // 双检:并发调用时只让先到者解析,后到者直接消费缓存
        cachedVerdict(host)?.let { return it }
        val blocked = try {
            resolver.resolve(host).any { isPrivateAddress(it) }
        } catch (_: Exception) {
            true // 解析失败(不存在/遭劫持)时保守拒绝,不发抓取请求
        }
        // 有界缓存:超过上限整体清空,避免条目无限增长
        if (hostVerdictCache.size >= HOST_VERDICT_CACHE_MAX) {
            hostVerdictCache.clear()
        }
        hostVerdictCache[host] = HostVerdict(blocked = blocked, expiresAt = System.currentTimeMillis() + HOST_VERDICT_TTL_MS)
        return blocked
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
