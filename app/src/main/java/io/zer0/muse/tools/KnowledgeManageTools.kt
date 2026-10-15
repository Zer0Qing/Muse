package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.muse.data.knowledge.KnowledgeBaseDao
import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.rag.RagService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 知识库管理工具集 —— 让助手能对知识库做写操作。
 *
 * 此前助手对知识库只有 knowledge_search（只读检索），删文档/删库/建库/列清单
 * 全部做不到——用户实测"叫 AI 删也没删掉，换 Agnes/DeepSeek 都不行"。
 *
 * 命名统一 `knowledge_`，与 `app_*`（应用控制）、`screen_*`（UI 自动化）并列：
 *  - `knowledge_list_docs`   列出指定知识库的全部文档（SAFE，只读）
 *  - `knowledge_delete_doc`  删除一篇文档（HIGH，走审批）
 *  - `knowledge_create_kb`   创建知识库（HIGH）
 *  - `knowledge_delete_kb`   删除知识库（HIGH，连带删其全部文档）
 *
 * 设计约束：
 *  - 不提供"改文档内容"——那是导入/重索引的职责，通过工具改正文容易引入脏数据；
 *  - 默认知识库（id=default）不可删，与 UI 层 KnowledgeScreen 的保护一致；
 *  - 删文档同时清 chunks 与向量索引（复用 RagService.deleteDocument），不留孤儿。
 */
class KnowledgeManageTools(
    private val docDao: KnowledgeDocDao,
    private val kbDao: KnowledgeBaseDao,
    private val ragService: RagService,
) {
    companion object {
        private const val TAG = "KnowledgeManageTools"
    }

    fun register(registry: ToolRegistry) {
        registerListDocs(registry)
        registerDeleteDoc(registry)
        registerCreateKb(registry)
        registerDeleteKb(registry)
    }

    /** 列出知识库的文档清单（SAFE，只读）。 */
    private fun registerListDocs(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "knowledge_list_docs",
                description = "列出指定知识库的全部用户文档（id、标题、分块数、更新时间）。" +
                    "不传 kb_id 时列出默认知识库。用于删除前确认文档 id。",
                parameters = mapOf(
                    "kb_id" to "可选，知识库 id；省略时查默认知识库",
                ),
                required = emptySet(),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val kbId = args["kb_id"]?.trim().takeUnless { it.isNullOrBlank() } ?: "default"
            val docs = kotlinx.coroutines.runBlocking { docDao.getByKbIds(listOf(kbId)) }
            if (docs.isEmpty()) {
                "知识库 $kbId 中没有用户文档。"
            } else {
                docs.joinToString("\n") { doc ->
                    "${doc.id} | ${doc.title} | ${doc.chunkCount} 块 | ${doc.fileType}"
                }
            }
        }
    }

    /** 删除一篇知识库文档（HIGH，走审批）。 */
    private fun registerDeleteDoc(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "knowledge_delete_doc",
                description = "从知识库中删除一篇文档（连带其分块与向量索引）。" +
                    "执行前应先用 knowledge_list_docs 确认文档 id；不可撤销。",
                parameters = mapOf(
                    "doc_id" to "必填，文档 id（可用 knowledge_list_docs 获取）",
                ),
                required = setOf("doc_id"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val docId = args["doc_id"]?.trim().orEmpty()
            if (docId.isBlank()) {
                errorJson("参数 doc_id 缺失或为空")
            } else {
                kotlinx.coroutines.runBlocking {
                    val doc = docDao.getById(docId)
                    when {
                        doc == null -> errorJson("未找到文档 $docId")
                        doc.isInternal -> errorJson("文档 $docId 是内部开发文档，不允许通过工具删除")
                        else -> {
                            ragService.deleteDocument(docId)
                            Logger.i(TAG, "工具删除知识库文档: $docId (${doc.title})")
                            okJson("已删除文档 ${doc.title}（$docId），分块与向量索引一并清理。")
                        }
                    }
                }
            }
        }
    }

    /** 创建知识库（HIGH）。 */
    private fun registerCreateKb(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "knowledge_create_kb",
                description = "创建一个新的知识库。id 可省略（自动生成）；名称必填。" +
                    "默认知识库（id=default）不可重复创建。",
                parameters = mapOf(
                    "name" to "必填，知识库名称",
                    "kb_id" to "可选，知识库 id（省略时自动生成）",
                    "description" to "可选，知识库描述",
                ),
                required = setOf("name"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val name = args["name"]?.trim().orEmpty()
            if (name.isBlank()) {
                errorJson("参数 name 缺失或为空")
            } else {
                val id = args["kb_id"]?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "kb-${System.currentTimeMillis()}"
                kotlinx.coroutines.runBlocking {
                    val existing = kbDao.getById(id)
                    if (existing != null) {
                        errorJson("知识库 id=$id 已存在（名称：${existing.name}），请换一个 id 或直接使用它")
                    } else {
                        kbDao.upsert(
                            KnowledgeBaseEntity(
                                id = id,
                                name = name,
                                description = args["description"]?.trim().orEmpty(),
                            ),
                        )
                        Logger.i(TAG, "工具创建知识库: $id ($name)")
                        okJson("已创建知识库「$name」（id=$id）。现在可以往里导入文档了。")
                    }
                }
            }
        }
    }

    /** 删除知识库（HIGH，连带删其全部文档与文件夹）。 */
    private fun registerDeleteKb(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "knowledge_delete_kb",
                description = "删除一个知识库及其全部文档、文件夹与向量索引。不可撤销。" +
                    "默认知识库（id=default）不可删除。",
                parameters = mapOf(
                    "kb_id" to "必填，知识库 id（可用 knowledge_list_docs 前先确认）",
                ),
                required = setOf("kb_id"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val kbId = args["kb_id"]?.trim().orEmpty()
            when {
                kbId.isBlank() -> errorJson("参数 kb_id 缺失或为空")
                kbId == "default" -> errorJson("默认知识库不可删除（与 UI 层保护一致）")
                else -> {
                    kotlinx.coroutines.runBlocking {
                        val kb = kbDao.getById(kbId)
                        if (kb == null) {
                            errorJson("未找到知识库 $kbId")
                        } else {
                            val docs = docDao.getByKbIds(listOf(kbId))
                            docs.forEach { doc -> ragService.deleteDocument(doc.id) }
                            kbDao.delete(kbId)
                            Logger.i(TAG, "工具删除知识库: $kbId (${kb.name}), 文档 ${docs.size} 篇")
                            okJson("已删除知识库「${kb.name}」（$kbId）及其 ${docs.size} 篇文档。")
                        }
                    }
                }
            }
        }
    }

    private fun okJson(message: String): String = buildJsonObject {
        put("ok", JsonPrimitive(true))
        put("message", JsonPrimitive(message))
    }.toString()

    private fun errorJson(message: String): String = buildJsonObject {
        put("ok", JsonPrimitive(false))
        put("error", JsonPrimitive(message))
    }.toString()
}

/** 注册器：走 Koin 注入，由 AutomationInitializer 统一引导。 */
class KnowledgeManageToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val tools: KnowledgeManageTools,
) {
    init {
        tools.register(toolRegistry)
    }
}
