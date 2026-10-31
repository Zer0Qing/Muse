package io.zer0.ai.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 消息附件元数据的序列化契约。
 *
 * 这些 JSON 会被写进 `messages.attachmentsJson` 并长期留在库里，
 * 所以两个方向都必须稳：
 *  - 新字段要能存下来（否则附件路径丢了就再也点不开原件）；
 *  - 老 JSON 要能读回来（否则升级后历史消息直接解析失败）。
 */
class AttachmentRefSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `workspacePath 能往返`() {
        val original = AttachmentRef(
            id = "a1",
            name = "报告.pdf",
            mimeType = "application/pdf",
            size = 1024L,
            workspacePath = "attachments/报告.pdf",
        )

        val encoded = json.encodeToString(AttachmentRef.serializer(), original)
        val decoded = json.decodeFromString(AttachmentRef.serializer(), encoded)

        assertEquals(original, decoded)
        assertEquals("attachments/报告.pdf", decoded.workspacePath)
    }

    @Test
    fun `旧 JSON 没有 workspacePath 也能解析`() {
        // 这条形状来自引入 workspacePath 之前写进库的数据。
        val legacy = """{"id":"a1","name":"old.pdf","mimeType":"application/pdf","size":10,"sourceType":"FILE"}"""

        val decoded = json.decodeFromString(AttachmentRef.serializer(), legacy)

        assertEquals("old.pdf", decoded.name)
        assertNull(decoded.workspacePath)
    }

    @Test
    fun `没有路径时字段不出现在 JSON 里`() {
        // 默认值不落盘，旧数据不会被无意义地撑大。
        val encoded = json.encodeToString(
            AttachmentRef.serializer(),
            AttachmentRef(id = "a1", name = "x.txt"),
        )

        assertEquals(false, encoded.contains("workspacePath"))
    }
}
