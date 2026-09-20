package io.zer0.muse.ui.theme

import io.zer0.common.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡皮肤目录纯逻辑测试:解析容错、非法皮肤过滤、选中语义与数量上限。
 *
 * 存储层 [BubbleSkinStore] 只是 DataStore 薄包装,策略全部集中在
 * [BubbleSkinCatalog],因此这里覆盖"非法 schema/对比度回退 default"的完整判定。
 */
class BubbleSkinCatalogTest {

    private val validStyle = BubbleRoleStyle(
        surfaceArgb = 0xFF202020L,
        contentArgb = 0xFFF5F5F5L,
        radiusDp = 14f,
    )

    /** 对比度不足(浅底浅字)的样式,用于验证过滤/回退。 */
    private val lowContrastStyle = BubbleRoleStyle(
        surfaceArgb = 0xFFF2F2F2L,
        contentArgb = 0xFFE8E8E8L,
    )

    private fun skin(id: String, style: BubbleRoleStyle = validStyle): BubbleSkin = BubbleSkin(
        id = id,
        name = "Skin $id",
        light = mapOf(BubbleRole.USER to style, BubbleRole.ASSISTANT to style),
        dark = mapOf(BubbleRole.USER to style, BubbleRole.ASSISTANT to style),
    )

    private fun encode(skins: List<BubbleSkin>): String =
        AppJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(BubbleSkin.serializer()),
            skins,
        )

    @Test
    fun `decode tolerates missing garbage and invalid entries`() {
        assertTrue(BubbleSkinCatalog.decode(null).isEmpty())
        assertTrue(BubbleSkinCatalog.decode("   ").isEmpty())
        assertTrue(BubbleSkinCatalog.decode("{ not json").isEmpty())
        assertTrue(BubbleSkinCatalog.decode("""{"id":"not-a-list"}""").isEmpty())

        val mixed = BubbleSkinCatalog.decode(
            encode(
                listOf(
                    skin("valid-one"),
                    skin("bad-contrast", lowContrastStyle),
                    BubbleSkin(id = BubbleSkinCatalog.DEFAULT_SKIN_ID, name = "Fake builtin"),
                    BubbleSkin(
                        schemaVersion = 99,
                        id = "future-schema",
                        name = "Future",
                        light = mapOf(BubbleRole.USER to validStyle),
                    ),
                    skin("valid-two"),
                ),
            ),
        )

        assertEquals(listOf("valid-one", "valid-two"), mixed.map { it.id })
    }

    @Test
    fun `encode round trips valid skins only`() {
        val encoded = BubbleSkinCatalog.encode(listOf(skin("round-trip")))

        val decoded = BubbleSkinCatalog.decode(encoded)

        assertEquals(1, decoded.size)
        assertEquals("round-trip", decoded.single().id)
        assertEquals(validStyle.surfaceArgb, decoded.single().light.getValue(BubbleRole.USER).surfaceArgb)
    }

    @Test
    fun `upsert replaces same id and rejects invalid skin`() {
        val current = listOf(skin("keep"), skin("target"))

        val replaced = BubbleSkinCatalog.upsert(current, skin("target").copy(name = "Renamed"))
        assertEquals(listOf("keep", "target"), replaced.map { it.id })
        assertEquals("Renamed", replaced.first { it.id == "target" }.name)

        val rejected = BubbleSkinCatalog.upsert(current, skin("bad", lowContrastStyle))
        assertEquals(current.map { it.id }, rejected.map { it.id })
        assertTrue(rejected.none { it.id == "bad" })

        // 内置 id 不允许作为自定义皮肤进入列表
        val builtinAttempt = BubbleSkinCatalog.upsert(current, skin(BubbleSkinCatalog.DEFAULT_SKIN_ID))
        assertTrue(builtinAttempt.none { it.id == BubbleSkinCatalog.DEFAULT_SKIN_ID })
    }

    @Test
    fun `remove drops the requested skin`() {
        val current = listOf(skin("a"), skin("b"))

        assertEquals(listOf("b"), BubbleSkinCatalog.remove(current, "a").map { it.id })
        assertEquals(listOf("a", "b"), BubbleSkinCatalog.remove(current, "missing").map { it.id })
    }

    @Test
    fun `sanitize caps count and drops duplicates`() {
        val many = (1..BubbleSkinCatalog.MAX_CUSTOM_SKINS + 5).map { skin("skin-$it") } +
            listOf(skin("skin-1"))

        val sanitized = BubbleSkinCatalog.sanitize(many)

        assertEquals(BubbleSkinCatalog.MAX_CUSTOM_SKINS, sanitized.size)
        assertEquals(sanitized.size, sanitized.map { it.id }.toSet().size)
    }

    @Test
    fun `select returns null for builtin missing or invalid selection`() {
        val skins = listOf(skin("custom"))

        assertNull(BubbleSkinCatalog.select(skins, BubbleSkinCatalog.DEFAULT_SKIN_ID))
        assertNull(BubbleSkinCatalog.select(skins, ""))
        assertNull(BubbleSkinCatalog.select(skins, "does-not-exist"))
        // 列表内的非法皮肤(对比度不足)不会生效,等价于回退内置 default
        assertNull(BubbleSkinCatalog.select(listOf(skin("bad", lowContrastStyle)), "bad"))
        assertEquals("custom", BubbleSkinCatalog.select(skins, "custom")?.id)
    }

    @Test
    fun `parseSkin accepts valid json and rejects invalid schema resources`() {
        val validJson = AppJson.encodeToString(BubbleSkin.serializer(), skin("shared-skin"))
        assertNotNull(BubbleSkinCatalog.parseSkin(validJson))
        assertEquals("shared-skin", BubbleSkinCatalog.parseSkin(validJson)?.id)

        assertNull(BubbleSkinCatalog.parseSkin(null))
        assertNull(BubbleSkinCatalog.parseSkin(""))
        assertNull(BubbleSkinCatalog.parseSkin("{ not json"))
        assertNull(
            BubbleSkinCatalog.parseSkin(
                AppJson.encodeToString(BubbleSkin.serializer(), skin("bad-contrast", lowContrastStyle)),
            ),
        )
        assertNull(
            BubbleSkinCatalog.parseSkin(
                AppJson.encodeToString(
                    BubbleSkin.serializer(),
                    skin("future").copy(schemaVersion = BubbleSkinValidator.CURRENT_SCHEMA + 1),
                ),
            ),
        )
        assertNull(
            BubbleSkinCatalog.parseSkin(
                AppJson.encodeToString(
                    BubbleSkin.serializer(),
                    skin(BubbleSkinCatalog.DEFAULT_SKIN_ID),
                ),
            ),
        )
    }
}
