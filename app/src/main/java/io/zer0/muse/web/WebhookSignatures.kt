package io.zer0.muse.web

/**
 * v2.0: webhook 签名工具 — 基于 [Ed25519](纯 Kotlin 实现)。
 *
 * 用于 QQ 开放平台回调:
 *  - [signChallenge]: op=13 回调地址验证(对 event_ts + plain_token 签名);
 *  - [verifyWebhook]: 事件推送防篡改校验(对 timestamp + body 验签)。
 *
 * 密钥为渠道配置中的 Bot Secret(APP Secret)。
 */
internal object WebhookSignatures {

    /** op=13 挑战响应:返回 hex 签名。 */
    fun signChallenge(secret: String, eventTs: String, plainToken: String): String {
        val seed = Ed25519.seedFromSecret(secret)
        val signature = Ed25519.sign(seed, (eventTs + plainToken).toByteArray(Charsets.UTF_8))
        return signature.joinToString("") { "%02x".format(it) }
    }

    /** 事件推送验签:校验 hex 签名对 timestamp + body 有效。 */
    fun verifyWebhook(secret: String, timestamp: String, body: String, signatureHex: String): Boolean {
        if (secret.isBlank() || timestamp.isBlank() || signatureHex.isBlank()) return false
        val signature = runCatching {
            ByteArray(signatureHex.length / 2) { i ->
                signatureHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull() ?: return false
        if (signature.size != 64) return false
        val publicKey = Ed25519.seedToPublicKey(Ed25519.seedFromSecret(secret))
        return Ed25519.verify(publicKey, (timestamp + body).toByteArray(Charsets.UTF_8), signature)
    }
}
