package io.zer0.muse.knowledge.retrieval

import io.zer0.muse.knowledge.domain.KnowledgeScope
import io.zer0.muse.knowledge.domain.ScopeDoc

/**
 * 范围解析器 — 把「助手绑定(KB/文件夹)」解析为显式 [KnowledgeScope]。
 *
 * 合同(由 KnowledgeScopeContractTest 逐条钉住):
 *  - 绑定引用的目标全部不存在 → [KnowledgeScope.Invalid],不得回退全库或伪装成空结果;
 *  - 入口未绑定任何范围(两个入参都为空)→ [KnowledgeScope.None];
 *  - 命中部分存在 → [KnowledgeScope.Resolved],docIds 只含命中文档;
 *  - 解析执行抛错 → [KnowledgeScope.Failed];
 *  - 同名文件夹跨库不合并:folder 匹配必须同时约束 kbId。
 */
interface KnowledgeScopeResolver {

    /**
     * 解析助手绑定范围。
     *
     * @param knowledgeBaseIds 整库绑定(旧形态);[folderScopes] 非空时被忽略,
     *   与旧链「文件夹优先、未设文件夹回退整库」的语义一致。
     * @param folderScopes (kbId, folderPath) 绑定;folderPath 为库内相对路径
     *   (未归一化原始输入,解析器内部归一化),空串表示整库。
     */
    suspend fun resolveBinding(
        knowledgeBaseIds: List<String>,
        folderScopes: List<Pair<String, String>>,
    ): KnowledgeScope
}

/**
 * 生产实现:文档数据通过 [docsByKbIds] 函数注入,保持本包不依赖 Room/rag 实现。
 *
 * 过渡期说明:文件夹前缀匹配规则与旧链 `RagService.pathHitsFolder` 一致
 * (空 = 整库;相等或前缀含子目录)。两条实现并存到 R6 统一查询计划时收敛;
 * 规则漂移由 `same_folder_name_in_two_kbs_does_not_merge` 等合同测试拦截。
 */
class DefaultKnowledgeScopeResolver(
    private val docsByKbIds: suspend (List<String>) -> List<ScopeDoc>,
) : KnowledgeScopeResolver {

    override suspend fun resolveBinding(
        knowledgeBaseIds: List<String>,
        folderScopes: List<Pair<String, String>>,
    ): KnowledgeScope {
        val kbIds = knowledgeBaseIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val scopes = folderScopes
            .map { (kbId, path) -> kbId.trim() to FolderPathNormalizer.normalize(path) }
            .filter { it.first.isNotBlank() }
            .distinct()

        // 两个入参都为空 = 入口未绑定任何范围 → None(零结果语义,调用方不得扩大为全库;
        // 明确拥有全库许可的入口应自行构造 AllUserLibraries,而不是把 None 重新解释成全库)。
        if (kbIds.isEmpty() && scopes.isEmpty()) return KnowledgeScope.None

        // 绑定引用的目标全部无法命中 → Invalid(失效绑定):调用方必须提示修复,
        // 不得静默回退成 None(=空结果)或全库。区分依据:输入绑定非空但展开为零。
        val docs = try {
            docsByKbIds((scopes.map { it.first } + kbIds).distinct())
        } catch (t: Throwable) {
            return KnowledgeScope.Failed(t.message ?: "scope resolution failed")
        }
        if (docs.isEmpty()) return KnowledgeScope.Invalid

        val hits = if (scopes.isNotEmpty()) {
            docs.asSequence()
                .filter { doc -> scopes.any { (kbId, path) -> doc.kbId == kbId && pathHitsFolder(doc.folderPath, path) } }
                .map { it.docId }
                .toSet()
        } else {
            docs.map { it.docId }.toSet()
        }
        // 绑定有效但命中文档为零(如文件夹下没有文档)→ Resolved(空集)语义上等同
        // None:统一折叠为 None,调用方按零结果处理,同样不得扩大为全库。
        return if (hits.isEmpty()) KnowledgeScope.None else KnowledgeScope.Resolved(hits)
    }

    /** 空路径 = 整库;否则路径相等或前缀含子目录(与旧链同规则)。 */
    private fun pathHitsFolder(docPath: String, boundPath: String): Boolean =
        boundPath.isEmpty() || docPath == boundPath || docPath.startsWith("$boundPath/")
}

/**
 * R1 mention 合同:把「@mention 定向」与「入口许可」合并为最终检索范围。
 *
 * 合同(由 `empty permit is never re-expanded to full library` 钉住):
 *  - 许可=null(未绑定助手):mention 存在 → 定向 mention;否则 null(AllUserLibraries 既有行为);
 *  - 许可=空列表(失效/空绑定、解析失败):**一律返回空列表(零结果)** —— 无论 mention 是否
 *    存在、是否越界,空许可不得被 `takeIf { isNotEmpty() }` 重新解释成 null(全库);
 *  - 许可=非空:mention 全部在许可内 → mention 定向;部分越界 → 只留许可内部分;
 *    全部越界 → 回退许可本身(mention 不越权、也不清空检索)。
 *
 * 返回 null 的唯一来源 = 未绑定且无 mention;返回空列表的唯一来源 = 显式零结果许可。
 */
fun mergeMentionWithPermit(
    mentionDocIds: List<String>?,
    permittedDocIds: List<String>?,
): List<String>? = when {
    permittedDocIds == null -> mentionDocIds?.takeIf { it.isNotEmpty() }
    permittedDocIds.isEmpty() -> emptyList()
    mentionDocIds.isNullOrEmpty() -> permittedDocIds
    else -> {
        val permitSet = permittedDocIds.toSet()
        val inScope = mentionDocIds.filter { it in permitSet }
        inScope.ifEmpty { permittedDocIds }
    }
}

/**
 * 文件夹路径归一化:规则与 `KnowledgeFolderBrowser.normalize` 相同
 * (反斜杠统一、去空段/`.`、`..` 回退上级)。单独提出以避免 knowledge 包反向依赖 rag 包;
 * 两处规则必须保持一致,漂移由合同测试与 R6 统一查询计划收敛。
 */
private object FolderPathNormalizer {
    fun normalize(path: String): String {
        val segments = mutableListOf<String>()
        path.replace('\\', '/').split('/').map { it.trim() }.forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/")
    }
}
