package io.zer0.muse.knowledge.ingest

/**
 * R3 解析合同 — 「解析成功」具有准确语义(计划 R3:Partial/NeedsOcr/Failed 不得报 Complete)。
 *
 * [ParseOutcome] 是解析器的唯一输出合同;调用方按 [Coverage] 与 [OcrRequirement]
 * 决定后续(索引/提示修复/等待授权),不得把任何非 Complete 状态当成功全文处理。
 */

/** 解析覆盖状态 —— 每种状态对用户可见语义唯一。 */
enum class ParseCoverage {
    /** 全部内容解析成功且完整(页数/段落齐全)。 */
    COMPLETE,

    /**
     * 部分解析:有内容产出但覆盖不全(超页数上限被截/个别页失败/个别段跳过)。
     * 必须携带 [ParseOutcome.coverageNote] 说明缺了什么,不得静默。
     */
    PARTIAL,

    /** 无可提取文字(扫描件无文字层/纯图片);需 OCR 才有正文。 */
    NO_TEXT,

    /** 解析执行失败(损坏/加密/IO 异常);无内容产出,错误信息不得进入任何 chunk。 */
    FAILED,
}

/** OCR 需求 —— 与 [ParseCoverage.NO_TEXT]/[ParseCoverage.PARTIAL] 搭配。 */
enum class OcrRequirement {
    /** 不需要 OCR。 */
    NONE,

    /** 需要 OCR 才能补全;等待既有授权策略,不静默调用新服务(计划 R3 步骤 4)。 */
    REQUIRED_PENDING_POLICY,
}

/**
 * 单个解析块 —— 解析器的最小产出单位,携带可解释定位。
 * 文本内容与位置(页/段/偏移)分离,供 chunk 层回填 locator。
 */
data class ParsedBlock(
    /** 块文本(解析产出;错误提示绝不出现在任何 block 内容中)。 */
    val text: String,
    /** 页码(从 1 起;无页概念的来源=0)。 */
    val page: Int = 0,
    /** 段落序号(页内;从 1 起)。 */
    val section: Int = 0,
    /** 结构提示:heading / paragraph / code / table(供分块器保留结构)。 */
    val kind: String = "paragraph",
)

/**
 * 解析结果 —— 「成功」的准确语义载体。
 * [coverage]=COMPLETE 当且仅当全部内容解析且完整;任何截断/缺页/跳段都是 PARTIAL;
 * 无文字是 NO_TEXT(不是空 COMPLETE);异常是 FAILED(错误串只进 [error],不进 blocks)。
 */
data class ParseOutcome(
    val coverage: ParseCoverage,
    /** 产出块(COMPLETE/PARTIAL 非空;NO_TEXT/FAILED 为空)。 */
    val blocks: List<ParsedBlock>,
    /** OCR 需求(NO_TEXT/部分截断的扫描件 → REQUIRED_PENDING_POLICY)。 */
    val ocrRequirement: OcrRequirement = OcrRequirement.NONE,
    /** 覆盖说明(PARTIAL 必填:缺了什么/为什么);COMPLETE/NO_TEXT/FAILED 可为空串。 */
    val coverageNote: String = "",
    /** 错误信息(FAILED 必填);仅用于用户提示与日志,绝不出现在 blocks/chunk 内容。 */
    val error: String = "",
) {
    /** 合同不变式:任何非 COMPLETE 不得伪装成功(由 KnowledgeParseOutcomeTest 钉住)。 */
    val isUsableFullText: Boolean get() = coverage == ParseCoverage.COMPLETE
}

/**
 * 统一来源输入 —— SAF/归档/会话附件/内置资源/粘贴文本输出同一形状(计划 R3 步骤 6)。
 * 原件 hash 与正文 hash 分开(设计合同):[sourceHash] 是原件字节 hash,
 * [contentHash] 是解析后正文 hash(与 R2 的 SourceRef.contentHash 语义对齐)。
 */
data class SourceInput(
    /** 来源方案: file / archive-entry / session-attachment / asset / pasted-text。 */
    val scheme: String,
    /** 方案内地址(SAF URI/归档虚拟路径/附件 id/asset 路径;粘贴文本=空)。 */
    val location: String,
    /** 原件字节(可直接解析的小输入;大文件由调用方流式提供时的占位合同,R4 扩展)。 */
    val bytes: ByteArray?,
    /** 原件字节 hash(SHA-256 hex;bytes 为 null 时空串)。 */
    val sourceHash: String,
    /** 展示名(文件名/附件名;不含路径秘密)。 */
    val displayName: String,
) {
    // ByteArray 相等性按内容(数据类默认按引用,合同要求按值比较的场景显式实现)
    override fun equals(other: Any?): Boolean =
        other is SourceInput && other.scheme == scheme && other.location == location &&
            other.sourceHash == sourceHash && other.displayName == displayName &&
            (bytes?.contentEquals(other.bytes) ?: (other.bytes == null))

    override fun hashCode(): Int {
        var result = scheme.hashCode()
        result = 31 * result + location.hashCode()
        result = 31 * result + sourceHash.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + (bytes?.contentHashCode() ?: 0)
        return result
    }
}
