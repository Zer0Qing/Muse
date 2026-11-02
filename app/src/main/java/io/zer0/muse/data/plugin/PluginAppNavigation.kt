package io.zer0.muse.data.plugin

/**
 * 插件可以跳转到的目标（`app.navigate`）。
 *
 * 白名单而不是"任意深链"：跳转目标决定用户看到什么，一个插件不该能把用户
 * 弹到任意内部页面。这份表**逐条对齐宿主已有的深链 host**——
 * 表里多写一个宿主不认的名字，插件就会"调用成功但什么都没发生"，那是最难查的一类失败。
 *
 * 参数化目标（某个会话/某条定时任务/某条速记）单独处理：id 必须符合宿主既有的 id 规则，
 * 否则拼进 URI 就成了注入点。
 */
object PluginAppNavigation {

    /** 固定目标：名字即深链 host，与 `ShareIntentHandler.parseDeepLink` 一一对应。 */
    enum class Target(val host: String) {
        HOME("home"),
        NEW_SESSION("new"),
        CHAT("chat"),
        ASSISTANTS("assistants"),
        SETTINGS("settings"),
        SCHEDULED_TASKS("scheduled-tasks"),
        QUICK_NOTES("quick-notes"),
        MEMORY("memory"),
        KNOWLEDGE("knowledge"),
        KNOWLEDGE_BASES("knowledge-bases"),
        CLOUD_BACKUP("cloud-backup"),
        SETTINGS_ABOUT("settings-about"),
        SETTINGS_AGENT("settings-agent"),
        SETTINGS_MODEL("settings-model"),
        SETTINGS_DATA("settings-data"),
        ;

        val uri: String get() = "muse://$host"
    }

    /** 参数化目标：`session:<id>` / `scheduled-task:<id>` / `quick-note:<id>`。 */
    enum class Parametric(val host: String) {
        SESSION("session"),
        SCHEDULED_TASK("scheduled-task"),
        QUICK_NOTE("quick-note"),
        ;
    }

    sealed interface Parsed {
        data class Ok(val uri: String) : Parsed
        data class Invalid(val reason: String) : Parsed
    }

    /** id 规则与通知深链一致：字母/数字/下划线/短横线，1–64。 */
    private val ID_RULE = Regex("^[A-Za-z0-9_-]{1,64}$")

    /**
     * 解析插件传来的目标。
     *
     * 接受三种写法，都归一化成 URI，避免作者在写法之间猜：
     *  - 枚举名：`settings` / `scheduled-tasks`
     *  - 完整 URI：`muse://settings`
     *  - 参数化：`session:<id>` 或 `muse://session/<id>`
     */
    fun parse(raw: String?): Parsed {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Parsed.Invalid("缺少 target 参数（可用值见作者文档）")

        parametricId(text)?.let { (target, id) ->
            return if (ID_RULE.matches(id)) {
                Parsed.Ok("muse://${target.host}/$id")
            } else {
                Parsed.Invalid("${target.host} 的 id 不合法：只允许字母、数字、下划线与短横线，长度 1-64")
            }
        }

        val normalized = text.removePrefix("muse://").trim('/')
        val target = Target.entries.firstOrNull {
            it.name.equals(normalized, ignoreCase = true) || it.host == normalized
        }
        return if (target != null) {
            Parsed.Ok(target.uri)
        } else {
            Parsed.Invalid(
                "不支持的跳转目标 '$text'；可用：" +
                    Target.entries.joinToString("、") { it.host } +
                    "；参数化：session:<id>、scheduled-task:<id>、quick-note:<id>",
            )
        }
    }

    /** 命中参数化目标时返回目标与 id。 */
    private fun parametricId(text: String): Pair<Parametric, String>? {
        Parametric.entries.forEach { target ->
            val prefix = "muse://${target.host}/"
            if (text.startsWith(prefix)) return target to text.removePrefix(prefix)
            val shorthand = "${target.host}:"
            if (text.startsWith(shorthand)) return target to text.removePrefix(shorthand)
        }
        return null
    }
}
