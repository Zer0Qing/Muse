package io.zer0.muse.tools

import androidx.room.withTransaction
import io.zer0.common.Logger
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.knowledge.KnowledgeBaseDao
import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkDao
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.data.knowledge.KnowledgeFolderDao
import io.zer0.muse.data.knowledge.KnowledgeFolderEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.rag.KnowledgeFolderBrowser
import io.zer0.muse.rag.RagService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 知识库级联写操作的唯一实现 —— UI（KnowledgeScreen）与工具（knowledge_delete_kb）共用。
 *
 * 分开写两份的代价是真实的：UI 删库只删了文档与文件夹行，助手的 knowledgeBaseIdsJson /
 * knowledgeFolderScopesJson 里还留着已不存在的 kbId，助手从此“配了库但永远搜不到”；
 * 工具删库又漏了文件夹表。所以删除、目录重命名/删除这类跨表动作只能有一个落地点。
 *
 * 约定：
 *  - 默认知识库不可删（与 UI、工具的保护一致）；
 *  - 内部文档（isInternal）不删，属于被删库时改挂默认库，不能成为孤儿；
 *  - 删除目录时文档回根，助手作用域同步重写或移除，不留空作用域（空数组在读取侧等于整库）。
 */
class KnowledgeCascade(
    private val docDao: KnowledgeDocDao,
    private val chunkDao: KnowledgeChunkDao,
    private val kbDao: KnowledgeBaseDao,
    private val folderDao: KnowledgeFolderDao,
    private val ragService: RagService,
    private val assistantRepository: AssistantRepository? = null,
    /** 可空：未注入数据库时退化为逐表删除（无单事务保证）。 */
    private val db: MuseDb? = null,
) {
    companion object {
        private const val TAG = "KnowledgeCascade"

        /** 与 [KnowledgeDocEntity.kbId] 默认值一致。 */
        const val DEFAULT_KB_ID = "default"
        private const val DELETE_CHUNK_BATCH_SIZE = 200
    }

    /** 删库结果。 */
    data class DeleteKbResult(
        val deletedDocs: Int,
        val reassignedInternalDocs: Int,
        val detachedAssistants: Int,
    )

    /** 目录操作结果 —— 用类型化结果表达业务冲突，不用异常。 */
    sealed interface FolderResult {
        data class Ok(val path: String) : FolderResult
        data object NameConflict : FolderResult
        data object NotFound : FolderResult
    }

    /** 删除知识库及其全部文档、文件夹、绑定与助手引用。默认库拒绝删除。 */
    suspend fun deleteKnowledgeBase(kbId: String): io.zer0.common.Result<DeleteKbResult> {
        if (kbId.isBlank()) {
            return io.zer0.common.Result.Error("kbId 不能为空")
        }
        if (kbId == DEFAULT_KB_ID) {
            return io.zer0.common.Result.Error("默认知识库不可删除")
        }
        if (kbDao.getById(kbId) == null) {
            return io.zer0.common.Result.Error("未找到知识库 $kbId")
        }
        // 业务拒绝与执行失败分开：前者是调用方的错，后者需要留日志与原因。
        // 放到 NonCancellable + Dispatchers.IO：删大型知识库时关弹窗或退出页面不得中断删库，
        // 同时用批量删除接口，只在结束时统一重写一次 HNSW 索引。
        val outcome = runCatching {
            withContext(NonCancellable + Dispatchers.IO) {
                inTransaction {
                    // 含 internal 文档：getByKbIds 会排掉它们，而“孤儿内部文档”正是要修的问题之一。
                    val allDocs = docDao.getAll().filter { it.kbId == kbId }
                    val userDocs = allDocs.filterNot { it.isInternal }
                    val internalDocs = allDocs.filter { it.isInternal }

                    val userDocIds = userDocs.map { it.id }
                    if (userDocIds.isNotEmpty()) {
                        ragService.deleteDocuments(userDocIds)
                        // SQLite 单次 IN 参数上限 999，分批兜底清理残余分块行
                        userDocIds.chunked(DELETE_CHUNK_BATCH_SIZE).forEach { batch ->
                            chunkDao.deleteByDocIds(batch)
                        }
                    }
                    internalDocs.forEach { doc ->
                        docDao.upsert(doc.copy(kbId = DEFAULT_KB_ID, updatedAt = System.currentTimeMillis()))
                    }
                    folderDao.deleteByKb(kbId)
                    kbDao.delete(kbId)
                    val detached = assistantRepository?.detachKnowledgeBase(kbId) ?: 0
                    Logger.i(
                        TAG,
                        "删除知识库 $kbId：文档 ${userDocs.size} 篇，内部文档改挂 ${internalDocs.size} 篇，" +
                            "同步助手 $detached 个",
                    )
                    DeleteKbResult(
                        deletedDocs = userDocs.size,
                        reassignedInternalDocs = internalDocs.size,
                        detachedAssistants = detached,
                    )
                }
            }
        }
        return outcome.fold(
            onSuccess = { io.zer0.common.Result.Success(it) },
            onFailure = { error ->
                Logger.w(TAG, "删除知识库失败: $kbId", error)
                io.zer0.common.Result.Error(error.message ?: "删除知识库失败", error)
            },
        )
    }

    /** 新建文件夹；同父同名已存在时返回 [FolderResult.NameConflict]，不静默覆盖。 */
    suspend fun createFolder(kbId: String, path: String): FolderResult {
        val normalized = KnowledgeFolderBrowser.normalize(path)
        if (kbId.isBlank() || normalized.isEmpty()) return FolderResult.NotFound
        if (folderExists(kbId, normalized)) return FolderResult.NameConflict
        folderDao.upsert(
            KnowledgeFolderEntity(
                id = "kbfolder-$kbId-$normalized",
                kbId = kbId,
                path = normalized,
                name = normalized.substringAfterLast('/'),
            ),
        )
        return FolderResult.Ok(normalized)
    }

    /** 重命名文件夹（含子目录）：文件夹行、文档 folderPath、助手作用域一起改。 */
    suspend fun renameFolder(kbId: String, oldPath: String, newName: String): FolderResult = withContext(NonCancellable + Dispatchers.IO) {
        inTransaction {
            val old = KnowledgeFolderBrowser.normalize(oldPath)
            val name = newName.trim()
            if (kbId.isBlank() || old.isEmpty() || name.isEmpty()) return@inTransaction FolderResult.NotFound
            val parent = KnowledgeFolderBrowser.parent(old)
            val newPath = KnowledgeFolderBrowser.normalize(if (parent.isEmpty()) name else "$parent/$name")
            if (newPath == old) return@inTransaction FolderResult.Ok(newPath)
            if (!folderExists(kbId, old)) return@inTransaction FolderResult.NotFound
            if (folderExists(kbId, newPath)) return@inTransaction FolderResult.NameConflict
            val now = System.currentTimeMillis()
            // 显式文件夹行：整棵子树换前缀
            folderDao.getByPathRecursive(kbId, old).forEach { folder ->
                val moved = newPath + folder.path.removePrefix(old)
                folderDao.deleteById(folder.id)
                folderDao.upsert(
                    folder.copy(
                        id = "kbfolder-$kbId-$moved",
                        path = moved,
                        name = moved.substringAfterLast('/'),
                        updatedAt = now,
                    ),
                )
            }
            docDao.getAll()
                .filter { it.kbId == kbId }
                .forEach { doc ->
                    val path = KnowledgeFolderBrowser.folderPath(doc)
                    if (path == old || path.startsWith("$old/")) {
                        docDao.upsert(
                            KnowledgeFolderBrowser.withFolderPath(doc, newPath + path.removePrefix(old))
                                .copy(updatedAt = now),
                        )
                    }
                }
            assistantRepository?.rewriteKnowledgeFolderScopes(kbId, old, newPath)
            FolderResult.Ok(newPath)
        }
    }

    /** 删除文件夹（含子目录）：文档回根，助手作用域里指向它的条目一并移除。 */
    suspend fun deleteFolder(kbId: String, path: String): FolderResult = withContext(NonCancellable + Dispatchers.IO) {
        inTransaction {
            val target = KnowledgeFolderBrowser.normalize(path)
            if (kbId.isBlank() || target.isEmpty()) return@inTransaction FolderResult.NotFound
            if (!folderExists(kbId, target)) return@inTransaction FolderResult.NotFound
            val now = System.currentTimeMillis()
            folderDao.deleteByPathRecursive(kbId, target)
            docDao.getAll()
                .filter { it.kbId == kbId }
                .forEach { doc ->
                    val docPath = KnowledgeFolderBrowser.folderPath(doc)
                    if (docPath == target || docPath.startsWith("$target/")) {
                        docDao.upsert(
                            KnowledgeFolderBrowser.withFolderPath(doc, "").copy(updatedAt = now),
                        )
                    }
                }
            assistantRepository?.rewriteKnowledgeFolderScopes(kbId, target, newPath = null)
            FolderResult.Ok(target)
        }
    }

    /** 同层同名判定：显式文件夹行，或已被文档路径隐式占用的目录都算已存在。 */
    private suspend fun folderExists(kbId: String, path: String): Boolean {
        if (folderDao.getByPath(kbId, path) != null) return true
        return docDao.getAll().any { doc ->
            doc.kbId == kbId && KnowledgeFolderBrowser.folderPath(doc) == path
        }
    }

    private suspend fun <T> inTransaction(block: suspend () -> T): T {
        val database = db
        return if (database == null) block() else database.withTransaction { block() }
    }
}

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
    /**
     * 删库级联（文档/分块/文件夹/助手引用）。未注入时从容器按需解析；
     * 容器也不可用时才退化为只删文档+库行（并留 WARN 日志）。
     */
    private val cascade: KnowledgeCascade? = null,
) {
    /** 级联实现：优先用注入的那个，否则按需从容器组装（不要求在装配处手写参数）。 */
    private val effectiveCascade: KnowledgeCascade? by lazy {
        cascade ?: resolveCascadeFromContainer()
    }

    private fun resolveCascadeFromContainer(): KnowledgeCascade? {
        val koin = org.koin.core.context.GlobalContext.getOrNull() ?: return null
        return runCatching {
            // 显式写类型：类型推断在多参数构造器上容易取错定义
            KnowledgeCascade(
                docDao = koin.get<KnowledgeDocDao>(),
                chunkDao = koin.get<KnowledgeChunkDao>(),
                kbDao = koin.get<KnowledgeBaseDao>(),
                folderDao = koin.get<KnowledgeFolderDao>(),
                ragService = koin.get<RagService>(),
                assistantRepository = koin.get<AssistantRepository>(),
                db = koin.get<MuseDb>(),
            )
        }.onFailure { t ->
            Logger.w(TAG, "无法从容器组装知识库级联实现: ${t.message}", t)
        }.getOrNull()
    }

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
                    "kb_id" to "知识库 id；省略时查默认知识库",
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
                    "doc_id" to "文档 id（可用 knowledge_list_docs 获取）",
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
                    "name" to "知识库名称",
                    "kb_id" to "知识库 id（省略时自动生成）",
                    "description" to "知识库描述",
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
                    "kb_id" to "知识库 id（可用 knowledge_list_docs 前先确认）",
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
                            // 与 UI 删库走同一实现：文档/分块/文件夹/助手引用一次清完
                            val removed = effectiveCascade?.deleteKnowledgeBase(kbId)
                            if (removed == null) {
                                docDao.getByKbIds(listOf(kbId)).forEach { doc -> ragService.deleteDocument(doc.id) }
                                kbDao.delete(kbId)
                                Logger.w(TAG, "未注入级联实现，删库 $kbId 只清理了文档与库行")
                                okJson("已删除知识库「${kb.name}」（$kbId）。")
                            } else {
                                removed.onError { message, t ->
                                    Logger.w(TAG, "工具删除知识库失败: $kbId: $message", t)
                                }
                                removed.getOrNull()?.let { result ->
                                    Logger.i(
                                        TAG,
                                        "工具删除知识库: $kbId (${kb.name}), 文档 ${result.deletedDocs} 篇",
                                    )
                                    okJson(
                                        "已删除知识库「${kb.name}」（$kbId）及其 ${result.deletedDocs} 篇文档。" +
                                            if (result.detachedAssistants > 0) {
                                                "已同步 ${result.detachedAssistants} 个助手的知识库绑定。"
                                            } else {
                                                ""
                                            },
                                    )
                                } ?: errorJson("删除知识库 $kbId 失败：${removed.messageOrUnknown()}")
                            }
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

    /** [io.zer0.common.Result] 的错误文案（成功分支不会走到这里）。 */
    private fun io.zer0.common.Result<*>.messageOrUnknown(): String = when (this) {
        is io.zer0.common.Result.Error -> message
        is io.zer0.common.Result.Success -> "unknown"
    }

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
