package io.zer0.muse.data.plugin

import io.zer0.muse.data.SettingsRepository

/**
 * 插件改外观（`app.theme`）。
 *
 * 只开放**明暗模式**（浅色/深色/跟随系统），不开放具体皮肤 id：
 *
 *  - 皮肤 id 的取值空间包含**用户自建主题**，而自建主题是 UI 层的数据，
 *    数据层拿不到完整清单 —— 校验不了就等于让插件写一个"不存在的主题"，
 *    结果要么静默无效，要么把界面弄成默认色，用户会以为坏了；
 *  - 而且挑哪个皮肤是**口味**，不该由插件替用户决定；明暗模式是使用场景
 *    （晚上/白天），这才是插件能合理推断的东西。
 *
 * 与 [PluginMessageBridge] 同样是"宿主写入"类能力：默认关，需要用户逐项打开。
 */
class PluginAppearanceBridge(
    private val settings: SettingsRepository,
) {

    sealed interface Applied {
        data object Ok : Applied
        data class Rejected(val reason: String) : Applied
    }

    /** 允许的模式，与设置页的取值保持一致。 */
    enum class Mode(val wire: String) {
        LIGHT("light"),
        DARK("dark"),
        SYSTEM("system"),
        ;

        companion object {
            fun parse(raw: String?): Mode? = entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }
        }
    }

    /**
     * 可写的设置项白名单。
     *
     * 只有**外观类**、且**可逆**的项：字号、动态取色、高对比、默认首页。
     * 不放开关类（例如"是否允许插件执行"）、不放数据类（例如备份路径）——
     * 那些被插件改掉之后，用户很难意识到发生了什么，也很难改回来。
     */
    enum class SettingKey(val wire: String) {
        FONT_SIZE_SCALE("font_size_scale"),
        DYNAMIC_COLOR("dynamic_color"),
        HIGH_CONTRAST("high_contrast"),
        DEFAULT_HOME_PAGE("default_home_page"),
        ;

        companion object {
            fun parse(raw: String?): SettingKey? = entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }
        }
    }

    /**
     * 写一个白名单里的设置项。
     *
     * @param value 统一用字符串传：`font_size_scale` 是 "1.15" 这种，
     *        布尔用 "true"/"false"，整数用十进制。类型由 key 决定，不猜。
     */
    suspend fun setSetting(rawKey: String?, rawValue: String?): Applied {
        val key = SettingKey.parse(rawKey)
            ?: return Applied.Rejected(
                "不支持的设置项 '$rawKey'；可写：" +
                    SettingKey.entries.joinToString("、") { it.wire },
            )
        val value = rawValue?.trim().orEmpty()
        return when (key) {
            SettingKey.FONT_SIZE_SCALE -> {
                val scale = value.toFloatOrNull()
                when {
                    scale == null -> Applied.Rejected("font_size_scale 需要数字，例如 1.15")
                    scale !in MIN_FONT_SCALE..MAX_FONT_SCALE -> Applied.Rejected(
                        "font_size_scale 超出范围（$MIN_FONT_SCALE–$MAX_FONT_SCALE）：$scale",
                    )
                    else -> write("font_size_scale") { settings.saveFontSizeScale(scale.toString()) }
                }
            }
            SettingKey.DYNAMIC_COLOR -> writeBool(value, "dynamic_color") {
                settings.saveDynamicColor(it)
            }
            SettingKey.HIGH_CONTRAST -> writeBool(value, "high_contrast") {
                settings.saveHighContrast(it)
            }
            SettingKey.DEFAULT_HOME_PAGE -> {
                val page = value.toIntOrNull()
                when {
                    page == null -> Applied.Rejected("default_home_page 需要整数，例如 0")
                    page !in MIN_HOME_PAGE..MAX_HOME_PAGE -> Applied.Rejected(
                        "default_home_page 超出范围（$MIN_HOME_PAGE–$MAX_HOME_PAGE）：$page",
                    )
                    else -> write("default_home_page") { settings.saveDefaultHomePage(page) }
                }
            }
        }
    }

    private suspend fun writeBool(raw: String, label: String, save: suspend (Boolean) -> Unit): Applied = when (raw.lowercase()) {
        "true" -> write(label) { save(true) }
        "false" -> write(label) { save(false) }
        else -> Applied.Rejected("$label 需要 true 或 false：'$raw'")
    }

    private suspend fun write(label: String, block: suspend () -> Unit): Applied = runCatching { block() }
        .fold(
            onSuccess = { Applied.Ok },
            onFailure = { error ->
                Applied.Rejected("写入 $label 失败：${error.message ?: error.javaClass.simpleName}")
            },
        )

    suspend fun setMode(raw: String?): Applied {
        val mode = Mode.parse(raw)
            ?: return Applied.Rejected(
                "不支持的模式 '$raw'：只允许 " + Mode.entries.joinToString(" / ") { it.wire },
            )
        return runCatching {
            settings.saveThemeMode(mode.wire)
            Applied.Ok as Applied
        }.getOrElse { error ->
            Applied.Rejected("切换外观失败：${error.message ?: error.javaClass.simpleName}")
        }
    }
}

/** 字号范围：与设置页滑块一致。 */
private const val MIN_FONT_SCALE = 0.8f
private const val MAX_FONT_SCALE = 1.6f

/** 首页取值：0=任务，1=Agent，2=聊天（与设置页一致）。 */
private const val MIN_HOME_PAGE = 0
private const val MAX_HOME_PAGE = 2

/**
 * 进程级持有外观通道。
 *
 * 与 [PluginMessageChannels] 同一手法：避免给 `SkillMediaToolsImpl` 再加构造参数
 * （那会连带 detekt 基线按签名失配）。**如果以后出现第三个这类通道，应当合并成一个
 * `PluginHostServices`** —— 现在只有两个，合并的收益还盖不过改动面。
 */
object PluginAppearanceChannels {

    @Volatile
    private var bridge: PluginAppearanceBridge? = null

    val current: PluginAppearanceBridge? get() = bridge

    fun install(value: PluginAppearanceBridge?) {
        bridge = value
    }

    /** 仅测试用。 */
    internal fun resetForTests() {
        bridge = null
    }
}
