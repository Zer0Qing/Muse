package io.zer0.muse.web

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** settings.snapshot 载荷（含子页 values）与 settings.update 类型校验的行为测试。 */
class HostSettingsCommandsTest {

    /** 假仓储：记录写入并让随后的读取看到新值，等价于“写进去再读回来”。 */
    private class FakeAccess(initial: HostSettingsValues = HostSettingsValues()) : HostSettingsAccess {
        private val overrides = mutableMapOf<String, String>()
        private val initialValues = initial
        val writes = mutableListOf<Pair<String, String>>()

        override suspend fun readValues(): HostSettingsValues = initialValues.copy(raw = initialValues.raw + overrides)

        override suspend fun write(key: String, value: String) {
            writes += key to value
            overrides[key] = value
        }
    }

    /** 假文案解析：每个资源名都解析出带语言前缀的文本，便于断言“文本来自解析而不是资源名”。 */
    private val strings = HostSettingsStrings { name, language -> "text($language):$name" }

    private fun commands(access: HostSettingsAccess) = HostSettingsCommands(access, strings)

    private fun HostSettingsSnapshot.value(key: String): HostSettingValue = values.single { it.key == key }

    // ── values 覆盖 ──────────────────────────────────────────────

    @Test
    fun `values cover every category with the right type`() = runTest {
        val snapshot = commands(FakeAccess()).snapshot()
        val byKey = snapshot.values.associateBy { it.key }

        // 外观
        assertEquals("enum", byKey.getValue("themeMode").type)
        assertEquals("enum", byKey.getValue("fontSizeScale").type)
        assertEquals("enum", byKey.getValue("liquidGlassMode").type)
        assertEquals("number", byKey.getValue("liquidGlassStrength").type)
        assertEquals("boolean", byKey.getValue("dynamicColor").type)
        assertEquals("boolean", byKey.getValue("highContrast").type)
        // 聊天
        assertEquals("boolean", byKey.getValue(HostSettingKeys.CHAT_ENTER_TO_SEND).type)
        assertEquals("boolean", byKey.getValue("chatPreferences.showToolCallDetails").type)
        assertEquals("boolean", byKey.getValue("chatPreferences.showTimestamp").type)
        assertEquals("boolean", byKey.getValue(HostSettingKeys.CHAT_STREAM_RESPONSE).type)
        assertEquals("boolean", byKey.getValue("chatPreferences.forceReasoning").type)
        assertEquals("enum", byKey.getValue("chatPreferences.reasoningLevel").type)
        // 语音
        assertEquals("enum", byKey.getValue("mediaConfig.ttsEngine").type)
        assertEquals("number", byKey.getValue("mediaConfig.ttsSpeechRate").type)
        assertEquals("text", byKey.getValue("mediaConfig.ttsVoiceName").type)
        // 记忆
        assertEquals("boolean", byKey.getValue(HostSettingKeys.MEMORY).type)
        assertEquals("boolean", byKey.getValue("notificationMemoryEnabled").type)
        // 通用
        assertEquals("enum", byKey.getValue("language").type)
        assertEquals("boolean", byKey.getValue("model.configured").type)
    }

    @Test
    fun `number items carry their range so the client can render a slider`() = runTest {
        val snapshot = commands(FakeAccess()).snapshot()

        val strength = snapshot.value("liquidGlassStrength")
        assertEquals(0.0, strength.min)
        assertEquals(100.0, strength.max)
        assertNotNull("数字项必须给步进,否则滑块无处落点", strength.step)

        val rate = snapshot.value("mediaConfig.ttsSpeechRate")
        assertEquals(0.5, rate.min)
        assertEquals(2.0, rate.max)
    }

    @Test
    fun `every enum option carries a localized label`() = runTest {
        val snapshot = commands(FakeAccess(HostSettingsValues(language = "ja"))).snapshot()

        val enums = snapshot.values.filter { it.type == "enum" }
        assertTrue("至少要有主题/语言/引擎/思考强度几个枚举项", enums.size >= 4)
        enums.forEach { item ->
            assertTrue("${item.key} 应带候选", item.options.isNotEmpty())
            item.options.forEach { option ->
                assertTrue("${item.key}/${option.value} 的显示名为空", option.label.isNotBlank())
                // 显示名来自 Host 的本地化解析,不是资源名本身。
                assertTrue(
                    "${item.key}/${option.value} 的显示名不像本地化文本: ${option.label}",
                    option.label.startsWith("text(ja):"),
                )
            }
        }
    }

