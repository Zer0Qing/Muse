package io.zer0.muse.data.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.7.x(E3): 人设卡的严格 schema。
 *
 * 严格性的价值在于**拒绝**,不在于宽容:一份拼错字段的文件如果被"尽力解释"成默认值,
 * 用户看到的是一次成功的导入 + 一个空白人设。所以下面每一条都是"必须被拒"。
 */
class PersonaCardSchemaTest {

    private val base = PersonaCard(name = "小满")

    private fun decode(text: String): PersonaParse = PersonaCardCodec.decode(text)

    @Test
    fun `合法卡解析通过`() {
        val outcome = decode(PersonaCardCodec.encode(base.copy(systemPrompt = "温和、就事论事")))

        assertTrue(outcome.toString(), outcome is PersonaParse.Ok)
        assertEquals("温和、就事论事", (outcome as PersonaParse.Ok).card.systemPrompt)
    }

    @Test
    fun `未知字段被拒绝而不是被忽略`() {
        val text = """{"schemaVersion":1,"kind":"muse.persona","name":"小满","customHeadersJson":{"Authorization":"x"}}"""

        val outcome = decode(text)
        assertTrue("未知键必须失败: $outcome", outcome is PersonaParse.Invalid)
    }

    @Test
    fun `类型不符被拒绝而不是悄悄降级`() {
        // AppJson 的 coerceInputValues 会把 "false" 这类值降级成默认;严格配置必须报错。
        val outcome = decode("""{"schemaVersion":1,"kind":"muse.persona","name":"小满","memoryEnabled":"yes please"}""")

        assertTrue("布尔写成字符串必须失败: $outcome", outcome is PersonaParse.Invalid)
    }

    @Test
    fun `缺必填字段被拒绝`() {
        val outcome = decode("""{"schemaVersion":1,"kind":"muse.persona"}""")

        assertTrue("没有 name 必须失败: $outcome", outcome is PersonaParse.Invalid)
    }

    @Test
    fun `kind 与版本不匹配各自给出原因`() {
        val wrongKind = decode("""{"schemaVersion":1,"kind":"sillytavern","name":"小满"}""")
        assertTrue(wrongKind.toString(), wrongKind is PersonaParse.Invalid)
        assertTrue((wrongKind as PersonaParse.Invalid).reason, wrongKind.reason.contains("kind"))

        val wrongVersion = decode("""{"schemaVersion":99,"kind":"muse.persona","name":"小满"}""")
        assertTrue(wrongVersion.toString(), wrongVersion is PersonaParse.Invalid)
        assertTrue((wrongVersion as PersonaParse.Invalid).reason, wrongVersion.reason.contains("99"))
    }

    @Test
    fun `名称为空或过长都拒绝`() {
        assertTrue("空名称必须被拒", decode("""{"schemaVersion":1,"kind":"muse.persona","name":""}""") is PersonaParse.Invalid)
        val long = "很".repeat(90)
        val outcome = decode("""{"schemaVersion":1,"kind":"muse.persona","name":"$long"}""")
        assertTrue(outcome.toString(), outcome is PersonaParse.Invalid)
    }

    @Test
    fun `超长正文拒绝而不是截断`() {
        // 截断会静默改掉人设 —— 用户导入的是完整版,存下来的却是前半段,这比拒绝难查得多。
        val huge = "设".repeat(200_001)
        val card = base.copy(systemPrompt = huge)

        val outcome = decode(PersonaCardCodec.encode(card))
        assertTrue("超正文必须被拒: $outcome", outcome is PersonaParse.Invalid)
        assertEquals("要指名是哪个字段", "systemPrompt", (outcome as PersonaParse.Invalid).field)
    }

