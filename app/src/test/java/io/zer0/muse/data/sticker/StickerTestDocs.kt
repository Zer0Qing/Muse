package io.zer0.muse.data.sticker

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 测试用的最小 docx 构造（表情包重做 P2/P3/P5 共用）。
 *
 * 抽出来是因为三处各写了一份：一旦 OOXML 的 rels 写法改了，
 * 三份夹具会分别坏掉，而且坏的方式不一样 —— 排查时会以为是产品代码的问题。
 * 这份夹具自己就踩过一次（`${'{'}index{'}'}`} 转义写成字面量，白查三轮）。
 */
object StickerTestDocs {

    /** PNG 魔数开头的假图：够让类型嗅探认出来，又不是可解码图（尺寸解不出时应按中性放行）。 */
    fun fakePng(seed: Int): ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        ByteArray(9) { ((it + seed) % 251).toByte() }

    fun imageParagraph(rid: String) = "<w:p><w:r><w:drawing><a:blip r:embed=\"" + rid + "\"/></w:drawing></w:r></w:p>"

    fun textParagraph(text: String) = "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p>"

    /** 写一个含 word/media 与 rels 的最小 docx。rels 按 media 顺序生成 rId1..n。 */
    fun writeDocx(target: File, document: String, media: Map<String, ByteArray>): File {
        ZipOutputStream(FileOutputStream(target), Charsets.UTF_8).use { zos ->
            fun put(path: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
            put("word/document.xml", document.toByteArray(Charsets.UTF_8))
            val rels = media.keys.mapIndexed { index, path ->
                "<Relationship Id=\"rId" + (index + 1) + "\" Type=\"image\" Target=\"" +
                    path.removePrefix("word/") + "\"/>"
            }.joinToString("")
            put("word/_rels/document.xml.rels", ("<Relationships>" + rels + "</Relationships>").toByteArray(Charsets.UTF_8))
            media.forEach { (path, bytes) -> put(path, bytes) }
        }
        return target
    }

    /** 一张带标题、带配文、带原图链接的三图文档。 */
    fun threeImageDocx(dir: File, name: String = "pack.docx"): File {
        val document = "<w:document><w:body>" +
            textParagraph("呆猫八条") +
            imageParagraph("rId1") +
            textParagraph("https://93.184.216.34/i/one.jpg（小猫歪头）") +
            imageParagraph("rId2") +
            textParagraph("小猫躲墙后") +
            imageParagraph("rId3") +
            textParagraph("（第三张）") +
            "</w:body></w:document>"
        return writeDocx(
            File(dir, name),
            document,
            mapOf(
                "word/media/image1.png" to fakePng(1),
                "word/media/image2.png" to fakePng(2),
                "word/media/image3.png" to fakePng(3),
            ),
        )
    }
}
