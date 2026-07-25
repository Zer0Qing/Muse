package io.zer0.muse.transformer

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.TimeZone

/**
 * Prompt 模板 Transformer(Phase 8.1 H1 + Phase 9.2 M22 增强 + v1.97 变量扩展)。
 *
 * 把 SYSTEM 消息 content 当作 Pebble 兼容模板渲染,支持:
 *  - 变量: {{ var }} / {{ user.name }}(点号路径)
 *  - 过滤器: {{ var | upper }} / {{ var | default('foo') }} / {{ list | length }}
 *  - 条件: {% if cond %}...{% elif c %}...{% else %}...{% endif %}
 *  - 循环: {% for item in items %}...{% endfor %}(loop.index/first/last/length)
 *  - 注释: {# comment #}
 *
 * 内置变量:
 *  - {{ date }}: 当前日期(yyyy-MM-dd)
 *  - {{ time }}: 当前时间(HH:mm:ss)
 *  - {{ datetime }}: 当前日期时间(yyyy-MM-dd HH:mm:ss)
 *  - {{ session_id }}: 当前会话 id
 *  - {{ model_id }}: 当前模型 id
 *  - {{ assistant_name }}: 当前 Assistant 名称(若 extras 提供)
 *
 * v1.97 新增(参考 rikkahub 13 个内置变量):
 *  - {{ locale }}: 系统语言/地区(如 zh-CN、en-US)
 *  - {{ timezone }}: 系统时区(如 Asia/Shanghai)
 *  - {{ device_info }}: 设备型号与厂商(如 "Pixel 7 (Google)")
 *  - {{ battery_level }}: 电池电量百分比(0-100,整数;读取失败为 "unknown")
 *  - {{ user }} / {{ user_name }} / {{ nickname }}: 用户昵称(从 extras "user_nickname" 读取)
 *  - {{ char }} / {{ character_name }}: 角色名(同 assistant_name,优先 extras "assistant_name")
 *
 * 自定义变量: extras 里 "template_vars" → Map<String, Any?> 会合并进变量映射。
 *
 * 用法:
 * ```
 * val context = TransformContext(
 *     extras = mapOf("template_vars" to mapOf("user_name" to "Alice"))
 * )
 * // system prompt: "你好 {{ user_name }},今天是 {{ date }}"
 * // → "你好 Alice,今天是 2026-07-04"
 * ```
 *
 * Phase 9.2 (M22) 升级:
 *  - 旧版用正则 [varRegex] 只支持 `{{var}}` 简单替换
 *  - 新版改用 [PebbleTemplateEngine],支持条件/循环/过滤器
 *  - 向后兼容:仅含 `{{var}}` 的旧模板仍能正常工作
 *  - 错误兜底:模板语法错误时返回原文(不抛异常,避免阻塞对话)
 *
 * v1.97 升级:
 *  - 新增 8 个内置变量(locale/timezone/device_info/battery_level/user/user_name/nickname/char)
 *  - 系统级变量(locale/timezone/device_info/battery_level)在 Transformer 内部计算,无需调用方传入
 *  - 用户级变量(user/user_name/nickname/char)从 extras 读取,由 ChatViewModel 注入
 */
