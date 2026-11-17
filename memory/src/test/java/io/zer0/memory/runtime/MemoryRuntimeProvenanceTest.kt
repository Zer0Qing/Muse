package io.zer0.memory.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * compiled provenance 的读取契约。
 *
 * 这些断言对应一个具体的用户风险：某次投影失败后，
 * 记忆页/prompt 仍把旧内容当「当前」展示，用户看到的是过期记忆却毫不知情。
 */
class MemoryRuntimeProvenanceTest {

    @Test
    fun `only READY sections are visible by default`() {
        val rows = listOf(
            section("facts", "READY", "用户喜欢猫"),
            section("today", "FAILED", "上次失败的投影"),
            section("week", "STALE", "过期内容"),
            section("longterm", "READY", "长期在做 Muse"),
        )

        val visible = MemoryCompiledProjection.visibleSections(rows, includeNonReady = false)

        assertEquals(setOf("facts", "longterm"), visible.keys)
    }

    @Test
    fun `explicit request can read failed rows for UI diagnosis`() {
        val rows = listOf(
            section("facts", "READY", "用户喜欢猫"),
            section("today", "FAILED", "上次失败的投影"),
        )

        val visible = MemoryCompiledProjection.visibleSections(rows, includeNonReady = true)

        assertEquals(setOf("facts", "today"), visible.keys)
    }

    @Test
    fun `provenance carries fingerprint status and revision`() {
        val row = section("facts", "READY", "用户喜欢猫").copy(
            fingerprint = "content-fp",
            sourceFingerprint = "source-fp",
            updatedAt = "2026-11-17T10:00:00Z",
        )

        val provenance = MemoryCompiledProjection.provenanceOf(row)

        assertEquals("content-fp", provenance.contentFingerprint)
        assertEquals("source-fp", provenance.sourceFingerprint)
        assertEquals(MemoryProjectionStatus.READY, provenance.status)
        assertEquals("2026-11-17T10:00:00Z", provenance.updatedAt)
    }

    @Test
    fun `failed projection keeps previous READY content instead of blanking it`() {
        val previous = section("today", "READY", "旧的今日总结").copy(fingerprint = "old-fp")

        val next = MemoryCompiledProjection.onProjectionFailure(
            previous = previous,
            reason = "upstream 502",
            now = "2026-11-17T11:00:00Z",
        ) ?: error("投影失败时必须保留上一版行，不能返回 null")

        // 内容必须原样保留：投影失败不该让用户今天的记忆凭空消失。
        assertEquals("旧的今日总结", next.content)
        assertEquals("old-fp", next.fingerprint)
        assertEquals("FAILED", next.status)
        assertEquals("2026-11-17T11:00:00Z", next.updatedAt)
    }

    @Test
    fun `unknown status string degrades to failed rather than ready`() {
        // 未知状态必须按「不可信」处理：宁可让 UI 提示异常，也不要把来路不明的内容当当前记忆。
        val row = section("facts", "WHATEVER", "内容")

        assertEquals(MemoryProjectionStatus.FAILED, MemoryCompiledProjection.provenanceOf(row).status)
        assertFalse(MemoryCompiledProjection.visibleSections(listOf(row), includeNonReady = false).containsKey("facts"))
    }

    private fun section(key: String, status: String, content: String) =
        io.zer0.memory.summary.ScopedCompiledSectionEntity(
            sectionKey = key,
            scope = "main",
            spaceId = "default",
            content = content,
            updatedAt = "2026-11-17T09:00:00Z",
            status = status,
        )

    @Test
    fun `legacy backup rows get an explicit baseline instead of looking current`() {
        // 旧备份没有 source_fingerprint / status：直接入库会变成「空指纹 + READY」，
        // 看起来和刚算出来的一模一样。必须留下可追问的标记。
        val legacy = io.zer0.memory.summary.ScopedCompiledSectionEntity(
            sectionKey = "facts",
            content = "很久以前的事实",
            updatedAt = "2025-01-01T00:00:00Z",
        )

        val normalized = MemoryCompiledProjection.normalizeRestored(legacy)

        assertEquals(MemoryCompiledProjection.LEGACY_BASELINE, normalized.sourceFingerprint)
        assertEquals("READY", normalized.status)
    }

    @Test
    fun `restore does not launder failed rows into ready`() {
        val failed = section("today", "FAILED", "失败的内容").copy(sourceFingerprint = "src")

        val normalized = MemoryCompiledProjection.normalizeRestored(failed)

        assertEquals("FAILED", normalized.status)
        assertEquals("src", normalized.sourceFingerprint)
    }
}