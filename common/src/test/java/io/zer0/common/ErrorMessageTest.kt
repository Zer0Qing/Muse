package io.zer0.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨模块错误码契约（`:common` 体检第二处）。
 *
 * 这里钉的不是行为而是**契约**：本文件的注释写着"[code] 一旦发布不可变更
 * （UI / 日志 / 埋点 / 服务端联调都会引用）"，可这条以前只有一句话守着 ——
 * 谁改名都不会红。现在改一个就会红。
 */
class ErrorMessageTest {

    private val all: List<ErrorMessage> = listOf(
        ErrorMessage.NetworkError.TIMEOUT,
        ErrorMessage.NetworkError.NO_CONNECTION,
        ErrorMessage.NetworkError.DNS_FAILED,
        ErrorMessage.AuthError.INVALID_KEY,
        ErrorMessage.AuthError.EXPIRED,
        ErrorMessage.AuthError.UNAUTHORIZED,
        ErrorMessage.ApiError.RATE_LIMIT(),
        ErrorMessage.ApiError.SERVER_ERROR,
        ErrorMessage.ApiError.MODEL_NOT_FOUND,
        ErrorMessage.ApiError.CONTEXT_LENGTH,
        ErrorMessage.StorageError.DB_FULL,
        ErrorMessage.StorageError.IO_ERROR,
        ErrorMessage.StorageError.MIGRATION_FAILED,
        ErrorMessage.ValidationError.EMPTY_INPUT,
        ErrorMessage.ValidationError.INVALID_FORMAT,
        ErrorMessage.GenericError.UNKNOWN,
    )

    @Test
    fun `错误码不可变更——改名或删项都会红在这里`() {
        val expected = setOf(
            "network_timeout",
            "network_no_connection",
            "network_dns_failed",
            "auth_invalid_key",
            "auth_expired",
            "auth_unauthorized",
            "api_rate_limit",
            "api_server_error",
            "api_model_not_found",
            "api_context_length",
            "storage_db_full",
            "storage_io_error",
            "storage_migration_failed",
            "validation_empty_input",
            "validation_invalid_format",
            "generic_unknown",
        )

        assertEquals(expected, all.map { it.code }.toSet())
    }

    @Test
    fun `每个错误码只出现一次`() {
        val codes = all.map { it.code }
        assertEquals("错误码必须一一对应 R.string 命名 error_<code>", codes.size, codes.distinct().size)
    }

    @Test
    fun `defaultMessage 只进日志所以一律是英文`() {
        // 界面走 app/ui 的穷尽 when 从 R.string 取，不经这里；
        // 有人在此写中文，就会变成"日志中文 + 界面另一套中文"的双份口径。
        val cjk = Regex("[\\u4e00-\\u9fff]")
        all.forEach { e ->
            assertFalse("${e.code} 的 defaultMessage 含中文，违反只进日志的约定", cjk.containsMatchIn(e.defaultMessage))
            assertTrue("${e.code} 必须有兜底文案", e.defaultMessage.isNotBlank())
        }
    }

    @Test
    fun `限流错误的重试等待只在真有值时进元数据`() {
        val withDelay = ErrorMessage.ApiError.RATE_LIMIT(retryAfter = 30)
        assertEquals(mapOf("retryAfter" to "30"), withDelay.metadata)

        val without = ErrorMessage.ApiError.RATE_LIMIT()
        assertTrue("上游没给就不该编一个", without.metadata.isEmpty())

        val log = withDelay.toLogString()
        assertTrue("日志要带稳定码", log.contains("[ERR:api_rate_limit]"))
        assertTrue("日志要带 retryAfter（联调时靠它）", log.contains("retryAfter=30;"))
        assertFalse("不该有中文：日志跨模块共用", log.any { it in '\u4e00'..'\u9fff' })
    }

    @Test
    fun `日志串包含码与兜底文案便于无上下文时定位`() {
        val log = ErrorMessage.StorageError.DB_FULL.toLogString()

        assertEquals("[ERR:storage_db_full] Database is full", log)
    }
}
