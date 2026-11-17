package io.zer0.muse.runtime

import io.zer0.common.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * runtime_missing 的结构化回执：宿主靠 error 码识别，模型靠 message 理解。
 * 两个字段缺一都会让安装卡或工具重试逻辑失效。
 */
class RuntimeMissingTest {

    @Test
    fun `payload carries stable code and readable message`() {
        val json = RuntimeMissing.toJson(downloadBytes = 76_628_106, installedBytes = 233_573_805)
        val obj = AppJson.parseToJsonElement(json) as JsonObject

        assertEquals("runtime_missing", (obj["error"] as JsonPrimitive).content)
        assertEquals("ubuntu-node", (obj["runtime"] as JsonPrimitive).content)
        assertEquals("true", (obj["installable"] as JsonPrimitive).content)
        assertEquals("76628106", (obj["download_bytes"] as JsonPrimitive).content)
        assertEquals("233573805", (obj["installed_bytes"] as JsonPrimitive).content)
        val message = (obj["message"] as JsonPrimitive).content
        assertTrue("message 要说明缺什么: $message", message.contains("运行环境"))
        assertNotNull(RuntimeMissing.guidance())
    }

    @Test
    fun `payload detector matches compact and spaced json`() {
        assertTrue(RuntimeMissing.isRuntimeMissingPayload("""{"error":"runtime_missing"}"""))
        assertTrue(RuntimeMissing.isRuntimeMissingPayload("""{"error": "runtime_missing"}"""))
        assertTrue(RuntimeMissing.isRuntimeMissingPayload("prefix {\"error\":\"runtime_missing\"} suffix"))
        assertFalse(RuntimeMissing.isRuntimeMissingPayload("""{"error":"other"}"""))
    }
}