    @Test
    fun `values mirror the current settings`() = runTest {
        val access = FakeAccess(
            HostSettingsValues(
                language = "en",
                raw = mapOf(
                    "themeMode" to "dark",
                    "liquidGlassStrength" to "55",
                    "chatPreferences.reasoningLevel" to "MEDIUM",
                    "mediaConfig.ttsEngine" to "openai",
                    "mediaConfig.ttsSpeechRate" to "1.25",
                    "model.configured" to "true",
                ),
            ),
        )

        val snapshot = commands(access).snapshot()

        assertEquals("dark", snapshot.value("themeMode").value)
        assertEquals("55", snapshot.value("liquidGlassStrength").value)
        assertEquals("MEDIUM", snapshot.value("chatPreferences.reasoningLevel").value)
        assertEquals("openai", snapshot.value("mediaConfig.ttsEngine").value)
        assertEquals("1.25", snapshot.value("mediaConfig.ttsSpeechRate").value)
        assertEquals("true", snapshot.value("model.configured").value)
    }

    @Test
    fun `read only items are advertised but never writable`() = runTest {
        val snapshot = commands(FakeAccess()).snapshot()

        val readOnly = snapshot.values.filterNot { it.writable }.map { it.key }
        assertEquals(listOf("mediaConfig.ttsVoiceName", "model.configured"), readOnly)
        assertTrue("只读项不得进白名单", readOnly.none { it in snapshot.writableKeys })
    }

    /** 防漂移：白名单必须是 values 里可写项的子集，且两者都由同一份定义派生。 */
    @Test
    fun `writable keys and the values list cannot drift apart`() = runTest {
        val snapshot = commands(FakeAccess()).snapshot()

        assertEquals(HostSettingDefinitions.writableKeys, snapshot.writableKeys)
        assertEquals(
            "values 里的可写项必须与白名单完全一致",
            snapshot.values.filter { it.writable }.map { it.key },
            snapshot.writableKeys,
        )
        assertEquals("白名单与 values 数量不一致", snapshot.values.size, snapshot.values.map { it.key }.distinct().size)
    }

    // ── 写入成功 ────────────────────────────────────────────────

    @Test
    fun `boolean number and enum writes land and read back`() = runTest {
        val access = FakeAccess()

        assertEquals(
            HostSettingsUpdateResult.Applied(HostSettingKeys.PII_GUARD),
            commands(access).update(HostSettingKeys.PII_GUARD, "true"),
        )
        assertEquals(
            HostSettingsUpdateResult.Applied("liquidGlassStrength"),
            commands(access).update("liquidGlassStrength", "70"),
        )
        assertEquals(HostSettingsUpdateResult.Applied("themeMode"), commands(access).update("themeMode", "dark"))

        assertEquals(
            listOf(
                HostSettingKeys.PII_GUARD to "true",
                "liquidGlassStrength" to "70",
                "themeMode" to "dark",
            ),
            access.writes,
        )
        val snapshot = commands(access).snapshot()
        assertEquals("true", snapshot.value(HostSettingKeys.PII_GUARD).value)
        assertEquals("70", snapshot.value("liquidGlassStrength").value)
        assertEquals("dark", snapshot.value("themeMode").value)
    }

    @Test
    fun `boolean accepts case and surrounding spaces`() = runTest {
        val access = FakeAccess()

        assertEquals(HostSettingsUpdateResult.Applied(HostSettingKeys.MEMORY), commands(access).update(HostSettingKeys.MEMORY, " TRUE "))

        assertEquals(listOf(HostSettingKeys.MEMORY to "true"), access.writes)
    }

    // ── 拒绝路径 ────────────────────────────────────────────────

    @Test
    fun `unknown key is rejected`() = runTest {
        val access = FakeAccess()
        val result = commands(access).update("someMagicKey", "true")

        assertTrue(result is HostSettingsUpdateResult.Rejected)
        assertTrue((result as HostSettingsUpdateResult.Rejected).reason.contains("someMagicKey"))
        assertTrue("未知键不得写入仓库", access.writes.isEmpty())
    }

    @Test
    fun `type mismatch is rejected`() = runTest {
        val access = FakeAccess()

        listOf(null, "", "yes", "1", "on").forEach { raw ->
            val rejected = commands(access).update(HostSettingKeys.PII_GUARD, raw)
            assertTrue("值 $raw 应被类型校验拒绝", rejected is HostSettingsUpdateResult.Rejected)
        }
        assertTrue("类型不合法时不得写入仓库", access.writes.isEmpty())
    }

