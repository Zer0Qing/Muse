package io.zer0.muse.web

/**
 * 设置主页需要的运行时值。
 *
 * 只装渲染要用的东西:少量只读展示值 + 常用开关的当前状态 + 子页表单用的原始值表。
 */
internal data class HostSettingsValues(
    val language: String = "",
    val themeMode: String = "",
    val fontSizeScale: String = "",
    val themeId: String = "",
    val toggles: Map<String, Boolean> = emptyMap(),
    /**
     * 子页表单的原始值，键同 [HostSettingDefinitions.all]。
     *
     * 一律用字符串承载（布尔 `true`/`false`，数字十进制文本），与写入时的字符串口径一致，
     * 快照里的类型信息由定义表提供，不需要在这里再造一套类型。
     */
    val raw: Map<String, String> = emptyMap(),
)

/**
 * 设置的读写口子。
 *
 * 写入是「键 + 已按类型规范化的字符串」：键必须在 [HostSettingDefinitions.writableKeys] 内、
 * 值必须通过该项的类型校验，这两件事在 [HostSettingsCommands] 里完成；到了这一层只剩
 * 按键分发到 SettingsRepository 的对应方法，浏览器提交的字符串不会原样进仓库。
 */
internal interface HostSettingsAccess {
    suspend fun readValues(): HostSettingsValues

    suspend fun write(key: String, value: String)
}

internal sealed class HostSettingsUpdateResult {
    data class Applied(val key: String) : HostSettingsUpdateResult()

    data class Rejected(val reason: String) : HostSettingsUpdateResult()
}

/**
 * settings.snapshot / settings.update 的实现。
 *
 * 白名单、类型、范围、枚举取值全部由 [HostSettingDefinitions] 这一份定义决定：
 * 下发清单与可写键集合同源，校验与写入共用同一份类型信息，不会出现「快照里有、写不进去」
 * 或「能写进去、快照里看不到」的漂移。
 *
 * 快照里的 `title` / `desc` / `groupTitle` / 枚举候选显示名都是已本地化文本，由
 * [HostSettingsStrings] 按当前应用语言解析后下发；资源名（`titleKey` / `descKey` / 分组 `key`）
 * 继续保留在协议里作为稳定标识。网页端因此不需要维护第二份文案。
 */
internal class HostSettingsCommands(
    private val access: HostSettingsAccess,
    private val strings: HostSettingsStrings,
) {

    suspend fun snapshot(): HostSettingsSnapshot {
        val values = access.readValues()
        return HostSettingsSnapshot(
            groups = HostSettingsCatalog.groups.map { group ->
                group.copy(
                    groupTitle = strings.resolve(group.key, values.language).orEmpty(),
                    entries = group.entries.map { it.withChecked(values.toggles).withLocalizedText(values.language) },
                )
            },
            toggles = HostSettingKeys.readable.map { key ->
                HostSettingToggle(key = key, enabled = values.toggles[key] ?: false)
            },
            display = HostSettingsDisplay(
                language = values.language,
                themeMode = values.themeMode,
                fontSizeScale = values.fontSizeScale,
                themeId = values.themeId,
            ),
            writableKeys = HostSettingDefinitions.writableKeys,
            values = HostSettingDefinitions.all.map { it.toProtocol(values.raw, values.language) },
        )
    }

    suspend fun update(key: String?, rawValue: String?): HostSettingsUpdateResult {
        val definition = HostSettingDefinitions.find(key) ?: return HostSettingsUpdateResult.Rejected(
            if (key.isNullOrBlank()) {
                "settings.update requires key"
            } else {
                "settings.update rejected: '${key.trim()}' is not a known setting"
            },
        )
        if (!definition.writable) {
            return HostSettingsUpdateResult.Rejected("settings.update rejected: '${definition.key}' is read-only")
        }
        return when (val parsed = definition.parse(rawValue)) {
            is HostSettingParseResult.Rejected -> HostSettingsUpdateResult.Rejected(parsed.reason)
            is HostSettingParseResult.Ok -> {
                access.write(definition.key, parsed.canonical)
                HostSettingsUpdateResult.Applied(definition.key)
            }
        }
    }

    /** 拼成协议里的一项：值 + 类型 + 本地化候选。 */
    private fun HostSettingDefinition.toProtocol(raw: Map<String, String>, language: String): HostSettingValue =
        HostSettingValue(
            key = key,
            type = type.wireName,
            value = raw[key] ?: defaultWireValue(),
            options = options.map { (value, labelResource) ->
                HostSettingOption(value = value, label = strings.resolve(labelResource, language).orEmpty())
            },
            writable = writable,
            min = min,
            max = max,
            step = step,
        )

    /** 取不到值时布尔按 false 回填，其余留空——和 toggles 的兜底同口径。 */
    private fun HostSettingDefinition.defaultWireValue(): String =
        if (type == HostSettingType.BOOLEAN) "false" else ""

    private fun HostSettingsEntry.withChecked(toggles: Map<String, Boolean>): HostSettingsEntry {
        val toggleKey = toggleKey ?: return this
        return copy(checked = toggles[toggleKey] ?: false)
    }

    /** 填本地化文本。解析不到就留空——宁可少一行字也不把资源名当文案显示。 */
    private fun HostSettingsEntry.withLocalizedText(language: String): HostSettingsEntry = copy(
        title = strings.resolve(titleKey, language).orEmpty(),
        // 没声明 descKey 的条目(说明由运行时拼,如代理状态)不下发说明。
        desc = descKey?.let { key -> strings.resolve(key, language) },
    )
}
