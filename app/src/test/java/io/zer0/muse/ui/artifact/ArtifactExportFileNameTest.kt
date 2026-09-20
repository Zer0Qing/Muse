package io.zer0.muse.ui.artifact

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 2: 产物另存文件名生成测试。
 */
class ArtifactExportFileNameTest {

    @Test
    fun `普通标题生成 txt 文件名`() {
        assertEquals("report.txt", artifactExportFileName("report"))
    }

    @Test
    fun `非法字符替换为下划线`() {
        assertEquals("My_Doc_2024.txt", artifactExportFileName("My Doc/2024"))
    }

    @Test
    fun `中文标题保留`() {
        assertEquals("数据分析.txt", artifactExportFileName("数据分析"))
    }

    @Test
    fun `空标题回退默认文件名`() {
        assertEquals("$DEFAULT_ARTIFACT_FILE_NAME.txt", artifactExportFileName("   "))
    }

    @Test
    fun `超长标题截断到 40 字符`() {
        assertEquals("a".repeat(40) + ".txt", artifactExportFileName("a".repeat(100)))
    }
}
