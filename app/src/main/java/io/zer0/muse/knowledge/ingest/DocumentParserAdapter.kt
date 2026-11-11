package io.zer0.muse.knowledge.ingest

import android.content.Context
import android.net.Uri
import io.zer0.muse.doc.DocumentParser

/**
 * R3 步骤 3/6:旧解析链适配 — 把 [DocumentParser] 的 Result<String> 世界映射到
 * [ParseOutcome] 合同,旧读取继续可用(计划 R3 回退:旧读取继续可用,新解析失败不替换
 * 旧活动版本)。
 *
 * 适配规则:
 *  - Result.Error → [ParseCoverage.FAILED],错误串只进 [ParseOutcome.error],
 *    绝不进 blocks(「错误提示不得出现在任何 chunk 内容中」);
 *  - 空白正文 → [ParseCoverage.NO_TEXT](不是空 COMPLETE);
 *  - 检测到旧链截断标记 → [ParseCoverage.PARTIAL],标记从正文剥离并转入
 *    coverageNote(旧链把标记当「文本结构的一部分」,适配层负责把它从 chunk 候选正文
 *    中分离 —— 内容语义与状态语义分开);
 *  - 其余 → COMPLETE。
 *
 * 格式路由:未知/缺失 MIME 且扩展名不在白名单 → FAILED(未知格式),**不再按 txt
 * 强读二进制**(R0 盘点缺陷;白名单见 [KnowledgeParserRegistry.isSupportedFormat])。
 */
class DocumentParserAdapter(
    private val parser: DocumentParser,
) {

    /** 旧链截断/上限标记识别:含「已截断」或「仅提取前」的方括号提示行。 */
    internal fun detectTruncationMarker(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { line ->
            line.startsWith("[…") && (line.contains("已截断") || line.contains("仅提取前"))
        }

    /**
     * 正文是否有实际内容 —— 排除解析器附加的「[文档信息]」「[目录]」元数据块与截断提示行。
     * 扫描件常只产出元数据;把元数据当正文会伪装成 COMPLETE(设备测试发现)。
     */
    internal fun hasMeaningfulBody(text: String): Boolean {
        var inMetadataSection = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) {
                inMetadataSection = false
                continue
            }
            if (line == "[文档信息]" || line == "[目录]") {
                inMetadataSection = true
                continue
            }
            if (line.startsWith("[…")) continue
            if (inMetadataSection) continue
            return true
        }
        return false
    }

    fun parseToOutcome(uri: Uri, context: Context, fileName: String): ParseOutcome {
        // 格式白名单路由:未知格式显式拒绝(不再按 txt 强读二进制)
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri)
        val extKnown = KnowledgeParserRegistry.isSupportedFormat(fileName)
        val mimeKnown = mimeType != null && (
            mimeType.startsWith("text/") ||
                mimeType in setOf(
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/epub+zip",
                ) ||
                mimeType.startsWith("image/")
            )
        if (!mimeKnown && !extKnown) {
            return ParseOutcome(
                coverage = ParseCoverage.FAILED,
                blocks = emptyList(),
                error = "未知文件格式,不支持导入: $fileName",
            )
        }

        return when (val result = parser.parseResult(uri, context)) {
            is io.zer0.common.Result.Success -> {
                val text = result.data
                when {
                    text.isBlank() -> ParseOutcome(
                        coverage = ParseCoverage.NO_TEXT,
                        blocks = emptyList(),
                        ocrRequirement = if (
                            mimeType?.startsWith("image/") == true ||
                            fileName.substringAfterLast('.', "").lowercase() in IMAGE_EXTS
                        ) {
                            OcrRequirement.REQUIRED_PENDING_POLICY
                        } else {
                            OcrRequirement.NONE
                        },
                    )
                    else -> {
                        val ext = fileName.substringAfterLast('.', "").lowercase()
                        if (!hasMeaningfulBody(text)) {
                            // 只有元数据/截断提示的 PDF 多为扫描件:不得报 COMPLETE。
                            ParseOutcome(
                                coverage = ParseCoverage.NO_TEXT,
                                blocks = emptyList(),
                                ocrRequirement = if (ext == "pdf" || ext in IMAGE_EXTS) {
                                    OcrRequirement.REQUIRED_PENDING_POLICY
                                } else {
                                    OcrRequirement.NONE
                                },
                            )
                        } else {
                            val marker = detectTruncationMarker(text)
                            if (marker != null) {
                                // 截断标记剥离:正文语义与状态语义分开,标记转入 coverageNote
                                val cleaned = text.lines()
                                    .filterNot { it.trim() == marker }
                                    .joinToString("\n")
                                ParseOutcome(
                                    coverage = ParseCoverage.PARTIAL,
                                    blocks = listOf(ParsedBlock(text = cleaned, page = 1, section = 1)),
                                    coverageNote = "原文超出解析上限被截断(${marker.removePrefix("[").removeSuffix("]")})",
                                )
                            } else {
                                ParseOutcome(
                                    coverage = ParseCoverage.COMPLETE,
                                    blocks = listOf(ParsedBlock(text = text, page = 1, section = 1)),
                                )
                            }
                        }
                    }
                }
            }
            is io.zer0.common.Result.Error -> ParseOutcome(
                coverage = ParseCoverage.FAILED,
                blocks = emptyList(),
                error = result.message,
            )
        }
    }

    companion object {
        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif")
    }
}