    @Test
    fun `number out of range is rejected`() = runTest {
        val access = FakeAccess()

        listOf("101", "-1", "1e9").forEach { raw ->
            val rejected = commands(access).update("liquidGlassStrength", raw)
            assertTrue("$raw 超出 0..100", rejected is HostSettingsUpdateResult.Rejected)
            assertTrue(
                "拒绝原因应点出范围: ${(rejected as HostSettingsUpdateResult.Rejected).reason}",
                rejected.reason.contains("out of range"),
            )
        }
        assertTrue(commands(access).update("mediaConfig.ttsSpeechRate", "0.1") is HostSettingsUpdateResult.Rejected)
        assertTrue("越界时不得写入仓库", access.writes.isEmpty())
    }

    @Test
    fun `invalid enum value is rejected`() = runTest {
        val access = FakeAccess()

        val rejected = commands(access).update("themeMode", "purple")
        assertTrue(rejected is HostSettingsUpdateResult.Rejected)
        assertTrue(
            "拒绝原因应列出候选: ${(rejected as HostSettingsUpdateResult.Rejected).reason}",
            rejected.reason.contains("dark"),
        )
        assertTrue(commands(access).update("mediaConfig.ttsEngine", "nope") is HostSettingsUpdateResult.Rejected)
        assertTrue("非法枚举值不得写入仓库", access.writes.isEmpty())
    }

    @Test
    fun `read only key is rejected`() = runTest {
        val access = FakeAccess()

        val rejected = commands(access).update("model.configured", "true")

        assertTrue(rejected is HostSettingsUpdateResult.Rejected)
        assertTrue((rejected as HostSettingsUpdateResult.Rejected).reason.contains("read-only"))
        assertTrue(access.writes.isEmpty())
    }

    @Test
    fun `missing key is rejected`() = runTest {
        val access = FakeAccess()

        assertTrue(commands(access).update(null, "true") is HostSettingsUpdateResult.Rejected)
        assertTrue(commands(access).update("   ", "true") is HostSettingsUpdateResult.Rejected)
        assertTrue(access.writes.isEmpty())
    }

    // ── 既有快照行为 ──────────────────────────────────────────────

    @Test
    fun `snapshot exposes every catalog group and the toggle state`() = runTest {
        val access = FakeAccess(
            HostSettingsValues(
                language = "zh",
                themeMode = "dark",
                fontSizeScale = "large",
                themeId = "sakura",
                toggles = mapOf(HostSettingKeys.PII_GUARD to true),
            ),
        )

        val snapshot = commands(access).snapshot()

        assertEquals(HostSettingsCatalog.groups.size, snapshot.groups.size)
        assertEquals(HostSettingsCatalog.groups.map { it.key }, snapshot.groups.map { it.key })
        assertEquals(
            HostSettingsCatalog.entries.map { it.id },
            snapshot.groups.flatMap { group -> group.entries.map { it.id } },
        )
        assertEquals("zh", snapshot.display.language)
        assertEquals("dark", snapshot.display.themeMode)
        assertEquals("large", snapshot.display.fontSizeScale)

        val switch = snapshot.groups.flatMap { it.entries }.single { it.kind == HostSettingsCatalog.KIND_SWITCH }
        assertEquals(HostSettingKeys.PII_GUARD, switch.toggleKey)
        assertEquals(true, switch.checked)
        assertTrue(
            snapshot.groups.flatMap { it.entries }
                .filter { it.kind != HostSettingsCatalog.KIND_SWITCH }
                .all { it.checked == null },
        )
    }

    @Test
    fun `snapshot keeps the readable toggle list within the agreed size`() = runTest {
        val snapshot = commands(FakeAccess()).snapshot()

        assertEquals(HostSettingKeys.readable.size, snapshot.toggles.size)
        assertTrue(
            "常用开关数量应在 8..12 之间,实际 ${snapshot.toggles.size}",
            snapshot.toggles.size in 8..12,
        )
        // 缺值按 false 回填而不是漏项,避免客户端把 undefined 当开关状态。
        assertTrue(snapshot.toggles.none { it.key.isBlank() })
        assertTrue(
            "可写键必须都能在 values 里读到,否则网页端写完后取不到新值",
            snapshot.writableKeys.all { key -> snapshot.values.any { it.key == key } },
        )
    }

