package io.zer0.muse.data.util

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 阶段 C: 网络工具 — 获取设备局域网 IPv4 地址。
 *
 * 用于 WebServer section 显示真实访问地址,避免用户手动查 IP。
 *
 * 实现: 遍历所有 NetworkInterface,过滤:
 *  - isUp
 *  - !isLoopback
 *  - inetAddress 是 Inet4Address
 * 优先返回 wlan0 / eth0 的地址,避免虚拟接口(如 docker0)。
 */
object NetworkUtils {

    fun getLocalIpAddress(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedByDescending { it.name == "wlan0" || it.name == "eth0" }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
                ?.hostAddress
        }.getOrNull()
    }
}
