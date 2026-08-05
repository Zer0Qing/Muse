package io.zer0.muse.ui.knowledge

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.form.MuseFloatingButton
import io.zer0.muse.ui.common.feedback.MuseToast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.zer0.muse.ui.common.navigation.MuseTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.statusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// L-KUI1: 内容截断上限,防止超大文档导致 OOM
private const val MAX_CONTENT_LENGTH = 500_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeScreen(
    onBack: () -> Unit,
    dao: KnowledgeDocDao = koinInject(),
    ragService: io.zer0.muse.rag.RagService = koinInject(),
    documentParser: io.zer0.muse.doc.DocumentParser = koinInject(),
    ocrManager: io.zer0.muse.doc.OcrManager = koinInject(),
    settings: io.zer0.muse.data.SettingsRepository = koinInject(),
    // v1.0.53: 封面库路由回调(文档详情 → 封面管理页)
    onOpenCoverManager: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    // v1.66: 知识库排序切换(原仅按 updated_at DESC,现支持 4 种排序)
    var sortMode by remember { mutableStateOf(KnowledgeSortMode.UPDATED) }
    // v1.0.62: 下拉刷新键,自增会重建 DAO flow 触发重新查询
    var refreshKey by remember { mutableStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    // v1.48: h13 首次加载用 null 显示加载态;搜索场景保留 emptyList,避免每次输入闪加载态
    // M-KB1: 搜索时转义 LIKE 通配符(% _ \),配合 DAO 的 ESCAPE '\' 子句
    // 用 observeAllUser() 在 DB 层排除 is_internal=true 的内部开发文档,避免向用户暴露
    val docs by remember(searchQuery, refreshKey) {
        if (searchQuery.isBlank()) dao.observeAllUser() else dao.search(io.zer0.muse.data.knowledge.KnowledgeDocDao.escapeLikeQuery(searchQuery))
    }.collectAsStateWithLifecycle(initialValue = if (searchQuery.isBlank()) null else emptyList())
    // v1.0.62: 下拉刷新时短暂显示指示器,让重新查询有可感知反馈
    LaunchedEffect(refreshKey) {
        if (refreshKey > 0) {
            isRefreshing = true
            delay(500)
            isRefreshing = false
        }
    }
    var detailTarget by remember { mutableStateOf<KnowledgeDocEntity?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf("") }
    // v1.67-B: 导入可取消 — 保存 Job 引用,取消时清理半成品文档
    var importJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // v1.67-A: 重新索引状态
    var reindexing by remember { mutableStateOf(false) }
    var reindexProgress by remember { mutableStateOf("") }
    var reindexTarget by remember { mutableStateOf<KnowledgeDocEntity?>(null) }
    // v1.0.53: AI 封面生成状态
    var generatingCover by remember { mutableStateOf(false) }
    val coverGenerator: io.zer0.muse.tools.CoverGenerator = koinInject()
    // v1.0.47 P7-3: 文件过大友好提示
    var fileSizeWarning by remember { mutableStateOf<Pair<String, String>?>(null) }

    // v1.54: 支持导入 txt/md/pdf/docx/epub/图片(OCR),导入后自动分块+生成 embedding 向量索引
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // v1.67-B: 保存 Job 引用以便取消
        importJob = scope.launch {
            importing = true
            importProgress = context.getString(R.string.knowledge_reading_file)
            val now = System.currentTimeMillis()
            var createdDocId: String? = null
            try {
                // L-KUI2: 用 ContentResolver 获取 DISPLAY_NAME,比 lastPathSegment 更可靠
                val fileName = withContext(Dispatchers.IO) {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                    } ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast('%')
                } ?: "doc-$now"
                // v1.0.47 P7-3: 文件大小检查 — 超限弹出友好提示,不继续导入
                val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024  // 50MB
                val fileSize = withContext(Dispatchers.IO) {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (idx >= 0 && cursor.moveToFirst()) cursor.getLong(idx) else -1L
                    } ?: -1L
                }
                if (fileSize > MAX_FILE_SIZE_BYTES) {
                    importing = false
                    fileSizeWarning = formatFileSize(fileSize) to formatFileSize(MAX_FILE_SIZE_BYTES)
                    return@launch
                }
                val lowerName = fileName.lowercase()
                val parserConfig = try {
                    settings.getRagConfig()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: java.io.IOException) {
                    Logger.w("KnowledgeScreen", "读取 RAG 配置失败: ${e.message}")
                    io.zer0.muse.rag.RagConfig()
                }
                // 根据扩展名选择解析方式
                val content = when {
                    lowerName.endsWith(".pdf") || lowerName.endsWith(".docx") ||
                    lowerName.endsWith(".doc") || lowerName.endsWith(".epub") ||
                        lowerName.endsWith(".pptx") -> {
                        importProgress = context.getString(R.string.knowledge_parsing_doc)
                        val parsed = withContext(Dispatchers.IO) {
                            documentParser.parseResult(
                                uri,
                                context,
                                parserConfig.documentParserType,
                                parserConfig.cloudParserEndpoint,
                                parserConfig.mineruEndpoint,
                                parserConfig.mineruToken,
                            )
                        }
                        if (parsed is io.zer0.common.Result.Error) {
                            MuseToast.show(parsed.message)
                            return@launch
                        }
                        val text = parsed.getOrNull()
                        if (text.isNullOrBlank()) {
                            MuseToast.show(context.getString(R.string.knowledge_import_empty))
                            return@launch
                        }
                        text
                    }
                    lowerName.endsWith(".png") || lowerName.endsWith(".jpg") ||
                        lowerName.endsWith(".jpeg") || lowerName.endsWith(".bmp") ||
                        lowerName.endsWith(".webp") -> {
                        importProgress = context.getString(R.string.knowledge_ocr)
                        ocrManager.recognize(uri, context)
                    }
                    else -> {
                        // txt/md/csv/json 等纯文本
                        // v1.73: 用 use{} 确保 InputStream/BufferedReader 关闭,避免 FD 泄漏
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                // v1.114: 限制读取 10MB,防止超大文件 OOM
                                val MAX_READ_BYTES = 10L * 1024 * 1024
                                val sb = StringBuilder()
                                val buffer = CharArray(8192)
                                var total = 0L
                                input.bufferedReader().use { reader ->
                                    while (true) {
                                        val read = reader.read(buffer)
                                        if (read <= 0) break
                                        total += read
                                        if (total > MAX_READ_BYTES) {
                                            error("文件过大,超过 ${MAX_READ_BYTES / 1024 / 1024}MB 限制")
                                        }
                                        sb.append(buffer, 0, read)
                                    }
                                }
                                sb.toString()
                            }
                        }
                    }
                }
                if (content.isNullOrBlank()) {
                    MuseToast.show(context.getString(R.string.knowledge_import_empty))
                    return@launch
                }
                val fileType = when {
                    lowerName.endsWith(".md") || lowerName.endsWith(".markdown") -> "md"
                    lowerName.endsWith(".pdf") -> "pdf"
                    lowerName.endsWith(".docx") || lowerName.endsWith(".doc") -> "docx"
                    lowerName.endsWith(".epub") -> "epub"
                    lowerName.endsWith(".csv") -> "csv"
                    lowerName.endsWith(".json") -> "json"
                    lowerName.endsWith(".png") || lowerName.endsWith(".jpg") ||
                        lowerName.endsWith(".jpeg") -> "ocr"
                    else -> "txt"
                }
                val docId = "doc-$now"
                createdDocId = docId
                // L-KUI1: 提取常量,截断时记录日志提示
                val truncatedContent = if (content.length > MAX_CONTENT_LENGTH) {
                    Logger.w("KnowledgeScreen", "内容超过 $MAX_CONTENT_LENGTH 字符,已截断(原 ${content.length} 字)")
                    content.take(MAX_CONTENT_LENGTH)
                } else content
                dao.upsert(
                    KnowledgeDocEntity(
                        id = docId,
                        title = fileName,
                        content = truncatedContent,
                        filePath = uri.toString(),
                        fileType = fileType,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                // v1.54: 自动分块 + 生成 embedding 向量索引
                importProgress = context.getString(R.string.knowledge_chunking)
                val ragConfig = parserConfig
                // v1.103: 不再静默吞异常。runCatching 失败时把原因拼进 toast,
                // 让用户知道为什么没建索引(网络/模型名/Provider 不兼容等),而不是只看到"已导入但未索引"。
                var indexError: Throwable? = null
                var indexErrorMsg: String? = null
                val indexResult = resultOf {
                    ragService.indexDocument(docId, truncatedContent, ragConfig) { current, total ->
                        importProgress = context.getString(R.string.knowledge_generating_vector, current, total)
                    }
                }.onError { msg, t -> indexErrorMsg = msg; indexError = t }
                val chunkCount = indexResult.getOrNull()
                if (chunkCount != null && chunkCount > 0) {
                    // 更新文档的分块数和 embedding 模型
                    val modelName = ragConfig.cloudModel.ifBlank { "cloud-default" }
                    dao.upsert(
                        (dao.getById(docId) ?: return@launch).copy(
                            chunkCount = chunkCount,
                            embeddingModel = modelName,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    MuseToast.show(context.getString(R.string.knowledge_imported_indexed, fileName, chunkCount))
                } else {
                    // v1.103: 索引失败时显示具体原因,引导用户去 RAG 设置检查配置
                    val reason = indexErrorMsg?.take(100)
                    val msg = if (reason.isNullOrBlank()) {
                        context.getString(R.string.knowledge_imported_no_index, fileName)
                    } else {
                        "${context.getString(R.string.knowledge_imported_no_index, fileName)}\n原因:$reason"
                    }
                    MuseToast.show(msg)
                    Logger.w("KnowledgeScreen", "indexDocument failed for $fileName", indexError)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // v1.67-B: 用户取消导入,清理半成品文档 + 已生成的分块
                createdDocId?.let { id ->
                    resultOf { dao.deleteDocWithChunks(id) }
                }
                MuseToast.show(context.getString(R.string.knowledge_import_cancelled))
                throw e
            } catch (e: java.io.IOException) {
                MuseToast.show(context.getString(R.string.knowledge_import_failed_read))
            } catch (e: Exception) {
                MuseToast.show(context.getString(R.string.knowledge_import_failed, e.message?.take(80) ?: ""))
            } finally {
                importing = false
                importProgress = ""
                importJob = null
            }
        }
    }

    // v1.67-A: 重新索引已有文档(更换 embedding 模型后或索引失败时可用)
    fun reindexDoc(doc: KnowledgeDocEntity) {
        reindexTarget = doc
        reindexing = true
        reindexProgress = context.getString(R.string.knowledge_reindex_progress)
        scope.launch {
            try {
                // M-KUI1: 移除 runCatching,改为 try-catch 并重抛 CancellationException
                val ragConfig = try {
                    settings.getRagConfig()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: java.io.IOException) {
                    Logger.w("KnowledgeScreen", "读取 RAG 配置失败: ${e.message}")
                    io.zer0.muse.rag.RagConfig()
                }
                val chunkCount = ragService.indexDocument(doc.id, doc.content, ragConfig) { current, total ->
                    reindexProgress = context.getString(R.string.knowledge_generating_vector, current, total)
                }
                if (chunkCount > 0) {
                    val modelName = ragConfig.cloudModel.ifBlank { "cloud-default" }
                    dao.upsert(
                        (dao.getById(doc.id) ?: return@launch).copy(
                            chunkCount = chunkCount,
                            embeddingModel = modelName,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    MuseToast.show(context.getString(R.string.knowledge_reindexed, doc.title, chunkCount))
                } else {
                    MuseToast.show(context.getString(R.string.knowledge_reindex_failed, doc.title))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                MuseToast.show(context.getString(R.string.knowledge_reindex_failed, e.message?.take(80) ?: ""))
            } finally {
                reindexing = false
                reindexProgress = ""
                reindexTarget = null
            }
        }
    }

    // R-UI-01: 修复/重建 FTS 索引(清影子表重建 + 全量重索引当前文档)
    fun repairKnowledgeFts() {
        reindexTarget = null
        reindexing = true
        reindexProgress = context.getString(R.string.knowledge_repairing)
        scope.launch {
            try {
                val ragConfig = try {
                    settings.getRagConfig()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: java.io.IOException) {
                    Logger.w("KnowledgeScreen", "读取 RAG 配置失败: ${e.message}")
                    io.zer0.muse.rag.RagConfig()
                }
                val failures = ragService.repairKnowledgeFtsIndex(ragConfig) { current, total ->
                    reindexProgress = context.getString(R.string.knowledge_generating_vector, current, total)
                }
                if (failures.isEmpty()) {
                    MuseToast.show(context.getString(R.string.knowledge_fts_repaired))
                } else {
                    MuseToast.show(context.getString(R.string.knowledge_fts_repaired_partial, failures.size))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                MuseToast.show(context.getString(R.string.knowledge_fts_repair_failed, e.message?.take(80) ?: ""))
            } finally {
                reindexing = false
                reindexProgress = ""
                reindexTarget = null
            }
        }
    }

    /**
     * v1.0.53: AI 生成封面并写回文档 frontmatter。
     *
     * 流程: CoverGenerator 生图入库 → FrontmatterParser.withCover 写回 →
     * dao.upsert 持久化 → 刷新列表。生成失败 Toast 提示,不残留状态。
     */
    fun generateCoverFor(doc: KnowledgeDocEntity) {
        if (generatingCover) return
        generatingCover = true
        scope.launch {
            try {
                var failureMsg: String? = null
                val item = coverGenerator.generateCover(
                    title = doc.title,
                    description = doc.content.take(200),
                ).onError { msg, _ -> failureMsg = msg }.getOrNull()
                if (item == null) {
                    MuseToast.show(
                        context.getString(
                            if (failureMsg?.contains("未配置绘图模型") == true) {
                                R.string.cover_generate_no_provider
                            } else {
                                R.string.cover_generate_failed
                            },
                            failureMsg?.take(60) ?: "",
                        ),
                    )
                    return@launch
                }
                // 写回 frontmatter(covers/ 相对路径)
                val newContent = io.zer0.muse.common.markdown.FrontmatterParser.withCover(
                    doc.content,
                    "covers/${item.fileName}",
                )
                dao.upsert(doc.copy(content = newContent, updatedAt = System.currentTimeMillis()))
                MuseToast.show(context.getString(R.string.cover_applied))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                MuseToast.show(context.getString(R.string.cover_generate_failed, e.message?.take(60) ?: ""))
            } finally {
                generatingCover = false
            }
        }
    }

    Scaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.knowledge_title),
                onBack = onBack,
                largeTitle = true,
                actions = {
                    // v1.66: 排序切换入口(iOS 风格动作弹窗)
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = stringResource(R.string.knowledge_sort))
                    }
                    IconButton(
                        onClick = { repairKnowledgeFts() },
                        enabled = !reindexing,
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.knowledge_repair_index),
                            tint = if (reindexing) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            MuseFloatingButton(
                icon = Icons.Default.Add,
                onClick = { if (!importing) importLauncher.launch("*/*") },
                contentDescription = stringResource(R.string.knowledge_import),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = MusePaddings.screen),
        ) {
            // iOS 风格搜索栏(surfaceVariant 背景 + 无框输入 + 圆角 + 搜索/清空图标)
            Surface(
                shape = MuseShapes.semiLarge,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = stringResource(R.string.knowledge_search_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.knowledge_clear),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(MusePaddings.itemGap))

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { refreshKey++ },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                val docsList = docs
                if (docsList == null) {
                    // v1.48: h13 首次加载显示居中加载指示器,避免闪空状态
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    // v0.43: 隐藏开发文档(isInternal=true 的内部文档只供 LLM 通过 knowledge_search 查询,不向用户展示)
                    // v1.133: 改用 isInternal 字段判断(替代原 fileType="devdoc" 硬编码,与 MIGRATION_38_39 标记一致)
                    // v1.66: 按 sortMode 排序(DAO 仅 updated_at DESC,其余维度在 UI 排序)
                    // M-Kn1: 用 remember 缓存 filter+sort 结果,避免每次重组都重算
                    val visibleDocs = remember(docsList, sortMode) {
                        docsList
                            .filterNot { it.isInternal }
                            .sortedWith(sortMode.comparator)
                    }
                    if (visibleDocs.isEmpty()) {
                        MuseEmptyState(
                            icon = if (searchQuery.isNotBlank()) Icons.Outlined.Description else Icons.AutoMirrored.Outlined.MenuBook,
                            title = if (searchQuery.isNotBlank()) stringResource(R.string.knowledge_no_match_title) else stringResource(R.string.knowledge_empty_title),
                            subtitle = if (searchQuery.isNotBlank()) stringResource(R.string.knowledge_no_match_subtitle) else stringResource(R.string.knowledge_empty_subtitle),
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 80.dp),
                        ) {
                            items(visibleDocs, key = { it.id }) { doc ->
                                DocCard(
                                    doc = doc,
                                    highlight = searchQuery.takeIf { it.isNotBlank() },
                                    onClick = { detailTarget = doc },
                                    onDelete = {
                                        scope.launch {
                                            dao.deleteDocWithChunks(doc.id)
                                        }
                                        MuseToast.show(context.getString(R.string.knowledge_deleted))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    detailTarget?.let { doc ->
        DocDetailDialog(
            doc = doc,
            onDismiss = { detailTarget = null },
            onReindex = { reindexDoc(doc) },
            onOpenCoverManager = onOpenCoverManager,
            onGenerateCover = { generateCoverFor(doc) },
            generatingCover = generatingCover,
        )
    }

    if (importing) {
        MuseDialog(
            // v1.67-B: 导入可取消
            onDismissRequest = { importJob?.cancel() },
            title = stringResource(R.string.knowledge_importing),
            content = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(importProgress.ifBlank { stringResource(R.string.knowledge_reading_default) }, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmText = stringResource(R.string.knowledge_cancel),
            onConfirm = { importJob?.cancel() },
            dismissText = null,
        )
    }

    // v1.0.47 P7-3: 文件过大友好提示弹窗
    fileSizeWarning?.let { (actual, limit) ->
        MuseDialog(
            onDismissRequest = { fileSizeWarning = null },
            title = stringResource(R.string.knowledge_file_too_large_title),
            content = {
                Text(
                    text = stringResource(R.string.knowledge_file_too_large_msg, actual, limit),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmText = stringResource(R.string.knowledge_file_too_large_confirm),
            onConfirm = { fileSizeWarning = null },
            dismissText = null,
        )
    }

    // v1.67-A: 重新索引进度弹窗
    if (reindexing) {
        MuseDialog(
            onDismissRequest = { /* 重新索引不可中断,避免半成品状态 */ },
            title = stringResource(R.string.knowledge_reindexing),
            content = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        reindexProgress.ifBlank { stringResource(R.string.knowledge_reindexing_default) },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            // 不可中断,无按钮(onConfirm=null 隐藏主按钮,dismissText=null 隐藏次按钮)
            onConfirm = null,
            dismissText = null,
        )
    }

    // 排序选择弹窗
    if (showSortMenu) {
        MuseDialog(
            onDismissRequest = { showSortMenu = false },
            title = stringResource(R.string.knowledge_sort),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    KnowledgeSortMode.entries.forEach { mode ->
                        val selected = mode == sortMode
                        Surface(
                            onClick = {
                                sortMode = mode
                                showSortMenu = false
                            },
                            shape = MuseShapes.semiLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = MusePaddings.iconPadding,
                                        vertical = MusePaddings.inputPadding,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
                            ) {
                                Icon(
                                    imageVector = mode.icon,
                                    contentDescription = null,
                                    tint = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                                Text(
                                    text = stringResource(mode.labelRes),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    ),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showSortMenu = false },
        )
    }
}

private data class FileTypeStyle(
    val icon: ImageVector,
    val iconColor: Color,
    val containerColor: Color,
)

@Composable
private fun fileTypeStyle(fileType: String): FileTypeStyle {
    // v1.0.52: 文档类型色接入语义状态色,深色模式自动切亮档保证图标可读
    val statusColors = MaterialTheme.statusColors
    return when (fileType.lowercase(Locale.getDefault())) {
        "pdf" -> FileTypeStyle(
            icon = Icons.Outlined.Description,
            iconColor = statusColors.error,
            containerColor = statusColors.error.copy(alpha = 0.10f),
        )
        "md", "markdown" -> FileTypeStyle(
            icon = Icons.Outlined.Description,
            iconColor = statusColors.info,
            containerColor = statusColors.info.copy(alpha = 0.10f),
        )
        "txt" -> FileTypeStyle(
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            iconColor = statusColors.success,
            containerColor = statusColors.success.copy(alpha = 0.10f),
        )
        else -> FileTypeStyle(
            icon = Icons.Outlined.Description,
            iconColor = statusColors.neutral,
            containerColor = statusColors.neutral.copy(alpha = 0.10f),
        )
    }
}

@Composable
private fun DocCard(
    doc: KnowledgeDocEntity,
    highlight: String?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val style = fileTypeStyle(doc.fileType)
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = MuseElevation.card,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(MusePaddings.cardInnerLoose),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color = style.containerColor, shape = MuseShapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.iconColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    doc.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // v1.71: 用 remember 缓存 SimpleDateFormat,避免列表项重组都新建对象
                val dateFmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_SHORT, Locale.getDefault()) }
                Text(
                    stringResource(R.string.knowledge_doc_meta_with_date, doc.fileType, doc.content.length, dateFmt.format(Date(doc.createdAt))),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.knowledge_delete),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.knowledge_delete_doc),
            itemName = doc.title,
            onConfirm = { showDeleteConfirm = false; onDelete() },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

@Composable
private fun DocDetailDialog(
    doc: KnowledgeDocEntity,
    onDismiss: () -> Unit,
    onReindex: () -> Unit,
    // v1.0.53: 封面能力(打开封面库 / 生成封面 / 生成中状态)
    onOpenCoverManager: () -> Unit = {},
    onGenerateCover: () -> Unit = {},
    generatingCover: Boolean = false,
) {
    // v1.0.53: 封面区解析需要 context(filesDir/covers)
    val context = LocalContext.current
    // v1.0.4 P2-2:从 doc.content 中分离 PDF 元数据块 / 目录块 / 正文,
    // 让用户在详情弹窗里看到结构化的文档信息卡片(而不是混在等宽正文中)。
    // DocumentParser 输出格式:[文档信息]\n字段: 值\n...\n\n[目录]\n• 条目\n...\n\n<正文>
    val parsed by remember(doc.id, doc.content) {
        mutableStateOf(parsePdfStructuredContent(doc.content, doc.fileType))
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = doc.title,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                // v1.0.53: 封面区(frontmatter cover 字段 → filesDir/covers/)
                val coverPath = io.zer0.muse.common.markdown.FrontmatterParser.parse(doc.content)?.cover
                val coverFile = coverPath?.let { path ->
                    if (path.startsWith("covers/")) {
                        java.io.File(java.io.File(context.filesDir, "covers"), path.removePrefix("covers/"))
                    } else null
                }
                if (coverFile != null && coverFile.exists()) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 5f)
                            .clip(MuseShapes.large),
                    ) {
                        coil.compose.AsyncImage(
                            model = coverFile,
                            contentDescription = doc.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // v1.0.53: 封面操作行(封面库 + 生成封面)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = onOpenCoverManager,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.cover_import))
                    }
                    androidx.compose.material3.Button(
                        onClick = onGenerateCover,
                        enabled = !generatingCover,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (generatingCover) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (generatingCover) stringResource(R.string.cover_generating) else stringResource(R.string.cover_generate))
                    }
                }
                Spacer(Modifier.height(10.dp))

                Text(
                    // v1.67-A: 显示分块数和 embedding 模型,让用户判断是否需要重新索引
                    text = buildString {
                        append(stringResource(R.string.knowledge_doc_chars, doc.fileType, doc.content.length))
                        append(if (doc.chunkCount > 0) stringResource(R.string.knowledge_doc_chunks, doc.chunkCount) else stringResource(R.string.knowledge_doc_not_indexed))
                        if (doc.embeddingModel.isNotBlank()) append(" · ${doc.embeddingModel}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))

                // v1.0.4 P2-2:PDF 元数据卡片(仅在有元数据时展示)
                if (parsed.metadata.isNotEmpty()) {
                    PdfMetadataCard(metadata = parsed.metadata)
                    Spacer(Modifier.height(8.dp))
                }
                // v1.0.4 P2-2:PDF 目录卡片(可折叠,仅在有目录时展示)
                if (parsed.outline.isNotEmpty()) {
                    PdfOutlineCard(outline = parsed.outline)
                    Spacer(Modifier.height(8.dp))
                }

                // 正文(无元数据/目录时退化为原始 content 展示,保持兼容)
                val bodyText = parsed.body
                Text(
                    bodyText,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MuseMonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmText = stringResource(R.string.knowledge_close),
        onConfirm = onDismiss,
        // v1.67-A: 重新索引入口(更换 embedding 模型后或首次索引失败时可用)
        dismissText = stringResource(R.string.knowledge_reindex),
        onDismiss = {
            onReindex()
            onDismiss()
        },
    )
}

/**
 * v1.0.4 P2-2:从 DocumentParser 输出的拼接文本中分离 PDF 元数据 / 目录 / 正文。
 *
 * DocumentParser.readPdf 的输出格式(见 doc/DocumentParser.kt:242-258):
 * ```
 * [文档信息]
 * 标题: xxx
 * 作者: xxx
 *
 * [目录]
 * • 第一章
 *   • 1.1 节
 *
 * <正文>
 * ```
 * 本函数把三块拆开,供 UI 分别结构化渲染。非 PDF 文档(fileType != "pdf")
 * 直接把整个 content 当作 body 返回,保持原有展示行为。
 */
private fun parsePdfStructuredContent(content: String, fileType: String): ParsedPdfContent {
    if (fileType.lowercase(Locale.getDefault()) != "pdf" || content.isBlank()) {
        return ParsedPdfContent(body = content)
    }
    // 仅在 content 以 [文档信息] 或 [目录] 开头时才解析(避免误吞普通正文)
    if (!content.startsWith("[文档信息]") && !content.startsWith("[目录]")) {
        return ParsedPdfContent(body = content)
    }

    val metadata = LinkedHashMap<String, String>()
    val outline = mutableListOf<String>()
    val bodyBuilder = StringBuilder()

    var section = "meta" // meta / outline / body
    content.lineSequence().forEach { line ->
        when {
            line == "[文档信息]" -> section = "meta"
            line == "[目录]" -> section = "outline"
            // 空行作为 section 切换的隐式分隔(DocumentParser 在块之间会输出空行)
            line.isBlank() && section != "body" -> {
                // 不立即切换:等遇到非 [xxx] 且非空行时再决定
            }
            section == "meta" && line.contains(": ") -> {
                val idx = line.indexOf(": ")
                if (idx > 0) {
                    metadata[line.substring(0, idx).trim()] = line.substring(idx + 2).trim()
                }
            }
            section == "outline" && line.startsWith("•") -> {
                outline.add(line.trimStart(' ', '•').trim())
            }
            section == "outline" && line.startsWith("  •") -> {
                outline.add("  " + line.trimStart(' ', '•').trim())
            }
            else -> {
                // 遇到非元数据/非目录条目,切换到 body 模式
                if (section != "body") section = "body"
                bodyBuilder.appendLine(line)
            }
        }
    }

    return ParsedPdfContent(
        metadata = metadata,
        outline = outline,
        body = bodyBuilder.toString().trim(),
    )
}

/** [parsePdfStructuredContent] 的解析结果。 */
private data class ParsedPdfContent(
    val metadata: Map<String, String> = emptyMap(),
    val outline: List<String> = emptyList(),
    val body: String = "",
)

/**
 * PDF 元数据卡片 — 字段名 + 值成对展示,limit 8 行避免过长。
 */
@Composable
private fun PdfMetadataCard(metadata: Map<String, String>) {
    Surface(
        shape = MuseShapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "文档信息",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(6.dp))
            metadata.forEach { (k, v) ->
                Text(
                    text = "$k: $v",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * PDF 目录卡片 — 默认折叠(避免占用过多弹窗空间),点击标题切换展开/折叠。
 */
@Composable
private fun PdfOutlineCard(outline: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = MuseShapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 标题行可点击,切换展开/折叠
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "目录(${outline.size} 条)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                outline.forEach { item ->
                    val indent = if (item.startsWith("  ")) 16.dp else 0.dp
                    Text(
                        text = "• " + item.trimStart(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = indent),
                    )
                }
            }
        }
    }
}

/**
 * v1.66: 知识库排序模式。
 * DAO 仅支持 updated_at DESC,其余维度在 UI 层用 comparator 排序。
 */
enum class KnowledgeSortMode(
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val comparator: Comparator<KnowledgeDocEntity>,
) {
    UPDATED(R.string.knowledge_sort_updated, Icons.Default.AccessTime, compareByDescending { it.updatedAt }),
    CREATED(R.string.knowledge_sort_created, Icons.Default.CalendarToday, compareByDescending { it.createdAt }),
    TITLE(R.string.knowledge_sort_title, Icons.Default.SortByAlpha, compareBy { it.title.lowercase(Locale.getDefault()) }),
    SIZE(R.string.knowledge_sort_size, Icons.Default.Storage, compareByDescending { it.content.length }),
}

/**
 * v1.0.47 P7-3: 格式化文件大小为人类可读字符串。
 */
private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIdx = 0
    while (size >= 1024 && unitIdx < units.lastIndex) {
        size /= 1024
        unitIdx++
    }
    return if (unitIdx == 0) "${bytes} ${units[unitIdx]}"
    else "%.1f ${units[unitIdx]}".format(size)
}
