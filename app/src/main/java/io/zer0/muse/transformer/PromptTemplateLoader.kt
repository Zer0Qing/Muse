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
 */
class PromptTemplateLoader(private val context: Context) {

    private val engine = PebbleTemplateEngine()
    // M-TPL6: 多协程并发访问(如 buildStaticSnapshot 内多次 render),用 ConcurrentHashMap 保证线程安全
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
        )
    }

    /**
     * 加载并渲染指定模板。
     *
     * @param name 模板名称(对应 assets/prompt_templates/{name}.prompt)
     * @param context 模板变量上下文(可选)
     * @param fallback 文件加载失败时的默认值
     */
    fun render(
        name: String,
        context: Map<String, Any?> = emptyMap(),
        fallback: String = "",
    ): String {
        val templateText = loadTemplate(name) ?: return fallback
        return try {
            engine.render(templateText, context)
        } catch (e: Exception) {
            Logger.w(TAG, "模板渲染失败: $name", e)
            templateText // 渲染失败时返回原始文本
        }
    }

    /**
     * 加载模板文本(带缓存)。
     */
    private fun loadTemplate(name: String): String? {
        cache[name]?.let { return it }

        return try {
            val fileName = "$TEMPLATE_DIR/$name.prompt"
            context.assets.open(fileName).bufferedReader().use { reader ->
                reader.readText().also {
                    cache[name] = it
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "模板加载失败: $name", e)
            null
        }
    }

    /**
     * 清除模板缓存(模板文件变更时调用)。
     */
    fun clearCache() {
        cache.clear()
    }
}
