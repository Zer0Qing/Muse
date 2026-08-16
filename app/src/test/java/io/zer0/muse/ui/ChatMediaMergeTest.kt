package io.zer0.muse.ui

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 审计修复 (A-16): 收尾消息媒体合并回归护栏。
 *
 * 背景: v1.0.75 "生图只显示链接"线上 bug 的修复点 — Agent Loop 收尾的
 * finalAssistantMessage 不含媒体字段,直接覆盖同 id 消息会丢 exec* 写入的
 * 图片/视频/二维码。此前的修复无任何单测覆盖(线上 bug 修复点零护栏)。
 */
class ChatMediaMergeTest {

    private fun assistantMsg(
        imageUrls: List<String> = emptyList(),
        imageBase64List: List<String> = emptyList(),
        videoFileUri: String? = null,
    ) = UIMessage(role = MessageRole.ASSISTANT, content = "回复正文", imageUrls = imageUrls,
        imageBase64List = imageBase64List, videoFileUri = videoFileUri)

    @Test
    fun `finalAssistant 自带媒体时以其为准`() {
        val final = assistantMsg(imageUrls = listOf("https://a.com/1.png"))
        val existing = assistantMsg(imageUrls = listOf("https://old.com/x.png"), videoFileUri = "https://old.com/v.mp4")
        val merged = mergeFinalAssistantMedia(final, existing)
        assertEquals(listOf("https://a.com/1.png"), merged.imageUrls)
        // 按字段独立回退: final 无 videoFileUri → 回退 existing 的视频
        assertEquals("https://old.com/v.mp4", merged.videoFileUri)
    }

    @Test
    fun `finalAssistant 无媒体时回退保留 existingMsg 的媒体`() {
        val final = assistantMsg()
        val existing = assistantMsg(
            imageUrls = listOf("https://a.com/1.png", "https://a.com/2.png"),
            imageBase64List = listOf("YmFzZTY0"),
            videoFileUri = "https://a.com/v.mp4",
        )
        val merged = mergeFinalAssistantMedia(final, existing)
        assertEquals(listOf("https://a.com/1.png", "https://a.com/2.png"), merged.imageUrls)
        assertEquals(listOf("YmFzZTY0"), merged.imageBase64List)
        assertEquals("https://a.com/v.mp4", merged.videoFileUri)
    }

    @Test
    fun `existingMsg 为 null 时媒体为空`() {
        val final = assistantMsg()
        val merged = mergeFinalAssistantMedia(final, null)
        assertEquals(emptyList<String>(), merged.imageUrls)
        assertEquals(emptyList<String>(), merged.imageBase64List)
        assertNull(merged.videoFileUri)
        assertEquals("回复正文", merged.content) // 非媒体字段不受影响
    }

    @Test
    fun `finalAssistant 与 existing 均有媒体时 final 优先且不叠加`() {
        val final = assistantMsg(imageUrls = listOf("https://new.com/n.png"), videoFileUri = "https://new.com/n.mp4")
        val existing = assistantMsg(imageUrls = listOf("https://old.com/o.png"), videoFileUri = "https://old.com/o.mp4")
        val merged = mergeFinalAssistantMedia(final, existing)
        assertEquals(listOf("https://new.com/n.png"), merged.imageUrls)
        assertEquals("https://new.com/n.mp4", merged.videoFileUri)
    }

    @Test
    fun `media 字段缺失但 content 保留_工具轮文本不回退`() {
        val final = assistantMsg(imageBase64List = listOf("aGk="))
        val existing = assistantMsg(imageUrls = listOf("https://old.com/o.png"))
        val merged = mergeFinalAssistantMedia(final, existing)
        // final 有 base64,imageUrls 为空 → 回退 existing 的 URL(两者并存不冲突)
        assertEquals(listOf("https://old.com/o.png"), merged.imageUrls)
        assertEquals(listOf("aGk="), merged.imageBase64List)
    }
}
