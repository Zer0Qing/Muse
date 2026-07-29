package io.zer0.muse.util

/**
 * v1.131: 集中管理应用内公共正则常量。
 *
 * 原先以下正则在多个文件重复定义,修改需多处同步:
 *  - 邮箱正则:ToolRegistry.kt(局部变量) + AccountScreen.kt(文件级常量)
 *  - think 标签正则:ChatStreamCoordinator.kt + ThinkTagTransformer.kt + GroupChatScheduler.kt
 *  - mood 标签正则:MoodTagTransformer.kt + GroupChatScheduler.kt
 *
 * 注:EmotionTracking.kt 的 `<mood value="..." label="...">...</mood>` 是属性式旧 schema,
 * 与此处的内容式 `<mood>...</mood>` 不兼容,故不合并,保留 EmotionTracking 独立定义。
 */
object MusePatterns {

    /**
     * 邮箱格式校验(简单正则,非 RFC 5322 完整实现)。
     * 用于:邮件工具收件人校验、账户登录注册表单校验。
     */
    val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    /**
     * 匹配 `<think>...</think>` 块(非贪婪,跨行 `[\s\S]`,忽略大小写)。
     * 捕获组 1 = think 内容。
     *
     * 用于:流式响应剥离 think 块、Transformer 管线、群聊调度器。
     */
    val THINK_TAG_REGEX = Regex("""<think>([\s\S]*?)</think>""", RegexOption.IGNORE_CASE)

    /**
     * 匹配 `<mood>...</mood>` 或 `[mood]...[/mood]` 块(非贪婪,跨行 `[\s\S]`,忽略大小写)。
     * 捕获组 1 = mood 内容。
     *
     * 用于:MoodTagTransformer 包装/剥离、群聊调度器剥离。
     *
     * v1.0.28 修复: 同时匹配尖括号 `<mood>` 和方括号 `[mood]` 两种格式。
     * 原因: prompt 模板要求 `<mood>`,但部分 LLM(尤其 deepseek 系列)会输出 `[mood]`,
     * 导致 mood 块不被剥离,直接展示给用户,后面跟着的 [PASS] 也作为正文残留。
     */
    val MOOD_TAG_REGEX = Regex(
        """(?:<mood>|\[mood\])([\s\S]*?)(?:</mood>|\[/mood\])""",
        RegexOption.IGNORE_CASE,
    )
}
