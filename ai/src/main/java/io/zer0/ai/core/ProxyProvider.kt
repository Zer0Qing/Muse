package io.zer0.ai.core

import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

/**
 * v2.6.9: 全局代理提供者（ai 模块侧的唯一真源）。
 *
 * ## 为什么需要它
 *
 * 全仓 30 处 `OkHttpClient.Builder()` 里，曾经只有 3 处应用了用户配置的代理。
 * 后果是用户配了代理后，聊天能过，但 MCP / 渠道 / ASR / 云端文档解析 /
 * 插件市场这些通路仍然直连——表现就是「有的功能能上网、有的不能」。
 *
 * ## 为什么现在能做到「改代理立即生效，不用重启」
 *
 * 旧实现调 `builder.proxy(proxy)`，prox​y 在 build 时就固定了，这就是
 * 「改代理要重启」的根因。但 OkHttp 的 [ProxySelector.select] **在每次建立
 * 新连接时都会被调用**。所以这里不装固定代理，而是装一个**动态 ProxySelector**：
 * 它每次 select 时读当前配置，配置一变，下一次建连就走新代理。
 *
 * 因此不需要重建 client、不需要重启、也不需要热替换 Koin 单例。
 *
 * 边界（诚实说明）：
 *  - 已建立并正在复用的长连接（keep-alive）会继续走旧代理，直到该连接被回收。
 *    这是有意为之——不该为换代理而切断正在传输的请求。
 *  - 若某处**又**额外调了 `builder.proxy(...)`，OkHttp 会忽略 selector，
 *    那种地方仍需重启。因此不要在别处再装固定代理。
 *
 * 线程安全：用 [AtomicReference]，读写无锁。写入发生在设置变更（低频），
 * 读取发生在每次建连（selector 调用），都是极轻量操作。
 */
object ProxyProvider {

    /** 代理描述。纯数据，不依赖 app 模块的 ProxyConfig。 */
    data class Descriptor(
        val enabled: Boolean,
        val type: String,
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        /** 不走代理的主机（逗号分隔，支持后缀、`*` 通配与 IP 前缀）。 */
        val bypassHosts: String = "",
    ) {
        val isUsable: Boolean
            get() = enabled && host.isNotBlank() && port in 1..65535

        /** 构造 java.net.Proxy（DNS 交给代理：用 createUnresolved）。 */
        fun toJavaProxy(): Proxy {
            val address = InetSocketAddress.createUnresolved(host.trim(), port)
            return when (type.uppercase()) {
                "SOCKS", "SOCKS5" -> Proxy(Proxy.Type.SOCKS, address)
                else -> Proxy(Proxy.Type.HTTP, address)
            }
        }

        /**
         * 判断某主机是否应跳过代理。
         *
         * 匹配规则：
         *  1. 完全相等
         *  2. 以 `.` 开头 → 后缀匹配（`.local` 命中 `a.local`）
         *  3. 含 `*` → 通配（`*.internal` 命中 `x.internal`）
         *  4. 以 `.` 结尾 → 前缀匹配（`192.168.` 命中 `192.168.1.5`）
         */
        fun shouldBypass(hostName: String): Boolean {
            if (bypassHosts.isBlank()) return false
            val target = hostName.trim().lowercase()
            if (target.isBlank()) return false
            return bypassHosts.split(',', ';', '\n')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .any { rule -> matchesRule(target, rule) }
        }

        private fun matchesRule(target: String, rule: String): Boolean = when {
            rule == target -> true
            rule.contains("*") ->
                Regex("^" + Regex.escape(rule).replace("\\*", ".*") + "$").matches(target)
            rule.startsWith(".") -> target.endsWith(rule) || target == rule.removePrefix(".")
            rule.endsWith(".") -> target.startsWith(rule)
            else -> false
        }
    }

    private val current = AtomicReference<Descriptor?>(null)

    private val noProxyList = listOf(Proxy.NO_PROXY)

    /**
     * 动态 ProxySelector —— 每次建连时读当前配置。
     * 这是「改代理不用重启」的实现核心。
     */
    private object DynamicProxySelector : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val cfg = current.get() ?: return noProxyList
            if (!cfg.isUsable) return noProxyList
            if (cfg.shouldBypass(uri.host.orEmpty())) return noProxyList
            return listOf(cfg.toJavaProxy())
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            // 建连失败不在这里重试：交给 OkHttp 自己的重试与上层错误处理，
            // 避免在 selector 里做隐藏重试导致耗时不可预期。
        }
    }

    /** 由 app 侧调用：更新当前代理（传 null 或 enabled=false 表示直连）。立即生效。 */
    fun set(descriptor: Descriptor?) {
        current.set(descriptor)
    }

    /** 当前代理描述（可能为 null）。 */
    fun get(): Descriptor? = current.get()

    /**
     * 把动态代理装到 OkHttpClient.Builder。
     *
     * **所有新建 OkHttpClient 的地方都应该调它**。注意：不要再额外调
     * `builder.proxy(...)`，那会让 selector 失效、退回「改代理要重启」。
     */
    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        builder.proxySelector(DynamicProxySelector)
        // 凭据不能放在 selector 里（selector 只选代理），由 authenticator 在
        // 收到 407 时补 Proxy-Authorization；且每次读当前配置，换密码也即生效。
        builder.proxyAuthenticator { _, response ->
            val cfg = current.get()
            when {
                cfg == null || !cfg.isUsable -> null
                cfg.username.isBlank() || cfg.password.isBlank() -> null
                // 已带认证头仍 407 → 凭据不对，放弃，避免死循环
                response.request.header("Proxy-Authorization") != null -> null
                else -> response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(cfg.username, cfg.password))
                    .build()
            }
        }
        return builder
    }

    /** 便捷构造：按当前代理创建一个 client。 */
    fun buildClient(configure: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient =
        applyTo(OkHttpClient.Builder().apply(configure)).build()
}
