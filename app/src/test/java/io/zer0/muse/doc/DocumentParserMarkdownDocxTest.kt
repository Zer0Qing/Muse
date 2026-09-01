package io.zer0.muse.doc

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * R-TEST-07: 知识库文档索引失败路径 — markdown / 双扩展名 .md.txt / docx 解析。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DocumentParserMarkdownDocxTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val parser = DocumentParser()

    @Test
    fun `markdown file parses as text`() {
        val file = writeTempFile("note.md", "# 标题\n\n正文内容")
        val result = parser.parseResult(Uri.fromFile(file), context)
        assertTrue("markdown 解析失败: $result", result.isSuccess)
        assertEquals("# 标题\n\n正文内容", result.getOrNull())
    }

    @Test
    fun `double extension md txt parses as text`() {
        val file = writeTempFile("note.md.txt", "双扩展名内容")
        val result = parser.parseResult(Uri.fromFile(file), context)
        assertTrue("md.txt 解析失败: $result", result.isSuccess)
        assertEquals("双扩展名内容", result.getOrNull())
    }

    @Test
    fun `minimal docx extracts paragraph text`() {
        val file = createMinimalDocx("第一段", "第二段")
        val result = parser.parseResult(Uri.fromFile(file), context)
        assertTrue("docx 解析失败: $result", result.isSuccess)
        val text = result.getOrNull() ?: ""
        assertTrue(text.contains("第一段"))
        assertTrue(text.contains("第二段"))
    }

    private fun writeTempFile(name: String, content: String): File {
        val file = File.createTempFile(name.replace('.', '_'), ".tmp", context.cacheDir)
        file.writeText(content)
        val renamed = File(context.cacheDir, name)
        file.renameTo(renamed)
        return renamed
    }

    private fun createMinimalDocx(vararg paragraphs: String): File {
        val file = File.createTempFile("docx_test_", ".docx", context.cacheDir)
        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
            append("<w:body>")
            paragraphs.forEach { append("<w:p><w:r><w:t>").append(it).append("</w:t></w:r></w:p>") }
            append("</w:body></w:document>")
        }
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(xml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return file
    }
}
