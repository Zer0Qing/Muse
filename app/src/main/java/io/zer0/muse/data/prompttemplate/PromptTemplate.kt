package io.zer0.muse.data.prompttemplate

import android.content.Context
import io.zer0.muse.R
import kotlinx.serialization.Serializable

/**
 * v1.58: Prompt 模板 — 预置场景提示词(写作/翻译/总结/代码/学习/创意)。
 *
 * 用户可从输入栏 plus 菜单进入模板库,选择模板一键插入输入框。
 * 与 QuickMessage(快捷短句 chips)区分:模板库是完整的长 prompt。
 *
 * 占位符语法说明(L-PT1):
 * - PromptTemplate 使用单括号占位符(如 {主题}),需用户手动替换或由调用方替换;
 *   模板内容仅作为初始文本插入输入框,不做自动变量渲染。
 * - QuickMessage 使用双括号占位符(如 {{input}}),由 QuickMessageRepository.renderTemplate
 *   自动渲染(input/clipboard/date)。两者语义不同,勿混用。
 *
 * @param category 分类:写作/翻译/总结/代码/学习/创意(见 [CATEGORIES] 常量,自由 String 不做枚举强校验)
 * @param builtIn 是否内置模板(内置不可删除)
 */
@Serializable
data class PromptTemplate(
    val id: String,
    val name: String,
    val category: String,
    val content: String,
    val builtIn: Boolean = false,
) {
    companion object {
        val CATEGORIES = listOf("Writing", "Translation", "Summary", "Code", "Learning", "Creative")

        fun getBuiltInPromptTemplates(context: Context): List<PromptTemplate> = listOf(
            PromptTemplate(
                "bi_write_email",
                context.getString(R.string.prompt_template_name_write_email),
                context.getString(R.string.prompt_template_category_writing),
                context.getString(R.string.prompt_template_content_write_email),
            ),
            PromptTemplate(
                "bi_write_article",
                context.getString(R.string.prompt_template_name_write_article),
                context.getString(R.string.prompt_template_category_writing),
                context.getString(R.string.prompt_template_content_write_article),
            ),
            PromptTemplate(
                "bi_translate_zh_en",
                context.getString(R.string.prompt_template_name_translate_zh_en),
                context.getString(R.string.prompt_template_category_translation),
                context.getString(R.string.prompt_template_content_translate_zh_en),
            ),
            PromptTemplate(
                "bi_translate_en_zh",
                context.getString(R.string.prompt_template_name_translate_en_zh),
                context.getString(R.string.prompt_template_category_translation),
                context.getString(R.string.prompt_template_content_translate_en_zh),
            ),
            PromptTemplate(
                "bi_summarize",
                context.getString(R.string.prompt_template_name_summarize),
                context.getString(R.string.prompt_template_category_summary),
                context.getString(R.string.prompt_template_content_summarize),
            ),
            PromptTemplate(
                "bi_summarize_meeting",
                context.getString(R.string.prompt_template_name_summarize_meeting),
                context.getString(R.string.prompt_template_category_summary),
                context.getString(R.string.prompt_template_content_summarize_meeting),
            ),
            PromptTemplate(
                "bi_code_review",
                context.getString(R.string.prompt_template_name_code_review),
                context.getString(R.string.prompt_template_category_code),
                context.getString(R.string.prompt_template_content_code_review),
            ),
            PromptTemplate(
                "bi_code_explain",
                context.getString(R.string.prompt_template_name_code_explain),
                context.getString(R.string.prompt_template_category_code),
                context.getString(R.string.prompt_template_content_code_explain),
            ),
            PromptTemplate(
                "bi_code_refactor",
                context.getString(R.string.prompt_template_name_code_refactor),
                context.getString(R.string.prompt_template_category_code),
                context.getString(R.string.prompt_template_content_code_refactor),
            ),
            PromptTemplate(
                "bi_learn_concept",
                context.getString(R.string.prompt_template_name_learn_concept),
                context.getString(R.string.prompt_template_category_learning),
                context.getString(R.string.prompt_template_content_learn_concept),
            ),
            PromptTemplate(
                "bi_learn_quiz",
                context.getString(R.string.prompt_template_name_learn_quiz),
                context.getString(R.string.prompt_template_category_learning),
                context.getString(R.string.prompt_template_content_learn_quiz),
            ),
            PromptTemplate(
                "bi_creative_story",
                context.getString(R.string.prompt_template_name_creative_story),
                context.getString(R.string.prompt_template_category_creative),
                context.getString(R.string.prompt_template_content_creative_story),
            ),
            PromptTemplate(
                "bi_creative_brainstorm",
                context.getString(R.string.prompt_template_name_creative_brainstorm),
                context.getString(R.string.prompt_template_category_creative),
                context.getString(R.string.prompt_template_content_creative_brainstorm),
            ),
        )
    }
}
