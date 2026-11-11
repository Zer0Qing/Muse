package io.zer0.muse.knowledge

import io.zer0.muse.data.knowledge.KnowledgeJobItemEntity
import io.zer0.muse.knowledge.domain.KnowledgeJobStage
import io.zer0.muse.knowledge.domain.KnowledgeJobState
import io.zer0.muse.knowledge.ui.KnowledgeJobUiMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R8 步骤 5/6(计划 R8:「处理页列出逐项状态、部分成功和修复动作，取消有终态」
 * 「导入状态和能力不混淆；正常资料无需用户理解 topK/向量维度才能使用」)。
 */
class KnowledgeUiStateTest {

    private fun item(
        id: String,
        state: KnowledgeJobState,
        stage: KnowledgeJobStage = KnowledgeJobStage.CHUNK,
        reason: String = "",
        cancelRequested: Int = 0,
    ) = KnowledgeJobItemEntity(
        jobItemId = id,
        jobId = "job-1",
        docId = "doc-$id",
        state = state.name,
        stage = stage.name,
        resultReason = reason,
        cancelRequested = cancelRequested,
    )

    @Test
    fun `partial success summary distinguishes success and failure`() {
        val state = KnowledgeJobUiMapper.toUiState(
            "job-1",
            listOf(
                item("1", KnowledgeJobState.SUCCEEDED),
                item("2", KnowledgeJobState.FAILED, reason = "解析失败"),
            ),
        )
        assertEquals(2, state.total)
        assertEquals(1, state.succeeded)
        assertEquals(1, state.failed)
        assertTrue(state.summary.contains("部分成功"))
        assertTrue("失败项展示错误", state.items.first { it.itemId == "2" }.hasError)
    }

    @Test
    fun `terminal states are all terminal`() {
        val state = KnowledgeJobUiMapper.toUiState(
            "job-1",
            listOf(
                item("1", KnowledgeJobState.SUCCEEDED),
                item("2", KnowledgeJobState.CANCELLED),
                item("3", KnowledgeJobState.FAILED),
            ),
        )
        assertTrue(state.allTerminal)
    }

    @Test
    fun `running item is cancelable and not terminal`() {
        val state = KnowledgeJobUiMapper.toUiState("job-1", listOf(item("1", KnowledgeJobState.RUNNING)))
        assertFalse(state.allTerminal)
        assertTrue(state.items.single().canCancel)
    }

    @Test
    fun `cancel requested is not cancelable again`() {
        val state = KnowledgeJobUiMapper.toUiState("job-1", listOf(item("1", KnowledgeJobState.CANCEL_REQUESTED, cancelRequested = 1)))
        assertFalse(state.items.single().canCancel)
        assertEquals("正在取消", state.items.single().stateLabel)
    }

    @Test
    fun `availability label does not leak internal topK or vector concepts`() {
        val succeeded = item("1", KnowledgeJobState.SUCCEEDED)
        val label = KnowledgeJobUiMapper.availabilityLabel(succeeded)
        assertFalse("不得暴露内部概念", label.contains("topK") || label.contains("向量") || label.contains("维度"))
        assertEquals("可检索", label)
    }

    @Test
    fun `empty job shows no files message`() {
        val state = KnowledgeJobUiMapper.toUiState("job-1", emptyList())
        assertEquals("没有可处理的文件", state.summary)
        assertTrue(state.allTerminal)
    }

    @Test
    fun `document source label never leaks credentials`() {
        val inline = io.zer0.muse.knowledge.ui.KnowledgeDocumentUiState(
            docId = "d", ownerType = "UserLibrary", ownerId = "kb", folderId = "",
            revisionId = "r", revisionNo = 1, fullContent = "body",
            sourceRefJson = """{"scheme":"content","location":"inline"}""", availability = "可检索",
        )
        assertEquals("行内正文", inline.sourceLabel)
        val file = inline.copy(sourceRefJson = """{"scheme":"file","location":"/private/path"}""")
        assertEquals("本地文件", file.sourceLabel)
        val missing = inline.copy(sourceRefJson = "")
        assertEquals("无原件", missing.sourceLabel)
        assertFalse(file.sourceLabel.contains("/private"))
    }

    @Test
    fun `settings mapper shows field sources and impact`() {
        val policy = io.zer0.muse.knowledge.domain.KnowledgePolicyResolver.resolve(
            io.zer0.muse.rag.RagConfig(chunkSize = 300, topK = 7),
            io.zer0.muse.data.knowledge.KnowledgeBaseEntity(id = "kb", name = "kb", chunkSizeOverride = 200),
        )
        val state = io.zer0.muse.knowledge.ui.KnowledgeSettingsUiMapper.fromPolicy(policy)
        assertEquals("200", state.ingestFields.first { it.label == "分块大小" }.value)
        assertEquals("知识库覆盖", state.ingestFields.first { it.label == "分块大小" }.sourceLabel)
        assertEquals("7", state.retrievalFields.first { it.label == "候选数 topK" }.value)
        assertTrue("影响面必须说明", state.retrievalFields.first { it.label == "候选数 topK" }.impact.contains("不触发重编码"))
        assertFalse(state.ingestFields.any { it.sourceLabel.isBlank() })
    }
}
