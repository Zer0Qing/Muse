package io.zer0.muse.data.prompttemplate

import android.content.Context
import kotlinx.serialization.Serializable
import io.zer0.muse.R

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
            PromptTemplate("bi_write_email", context.getString(R.string.prompt_template_name_write_email), context.getString(R.string.prompt_template_category_writing), "请帮我撰写一封邮件,主题如下:\n\n{主题}\n\n要求:语气正式、简洁明了,包含称呼、正文、结尾敬语。"),
            PromptTemplate("bi_write_article", context.getString(R.string.prompt_template_name_write_article), context.getString(R.string.prompt_template_category_writing), "请围绕以下主题撰写一篇结构清晰的文章:\n\n{主题}\n\n要求:包含引言、正文(2-3 个要点)、结论,字数约 800 字。"),
            PromptTemplate("bi_translate_zh_en", context.getString(R.string.prompt_template_name_translate_zh_en), context.getString(R.string.prompt_template_category_translation), "请将以下中文翻译成自然流畅的英文,保持原意,符合英语母语者表达习惯:\n\n{中文内容}"),
            PromptTemplate("bi_translate_en_zh", context.getString(R.string.prompt_template_name_translate_en_zh), context.getString(R.string.prompt_template_category_translation), "请将以下英文翻译成通顺自然的中文,保持原意,符合中文表达习惯:\n\n{英文内容}"),
            PromptTemplate("bi_summarize", context.getString(R.string.prompt_template_name_summarize), context.getString(R.string.prompt_template_category_summary), "请将以下内容总结为 3-5 个要点,每个要点一句话,保留关键信息:\n\n{内容}"),
            PromptTemplate("bi_summarize_meeting", context.getString(R.string.prompt_template_name_summarize_meeting), context.getString(R.string.prompt_template_category_summary), "请将以下会议记录整理为结构化纪要,包含:会议主题、关键讨论点、决议事项、后续行动项(含负责人):\n\n{会议记录}"),
            PromptTemplate("bi_code_review", context.getString(R.string.prompt_template_name_code_review), context.getString(R.string.prompt_template_category_code), "请审查以下代码,从可读性、性能、安全性、边界条件四个维度给出改进建议:\n\n```\n{代码}\n```"),
            PromptTemplate("bi_code_explain", context.getString(R.string.prompt_template_name_code_explain), context.getString(R.string.prompt_template_category_code), "请逐行解释以下代码的功能和逻辑,用通俗易懂的语言说明:\n\n```\n{代码}\n```"),
            PromptTemplate("bi_code_refactor", context.getString(R.string.prompt_template_name_code_refactor), context.getString(R.string.prompt_template_category_code), "请重构以下代码,提升可读性和可维护性,并说明每处改动的原因:\n\n```\n{代码}\n```"),
            PromptTemplate("bi_learn_concept", context.getString(R.string.prompt_template_name_learn_concept), context.getString(R.string.prompt_template_category_learning), "请用通俗易懂的方式解释以下概念,给出定义、核心要点、一个生活中的例子:\n\n{概念}"),
            PromptTemplate("bi_learn_quiz", context.getString(R.string.prompt_template_name_learn_quiz), context.getString(R.string.prompt_template_category_learning), "请围绕以下主题生成 5 道选择题(含答案和解析),由易到难:\n\n{主题}"),
            PromptTemplate("bi_creative_story", context.getString(R.string.prompt_template_name_creative_story), context.getString(R.string.prompt_template_category_creative), "请基于以下设定创作一个短篇故事(约 500 字),注重氛围和情节转折:\n\n{设定}"),
            PromptTemplate("bi_creative_brainstorm", context.getString(R.string.prompt_template_name_creative_brainstorm), context.getString(R.string.prompt_template_category_creative), "请围绕以下问题进行头脑风暴,给出 10 个有创意的解决思路,每个一句话:\n\n{问题}"),
        )
    }
}
