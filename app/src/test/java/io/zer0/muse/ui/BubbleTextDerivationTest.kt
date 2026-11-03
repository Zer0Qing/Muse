package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文渲染派生值（`deriveBubbleText`）。
 *
 * 这段逻辑以前写在组合体里、每次重组都重跑（表情包路径 findAll + 两条整段正则替换 + 标题切分），
 * 现在既被 `remember` 包住，也提成了纯函数。纯函数化之后才有这些用例 —— 否则「顺手调整一下
 * 标题切分」只能靠人眼看界面，而它影响的是每一条消息的正文呈现。
 */
class BubbleTextDerivationTest {

    private fun derive(
        content: String,
        body: String = content,
        imageUrls: List<String> = emptyList(),
        imageBase64List: List<String> = emptyList(),
        isStreaming: Boolean = false,
        isCompressed: Boolean = false,
    ) = deriveBubbleText(
        content = content,
        body = body,
        imageUrls = imageUrls,
        imageBase64List = imageBase64List,
        isStreaming = isStreaming,
        isCompressed = isCompressed,
    )

    @Test
    fun `plain_text_passes_through`() {
        val result = derive("普通正文")
        assertEquals("普通正文", result.content)
        assertEquals("普通正文", result.bodyContent)
        assertNull(result.titleText)
    }

    @Test
    fun `plain text does not need sticker or artifact sanitization`() {
        assertTrue(!needsBubbleTextSanitization("普通正文"))
        assertTrue(
            needsBubbleTextSanitization(
                "路径 /data/user/0/io.zer0.muse/files/stickers/a.webp",
            ),
        )
        assertTrue(
            needsBubbleTextSanitization(
                "[artifact:123e4567-e89b-12d3-a456-426614174000]",
            ),
        )
    }

    @Test
    fun `heading_is_split_out_of_body`() {
        val result = derive("# 标题行\n正文第一段")
        assertEquals("# 标题行", result.titleText)
        assertEquals("正文第一段", result.bodyContent)
        assertEquals("标题行", result.titleText!!.removePrefix("# "))
    }

    @Test
    fun `heading_is_not_split_while_streaming`() {
        val result = derive("# 标题行\n正文第一段", isStreaming = true)
        assertNull("流式期间不切标题（内容还会增长，切了会闪）", result.titleText)
        assertTrue("正文必须含标题行原文", result.bodyContent.contains("# 标题行"))
    }

    @Test
    fun `compressed_marker_is_stripped`() {
        val result = derive("[COMPRESSED]  历史摘要正文", isCompressed = true)
        assertEquals("历史摘要正文", result.content)
    }

    @Test
    fun `sticker_paths_are_removed_when_message_has_no_images`() {
        val content = "看这个 /data/user/0/io.zer0.muse/files/stickers/a.webp 表情"
        val result = derive(content)
        assertTrue("贴纸路径不应留在正文里", !result.content.contains("stickers/a.webp"))
        assertTrue("其它文字要保留", result.content.contains("看这个"))
    }

    @Test
    fun `sticker_scan_is_skipped_when_message_has_images`() {
        // 有图片时正文里那串路径可能是有意保留的（图片与文本双渲染），不能剔除。
        val content = "带图正文"
        val result = derive(content, imageUrls = listOf("https://example.com/a.png"))
        assertEquals(content, result.content)
    }

    @Test
    fun `artifact_marker_is_removed_from_body`() {
        val marker = "[artifact:123e4567-e89b-12d3-a456-426614174000]"
        val result = derive("正文$marker 结束")
        assertTrue("artifact 标记不应出现在正文", !result.bodyContent.contains("artifact:"))
        assertTrue(result.bodyContent.contains("正文"))
    }

    @Test
    fun `empty_body_with_images_keeps_empty_and_without_images_uses_space`() {
        assertEquals("", derive("", imageUrls = listOf("x")).content)
        assertEquals(" ", derive("").content)
    }
}