    @Test
    fun `导出形状里没有凭据与本地引用的位置`() {
        val json = PersonaCardCodec.encode(base)

        val neverExported = listOf(
            "customHeaders", "customBodies", "providerId", "modelId", "toolModelId",
            "messageCount", "lastUsedAt", "sortIndex", "apiKey",
        )
        for (forbidden in neverExported) {
            assertFalse("导出里出现了 $forbidden: $json", json.contains(forbidden))
        }
        // 会话侧字段同样不在:开场白是人设作者写的台词,聊天记录不是。
        assertFalse(json.contains("sessionId"))
    }

    @Test
    fun `套用到既有实体只改人设不改凭据与绑定`() {
        val existing = AssistantEntity(
            id = "keep-me",
            name = "旧名",
            systemPrompt = "旧设定",
            customHeadersJson = """{"Authorization":"Bearer keep"}""",
            providerId = "prov",
            modelId = "model",
            toolIdsJson = """["web_search"]""",
            messageCount = 99,
        )

        val applied = PersonaCardCodec.applyTo(base.copy(name = "新名", systemPrompt = "新设定"), existing)

        assertEquals("凭据留在本机", """{"Authorization":"Bearer keep"}""", applied.customHeadersJson)
        assertEquals("prov", applied.providerId)
        assertEquals("model", applied.modelId)
        assertEquals("""["web_search"]""", applied.toolIdsJson)
        assertEquals(99, applied.messageCount)
        assertEquals("新名", applied.name)
        assertEquals("新设定", applied.systemPrompt)
    }

    @Test
    fun `新建实体从空白凭据开始`() {
        val entity = PersonaCardCodec.toNewEntity(base)

        assertEquals("", entity.id)
        assertEquals("{}", entity.customHeadersJson)
        assertNull(entity.providerId)
        assertNull(entity.modelId)
        assertEquals("[]", entity.toolIdsJson)
        assertEquals(0, entity.messageCount)
        assertTrue(entity.createdAt > 0L)
    }
}

/**
 * E3 的差分预览:只列真的会变的东西,值只给摘要。
 */
class PersonaDiffTest {

    private val current = AssistantEntity(
        id = "a",
        name = "小满",
        systemPrompt = "旧设定:简洁",
        personaDefinition = "冷静",
        temperature = 0.7f,
        memoryEnabled = true,
    )

    @Test
    fun `没有变化时差分是空的`() {
        val changes = PersonaDiff.changes(current, PersonaCardCodec.fromEntity(current))

        assertEquals("完全相同的人设不该列出任何改动: $changes", 0, changes.size)
    }

    @Test
    fun `只有真的变化的字段进差分`() {
        val incoming = PersonaCardCodec.fromEntity(current).copy(name = "小满", systemPrompt = "新设定:详细", temperature = 1.1f)

        val changes = PersonaDiff.changes(current, incoming)
        assertEquals(listOf("systemPrompt", "temperature"), changes.map { it.field })
        assertEquals(io.zer0.muse.R.string.assistant_diff_system_prompt, changes[0].labelRes)
    }

    @Test
    fun `差分值是截断摘要而不是整段正文`() {
        val long = "很长".repeat(500)
        val incoming = PersonaCardCodec.fromEntity(current).copy(systemPrompt = long)

        val change = PersonaDiff.changes(current, incoming).single { it.field == "systemPrompt" }
        assertTrue("摘要要留得下: ${change.to.length}", change.to.length <= 60)
        assertTrue(change.to, change.to.endsWith("…"))
        assertFalse("摘要里不该有换行,否则弹窗排版会散", change.to.contains("\n"))
    }

    @Test
    fun `差分里不出现凭据字段`() {
        val withSecret = current.copy(customHeadersJson = """{"Authorization":"Bearer sk-very-secret"}""")
        val changes = PersonaDiff.changes(withSecret, PersonaCardCodec.fromEntity(withSecret).copy(name = "改名"))

        assertEquals(listOf("name"), changes.map { it.field })
        val rendered = changes.joinToString { "${it.from}${it.to}" }
        assertFalse("差分不许把凭据搬上界面: $rendered", rendered.contains("sk-very-secret"))
    }
}
