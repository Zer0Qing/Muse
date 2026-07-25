package io.zer0.muse.data

import kotlinx.serialization.Serializable

/**
 * 全局网络代理配置。
 *
 * 在设置页配置后,由 AppKoinModule / WebSearchService 在构造 OkHttpClient 时读取并应用。
 * 注意:Koin 单例在初始化时读一次配置,修改代理后需重启 App 才能生效。
 *
 * M-PRX1: password 字段涉及敏感凭据,应走 [SecureKeyStore] 加密后再持久化,
 * 不应明文落盘。本数据类只承载字段定义,加解密通过 [encrypted] / [decrypted]
 * 成员方法在序列化边界(SettingsRepository.saveProxyConfig / proxyConfigFlow)
 * 完成:写入 DataStore 前 `encrypted()`,读出后 `decrypted()`。
 *
 * SettingsRepository 由阶段1统一修复,本类仅提供加解密入口与说明。
 */
@Serializable
data class ProxyConfig(
    val enabled: Boolean = false,
    val type: String = "HTTP", // HTTP 或 SOCKS5
    val host: String = "",
    val port: Int = 7890,
    val username: String = "",
    val password: String = "",
) {
    /**
     * M-PRX1: 返回 password 已加密(走 [SecureKeyStore.encrypt])的副本,
     * 供持久化前调用。空密码原样保留(不加密空值)。
     */
    suspend fun encrypted(): ProxyConfig = copy(password = SecureKeyStore.encrypt(password))

    /**
     * M-PRX1: 返回 password 已解密(走 [SecureKeyStore.decrypt])的副本,
     * 供从持久化层读出后调用。旧版明文 password 由 decrypt 透传(兼容)。
     */
    suspend fun decrypted(): ProxyConfig = copy(password = SecureKeyStore.decrypt(password))
}
