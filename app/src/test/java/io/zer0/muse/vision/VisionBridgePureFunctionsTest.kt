package io.zer0.muse.vision

import io.mockk.mockk
import io.zer0.ai.ChatService
import io.zer0.muse.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-19: 视觉辅助流程编排的纯函数/降级提示测试。
 */
class VisionBridgePureFunctionsTest {

    private val bridge = VisionBridge(
        chatService = mockk<ChatService>(relaxed = true),
        settings = mockk<SettingsRepository>(relaxed = true),
        visionCache = mockk<VisionCache>(relaxed = true),
    )

    @Test
    fun `buildVisionContext joins descriptions with blank line`() {
        assertEquals("", bridge.buildVisionContext(emptyList()))
        assertEquals("描述一\n\n", bridge.buildVisionContext(listOf("描述一")))
        assertEquals("描述一\n\n描述二\n\n", bridge.buildVisionContext(listOf("描述一", "描述二")))
    }

    @Test
    fun `buildFailureNotice includes reason and guidance`() {
        val notice = bridge.buildFailureNotice("模型超时")
        assertTrue(notice.contains("模型超时"))
        assertTrue(notice.contains("图片分析失败"))
        assertTrue(notice.contains("请用户稍后重试"))

        val blank = bridge.buildFailureNotice(null)
        assertFalse(blank.contains("null"))
        assertTrue(blank.contains("图片分析失败"))
    }

    @Test
    fun `sniffMimeType recognizes common image prefixes`() {
        assertEquals("image/jpeg", VisionImagePreprocessor.sniffMimeType("/9j/AAAA"))
        assertEquals("image/png", VisionImagePreprocessor.sniffMimeType("iVBORw0KGgo"))
        assertEquals("image/gif", VisionImagePreprocessor.sniffMimeType("R0lGODlh"))
        assertEquals("image/webp", VisionImagePreprocessor.sniffMimeType("UklGRgI="))
        assertEquals("image/jpeg", VisionImagePreprocessor.sniffMimeType("not-an-image"))
    }

    @Test
    fun `hashShort is deterministic and distinguishes inputs`() {
        assertEquals("empty", VisionImagePreprocessor.hashShort(""))
        assertEquals("empty", VisionImagePreprocessor.hashShort("   "))
        val a = VisionImagePreprocessor.hashShort("first-image")
        val b = VisionImagePreprocessor.hashShort("second-image")
        assertEquals(a, VisionImagePreprocessor.hashShort("first-image"))
        assertTrue(a.length >= 16)
        assertFalse(a == b)
    }

    @Test
    fun `reasoning-only vision completion remains usable`() {
        assertEquals("识别结果", effectiveVisionResponseText("", "识别结果"))
        assertEquals("正文", effectiveVisionResponseText("正文", "不要覆盖正文"))
    }

    @Test
    fun `unsupported vision notice includes provider model and detected capability`() {
        val notice = visionUnsupportedReason("SiliconFlow", "GLM-4.6", setOf("text"))

        assertTrue(notice.contains("SiliconFlow"))
        assertTrue(notice.contains("GLM-4.6"))
        assertTrue(notice.contains("text"))
        assertTrue(notice.contains("不支持图片输入"))
    }
}
