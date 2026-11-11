package io.zer0.muse.knowledge.integration

import io.zer0.ai.core.RagCitation
import io.zer0.muse.knowledge.retrieval.EvidenceBundle
import io.zer0.muse.knowledge.retrieval.EvidenceUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * R7 证据快照 — 计划 R7「主聊天每轮独立保存 EvidenceBundle；持久引用区分提供给模型与
 * 正文实际引用，不给未使用资料套『已使用』标签」。
 *
 * 本类只定义**可序列化快照与编解码合同**;真正的消息持久化字段由聊天消息模型接线时使用
 * (R9 切换批次)。快照保留:最终上下文文本、实际发送片段、citation 快照、token 估计、traceId。
 *
 * 兼容性:
 *  - 快照带 [version],未知较新版本解码时按当前字段读取(不静默丢正文);
 *  - citation 使用既有 [RagCitation] 序列化(旧引用 JSON 可读)。
 */
@Serializable
data class EvidenceUnitSnapshot(
    val index: Int,
    val docId: String,
    val chunkId: String,
    val generationId: String,
    val docTitle: String,
    val locator: String,
    val sentSnippet: String,
    val truncated: Boolean,
    val tokenEstimate: Int,
)

@Serializable
data class EvidenceBundleSnapshot(
    val version: Int = CURRENT_VERSION,
    val finalText: String,
    val units: List<EvidenceUnitSnapshot>,
    val citations: List<RagCitation>,
    val tokenEstimate: Int,
    val traceId: String,
    /** 提供给模型的资料 vs 正文实际引用:两者分开记录,不互相冒充。 */
    val providedToModel: List<String> = emptyList(),
    val actuallyCited: List<String> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

object EvidenceSnapshotCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun toSnapshot(bundle: EvidenceBundle): EvidenceBundleSnapshot = EvidenceBundleSnapshot(
        finalText = bundle.finalText,
        units = bundle.units.map { it.toSnapshot() },
        citations = bundle.citations,
        tokenEstimate = bundle.tokenEstimate,
        traceId = bundle.traceId,
        providedToModel = bundle.citations.map { it.chunkId },
        actuallyCited = emptyList(), // 由正文引用解析后回填;未知时不得伪造"已使用"
    )

    fun fromSnapshot(snapshot: EvidenceBundleSnapshot): EvidenceBundle = EvidenceBundle(
        finalText = snapshot.finalText,
        units = snapshot.units.map { it.toUnit() },
        citations = snapshot.citations,
        tokenEstimate = snapshot.tokenEstimate,
        traceId = snapshot.traceId,
        skippedCount = 0,
        degraded = false,
    )

    fun encode(snapshot: EvidenceBundleSnapshot): String =
        json.encodeToString(EvidenceBundleSnapshot.serializer(), snapshot)

    fun decode(text: String): EvidenceBundleSnapshot =
        json.decodeFromString(EvidenceBundleSnapshot.serializer(), text)

    private fun EvidenceUnit.toSnapshot() = EvidenceUnitSnapshot(
        index = index,
        docId = docId,
        chunkId = chunkId,
        generationId = generationId,
        docTitle = docTitle,
        locator = locator,
        sentSnippet = sentSnippet,
        truncated = truncated,
        tokenEstimate = tokenEstimate,
    )

    private fun EvidenceUnitSnapshot.toUnit() = EvidenceUnit(
        index = index,
        docId = docId,
        chunkId = chunkId,
        generationId = generationId,
        docTitle = docTitle,
        locator = locator,
        sentSnippet = sentSnippet,
        truncated = truncated,
        tokenEstimate = tokenEstimate,
    )
}
