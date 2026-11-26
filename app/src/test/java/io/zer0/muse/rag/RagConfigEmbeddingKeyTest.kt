package io.zer0.muse.rag

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * embedding 指纹合同：导入与重索引写入的 `embedding_model` 必须是同一个口径。
 *
 * 两个必须钉住的事实：
 *  1. 本地模型换文件（路径相同、内容不同）必须改变 key —— 只看路径会让新旧向量混在一个文档下；
 *  2. 云端分支不得包含凭据 —— 这个 key 会落进 knowledge_docs.embedding_model 并出现在 UI 里。
 */
class RagConfigEmbeddingKeyTest {

    @Test
    fun `local key includes model digest`() {
        val base = RagConfig(
            embeddingSource = RagConfig.EmbeddingSource.LOCAL,
            localModelPath = "/data/app/muse_onnx/embedding.onnx",
            localModelDigest = "aaaa1111",
        )
        val replaced = base.copy(localModelDigest = "bbbb2222")

        assertNotEquals(
            "同一路径换成另一个模型文件时必须换 key",
            RagConfig.embeddingModelKey(base),
            RagConfig.embeddingModelKey(replaced),
        )
        assertTrue(RagConfig.embeddingModelKey(base).contains("aaaa1111"))
    }

    @Test
    fun `local key is stable when digest missing`() {
        val config = RagConfig(
            embeddingSource = RagConfig.EmbeddingSource.LOCAL,
            localModelPath = "/data/app/muse_onnx/embedding.onnx",
        )
        val key = RagConfig.embeddingModelKey(config)

        assertEquals("无指纹时必须给出稳定占位符，不能每次算出不同值", key, RagConfig.embeddingModelKey(config))
        assertTrue("占位符要能看出来「不知道指纹」", key.contains("no-digest"))
    }

    @Test
    fun `cloud key carries provider and model but no credentials`() {
        val config = RagConfig(
            embeddingSource = RagConfig.EmbeddingSource.CLOUD,
            cloudProviderId = "provider-a",
            cloudModel = "text-embedding-3-small",
        )
        val key = RagConfig.embeddingModelKey(config)

        assertTrue("云端 key 必须保留 provider", key.contains("provider-a"))
        assertTrue("云端 key 必须保留模型名", key.contains("text-embedding-3-small"))
        assertFalse("凭据不得进入 key（会落进文档列与 UI）", key.contains("sk-"))
    }

    @Test
    fun `keyword key is independent of cloud fields`() {
        val a = RagConfig(
            embeddingSource = RagConfig.EmbeddingSource.LOCAL_KEYWORD,
            cloudModel = "x",
        )
        val b = RagConfig(
            embeddingSource = RagConfig.EmbeddingSource.LOCAL_KEYWORD,
            cloudModel = "y",
        )
        assertEquals(RagConfig.embeddingModelKey(a), RagConfig.embeddingModelKey(b))
    }

    @Test
    fun `localModelDigest survives datastore json round trip`() {
        // DataStore 里存的是 JSON：新增字段必须向后兼容（旧 JSON 无该字段时取默认空串）
        val legacyJson = """{"embeddingSource":"LOCAL","localModelPath":"/m.onnx"}"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(RagConfig.serializer(), legacyJson)
        assertEquals("旧 JSON 缺字段时必须取默认值", "", decoded.localModelDigest)

        val withDigest = decoded.copy(localModelDigest = "deadbeef")
        val encoded = Json.encodeToString(RagConfig.serializer(), withDigest)
        val roundTrip = Json.decodeFromString(RagConfig.serializer(), encoded)
        assertEquals("deadbeef", roundTrip.localModelDigest)
    }
}
