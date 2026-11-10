package io.zer0.muse.knowledge.domain

/**
 * R1 检索范围合同 — 把「范围」从 `List<String>?` 的隐式语义升级为显式状态。
 *
 * 动机:旧链路三层隐式约定(`null`=全库、`emptyList()` 也被当全库、解析失败=null=全库)
 * 导致空绑定与失效绑定扩大成全库检索(R0 基线 A/B 红测钉住的缺陷)。
 * 每个状态对应唯一且可区分的含义,调用方不得用 `takeIf { it.isNotEmpty() }` 重新解释。
 */
sealed interface KnowledgeScope {

    /** 解析成功且命中非空:只允许检索这批文档。 */
    data class Resolved(val docIds: Set<String>) : KnowledgeScope

    /** 入口明确拥有全库许可时才允许使用;未经许可的入口不得构造该状态。 */
    data object AllUserLibraries : KnowledgeScope

    /** 显式空范围:未绑定任何范围或有效绑定但约束为空 = 零结果;不得扩大为全库。 */
    data object None : KnowledgeScope

    /** 绑定引用的目标全部不存在/已失效;调用方应提示修复,不得静默回退全库或空结果。 */
    data object Invalid : KnowledgeScope

    /** 解析执行失败(存储层错误等);不得伪装成 [None] 或 [AllUserLibraries]。 */
    data class Failed(val reason: String) : KnowledgeScope
}

/**
 * 范围解析所需的最小文档投影(文档 id + 库归属 + 已归一化文件夹路径)。
 *
 * 只携带范围判定所需字段,使范围合同不依赖 Room 实体;`folderPath` 必须已按
 * `KnowledgeFolderBrowser.normalize` 归一化(注入方责任),解析器不再二次归一化。
 */
data class ScopeDoc(
    val docId: String,
    val kbId: String,
    /** 库内相对文件夹路径;空串 = 库根。 */
    val folderPath: String,
)
