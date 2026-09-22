package io.zer0.muse.web

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0: Ed25519 正确性测试 — 使用 QQ 开放平台官方文档测试向量。
 *
 * 覆盖:seed 派生公钥 / 签名确定性(与官方向量一致) / 验签通过 / 篡改拒绝。
 */
class Ed25519Test {

    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    /** 官方 DEMO:seed 重复补足到 32 字节派生指定公钥;公钥部分与官方向量一致。 */
    @Test
    fun `seed derives expected public key from official demo`() {
        val secret = "naOC0ocQE3shWLAfffVLB1rhYPG7"
        val seed = Ed25519.seedFromSecret(secret)
        assertEquals("naOC0ocQE3shWLAfffVLB1rhYPG7naOC", String(seed, Charsets.UTF_8))
        val publicKey = Ed25519.seedToPublicKey(seed)
        val expected = bytes(
            215, 195, 98, 254, 120, 174, 248, 31, 242, 50, 135, 180, 147, 98, 139, 93,
            176, 42, 60, 79, 227, 11, 33, 94, 77, 25, 96, 155, 93, 118, 103, 58,
        )
        assertArrayEquals(expected, publicKey)
    }

    /**
     * 签名向量 — 与 Node.js crypto(官方文档同款的权威 Ed25519 实现)对标生成;
     * 同 seed 同消息输出逐字节一致。
     *
     * 注:官方文档「安全和授权」页的签名 DEMO 输出经 96 种消息变体枚举均无法复现,
     * 判断为文档示意值;此处以 Node 实现对标向量为准。
     */
    @Test
    fun `signature matches node js reference vector`() {
        val secret = "naOC0ocQE3shWLAfffVLB1rhYPG7"
        val seed = Ed25519.seedFromSecret(secret)
        val message = "1725442341{ \"op\": 0,\"d\": {}, \"t\": \"GATEWAY_EVENT_NAME\"}"
            .toByteArray(Charsets.UTF_8)
        val signature = Ed25519.sign(seed, message)
        val expected = hexToBytes(
            "2eb9983ebb8bb209e78fd095942f58e442656656e7975d01e64f9023a84b7c96" +
                "4290fdd40e5500c33867ccfe9563b7e0b6bac0e1d42c13e787b304fd51f71102",
        )
        assertArrayEquals(expected, signature)
        assertTrue(Ed25519.verify(Ed25519.seedToPublicKey(seed), message, signature))
        assertFalse(
            Ed25519.verify(Ed25519.seedToPublicKey(seed), "tampered".toByteArray(Charsets.UTF_8), signature),
        )
    }

    @Test
    fun `sign and verify roundtrip`() {
        val seed = Ed25519.seedFromSecret("test-secret-0123456789abcdef")
        val publicKey = Ed25519.seedToPublicKey(seed)
        val message = "hello ed25519".toByteArray(Charsets.UTF_8)
        val signature = Ed25519.sign(seed, message)
        assertTrue(Ed25519.verify(publicKey, message, signature))
    }

    @Test
    fun `verify rejects tampered message`() {
        val seed = Ed25519.seedFromSecret("test-secret-0123456789abcdef")
        val publicKey = Ed25519.seedToPublicKey(seed)
        val signature = Ed25519.sign(seed, "authentic".toByteArray(Charsets.UTF_8))
        assertFalse(Ed25519.verify(publicKey, "tampered".toByteArray(Charsets.UTF_8), signature))
    }

    @Test
    fun `verify rejects truncated signature`() {
        val seed = Ed25519.seedFromSecret("test-secret-0123456789abcdef")
        val publicKey = Ed25519.seedToPublicKey(seed)
        val message = "hello".toByteArray(Charsets.UTF_8)
        val signature = Ed25519.sign(seed, message)
        assertFalse(Ed25519.verify(publicKey, message, signature.copyOf(63)))
    }
}
