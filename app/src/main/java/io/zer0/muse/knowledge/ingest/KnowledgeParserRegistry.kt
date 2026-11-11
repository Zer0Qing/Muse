package io.zer0.muse.knowledge.ingest

/**
 * R3 步骤 2:统一格式注册表 + R3 步骤 1 的分类合同。
 *
 * [classify] 按 [ParseOutcome] 的已解析产出分类(解析器负责把原始字节变成 outcome,
 * 分类器只看 outcome 上的事实,不猜测);[supportedFormat] 是格式路由的白名单合同:
 * 覆盖 txt/md/pdf/docx/pptx/xlsx/epub 与图片入口,**未知格式明确拒绝**
 * (不按 txt 强读二进制 —— R0 盘点的已知缺陷)。
 */
object KnowledgeParserRegistry {

    /** 当前支持的格式白名单(扩展名,小写);路由时未知扩展名 → 拒绝。 */
    private val SUPPORTED_EXTENSIONS = setOf(
        "txt", "md", "pdf", "docx", "pptx", "xlsx", "epub",
        "png", "jpg", "jpeg", "webp", "gif", // 图片入口(OCR 路径,步骤 4)
    )

    /**
     * 格式路由:未知格式返回 false(调用方必须显式拒绝,不得按 txt 强读)。
     * 无扩展名/空名 → false(保守侧)。
     */
    fun isSupportedFormat(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && ext in SUPPORTED_EXTENSIONS
    }

    /**
     * R3 步骤 1 分类合同:把解析产出的状态收敛为用户可见语义。
     * 本函数是纯校验器 —— 它拒绝「带覆盖说明却自称 COMPLETE」的矛盾产出:
     * 有 coverageNote 的 COMPLETE 视为 PARTIAL(解析器多报比少报危险)。
     */
    fun classify(outcome: ParseOutcome): ParseCoverage = when (outcome.coverage) {
        ParseCoverage.COMPLETE ->
            if (outcome.coverageNote.isNotBlank()) ParseCoverage.PARTIAL else ParseCoverage.COMPLETE
        else -> outcome.coverage
    }
}
