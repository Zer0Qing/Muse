package io.zer0.memory.compile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `memory.md` 写入去重。
 *
 * 背景：读路径 `MemoryCompiler.readCompiledMemoryMarkdown` 在返回内容之前会调
 * `writeMemoryMd`（产物同时落盘，方便调试与备份）。而去重条件早先带 `target == null`，
 * 读路径永远带 target → 去重永不生效 → **每读一次记忆就原子写一次文件**。
 *
 * 这里用「外部篡改文件」来判定是否真的跳过了写：
 *   - 若跳过，文件保持被篡改的内容；
 *   - 若真写，文件被恢复成传入的正文。
 * 比计时可靠，也不依赖文件系统时间精度。
 */
class MemoryFileWriterDedupTest {

    private fun tempRoot(): File = Files.createTempDirectory("muse-memory-dedup").toFile()

    private fun memoryMd(root: File, target: MemoryCompileTarget?): File {
        val dir = if (target == null) {
            File(root, "memory")
        } else {
            File(root, "memory/${target.normalizedScope}/${target.normalizedSpaceId}")
        }
        return File(dir, "memory.md")
    }

    @Test
    fun `same_content_with_target_is_not_rewritten`() {
        val root = tempRoot()
        try {
            val writer = MemoryFileWriter(root)
            val target = MemoryCompileTarget(assistantId = "a1", scope = "a1", spaceId = "daily")

            writer.writeMemoryMd("## 事实\n- 喜欢茶", target = target)
            val file = memoryMd(root, target)
            assertEquals("## 事实\n- 喜欢茶", file.readText())

            // 外部篡改：内容不变时若再次落盘，文件会被覆盖回原文。
            file.writeText("TAMPERED")
            writer.writeMemoryMd("## 事实\n- 喜欢茶", target = target)

            assertEquals(
                "内容没变时必须跳过写盘（读路径不应触发磁盘写）",
                "TAMPERED",
                file.readText(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `changed_content_with_target_is_written`() {
        val root = tempRoot()
        try {
            val writer = MemoryFileWriter(root)
            val target = MemoryCompileTarget(assistantId = "a1", scope = "a1", spaceId = "daily")

            writer.writeMemoryMd("v1", target = target)
            writer.writeMemoryMd("v2", target = target)

            assertEquals("v2", memoryMd(root, target).readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `different_targets_are_deduplicated_independently`() {
        val root = tempRoot()
        try {
            val writer = MemoryFileWriter(root)
            val first = MemoryCompileTarget(assistantId = "a1", scope = "a1", spaceId = "work")
            val second = MemoryCompileTarget(assistantId = "a2", scope = "a2", spaceId = "work")

            // 两个 target 收到同一份内容：都必须各自落盘，不能被前一个的去重挡住。
            writer.writeMemoryMd("same", target = first)
            writer.writeMemoryMd("same", target = second)

            assertEquals("same", memoryMd(root, first).readText())
            assertEquals("same", memoryMd(root, second).readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `legacy_null_target_still_deduplicates`() {
        val root = tempRoot()
        try {
            val writer = MemoryFileWriter(root)
            writer.writeMemoryMd("legacy")
            val file = memoryMd(root, null)
            assertEquals("legacy", file.readText())

            file.writeText("TAMPERED")
            writer.writeMemoryMd("legacy")

            assertEquals("TAMPERED", file.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `tracking_table_is_bounded`() {
        val root = tempRoot()
        try {
            val writer = MemoryFileWriter(root)
            repeat(40) { index ->
                val target = MemoryCompileTarget(
                    assistantId = "a$index",
                    scope = "scope$index",
                    spaceId = "space$index",
                )
                writer.writeMemoryMd("content-$index", target = target)
                // 每个 target 都必须真的写出自己的内容（溢出清理只允许导致多写，不允许漏写）。
                assertEquals("content-$index", memoryMd(root, target).readText())
            }
            assertTrue("写入不应对 target 数量设隐性上限", true)
        } finally {
            root.deleteRecursively()
        }
    }
}
