package io.zer0.muse.web

import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.connector
import io.ktor.server.engine.sslConnector

/**
 * Web 服务的连接器配置。
 *
 * 用 CIO 引擎而不是 Netty：Netty 有多处依赖反射（`ReflectiveChannelFactory` 建 Channel、
 * `ResourceLeakDetector` 静态初始化按名字找方法），R8 混淆 release 包时会逐个把它们掐掉，
 * 表现为启动时 `NoClassDefFoundError` / `does not have a public non-arg constructor`。
 * CIO 是纯 Kotlin 实现，没有这些反射路径，也不额外引入 Netty 那一大坨依赖。
 */
internal fun webServerEngineConfiguration(
    tls: WebServerTls.TlsMaterial?,
    bindHost: String,
    listenPort: Int,
): CIOApplicationEngine.Configuration.() -> Unit {
    return {
        if (tls != null) {
            sslConnector(
                keyStore = tls.keyStore,
                keyAlias = tls.alias,
                keyStorePassword = { tls.password },
                privateKeyPassword = { tls.password },
            ) {
                host = bindHost
                port = listenPort
            }
        } else {
            connector {
                host = bindHost
                port = listenPort
            }
        }
    }
}
