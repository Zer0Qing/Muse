package io.zer0.muse.transformer

import android.content.Context
import io.zer0.common.Logger

/**
 * Phase 12: 提示词模板加载器。
 *
 * 将 SystemPromptAssembler 中的硬编码大段提示词抽取到 assets/prompt_templates/ 目录，
 * 通过 PebbleTemplateEngine 渲染，支持 {{变量}} 插值。
 *
 * 加载策略:
 * 1. 先从 assets/prompt_templates/ 加载 .prompt 文件
 * 2. 文件缺失或加载失败时返回 fallback 默认值(保持向后兼容)
 *
 * v1.0.51: 支持 locale 回落。加载顺序:
 * 1. {name}_{locale}.prompt (如 decision_tree_zh.prompt)
 * 2. {name}.prompt (无 locale 后缀,作为通用回落)
 * 3. fallback 参数
 *
 * locale 取值: zh / en / ja / ko / ru / system(跟随系统,由调用方解析后传入实际 locale)
 */
class PromptTemplateLoader(private val context: Context) {

    private val engine = PebbleTemplateEngine()
    // M-TPL6: 多协程并发访问(如 buildStaticSnapshot 内多次 render),用 ConcurrentHashMap 保证线程安全
    // v1.0.51: cache key 改为 "{name}_{locale}" 以支持 locale 维度缓存
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    companion object {
        private const val TAG = "PromptTemplateLoader"
        private const val TEMPLATE_DIR = "prompt_templates"

        // 已知模板文件清单(不含 .prompt 后缀)
        val TEMPLATE_NAMES = listOf(
            "decision_tree",
            "mood_format",
            "artifact_format",
            "self_reflection",
            "tool_discipline",
            "operation_safety",
            // v1.0.51: 新增默认人格模板 + 记忆规则 + 平台声明
            "default_persona",
            "memory_rules",
            "platform_decl",
        )
    }

    /**
     * 加载并渲染指定模板(无 locale,回落到通用模板)。
     *
     * @param name 模板名称(对应 assets/prompt_templates/{name}.prompt)
     * @param context 模板变量上下文(可选)
     * @param fallback 文件加载失败时的默认值
     */
    fun render(
        name: String,
        context: Map<String, Any?> = emptyMap(),
        fallback: String = "",
    ): String = render(name, locale = null, context, fallback)

    /**
     * v1.0.51: 加载并渲染指定模板(带 locale 回落)。
     *
     * 加载顺序:
     * 1. assets/prompt_templates/{name}_{locale}.prompt
     * 2. assets/prompt_templates/{name}.prompt
     * 3. fallback 参数
     *
     * @param name 模板名称
     * @param locale 语言代码(zh/en/ja/ko/ru),null 或空串表示不区分 locale
     * @param context 模板变量上下文(可选)
     * @param fallback 文件加载失败时的默认值
     */
    fun render(
        name: String,
        locale: String?,
        context: Map<String, Any?> = emptyMap(),
        fallback: String = "",
    ): String {
        val templateText = loadTemplate(name, locale) ?: return fallback
        return try {
            engine.render(templateText, context)
        } catch (e: Exception) {
            Logger.w(TAG, "模板渲染失败: $name (locale=$locale)", e)
            templateText // 渲染失败时返回原始文本
        }
    }

    /**
     * 加载模板文本(带缓存,按 locale 维度缓存)。
     *
     * 回落链: {name}_{locale}.prompt → {name}.prompt → null
     */
    private fun loadTemplate(name: String, locale: String?): String? {
        val effectiveLocale = locale?.takeIf { it.isNotBlank() && it != "system" }
        val cacheKey = if (effectiveLocale != null) "${name}_$effectiveLocale" else name

        cache[cacheKey]?.let { return it }

        // 1. 先尝试 locale 专属模板
        if (effectiveLocale != null) {
            val localizedFile = "$TEMPLATE_DIR/${name}_$effectiveLocale.prompt"
            try {
                context.assets.open(localizedFile).bufferedReader().use { reader ->
                    return reader.readText().also { cache[cacheKey] = it }
                }
            } catch (e: Exception) {
                // locale 专属模板不存在,继续尝试通用模板
            }
        }

        // 2. 回落到通用模板(无 locale 后缀)
        return try {
            val fileName = "$TEMPLATE_DIR/$name.prompt"
            context.assets.open(fileName).bufferedReader().use { reader ->
                reader.readText().also { cache[cacheKey] = it }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "模板加载失败: $name (locale=$locale)", e)
            null
        }
    }

    /**
     * 清除模板缓存(模板文件变更或 locale 切换时调用)。
     */
    fun clearCache() {
        cache.clear()
    }
}
