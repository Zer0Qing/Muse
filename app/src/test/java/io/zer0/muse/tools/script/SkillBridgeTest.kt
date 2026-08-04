package io.zer0.muse.tools.script

import io.zer0.common.AppJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** B8-05: Skill/Bot 桥接请求序列化测试。 */
class SkillBridgeTest {

    @Test
    fun bridgeRequestRoundTrip() {
        val request = BridgeRequest(
            operation = "http_get",
            params = buildJsonObject {
                put("url", JsonPrimitive("https://example.com"))
            },
        )

        val json = AppJson.encodeToString(BridgeRequest.serializer(), request)
        val decoded = AppJson.decodeFromString(BridgeRequest.serializer(), json)

        assertEquals("http_get", decoded.operation)
        assertEquals("https://example.com", decoded.params["url"]?.toString()?.trim('"'))
    }

    @Test
    fun bridgeMarkerIsStable() {
        assertEquals("__bridge__", BridgeRequest.BRIDGE_MARKER)
    }
}
