package io.zer0.muse.tools.script

import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * A single-purpose HTTP client for the SkillBridge network capability.
 *
 * The client resolves and validates a host once for each redirect hop, then supplies exactly that
 * address list to OkHttp through [Dns]. OkHttp therefore cannot perform a second system lookup
 * after the SSRF decision. A network interceptor validates the direct socket route both before
 * the request is sent and after the response is received.
 *
 * This class intentionally owns a fresh connection pool for every request. That prevents a socket
 * established for an earlier DNS result from being reused after a later resolution decision.
 */
internal class SkillBridgeHttpClient(
    private val baseClient: OkHttpClient = defaultBaseClient(),
    private val dns: Dns = Dns.SYSTEM,
    private val addressAllowed: (InetAddress) -> Boolean = ::isPublicAddress,
    private val onConnectionValidated: (InetAddress) -> Unit = {},
) {

    /**
     * Execute a GET while manually validating every redirect hop.
     *
     * @param startUrl URL of the first hop.
     * @param maxSize maximum response body bytes to decode.
     * @param maxRedirects maximum redirect hops to follow.
     * @return the final HTTP status and bounded UTF-8 response body.
     * @throws PinnedAddressException when URL parsing, DNS policy, or the connected route fails
     *   closed validation.
     * @throws IOException for transport failures and malformed redirect responses.
     */
    fun get(
        startUrl: String,
        maxSize: Int,
        maxRedirects: Int = MAX_REDIRECTS,
    ): Result {
        var current = startUrl
        repeat(maxRedirects + 1) {
            val request = try {
                Request.Builder().url(current).get().build()
            } catch (e: IllegalArgumentException) {
                throw IOException("URL 无法解析: $current", e)
            }
            executePinned(request).use { response ->
                if (response.code in 300..399) {
                    val location = response.header("Location")?.trim()?.takeIf { it.isNotBlank() }
                        ?: throw IOException("重定向(${response.code})缺少 Location 头")
                    val resolved = try {
                        URI(current).resolve(location).toString()
                    } catch (e: Exception) {
                        throw IOException("重定向 Location 无法解析: $location", e)
                    }
                    if (!resolved.startsWith("http://") && !resolved.startsWith("https://")) {
                        throw IOException("重定向目标协议非法: $resolved")
                    }
                    current = resolved
                } else {
                    return Result(
                        status = response.code,
                        body = readLimitedBody(response, maxSize),
                    )
                }
            }
        }
        throw IOException("重定向超过 $maxRedirects 跳上限")
    }

    /**
     * Execute a GET while manually validating every redirect hop and returning raw bytes.
     *
     * The byte variant exists for binary artifacts (plugin packages): decoding to UTF-8 would
     * corrupt ZIP payloads. Size, redirect, DNS, proxy, and route policy are identical to [get].
     */
    fun getBytes(
        startUrl: String,
        maxSize: Int,
        maxRedirects: Int = MAX_REDIRECTS,
    ): ByteResult {
        var current = startUrl
        repeat(maxRedirects + 1) {
            val request = try {
                Request.Builder().url(current).get().build()
            } catch (e: IllegalArgumentException) {
                throw IOException("URL 无法解析: $current", e)
            }
            executePinned(request).use { response ->
                if (response.code in 300..399) {
                    val location = response.header("Location")?.trim()?.takeIf { it.isNotBlank() }
                        ?: throw IOException("重定向(${response.code})缺少 Location 头")
                    val resolved = try {
                        URI(current).resolve(location).toString()
                    } catch (e: Exception) {
                        throw IOException("重定向 Location 无法解析: $location", e)
                    }
                    if (!resolved.startsWith("http://") && !resolved.startsWith("https://")) {
                        throw IOException("重定向目标协议非法: $resolved")
                    }
                    current = resolved
                } else {
                    return ByteResult(
                        status = response.code,
                        body = readLimitedBytes(response, maxSize),
                    )
                }
            }
        }
        throw IOException("重定向超过 $maxRedirects 跳上限")
    }

    /**
     * Execute one already-built request with one-shot DNS pinning.
     *
     * The method is internal so JVM tests can use a deterministic DNS implementation and a local
     * MockWebServer. Production callers should use [get], which retains the redirect hop policy.
     */
    internal fun executePinned(request: Request): Response {
        val host = request.url.host
        val url = request.url.toString()
        rejectConfiguredProxy(url)
        val addresses = resolveAndValidate(host, url)
        val pinnedDns = Dns { requestedHost ->
            if (!requestedHost.equals(host, ignoreCase = true)) {
                throw UnknownHostException("DNS 主机与已校验主机不一致: $requestedHost")
            }
            // Return the exact pre-validated result; never invoke system DNS again.
            addresses
        }
        val client = baseClient.newBuilder()
            .dns(pinnedDns)
            // The origin address is what was validated. Explicit proxies are rejected above;
            // disabling proxy selection here also prevents an ambient system proxy from becoming
            // an unvalidated route.
            .proxy(Proxy.NO_PROXY)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
            // A failed connection must not trigger another route/DNS attempt with a new decision.
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .addNetworkInterceptor(connectionValidationInterceptor(host, addresses))
            .build()
        return client.newCall(request).execute()
    }

    private fun rejectConfiguredProxy(url: String) {
        val configuredProxy = baseClient.proxy
        if (configuredProxy != null && configuredProxy.type() != Proxy.Type.DIRECT) {
            throw PinnedAddressException(url, "拒绝未验证的代理连接: ${configuredProxy.type()}")
        }
    }

    private fun resolveAndValidate(host: String, url: String): List<InetAddress> {
        if (host == "localhost") {
            throw PinnedAddressException(url, "localhost 不允许通过 SkillBridge 访问")
        }
        val addresses = try {
            dns.lookup(host)
        } catch (e: Exception) {
            throw PinnedAddressException(url, "DNS 解析失败: $host", e)
        }
        if (addresses.isEmpty() || addresses.any { !addressAllowed(it) }) {
            throw PinnedAddressException(url, "DNS 地址未通过 SSRF 校验: $host")
        }
        return addresses.distinctBy { it.address.contentHashCode() }
    }

    private fun connectionValidationInterceptor(
        host: String,
        addresses: List<InetAddress>,
    ): Interceptor = Interceptor { chain ->
        val before = connectedAddressOrThrow(chain, host, addresses)
        onConnectionValidated(before)
        val response = chain.proceed(chain.request())
        val after = try {
            connectedAddressOrThrow(chain, host, addresses)
        } catch (e: IOException) {
            response.close()
            throw e
        }
        onConnectionValidated(after)
        response
    }

    private fun connectedAddressOrThrow(
        chain: Interceptor.Chain,
        host: String,
        addresses: List<InetAddress>,
    ): InetAddress {
        val connection = chain.connection()
            ?: throw PinnedAddressException(chain.request().url.toString(), "连接建立后无法取得连接信息")
        val route = connection.route()
        if (route.proxy.type() != Proxy.Type.DIRECT) {
            throw PinnedAddressException(chain.request().url.toString(), "拒绝未验证的代理连接: $host")
        }
        val connected = route.socketAddress.address
            ?: throw PinnedAddressException(chain.request().url.toString(), "连接地址未解析: $host")
        if (!addresses.any { sameAddress(it, connected) } || !addressAllowed(connected)) {
            throw PinnedAddressException(
                chain.request().url.toString(),
                "实际连接地址未通过 DNS 校验: $host -> ${connected.hostAddress}",
            )
        }
        return connected
    }

    private fun readLimitedBody(response: Response, maxBytes: Int): String {
        val body = response.body
        val input = body.byteStream()
        return input.use {
            val buffer = ByteArray(maxBytes)
            var total = 0
            var count = input.read(buffer, total, maxBytes - total)
            while (count != -1 && total < maxBytes) {
                total += count
                if (total >= maxBytes) break
                count = input.read(buffer, total, maxBytes - total)
            }
            buffer.copyOf(total).toString(Charsets.UTF_8)
        }
    }

    private fun readLimitedBytes(response: Response, maxBytes: Int): ByteArray {
        val input = response.body.byteStream()
        return input.use {
            val buffer = ByteArray(maxBytes)
            var total = 0
            var count = input.read(buffer, total, maxBytes - total)
            while (count != -1 && total < maxBytes) {
                total += count
                if (total >= maxBytes) break
                count = input.read(buffer, total, maxBytes - total)
            }
            buffer.copyOf(total)
        }
    }

    /** Final status/body returned after bounded response consumption. */
    internal data class Result(
        val status: Int,
        val body: String,
    )

    /** Final status/raw body returned after bounded response consumption (binary-safe). */
    internal data class ByteResult(
        val status: Int,
        val body: ByteArray,
    )

    /** Fail-closed error carrying the exact hop whose DNS/connection check failed. */
    internal class PinnedAddressException(
        val url: String,
        message: String,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    private companion object {
        const val MAX_REDIRECTS = 5

        fun defaultBaseClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        fun sameAddress(first: InetAddress, second: InetAddress): Boolean =
            first.address.contentEquals(second.address)

        @Suppress("ComplexCondition")
        fun isPublicAddress(address: InetAddress): Boolean {
            if (address is Inet6Address) {
                val bytes = address.address
                // IPv4-mapped IPv6 can bypass InetAddress' IPv4 predicates on some runtimes.
                if (isV4Mapped(bytes)) {
                    return !isPrivateIpv4(
                        bytes[12].toInt() and 0xff,
                        bytes[13].toInt() and 0xff,
                    )
                }
                // IPv6 ULA fc00::/7 is not covered by InetAddress.isSiteLocalAddress.
                if ((bytes[0].toInt() and 0xFE) == 0xFC) return false
            }
            return !address.isLoopbackAddress && !address.isAnyLocalAddress &&
                !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
                !address.isMulticastAddress
        }

        private fun isV4Mapped(bytes: ByteArray): Boolean =
            bytes.size == 16 && bytes.copyOfRange(0, 10).all { it == 0.toByte() } &&
                bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()

        private fun isPrivateIpv4(first: Int, second: Int): Boolean = when {
            first == 0 -> true
            first == 10 -> true
            first == 127 -> true
            first == 169 && second == 254 -> true
            first == 172 && second in 16..31 -> true
            first == 192 && second == 168 -> true
            first >= 224 -> true
            else -> false
        }
    }
}
