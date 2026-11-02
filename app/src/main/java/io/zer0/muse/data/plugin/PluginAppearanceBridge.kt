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
            fun parse(raw: String?): Mode? =
                entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }
        }
    }

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
