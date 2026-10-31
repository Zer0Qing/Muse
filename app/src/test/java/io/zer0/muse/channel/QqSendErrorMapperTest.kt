package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QQ 发送失败的可读化。
 *
 * 这些错误码决定了用户该怎么处置：
 * 「被动回复超时」下一次就可能成功，「机器人不在群里」不改配置永远失败。
 * 两者糊成同一句话，用户就只能一直猜。
 */
class QqSendErrorMapperTest {

    @Test
    fun `从 HTTP 前缀的错误体里抠出错误码`() {
        val raw = """HTTP 400: {"message":"主动消息失败, 无权限","code":40034102}"""
        assertEquals(40034102, QqSendErrorMapper.extractCode(raw))
    }

    @Test
    fun `纯 JSON 错误体也能抠出错误码`() {
        // 不带 "HTTP xxx:" 前缀时，不能按冒号切——那会把 JSON 本身切碎
        val raw = """{"code":40034128,"message":"被动回复已超时"}"""
        assertEquals(40034128, QqSendErrorMapper.extractCode(raw))
    }

    @Test
    fun `err_code 字段兼容`() {
        val raw = """HTTP 400: {"err_code":40034101}"""
        assertEquals(40034101, QqSendErrorMapper.extractCode(raw))
    }

    @Test
    fun `无错误码时返回 null`() {
        assertNull(QqSendErrorMapper.extractCode("HTTP 500: gateway timeout"))
        assertNull(QqSendErrorMapper.extractCode(null))
        assertNull(QqSendErrorMapper.extractCode(""))
    }

    @Test
    fun `被动回复超时说清是太晚了`() {
        val text = QqSendErrorMapper.describe("HTTP 400: {\"code\":40034128}", isGroup = true)
        assertTrue(text.contains("5 分钟"))
    }

    @Test
    fun `不在群里时提示先拉群`() {
        val text = QqSendErrorMapper.describe("HTTP 400: {\"code\":40034101}", isGroup = true)
        assertTrue(text.contains("拉进群"))
    }

    @Test
    fun `无主动消息权限时说明群聊不支持主动推送`() {
        val text = QqSendErrorMapper.describe("HTTP 400: {\"code\":40034102}", isGroup = true)
        assertTrue(text.contains("主动"))
    }

    @Test
    fun `未知错误码原样透传而不是编造含义`() {
        val text = QqSendErrorMapper.describe("HTTP 400: {\"code\":999999,\"message\":\"weird\"}", isGroup = false)
        assertTrue(text.contains("999999") || text.contains("weird"))
    }

    @Test
    fun `配置类错误标记为永久失败`() {
        assertTrue(QqSendErrorMapper.isPermanent("HTTP 400: {\"code\":40034101}"))
        assertTrue(QqSendErrorMapper.isPermanent("HTTP 400: {\"code\":40034102}"))
    }

    @Test
    fun `被动窗口过期对这条消息同样是永久失败`() {
        // 时间只会更晚，重试这条消息没有任何胜算 —— 与「配置类永久」不同的是
        // 下一条新消息仍然能正常回复，所以只收当前入站，不把渠道判死。
        assertTrue(QqSendErrorMapper.isPermanent("HTTP 400: {\"code\":40034128}"))
    }

    @Test
    fun `无错误码与频控类错误仍可重试`() {
        assertFalse(QqSendErrorMapper.isPermanent(null))
        assertFalse(QqSendErrorMapper.isPermanent("HTTP 500: gateway timeout"))
        assertFalse(QqSendErrorMapper.isPermanent("HTTP 400: {\"code\":40034105}"))
    }
}
