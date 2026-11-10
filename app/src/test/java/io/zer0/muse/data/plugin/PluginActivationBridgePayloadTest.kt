package io.zer0.muse.data.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 懒激活事件的载荷（插件计划 P3b）。
 *
 * 载荷是插件唯一能看到的东西，两件事必须对：
 *  - **转义**：消息里有引号/换行时不能拼出非法 JSON（插件那边会直接解析失败）；
 *  - **截断**：事件是给插件做轻量反应的，不是把整段对话搬过去。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginActivationBridgePayloadTest {

    private lateinit var bridge: PluginActivationBridge

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        bridge = PluginActivationBridge(
            context = context,
            pluginManager = mockk(relaxed = true),
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            settings = mockk(relaxed = true),
        )
    }

    private fun parse(payload: String): JsonObject = io.zer0.common.AppJson.parseToJsonElement(payload).let { it as JsonObject }

    @Test
    fun `a message payload names the event and carries the text`() {
        val payload = parse(bridge.messagePayload("你好"))

        assertEquals("onMessage", payload["event"]?.jsonPrimitive?.content)
        assertEquals("你好", payload["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `quotes and newlines survive as valid json`() {
        val payload = bridge.messagePayload("他说\"你好\"\n然后走了")

        // 能解析就说明转义是对的；解析失败会直接抛异常
        val parsed = parse(payload)
        assertEquals("他说\"你好\"\n然后走了", parsed["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `long messages are truncated before reaching the plugin`() {
        val long = "字".repeat(2_000)

        val parsed = parse(bridge.messagePayload(long))

        val length = parsed["text"]?.jsonPrimitive?.content?.length ?: 0
        assertTrue("事件载荷不能把整段对话搬过去: $length", length <= 500)
    }

    @Test
    fun `a tool call payload names the event and the tool`() {
        val payload = parse(bridge.toolCallPayload("screen_read"))

        assertEquals("onToolCall", payload["event"]?.jsonPrimitive?.content)
        assertEquals("screen_read", payload["tool"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the payload never carries tool arguments`() {
        // 参数里常有用户内容；这里只断言载荷结构里没有额外字段
        val payload = parse(bridge.toolCallPayload("web_search"))

        assertEquals(setOf("event", "tool"), payload.keys)
    }
}
