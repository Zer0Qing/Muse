package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面槽位（`ui.slot`）。
 *
 * 两类断言：
 *  1. **槽位名校验** —— 宿主没实现的槽位必须在校验阶段被拒，否则插件会"贡献成功但永远看不到"；
 *  2. **序列化兼容** —— 不带 `slots` 的旧清单序列化后**不能多出字段**，
 *     否则所有已签名包的验签会立刻失败（v2.0.0 的同类事故）。
 */
class PluginSlotTest {

    @Test
    fun `a known slot with a view file passes validation`() {
        assertNull(
            PluginSlot.validate(SlotContribution(slot = "chat.top", view = "ui/chat-top.json")),
        )
    }

    @Test
    fun `an unknown slot is refused and lists what is available`() {
        val reason = PluginSlot.validate(SlotContribution(slot = "chat.bottom", view = "ui/x.json"))

        assertTrue("要说清未知槽位: $reason", reason!!.contains("未知槽位"))
        assertTrue("要列出可用值: $reason", reason.contains("chat.top"))
    }

    @Test
    fun `a contribution without a view file is refused`() {
        val reason = PluginSlot.validate(SlotContribution(slot = "chat.top", view = "  "))

        assertTrue(reason!!.contains("没有指定界面文件"))
    }

    @Test
    fun `the slot list is exactly what the host renders`() {
        // 这个断言是给"以后加了枚举但没接到宿主"设的绊子
        assertEquals(listOf("chat.top"), PluginSlot.availableNames())
        assertEquals(3, PluginSlot.MAX_SLOT_PLUGINS)
    }

    // ── 序列化兼容 ──────────────────────────────────────────────────────

    @Test
    fun `a manifest without slots serialises without the field`() {
        val manifest = PluginManifest(
            id = "p1",
            name = "p1",
            contributes = PluginContributes(),
        )

        val json = AppJson.encodeToString(PluginManifest.serializer(), manifest)

        assertFalse("空 slots 不能被编码进去: $json", json.contains("\"slots\""))
    }

    @Test
    fun `a manifest with slots keeps them through a round trip`() {
        val manifest = PluginManifest(
            id = "p1",
            name = "p1",
            contributes = PluginContributes(
                slots = listOf(SlotContribution(slot = "chat.top", view = "ui/chat-top.json")),
            ),
        )

        val json = AppJson.encodeToString(PluginManifest.serializer(), manifest)
        val parsed = AppJson.decodeFromString(PluginManifest.serializer(), json)

        assertTrue("声明了就该编码: $json", json.contains("\"slots\""))
        assertEquals(
            listOf(SlotContribution(slot = "chat.top", view = "ui/chat-top.json")),
            parsed.contributes?.slots,
        )
    }

    @Test
    fun `an old manifest without contributes still parses`() {
        val json = """{"id":"old","name":"old","version":"1.0.0","entry":"main.js"}"""

        val parsed = AppJson.decodeFromString(PluginManifest.serializer(), json)

        assertNull("旧清单没有 contributes", parsed.contributes)
        assertEquals("old", parsed.id)
    }
}
