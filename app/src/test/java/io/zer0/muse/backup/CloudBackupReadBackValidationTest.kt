package io.zer0.muse.backup

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 (可靠性 P1): 云备份读回校验 — 解密后 NDJSON 结构/meta 校验测试。
 *
 * 覆盖 [validateCloudReadBackNdjson]:
 *  - 合法 NDJSON(meta + 数据行)通过;
 *  - 空负载/非 JSON/首行非 meta/计数非法/全零计数/无数据行 明确拒绝;
 *  - 加密备份: 正确密码通过,错误密码与缺密码给出明确失败原因。
 */
class CloudBackupReadBackValidationTest {

    private val validMeta =
        """{"type":"meta","version":3,"sessions":1,"messages":2,"facts":0,"settings":0}"""
    private val validRecord = """{"type":"session","data":{"id":"s1"}}"""

    private fun ndjson(vararg lines: String): ByteArray =
        lines.joinToString("\n").toByteArray(Charsets.UTF_8)

    @Test
    fun `valid plaintext ndjson passes`() {
        assertNull(validateCloudReadBackNdjson(ndjson(validMeta, validRecord), ""))
    }

    @Test
    fun `empty payload is rejected`() {
        assertNotNull(validateCloudReadBackNdjson(ByteArray(0), ""))
    }

    @Test
    fun `non json first line is rejected`() {
        val error = validateCloudReadBackNdjson(ndjson("not-json", validRecord), "")
        assertNotNull(error)
        assertTrue("原因应说明首行非法: $error", error!!.contains("not a JSON object"))
    }

    @Test
    fun `non meta first line is rejected`() {
        val error = validateCloudReadBackNdjson(ndjson("""{"type":"session"}"""), "")
        assertNotNull(error)
        assertTrue("原因应说明缺少 meta: $error", error!!.contains("expected 'meta'"))
    }

    @Test
    fun `meta with all zero counts is rejected`() {
        val error = validateCloudReadBackNdjson(
            ndjson("""{"type":"meta","version":3,"sessions":0,"messages":0}"""),
            "",
        )
        assertNotNull(error)
        assertTrue("空备份应被拒绝: $error", error!!.contains("no data"))
    }

    @Test
    fun `meta without record lines is rejected`() {
        val error = validateCloudReadBackNdjson(ndjson(validMeta), "")
        assertNotNull(error)
        assertTrue("无数据行应被拒绝: $error", error!!.contains("no record lines"))
    }

    @Test
    fun `illegal meta count is rejected`() {
        val error = validateCloudReadBackNdjson(
            ndjson("""{"type":"meta","sessions":"many"}""", validRecord),
            "",
        )
        assertNotNull(error)
        assertTrue("非法计数应被拒绝: $error", error!!.contains("not an integer"))
    }

    @Test
    fun `encrypted ndjson passes with correct password`() {
        val encrypted = BackupCrypto.encrypt(ndjson(validMeta, validRecord), "pass-123")
        assertNull(validateCloudReadBackNdjson(encrypted, "pass-123"))
    }

    @Test
    fun `encrypted ndjson with wrong password reports decrypt failure`() {
        val encrypted = BackupCrypto.encrypt(ndjson(validMeta, validRecord), "pass-123")
        val error = validateCloudReadBackNdjson(encrypted, "wrong-password")
        assertNotNull(error)
        assertTrue("错误密码应给出解密失败原因: $error", error!!.contains("decrypt failed"))
    }

    @Test
    fun `encrypted ndjson without password reports missing password`() {
        val encrypted = BackupCrypto.encrypt(ndjson(validMeta, validRecord), "pass-123")
        val error = validateCloudReadBackNdjson(encrypted, "")
        assertNotNull(error)
        assertTrue("缺密码应显式失败: $error", error!!.contains("password is empty"))
    }
}
