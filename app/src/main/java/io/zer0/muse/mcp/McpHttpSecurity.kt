package io.zer0.muse.mcp

import io.zer0.muse.ui.common.SsrfGuard
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException

/** Share one address policy between discovery, token exchange, and MCP transports. */
internal object McpHttpSecurity {

    fun secureClient(base: OkHttpClient, resourceUrl: String): OkHttpClient {
        val localOrigin = runCatching { resourceUrl.toHttpUrl() }
            .getOrNull()
            ?.takeIf { it.scheme == "http" && it.host in LOOPBACK_HOSTS }
        val guardedDns = Dns { host -> resolveAllowed(host, localOrigin) }
        return base.newBuilder()
            .dns(guardedDns)
            .followRedirects(false)
            .followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                val url = chain.request().url
                val allowedLocalTarget = localOrigin?.let { sameLocalOrigin(url, it) } == true
                val connection = chain.connection()
                val route = connection?.route()
                if (route?.proxy?.type() == Proxy.Type.DIRECT) {
                    val address = connection.socket().inetAddress
                    if (!allowedLocalTarget && SsrfGuard.isPrivateAddress(address)) {
                        throw IOException("Private network target rejected")
                    }
                    if (allowedLocalTarget && !address.isLoopbackAddress) {
                        throw IOException("Local MCP target resolved outside loopback")
                    }
                } else {
                    resolveAllowed(url.host, localOrigin)
                }
                chain.proceed(chain.request())
            }
            .build()
    }

    private fun resolveAllowed(host: String, localOrigin: HttpUrl?): List<InetAddress> {
        val addresses = lookupAddresses(host)
        validateResolvedAddresses(host, addresses, localOrigin)
        return addresses
    }

    private fun lookupAddresses(host: String): List<InetAddress> =
        try {
            Dns.SYSTEM.lookup(host)
        } catch (_: UnknownHostException) {
            throw UnknownHostException("MCP host could not be resolved")
        }

    private fun validateResolvedAddresses(host: String, addresses: List<InetAddress>, localOrigin: HttpUrl?) {
        if (addresses.isEmpty()) throw UnknownHostException("MCP host has no resolved addresses")
        if (localOrigin?.host == host) {
            validateLoopbackAddresses(addresses)
        } else {
            validatePublicAddresses(addresses)
        }
    }

    private fun validateLoopbackAddresses(addresses: List<InetAddress>) {
        if (addresses.any { !it.isLoopbackAddress }) {
            throw UnknownHostException("Local MCP host resolved outside loopback")
        }
    }

    private fun validatePublicAddresses(addresses: List<InetAddress>) {
        if (addresses.any(SsrfGuard::isPrivateAddress)) {
            throw UnknownHostException("Private network target rejected")
        }
    }

    private fun sameLocalOrigin(url: HttpUrl, origin: HttpUrl): Boolean =
        url.scheme == "http" && url.host == origin.host && url.port == origin.port && url.host in LOOPBACK_HOSTS

    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
}
