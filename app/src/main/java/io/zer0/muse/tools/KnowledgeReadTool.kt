package io.zer0.muse.tools

import io.zer0.muse.data.knowledge.KnowledgeDocDao
import kotlinx.coroutines.runBlocking

/**
 * v2.6.2: 按 docId 读取知识库文档全文。
 *
 * 此前助手对知识库只有 knowledge_search（向量检索，返回 topK 片段），对一篇
 * 195 页的 PDF 只能看到零散碎片，无法完整理解文档结构或按需阅读全文。
 *
 * 用法：先用 knowledge_list_docs 确认文档 id，再用本工具读全文。
 * 超长文档按 offset 分段读取（每次最多 [MAX_CHARS_PER_READ] 字符），避免
 * 一次性把 195 页塞进上下文窗口。
 *
 * 内部文档（is_internal=1）不允许通过本工具读取——那是 devdoc，与用户知识库隔离。
 */
object KnowledgeReadTool {

    const val TOOL_NAME = "knowledge_read_doc"

    /** 单次读取上限。195 页 PDF ≈ 50 万字符，分多次读。 */
    private const val MAX_CHARS_PER_READ = 30_000

    fun toolDef() = ToolRegistry.ToolDef(
        name = TOOL_NAME,
        description = "读取知识库文档的全文内容。超长文档按 offset 分段（每次最多 30000 字符），" +
            "先不传 offset 读第一段，返回末尾会给出剩余字符数和下一次 offset。" +
            "文档 id 可用 knowledge_list_docs 获取。",
        parameters = mapOf(
            "doc_id" to "必填，文档 id（可用 knowledge_list_docs 获取）",
            "offset" to "可选，起始字符偏移；省略时从 0 开始",
        ),
        required = setOf("doc_id"),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    fun executeFromArgs(args: Map<String, String>, docDao: KnowledgeDocDao): String {
        val docId = args["doc_id"]?.trim().orEmpty()
        if (docId.isBlank()) return "Error: doc_id is required."
        val offset = args["offset"]?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        return runBlocking {
            val doc = docDao.getById(docId)
            when {
                doc == null -> "Error: 未找到文档 $docId。可用 knowledge_list_docs 查看文档清单。"
                doc.isInternal -> "Error: 文档 $docId 是内部开发文档，不允许通过本工具读取。"
                doc.content.isBlank() -> "文档 ${doc.title}（$docId）正文为空。"
                else -> {
                    val content = doc.content
                    val total = content.length
                    if (offset >= total) {
                        "已超出文档末尾（总长 $total 字符，请求 offset=$offset）。"
                    } else {
                        val end = minOf(offset + MAX_CHARS_PER_READ, total)
                        val chunk = content.substring(offset, end)
                        val remaining = total - end
                        buildString {
                            appendLine("「${doc.title}」（$docId）— 第 ${offset + 1}-$end / $total 字符")
                            if (remaining > 0) {
                                appendLine("[还有 $remaining 字符，用 offset=$end 继续读取]")
                            } else {
                                appendLine("[全文完]")
                            }
                            appendLine("---")
                            append(chunk)
                        }
                    }
                }
            }
        }
    }
}

/** 注册器：走 Koin 注入，由 AutomationInitializer 统一引导。 */
class KnowledgeReadToolRegistrar(
    private val toolRegistry: ToolRegistry,
    private val docDao: KnowledgeDocDao,
) {
    init {
        toolRegistry.register(KnowledgeReadTool.toolDef()) { args ->
            KnowledgeReadTool.executeFromArgs(args, docDao)
        }
    }
}
