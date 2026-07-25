package io.zer0.muse.web

import io.zer0.muse.data.SecureKeyStore
import kotlinx.serialization.Serializable
import java.security.SecureRandom

/**
 * Phase 8.11: 嵌入式 Web 服务器配置。
 *
 * - [enabled]: 是否在 App 启动时自动启动 Web 服务器
 * - [port]: 监听端口(默认 8765,避免与常见服务冲突)
 * - [password]: 登录密码(空字符串表示首次启动时生成随机密码)
 * - [pin]: 6 位数字 PIN,Web 端首次访问需输入(空字符串表示首次启动时生成)
 *
 * 持久化: SettingsRepository DataStore,JSON 整存整取。
 * 安全: 密码同时作为 JWT HMAC-SHA256 签名密钥(一物两用,减少配置项)。
 * PIN 校验通过后签发 JWT,与密码登录复用同一鉴权体系。
 */
@Serializable
data class WebServerConfig(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val password: String = "",
    val pin: String = "",
) {
    /**
     * H8: 返回 password/pin 已加密(走 [SecureKeyStore.encrypt])的副本,
     * 供持久化前调用。空值原样保留(不加密空值)。
     * 参照 [io.zer0.muse.data.ProxyConfig.encrypted] 模式。
     */
    suspend fun encrypted(): WebServerConfig = copy(
        password = SecureKeyStore.encrypt(password),
        pin = SecureKeyStore.encrypt(pin),
    )

    /**
     * H8: 返回 password/pin 已解密(走 [SecureKeyStore.decrypt])的副本,
     * 供从持久化层读出后调用。旧版明文由 decrypt 透传(兼容)。
     * 参照 [io.zer0.muse.data.ProxyConfig.decrypted] 模式。
     */
    suspend fun decrypted(): WebServerConfig = copy(
        password = SecureKeyStore.decrypt(password),
        pin = SecureKeyStore.decrypt(pin),
    )

    companion object {
        /** 默认端口(避开 8080/3000 等常见开发端口)。 */
        const val DEFAULT_PORT = 8765

        /** 随机密码字符集(去掉易混淆字符 0/O/1/l/I)。 */
        private val PASSWORD_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789".toCharArray()

        /** 生成 8 位随机密码(首次启用时用)。H6: 使用 SecureRandom 替代 Math.random()。 */
        fun generateRandomPassword(length: Int = 8): String {
            val sb = StringBuilder(length)
            val random = SecureRandom()
            repeat(length) {
                sb.append(PASSWORD_CHARS[random.nextInt(PASSWORD_CHARS.size)])
            }
            return sb.toString()
        }

        /**
         * 生成 6 位数字 PIN(首位 1-9,避免前导零导致显示与输入混淆)。
         * H7: 使用 SecureRandom 替代 Math.random(),消除截断偏差(L3)。
         */
        fun generateRandomPin(): String {
            val sb = StringBuilder(PIN_LENGTH)
            val random = SecureRandom()
            // 首位 1-9,避免前导零
            sb.append(1 + random.nextInt(9))
            repeat(PIN_LENGTH - 1) {
                sb.append(random.nextInt(10))
            }
            return sb.toString()
        }

        /** PIN 固定长度。 */
        const val PIN_LENGTH = 6
    }
}
