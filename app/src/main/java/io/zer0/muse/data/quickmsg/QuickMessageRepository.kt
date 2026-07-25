package io.zer0.muse.data.quickmsg

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * QuickMessage 仓库 — Phase 8.5。
 */
class QuickMessageRepository(
    private val dao: QuickMessageDao,
) {
    // L-PID5: 加 distinctUntilChanged,避免上游无意义重复发射触发 UI 重绘。
    fun observeAll(): Flow<List<QuickMessageEntity>> = dao.observeAll().distinctUntilChanged()

    fun observeForAssistant(assistantId: String): Flow<List<QuickMessageEntity>> =
        dao.observeForAssistant(assistantId).distinctUntilChanged()

    suspend fun getByIdsEnabled(ids: List<String>): List<QuickMessageEntity> =
        if (ids.isEmpty()) emptyList() else dao.getByIdsEnabled(ids)

    suspend fun getById(id: String): QuickMessageEntity? = dao.getById(id)

    suspend fun upsert(entity: QuickMessageEntity) = dao.upsert(entity)

    suspend fun delete(id: String) = dao.deleteById(id)

    /**
     * 渲染快捷消息模板: 替换 {{input}} / {{clipboard}} / {{date}} 变量。
     * - {{input}}: 当前输入框内容(若为空则替换为空串)
     * - {{clipboard}}: 系统剪贴板内容
     * - {{date}}: 当前日期(yyyy-MM-dd)
     *
     * Phase 8.5 修复: 用正则一次性匹配所有变量,避免顺序替换导致用户输入中的
     * `{{date}}` 等占位符被二次替换。原实现 `template.replace("{{input}}", input).replace("{{date}}", date)`
     * 会让模板="翻译: {{input}}" + 用户输入="今天是 {{date}}" 错误变成 "翻译: 今天是 2026-07-04"。
     *
     * L-QM1: 正则允许可选空格,匹配 {{ input }} / {{input }} / {{ input}} 等变体。
     *
     * L-QM2 设计权衡: 渲染逻辑放在 Repository 而非独立 Renderer/Util。原因:
     *  - 渲染依赖运行时上下文(input/clipboard/date),且仅 Repository 的调用方(ChatViewModel)
     *    持有这些上下文;抽成独立 Util 仍需传入相同参数,收益有限。
     *  - 当前仅 QuickMessage 一处使用,无复用需求;若后续 PromptTemplate 也需自动渲染,再抽公共 Renderer。
     */
    fun renderTemplate(
        template: String,
        currentInput: String = "",
        clipboard: String = "",
        date: String = java.time.LocalDate.now().toString(),
    ): String {
        if (template.isEmpty()) return template
        val replacements = mapOf(
            "input" to currentInput,
            "clipboard" to clipboard,
            "date" to date,
        )
        // L-QM1: \s* 允许变量名两侧可选空格
        val pattern = Regex("""\{\{\s*(input|clipboard|date)\s*\}\}""")
        return pattern.replace(template) { match ->
            replacements[match.groupValues[1]] ?: match.value
        }
    }
}
