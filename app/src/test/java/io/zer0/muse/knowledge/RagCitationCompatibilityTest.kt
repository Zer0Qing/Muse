package io.zer0.muse.knowledge

import io.zer0.ai.core.RagCitation
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R7 步骤 1(计划 R7:「旧 citation JSON 兼容测试」)。
 *
 * 旧版 RagCitation 没有 isInternal 字段;反序列化必须按默认值 false 兼容,
 * 不能在升级后读不出历史引用。
 */
class RagCitationCompatibilityTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `old citation json without isInternal deserializes with default false`() {
        val oldJson = """
            {"index":1,"docId":"doc-1","docTitle":"旧文档","chunkId":"chunk-1","chunkIndex":0,
             "snippet":"旧片段","score":0.9,"matchType":"vector"}
        """.trimIndent()
        val citation = json.decodeFromString<RagCitation>(oldJson)
        assertEquals(1, citation.index)
        assertEquals("doc-1", citation.docId)
        assertEquals("旧文档", citation.docTitle)
        assertFalse("旧数据默认非内部文档", citation.isInternal)
    }

    @Test
    fun `new citation json with isInternal round trips`() {
        val citation = RagCitation(
            index = 2,
            docId = "devdoc-1",
            docTitle = "内置帮助",
            chunkId = "chunk-2",
            chunkIndex = 3,
            snippet = "帮助片段",
            score = 0.5f,
            matchType = "keyword_fallback",
            isInternal = true,
        )
        val encoded = json.encodeToString(RagCitation.serializer(), citation)
        val decoded = json.decodeFromString<RagCitation>(encoded)
        assertEquals(citation, decoded)
        assertTrue(decoded.isInternal)
    }

    @Test
    fun `unknown future fields are tolerated`() {
        val futureJson = """
            {"index":1,"docId":"d","docTitle":"t","chunkId":"c","chunkIndex":0,
             "snippet":"s","score":0.1,"matchType":"hybrid","isInternal":false,
             "futureField":"ignored"}
        """.trimIndent()
        val citation = json.decodeFromString<RagCitation>(futureJson)
        assertEquals("d", citation.docId)
    }
}
