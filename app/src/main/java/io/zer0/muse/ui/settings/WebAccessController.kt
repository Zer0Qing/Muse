package io.zer0.muse.ui.settings

import io.zer0.muse.web.WebServerConfig
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 网页版「访问地址」的解析结果。
 *
 * 只描述地址与可见范围，不含运行状态 —— 服务是否在跑由调用方从
 * `WebServer.isRunning` 取，配置里的 `enabled` 不足以代表真实运行状态。
 */
internal data class WebAccessAddress(
    /** 可用的访问地址；null 表示开了局域网访问但没拿到局域网 IP，调用方要提示而不是留空。 */
    val url: String?,
    /** true 表示当前仅本机可访问（[WebServerConfig.allowLan] 关闭）。 */
    val localOnly: Boolean,
)

/** 仅本机访问时使用的回环地址。 */
private const val LOOPBACK_HOST = "127.0.0.1"

/** 网卡名偏好：按顺序取第一个命中的前缀，无线优先，其余（含虚拟网卡）垫底。 */
private val LAN_INTERFACE_PREFIXES = listOf("wlan", "ap", "eth")

/**
 * 组装访问地址：`http://<ip>:<port>`；[ip] 为空时返回 null。
 *
 * 纯函数，供页面与单测共用，不读系统状态。
 */
internal fun buildWebAccessUrl(ip: String?, port: Int, https: Boolean = false): String? {
    val host = ip?.trim().orEmpty()
    if (host.isEmpty()) return null
    val scheme = if (https) "https" else "http"
    return "$scheme://$host:$port"
}

/**
 * 按「是否开放局域网」折算页面要展示的访问地址。
 *
 * - 局域网访问关闭：固定回环地址，并标记 [WebAccessAddress.localOnly]
 * - 局域网访问开启：用局域网 IP；没拿到 IP 时 [WebAccessAddress.url] 为 null
 */
internal fun resolveWebAccessAddress(
    allowLan: Boolean,
    lanIp: String?,
    port: Int,
    https: Boolean = false,
): WebAccessAddress = if (allowLan) {
    WebAccessAddress(url = buildWebAccessUrl(lanIp, port, https), localOnly = false)
} else {
    WebAccessAddress(url = buildWebAccessUrl(LOOPBACK_HOST, port, https), localOnly = true)
}

/** 一张网卡上的一个候选地址。 */
internal data class LanIpCandidate(
    /** 网卡名，如 `wlan0`。 */
    val interfaceName: String,
    /** 点分十进制 IPv4 地址。 */
    val address: String,
    /** 是否属于私有网段（`siteLocal`）。 */
    val siteLocal: Boolean,
    /** 是否回环地址。 */
    val loopback: Boolean,
)

/**
 * 从候选地址里挑一个可用于局域网访问的 IPv4。
 *
 * 规则：排除回环地址、非私有网段地址与空地址；网卡名命中 [LAN_INTERFACE_PREFIXES]
 * 的排前面（`wlan` → `ap` → `eth`，虚拟网卡如 `docker0` 排最后）；
 * 同档保持枚举顺序，取第一个可用的。
 */
internal fun pickLanIpAddress(candidates: List<LanIpCandidate>): String? {
    var best: LanIpCandidate? = null
    var bestRank = Int.MAX_VALUE
    for (candidate in candidates) {
        if (candidate.loopback || !candidate.siteLocal || candidate.address.isBlank()) continue
        val rank = interfaceRank(candidate.interfaceName)
        if (rank < bestRank) {
            best = candidate
            bestRank = rank
        }
    }
    return best?.address
}

/** 网卡名的偏好次序；未命中偏好表时返回 [Int.MAX_VALUE]。 */
private fun interfaceRank(interfaceName: String): Int {
    val index = LAN_INTERFACE_PREFIXES.indexOfFirst { interfaceName.startsWith(it) }
    return if (index < 0) Int.MAX_VALUE else index
}

/** 枚举当前设备的局域网 IPv4 地址；取不到时返回 null。 */
internal fun resolveLanIpAddress(): String? =
    runCatching { pickLanIpAddress(collectLanIpCandidates()) }.getOrNull()

/** 遍历所有网卡，收集非回环 IPv4 候选(地址里的 zone id 后缀会被去掉)。 */
private fun collectLanIpCandidates(): List<LanIpCandidate> {
    val result = mutableListOf<LanIpCandidate>()
    for (nic in NetworkInterface.getNetworkInterfaces().toList()) {
        if (!nic.isUp || nic.isLoopback) continue
        for (address in nic.inetAddresses.toList()) {
            val candidate = ipv4CandidateOf(nic, address)
            if (candidate != null) result += candidate
        }
    }
    return result
}

/** 把网卡上的一个地址折算成候选；非 IPv4 或拿不到点分地址时返回 null。 */
private fun ipv4CandidateOf(nic: NetworkInterface, address: InetAddress): LanIpCandidate? {
    val ipv4 = address as? Inet4Address ?: return null
    val host = ipv4.hostAddress?.substringBefore('%') ?: return null
    return LanIpCandidate(
        interfaceName = nic.name.orEmpty(),
        address = host,
        siteLocal = ipv4.isSiteLocalAddress,
        loopback = ipv4.isLoopbackAddress,
    )
}

/**
 * 网页版开关的副作用边界：写配置 + 启停服务。
 *
 * 抽成接口是为了让开关逻辑可以脱离 Android / Ktor 做断言（见 WebAccessToggleTest）。
 */
internal interface WebAccessHost {
    /** 写入持久化配置。 */
    suspend fun saveConfig(config: WebServerConfig)

    /** 启动服务，返回是否成功。 */
    suspend fun startServer(): Boolean

    /** 停止服务并释放端口。 */
    suspend fun stopServer()
}

/**
 * 切换「启用网页版」：先落配置，再按需启动或停止服务。
 *
 * 服务已经跑着就不重复启动；关掉开关时只在确实运行时才停，避免多余的 stop。
 *
 * @return 切换后服务的实际运行状态
 */
internal suspend fun applyWebAccessEnabled(
    host: WebAccessHost,
    current: WebServerConfig,
    enabled: Boolean,
    running: Boolean,
): Boolean {
    host.saveConfig(current.copy(enabled = enabled))
    if (!enabled) {
        if (running) host.stopServer()
        return false
    }
    if (running) return true
    return host.startServer()
}
