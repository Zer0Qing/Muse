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

/**
 * S2:写入侧在**动任何数据之前**就拒绝了这个键/值。
 *
 * 单独一个类型是为了让 [HostSettingsCommands] 能分辨两件事:
 *  - 这一类异常 → 数据没被碰过,不需要也不该声称「已回滚」;
 *  - 其它异常 → 可能改到一半,必须按回滚处理。
 * 用 IllegalArgumentException 混在里面会让回滚逻辑对前者也说「已回滚」,那就是在撒谎。
 */
internal class SettingsWriteReject(message: String) : IllegalStateException(message)

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
            is HostSettingParseResult.Ok -> writeWithRollback(definition, parsed.canonical)
        }
    }

    /**
     * S2:一次被受理的写入要么落地,要么回到写入前的样子,而且**说得出为什么没落地**。
     *
     * 三条纪律:
     *  1. 校验通过不等于写成功。写入侧抛出来的问题必须回执 Rejected,
     *     否则网页端已经把开关拨过去了,还以为保存好了;
     *  2. 通用异常按「可能改到一半」处理:先回滚再报告,回滚本身失败也要说出来;
     *     [SettingsWriteReject] 表示写入侧在动任何数据之前就拒绝了,不回滚也不假装回滚过。
     *  3. 失败原因里不许出现提交的值。设置项以后会有密钥类的文本值(中继密钥、Webhook secret),
     *     底层异常的 message 经常把输入原样带出来(`NumberFormatException: For input string: "sk-…"`),
     *     而这些文本会被网页端显示、被用户截图求助 —— 泄漏就是这么走的。
     */
    @Suppress("TooGenericExceptionCaught") // 写入侧可能是 DataStore IO、序列化、仓库内部任何一种失败;漏掉任何一种都会把回滚跳过
    private suspend fun writeWithRollback(definition: HostSettingDefinition, canonical: String): HostSettingsUpdateResult {
        val previous = runCatching { access.readValues().wireValueOf(definition) }.getOrNull()
        return try {
            access.write(definition.key, canonical)
            HostSettingsUpdateResult.Applied(definition.key)
        } catch (error: SettingsWriteReject) {
            HostSettingsUpdateResult.Rejected(describeFailure(definition, error.message, canonical, ""))
        } catch (error: Exception) {
            val tail = when {
                previous == null -> " (原值未能读出,配置未改动)"
                runCatching { access.write(definition.key, previous) }.isSuccess -> " (已回滚到写入前的值)"
                else -> " (回滚也失败,请用手机端检查这一项)"
            }
            HostSettingsUpdateResult.Rejected(describeFailure(definition, error.message, canonical, tail))
        }
    }

    /**
     * 组装失败原因:说得出哪一项 + 底层给的理由 + 回滚结果,且不含提交的值。
     *
     * 底层理由里已经带键名的([SettingsWriteReject] 那一类)不再重复冠一遍,
     * 否则同一条消息里会出现两次 `liquidGlassStrength`。
     */
    private fun describeFailure(definition: HostSettingDefinition, rawReason: String?, canonical: String, tail: String): String {
        val scrubbed = scrubValue(rawReason ?: "settings.update failed", canonical)
        val withKey = if (scrubbed.contains(definition.key)) scrubbed else "settings.update failed for '${definition.key}': $scrubbed"
        return withKey + tail
    }

    /** 把提交值从原因文本里抹掉;短值(布尔、小整数)不抹,否则会把话说没。 */
    private fun scrubValue(reason: String, value: String): String = if (value.length >= MIN_SCRUB_CHARS && reason.contains(value)) {
        reason.replace(value, "<value>")
    } else {
        reason
    }

    /** 当前这一项在线上的文本值(布尔在 toggles,其余在 raw)。 */
    private fun HostSettingsValues.wireValueOf(definition: HostSettingDefinition): String? = when {
        definition.type == HostSettingType.BOOLEAN -> toggles[definition.key]?.toString() ?: raw[definition.key]
        else -> raw[definition.key]
    }

    /** 拼成协议里的一项：值 + 类型 + 本地化候选。 */
    private fun HostSettingDefinition.toProtocol(raw: Map<String, String>, language: String): HostSettingValue = HostSettingValue(
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
        advanced = advanced,
        widget = widgetName(),
        dependsOn = dependsOnKey?.let { HostSettingDependency(key = it, values = dependsOnValues) },
    )

    /** 取不到值时布尔按 false 回填，其余留空——和 toggles 的兜底同口径。 */
    private fun HostSettingDefinition.defaultWireValue(): String = if (type == HostSettingType.BOOLEAN) "false" else ""

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

    private companion object {
        /**
         * 短于这个长度的提交值不从失败原因里抹掉。
         *
         * 「true」「5」这类值出现在 message 里不构成泄漏,抹掉反而把话说没;
         * 而能构成密钥的值不会短到这个以下。
         */
        const val MIN_SCRUB_CHARS = 6
    }
}