    /**
     * 本地化文本随快照下发:分组带 groupTitle,条目带 title,声明了 descKey 的带 desc。
     * 这是网页端删掉自己的文案表后唯一的数据源,缺字就等于界面上空一行。
     */
    @Test
    fun `snapshot carries localized text for every group and entry`() = runTest {
        val snapshot = commands(FakeAccess(HostSettingsValues(language = "ja"))).snapshot()

        snapshot.groups.forEach { group ->
            assertTrue("分组 ${group.key} 缺少本地化标题", group.groupTitle.isNotBlank())
            assertEquals("text(ja):${group.key}", group.groupTitle)
            assertTrue("分组 ${group.key} 没有条目", group.entries.isNotEmpty())
            group.entries.forEach { entry ->
                assertTrue("条目 ${entry.id} 缺少本地化标题", entry.title.isNotBlank())
                assertEquals("text(ja):${entry.titleKey}", entry.title)
                assertEquals(entry.descKey?.let { "text(ja):$it" }, entry.desc)
            }
        }
        assertTrue(
            "至少要有一条带说明的条目,否则这条断言等于没测",
            snapshot.groups.flatMap { it.entries }.any { it.desc != null },
        )
    }

    @Test
    fun `unresolvable resources leave empty text instead of raw resource names`() = runTest {
        val missing = HostSettingsStrings { _, _ -> null }
        val snapshot = HostSettingsCommands(FakeAccess(), missing).snapshot()

        assertTrue("解析不到也不能把资源名当文案", snapshot.groups.all { it.groupTitle.isEmpty() })
        assertTrue(snapshot.groups.flatMap { it.entries }.all { it.title.isEmpty() && it.desc == null })
        assertTrue("枚举候选的显示名同样不能退回资源名", snapshot.values.all { item -> item.options.all { it.label.isEmpty() } })
    }

    // ── S2：写入事务（校验通过 ≠ 写成功）────────────────────────

    /** 能在写入时按条件抛错的假仓储；抛出的文本刻意带上提交的值，用来验脱敏。 */
    private class FlakyAccess(
        private val initial: HostSettingsValues,
        private val boom: (key: String, value: String) -> Throwable?,
    ) : HostSettingsAccess {
        val writes = mutableListOf<Pair<String, String>>()

        override suspend fun readValues(): HostSettingsValues = initial

        override suspend fun write(key: String, value: String) {
            writes += key to value
            boom(key, value)?.let { throw it }
        }
    }

    @Test
    fun `写入失败必须回执 Rejected 而不是 Applied`() = runTest {
        val access = FlakyAccess(
            HostSettingsValues(raw = mapOf("themeMode" to "dark")),
        ) { _, value -> IllegalStateException("DataStore failed while applying $value") }

        val result = commands(access).update("themeMode", "system")

        assertTrue("写失败了却回执 Applied,网页端会以为保存好了: $result", result is HostSettingsUpdateResult.Rejected)
    }

    @Test
    fun `失败后回到写入前的值`() = runTest {
        val access = FlakyAccess(
            HostSettingsValues(raw = mapOf("themeMode" to "dark")),
        ) { _, value -> if (value == "system") IllegalStateException("boom") else null }

        commands(access).update("themeMode", "system")

        assertEquals(
            "第一次写入 + 一次回滚,顺序与值都必须是这个",
            listOf("themeMode" to "system", "themeMode" to "dark"),
            access.writes,
        )
    }

    @Test
    fun `失败原因不回显提交的值`() = runTest {
        // 底层异常经常把输入原样写进 message(NumberFormatException 那类)。
        // 设置项以后会有密钥型文本值,而这段文本会被网页端显示、被用户截图求助。
        val access = FlakyAccess(
            HostSettingsValues(raw = mapOf("themeMode" to "dark")),
        ) { _, value -> IllegalStateException("rejected input: $value") }

        val reason = (commands(access).update("themeMode", "system") as HostSettingsUpdateResult.Rejected).reason

        assertFalse("原因里出现了提交的原值: $reason", reason.contains("system"))
        assertTrue("原值应被替换成占位符: $reason", reason.contains("<value>"))
        assertTrue("仍然要说清是哪一项: $reason", reason.contains("themeMode"))
    }

    @Test
    fun `写入侧预先拒绝时不声称已回滚`() = runTest {
        // SettingsWriteReject 的含义是「没碰过任何数据」。这时说「已回滚到写入前的值」是撒谎,
        // 而用户会去核对一个根本没被改过的开关。
        val access = FlakyAccess(
            HostSettingsValues(raw = mapOf("liquidGlassStrength" to "40")),
        ) { _, _ -> SettingsWriteReject("settings.update rejected: 'liquidGlassStrength' expects a whole number") }

        val reason = (commands(access).update("liquidGlassStrength", "12.5") as HostSettingsUpdateResult.Rejected).reason

        assertFalse("没有发生回滚却写了回滚文案: $reason", reason.contains("回滚"))
        assertEquals("被预先拒绝的写入不该有第二次落库动作", 1, access.writes.size)
    }

