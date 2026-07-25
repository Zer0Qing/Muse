package io.zer0.muse.privacy

/**
 * Safety Policy — 用户输入安全策略,保守过滤明确违规内容。
 *
 * 设计原则:
 *  - 不做过度审查,只过滤明确违规的敏感词(毒品/武器/暴力相关)。
 *  - 关键词列表保守,避免误伤正常对话(如"杀毒""武器化思想"等正常用语)。
 *  - 命中后给出友好的拒绝理由与建议,不直接屏蔽输入(由调用方决定如何处理)。
 *
 * 参考实现:openhanako 项目的 safety-policy.ts。
 */
object SafetyPolicy {

    /**
     * 安全检查结果。
     *
     * @param safe       是否安全(true=可发送给 LLM,false=应拒绝或警告)
     * @param reason     不安全时的原因(用于日志/调试,null 表示安全)
     * @param suggestion 不安全时的建议(展示给用户的友好提示,null 表示安全)
     */
    data class SafetyResult(
        val safe: Boolean,
        val reason: String?,
        val suggestion: String?,
    )

    /**
     * 保守的违规关键词列表(小写匹配,中文直接匹配)。
     *
     * 仅列入明确违规的硬性内容,避免误伤:
     *  - 毒品:海洛因/可卡因/冰毒/大麻(俗称)/摇头丸 等
     *  - 武器:枪支制造/炸弹制作/弹药 等(带"制作/制造"等动词上下文)
     *  - 暴力:杀人方法/分尸 等(明确违法指导)
     *
     * 注意:关键词带上下文限定(如"枪支制造"而非单"枪"),降低误检率。
     */
    private val blockedKeywords: List<String> = listOf(
        // 毒品类(明确违禁品)
        "海洛因",
        "可卡因",
        "冰毒",
        "摇头丸",
        "甲基苯丙胺",
        "制作炸弹",
        "制造炸弹",
        "炸弹制作",
        // 武器制造类(带制造上下文)
        "枪支制造",
        "制造枪支",
        "制作枪支",
        "弹药制造",
        // 暴力犯罪指导
        "杀人方法",
        "如何杀人",
        "分尸方法",
        "如何分尸",
    )

    /**
     * 检查用户输入是否安全。
     *
     * 实现:简单的子串匹配(大小写不敏感)。命中任一关键词即视为不安全。
     * 不做语义分析,不接入外部审核服务,保持轻量与可预测。
     *
     * @param text 用户输入文本
     * @return 安全检查结果,默认 safe=true
     */
    fun checkInput(text: String): SafetyResult {
        if (text.isBlank()) return SafetyResult(safe = true, reason = null, suggestion = null)
        val lower = text.lowercase()
        for (keyword in blockedKeywords) {
            if (lower.contains(keyword)) {
                return SafetyResult(
                    safe = false,
                    reason = "命中违规关键词: $keyword",
                    suggestion = "该话题可能涉及违法内容,请修改后重试。",
                )
            }
        }
        return SafetyResult(safe = true, reason = null, suggestion = null)
    }
}