class TemplateTransformer(
    /** v1.97: 用于读取电池电量(可空,单元测试时可不传)。 */
    private val appContext: Context? = null,
) : Transformer {

    override val name: String = "Template"

    /** Phase 9.2: Pebble 兼容模板引擎(单例,无状态)。 */
    private val engine = PebbleTemplateEngine()

    override suspend fun transform(
        messages: List<UIMessage>,
        context: TransformContext,
    ): List<UIMessage> {
        // 只处理 SYSTEM 消息(其他角色消息不做模板替换,避免改动用户原文)
        // L-TPL3: 合并为单次遍历(原代码遍历两次)
        val hasTemplate = messages.any {
            it.role == MessageRole.SYSTEM && (it.content.contains("{{") || it.content.contains("{%"))
        }
        if (!hasTemplate) return messages

        // 收集变量映射(传给 PebbleTemplateEngine)
        val vars: Map<String, Any?> = buildMap {
            // 内置变量 — 时间
            val now = java.time.LocalDateTime.now()
            put("date", now.toLocalDate().toString())
            put("time", now.toLocalTime().toString().take(8))
            put("datetime", now.toString().take(19).replace('T', ' '))
            context.sessionId?.let { put("session_id", it) }
            context.modelId?.let { put("model_id", it) }

            // v1.97: 内置变量 — 系统信息(无需调用方传入)
            put("locale", Locale.getDefault().toLanguageTag())
            put("timezone", TimeZone.getDefault().id)
            put("device_info", "${Build.MODEL} (${Build.MANUFACTURER})")
            put("battery_level", readBatteryLevel())

            // v1.97: 用户级变量 — 从 extras 读取(ChatViewModel 注入)
            // v1.97 修复:原 {{userName}} 占位符无法替换,因 TemplateTransformer 只注册了 snake_case 别名。
            //           现补充 camelCase 别名 userName / assistantName,与 snake_case 等价,兼容用户手写习惯。
            // v1.97 兜底:user_nickname 为空时给默认值"你",避免 {{user_name}} 字面量留在 prompt 中。
            // v1.109 修复: assistant_name 为空时给默认值"Muse"(品牌名),避免 {{char}} 渲染为空串。
            val userNickname = (context.extra("user_nickname") as? String) ?: "你"
            put("user", userNickname)
            put("user_name", userNickname)
            put("nickname", userNickname)
            put("userName", userNickname)  // v1.97: camelCase 别名
            val assistantName = (context.extra("assistant_name") as? String) ?: "Muse"
            put("assistant_name", assistantName)
            put("char", assistantName)
            put("character_name", assistantName)
            put("assistantName", assistantName)  // v1.97: camelCase 别名

            // 用户自定义变量(template_vars 是 Map<String, Any?>)
            (context.extra("template_vars") as? Map<*, *>)?.forEach { (k, v) ->
                if (k is String) put(k, v)
            }
        }
        // L-TPL4: vars 至少包含 date/time/datetime(永不为空),原 if (vars.isEmpty()) return messages 为死代码已删除
        // M-TPL5: 改用 for 循环而非 messages.map,以便在循环体内调用 suspend 的 withContext。
        // PebbleTemplateEngine.render 是同步阻塞调用,复杂模板(含 for 循环/嵌套 if)可能耗时,
        // 用 withContext(Dispatchers.Default) 包裹避免阻塞当前协程(如主线程派发的 IO 协程)。
        val result = ArrayList<UIMessage>(messages.size)
        for (msg in messages) {
            if (msg.role != MessageRole.SYSTEM) {
                result.add(msg)
                continue
            }
            if (!msg.content.contains("{{") && !msg.content.contains("{%")) {
                result.add(msg)
                continue
            }

            // 错误兜底:模板语法错误时返回原文,不阻塞对话
            val rendered = withContext(Dispatchers.Default) {
                runCatching { engine.render(msg.content, vars) }.getOrElse {
                    // 渲染失败:保留原文,Logger 记录(避免阻塞对话)
                    Logger.w("TemplateTransformer", "模板渲染失败: ${it.message}")
                    msg.content
                }
            }
            result.add(msg.copy(content = rendered))
        }
        return result
    }

    /**
     * v1.97: 读取电池电量百分比(0-100)。
     *
     * 通过 [BatteryManager] 系统服务读取,失败时返回 "unknown"。
     * appContext 为空时(单元测试)也返回 "unknown"。
     */
    private fun readBatteryLevel(): String {
        val ctx = appContext ?: return "unknown"
        return runCatching {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return "unknown"
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level in 0..100) level.toString() else "unknown"
        }.getOrElse {
            Logger.w("TemplateTransformer", "读取电池电量失败: ${it.message}")
            "unknown"
        }
    }
}
