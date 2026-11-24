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
            "execution_rules",
            "decision_tree",
            "mood_format",
            "artifact_format",
            "self_reflection",
            "tool_discipline",
            "operation_safety",
            // v2.5.3: 行动纪律 + 交付契约
            "action_discipline",
            "delivery_contract",
            // v2.6.6: 生成式界面（引导模型在对话里生成可交互卡片）
            "generative_ui",
            // v1.0.51: 新增默认人格模板 + 记忆规则 + 平台声明
            "default_persona",
            "memory_rules",
            "platform_decl",
            "cover_generation",
        )
    }

    /**
     * 加载并渲染指定模板(无 locale,回落到通用模板)。
     *
     * @param name 模板名称(对应 assets/prompt_templates/{name}_zh.prompt)
     * @param context 模板变量上下文(可选)
     * @param fallback 文件加载失败时的默认值
     */
    fun render(name: String, context: Map<String, Any?> = emptyMap(), fallback: String = ""): String =
        render(name, locale = null, context, fallback)

    /**
     * v1.0.51: 加载并渲染指定模板(带 locale 回落)。
     *
     * 加载顺序:
     * 1. assets/prompt_templates/{name}_{normalizedLocale}.prompt
     * 2. 中文/空 locale 回落 {name}_zh.prompt；非中文 locale 回落 {name}_en.prompt
     * 3. 兼容兜底 {name}.prompt
     * 4. fallback 参数
     *
     * @param name 模板名称
     * @param locale 语言代码(zh/zh-CN/zh-TW/zh-Hans/zh-Hant/en/ja/ko/ru),null 或空串/system 默认走中文
     * @param context 模板变量上下文(可选)
     * @param fallback 文件加载失败时的默认值
     */
    fun render(name: String, locale: String?, context: Map<String, Any?> = emptyMap(), fallback: String = ""): String {
        val templateText = loadTemplate(name, locale) ?: return fallback
        return try {
            engine.render(templateText, context)
        } catch (e: Exception) {
            Logger.w(TAG, "模板渲染失败: $name (locale=$locale)", e)
            templateText // 渲染失败时返回原始文本
        }
    }

    /**
     * 归一化 locale：所有中文变体(zh/zh-CN/zh-TW/zh-Hans/zh-Hant 等)统一为 "zh"，
     * 避免先探测不存在的 zh-CN/zh-TW 文件；其他语言保留小写 BCP-47 以便未来按需扩展。
     */
    private fun normalizeLocale(locale: String?): String? {
        val cleaned = locale?.trim()?.replace('_', '-')?.takeIf {
            it.isNotEmpty() && !it.equals("system", ignoreCase = true)
        } ?: return null
        return if (cleaned.equals("zh", ignoreCase = true) || cleaned.startsWith("zh-", ignoreCase = true)) {
            "zh"
        } else {
            cleaned.lowercase()
        }
    }

    /**
     * 加载模板文本(带缓存,按归一化后的 locale 维度缓存)。
     *
     * 回落链:
     *  - 中文 / 未指定 locale: {name}_zh.prompt → {name}.prompt(兼容旧模板) → null
     *  - 非中文 locale: {name}_{locale}.prompt → {name}_en.prompt(国际回落)
     *    → {name}.prompt(兼容兜底) → null
     * 国际用户优先拿英文而非中文,避免日/韩/西/葡/俄用户收到中文提示词。
     */
    private fun loadTemplate(name: String, locale: String?): String? {
        val effectiveLocale = normalizeLocale(locale)
        val isChinese = effectiveLocale == null || effectiveLocale == "zh"
        val cacheKey = if (effectiveLocale != null) "${name}_$effectiveLocale" else "${name}_default"

        cache[cacheKey]?.let { return it }

        // 1. 先尝试归一化后的 locale 专属模板
        if (effectiveLocale != null) {
            readAsset("$TEMPLATE_DIR/${name}_$effectiveLocale.prompt")?.let { text ->
                cache[cacheKey] = text
                return text
            }
        }

        // 2. 语系回落: 中文(含 locale=null) 走 _zh.prompt; 非中文走 _en.prompt
        val fallbackSuffix = if (isChinese) "zh" else "en"
        if (effectiveLocale != fallbackSuffix) {
            readAsset("$TEMPLATE_DIR/${name}_$fallbackSuffix.prompt")?.let { text ->
                cache[cacheKey] = text
                return text
            }
        }

        // 3. 兼容兜底(无后缀自定义模板)
        readAsset("$TEMPLATE_DIR/$name.prompt")?.let { text ->
            cache[cacheKey] = text
            return text
        }
        Logger.w(TAG, "模板加载失败: $name (locale=$locale)")
        return null
    }

    /** 读取 assets 下模板文件;缺失返回 null,其他 IO/读取异常记录告警后返回 null。 */
    private fun readAsset(path: String): String? = try {
        context.assets.open(path).bufferedReader().use { it.readText() }
    } catch (_: java.io.FileNotFoundException) {
        // 按 locale 回落链探测时缺失属正常情况,交由下一级回落处理
        null
    } catch (e: Exception) {
        Logger.w(TAG, "读取提示词模板资产失败: $path (${e.message})", e)
        null
    }

    /**
     * 清除模板缓存(模板文件变更或 locale 切换时调用)。
     */
    fun clearCache() {
        cache.clear()
    }
}