    @Test
    fun `回滚也失败时说出来而不是假装恢复`() = runTest {
        val access = FlakyAccess(
            HostSettingsValues(raw = mapOf("themeMode" to "dark")),
        ) { _, value -> if (value == "dark") IllegalStateException("restore failed") else IllegalStateException("write failed") }

        val reason = (commands(access).update("themeMode", "system") as HostSettingsUpdateResult.Rejected).reason

        assertTrue("回滚失败必须明说: $reason", reason.contains("回滚也失败"))
    }

    @Test
    fun `读不到原值时不声称已回滚`() = runTest {
        val access = FlakyAccess(
            HostSettingsValues(raw = emptyMap()),
        ) { _, _ -> IllegalStateException("disk full") }

        val reason = (commands(access).update("themeMode", "system") as HostSettingsUpdateResult.Rejected).reason

        assertTrue("读不到原值就要说「未改动」: $reason", reason.contains("原值未能读出"))
        assertEquals(1, access.writes.size)
    }

    // ── S1：设置元数据 ────────────────────────────────────────

    @Test
    fun `元数据随快照下发`() = runTest {
        val byKey = commands(FakeAccess()).snapshot().values.associateBy { it.key }

        val strength = byKey.getValue("liquidGlassStrength")
        assertTrue("玻璃强度是高级项", strength.advanced)
        assertEquals("有上下界与步进就该是滑杆", "slider", strength.widget)
        assertEquals("liquidGlassMode", strength.dependsOn?.key)
        assertEquals(setOf("water", "frost"), strength.dependsOn?.values?.toSet())

        val level = byKey.getValue("chatPreferences.reasoningLevel")
        assertEquals("chatPreferences.forceReasoning", level.dependsOn?.key)
        assertEquals(listOf("true"), level.dependsOn?.values)
        assertFalse("思考强度本身不是高级项，它只是有前置条件", level.advanced)

        assertEquals("select", byKey.getValue("themeMode").widget)
        assertEquals("switch", byKey.getValue(HostSettingKeys.CHAT_ENTER_TO_SEND).widget)
        assertFalse("派生展示值不许被标成高级", byKey.getValue("model.configured").advanced)
    }

    @Test
    fun `控件名由类型与范围派生而不是逐项手写`() {
        assertEquals(
            HostSettingWidget.SLIDER,
            HostSettingDefinition("k", HostSettingType.NUMBER, min = 0.0, max = 1.0, step = 0.1).widgetName(),
        )
        // 只有一个界画不出滑杆的行程,仍然是输入框
        assertEquals(HostSettingWidget.NUMBER, HostSettingDefinition("k", HostSettingType.NUMBER, min = 0.0).widgetName())
        assertEquals(HostSettingWidget.NUMBER, HostSettingDefinition("k", HostSettingType.NUMBER).widgetName())
        assertEquals(HostSettingWidget.SWITCH, HostSettingDefinition("k", HostSettingType.BOOLEAN).widgetName())
        assertEquals(
            HostSettingWidget.SELECT,
            HostSettingDefinition("k", HostSettingType.ENUM, options = listOf("a" to "x", "b" to "y")).widgetName(),
        )
        assertEquals(
            "只剩一个候选的 enum 画单选列表没有意义,退回文本",
            HostSettingWidget.TEXT,
            HostSettingDefinition("k", HostSettingType.ENUM, options = listOf("a" to "x")).widgetName(),
        )
        assertEquals(
            "显式覆盖优先于派生",
            "stepper",
            HostSettingDefinition("k", HostSettingType.NUMBER, min = 0.0, max = 1.0, step = 0.1, widget = "stepper").widgetName(),
        )
    }

    @Test
    fun `范围三件套真的下发到线上`() = runTest {
        // 客户端要画滑杆就得拿到 min/max/step;这三个字段以前只在 Kotlin 侧存在,
        // TypeScript 那边没声明,于是收到也被当成不存在。
        val rate = commands(FakeAccess()).snapshot().values.single { it.key == "mediaConfig.ttsSpeechRate" }

        assertEquals(0.5, rate.min ?: 0.0, 0.0001)
        assertEquals(2.0, rate.max ?: 0.0, 0.0001)
        assertEquals(0.1, rate.step ?: 0.0, 0.0001)
        assertTrue("语速是细调项", rate.advanced)
    }
}
