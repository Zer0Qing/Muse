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

/**
 * 旧版 Word(.doc)拒收 + 文本编码截断边界。
 *
 * 两个都是「静默污染」类缺陷，不会报错，只会把乱码写进知识库并建立向量索引：
 *  1. .doc 是 OLE 复合二进制，旧链的 else 分支会把它当文本读；
 *  2. 1MB 截断点可能落在多字节字符中间，半个字符会让严格 UTF-8 解码失败，
 *     整篇被误判成 GBK（中文全变乱码）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DocumentParserLegacyDocAndEncodingTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val parser = DocumentParser()

    // ---------- 旧版 .doc 拒收 ----------

    @Test
    fun `legacy doc by extension is rejected not read as text`() {
        // 内容写成可读文本，确保失败原因是「拒收」而不是「读不出来」
        val file = File(context.cacheDir, "legacy-word.doc")
        file.writeText("这是本该被拒收的正文")

        val result = parser.parseResult(Uri.fromFile(file), context)

        assertTrue("旧版 .doc 必须返回错误而不是正文: $result", result.isError)
        val message = (result as io.zer0.common.Result.Error).message
        assertTrue("错误提示要说清怎么办(docx): $message", message.contains("docx"))
        assertEquals(
            "拒收时不得产出任何正文(否则会被当文本索引)",
            null,
            result.getOrNull(),
        )
    }

    @Test
    fun `docx is still accepted`() {
        // 防回归：拒收规则不能连 .docx 一起打死
        val file = File(context.cacheDir, "real-docx.docx")
        file.writeBytes(minimalDocxBytes("docx 正文"))

        val result = parser.parseResult(Uri.fromFile(file), context)

        assertTrue("docx 必须继续可解析: $result", result.isSuccess)
        assertTrue(result.getOrNull().orEmpty().contains("docx 正文"))
    }

    // ---------- UTF-8 截断点回退到字符边界 ----------

    @Test
    fun `truncation never splits a multi byte character`() {
        // 构造刚好超过上限的中文文本（每字 3 字节），让截断点必然落在字符中间附近
        val text = "汉".repeat(400_000)
        val file = File(context.cacheDir, "big-utf8.txt")
        file.writeText(text, Charsets.UTF_8)

        val result = parser.parseResult(Uri.fromFile(file), context)

        assertTrue("超限文本应解析成功(带截断标记): $result", result.isSuccess)
        val body = result.getOrNull().orEmpty()
        // 截断标记在，说明真的走到了截断分支
        assertTrue("应带截断标记", body.contains("已截断"))
        // 关键：正文里不能出现替换字符（半个字符被宽松解码的痕迹）
        assertTrue(
            "截断点必须回退到字符边界，不得产出替换字符",
            !body.contains('\uFFFD'),
        )
        assertTrue("正文应仍以中文字符开头", body.startsWith("汉"))
    }

    @Test
    fun `truncated utf8 is not misdetected as gbk`() {
        // 若半个字符导致严格 UTF-8 失败，整篇会被按 GBK 解码 → 中文变乱码。
        // 这里用「能被 GBK 解码但语义完全不同」的内容做钉子。
        val text = "中文内容测试".repeat(60_000)
        val file = File(context.cacheDir, "utf8-gbk-trap.txt")
        file.writeText(text, Charsets.UTF_8)

        val body = parser.parseResult(Uri.fromFile(file), context).getOrNull().orEmpty()

        assertTrue("截断后仍应能读回原中文，而不是 GBK 乱码", body.startsWith("中文内容测试"))
        // GBK 误判会把「中文」解成别的字，最直接的钉子是没有替换字符且前缀正确
        assertTrue("不得出现替换字符", !body.contains('\uFFFD'))
    }

    @Test
    fun `small utf8 text is unchanged`() {
        val file = File(context.cacheDir, "small-utf8.txt")
        file.writeText("短文本，不触发截断", Charsets.UTF_8)

        val body = parser.parseResult(Uri.fromFile(file), context).getOrNull().orEmpty()

        assertEquals("短文本，不触发截断", body)
    }

    private fun minimalDocxBytes(vararg paragraphs: String): ByteArray {
        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
            append("<w:body>")
            paragraphs.forEach { append("<w:p><w:r><w:t>").append(it).append("</w:t></w:r></w:p>") }
            append("</w:body></w:document>")
        }
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zos.write(xml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return out.toByteArray()
    }
}
