package io.zer0.muse

import io.zer0.common.resultOf
import kotlinx.coroutines.flow.first
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * P2-3 拆域：RAG / 知识库检索注册独立模块。
 */
val appRagModule = module {

    // v1.54: RAG 体系:Embedding 服务 + 向量检索编排
    // v1.134: 注入 filesDir 供 EmbeddingService 解析 ONNX 模型相对路径
    single {
        io.zer0.muse.rag.EmbeddingService(
            configStore = get(),
            client = get(named("chat")),
            filesDir = androidContext().filesDir,
        )
    }
    // v1.133: 本地 Rerank Provider(无依赖,降级方案)
    single<io.zer0.muse.rag.RerankProvider> { io.zer0.muse.rag.LocalRerankProvider() }
    // v1.134: 本地 ONNX Cross-Encoder Rerank Provider(可选,模型缺失时自动降级到 LocalRerankProvider)
    // 模型文件约定:filesDir/muse_onnx/rerank.onnx + 同目录 vocab.txt
    single {
        io.zer0.muse.rag.OnnxRerankProvider(
            modelPath = java.io.File(androidContext().filesDir, "muse_onnx/rerank.onnx").absolutePath,
        )
    }
    // v1.133: 混合检索服务(FTS4 + 向量 RRF)
    single {
        io.zer0.muse.rag.HybridSearchService(
            ftsDao = get<io.zer0.muse.data.session.MuseDb>().knowledgeChunkFtsDao(),
            vectorSearch = io.zer0.muse.rag.VectorSearchService(
                chunkPageProvider = { limit, offset ->
                    val titles = get<io.zer0.muse.data.knowledge.KnowledgeDocDao>().observeAll().first()
                        .associate { it.id to it.title }
                    get<io.zer0.muse.data.knowledge.KnowledgeChunkDao>().getPageWithEmbedding(limit, offset).map { chunk ->
                        io.zer0.muse.rag.VectorSearchService.ChunkWithDoc(
                            chunkId = chunk.id, docId = chunk.docId,
                            docTitle = titles[chunk.docId] ?: "Unknown",
                            content = chunk.content, embedding = chunk.embedding,
                            embeddingBlob = chunk.embeddingBlob, chunkIndex = chunk.chunkIndex,
                        )
                    }
                },
                chunkCountProvider = { get<io.zer0.muse.data.knowledge.KnowledgeChunkDao>().countIndexed() },
            ),
        )
    }
    single {
        io.zer0.muse.rag.RagService(
            chunkDao = get(),
            docDao = get(),
            ftsDao = get<io.zer0.muse.data.session.MuseDb>().knowledgeChunkFtsDao(),
            docTitleProvider = {
                get<io.zer0.muse.data.knowledge.KnowledgeDocDao>().observeAll()
                    .first().associate { it.id to it.title }
            },
            embeddingService = get(),
            hybridSearchService = get(),
            rerankProvider = get(),
            onnxRerankProvider = get(),
            // v1.103: 向量检索无结果时的关键词兜底;v1.133: snippet 改取首个 chunk(替代 content.take(500))
            keywordSearchFallback = { query, topK ->
                val docDao = get<io.zer0.muse.data.knowledge.KnowledgeDocDao>()
                val chunkDao = get<io.zer0.muse.data.knowledge.KnowledgeChunkDao>()
                docDao.search(query).first().take(topK).map { doc ->
                    val firstChunkContent = resultOf {
                        chunkDao.getByDoc(doc.id).firstOrNull()?.content ?: ""
                    }.getOrNull() ?: ""
                    doc.title to (firstChunkContent.ifBlank { doc.content.take(500) })
                }
            },
            // v1.0.12: HNSW 索引持久化文件路径 — 启用 RAG 向量索引落盘
            // 文件位置:filesDir/rag/hnsw_index.bin;App 重启后 MuseApp.onCreate 异步加载,
            // 避免每次启动都从 DB 全量重建索引。indexFile 默认 null(不持久化,仅内存),
            // 此处显式注入启用持久化,向后兼容旧调用方(默认 null 路径不受影响)。
            // rag/ 目录在注入时创建(mkdirs 幂等,已存在无副作用)。
            indexFile = java.io.File(androidContext().filesDir, "rag/hnsw_index.bin").apply {
                parentFile?.mkdirs()
            },
        )
    }
    // v1.133: KnowledgeBaseDao 单独注册(多知识库管理页用)
    single { get<io.zer0.muse.data.session.MuseDb>().knowledgeBaseDao() }
    // v1.0.47 P7-2: 会话级附件索引服务
    single { io.zer0.muse.rag.SessionAttachmentService(get(), get()) }
}