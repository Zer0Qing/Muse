package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import io.zer0.muse.tools.script.ToolDeclaration
import io.zer0.muse.ui.theme.BubbleRole
import io.zer0.muse.ui.theme.BubbleRoleStyle
import io.zer0.muse.ui.theme.BubbleSkin
import io.zer0.muse.ui.theme.BubbleSkinResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 4: 声明式 ui-skin 插件包加载分支测试。
 *
 * 覆盖:无 tools 的 ui-skin 包可以加载并解析 skin.json、包内不得出现 JS、
 * 缺少/非法皮肤资源必须拒绝,以及非法 schema/对比度在消费侧回退内置 default。
 */
class PluginPackageLoaderTest {

    private val validStyle = BubbleRoleStyle(
        surfaceArgb = 0xFF112233L,
        contentArgb = 0xFFF5F5F5L,
    )

    private fun validSkin(id: String = "night-skin"): BubbleSkin = BubbleSkin(
        id = id,
        name = "Night skin",
        light = mapOf(BubbleRole.USER to validStyle, BubbleRole.ASSISTANT to validStyle),
        dark = mapOf(BubbleRole.USER to validStyle, BubbleRole.ASSISTANT to validStyle),
    )

    private fun uiSkinManifest(
        capabilities: List<String> = listOf(PluginSecurityGate.UI_SKIN_CAPABILITY),
        tools: List<ToolDeclaration> = emptyList(),
        kind: String = PluginSecurityGate.UI_SKIN_KIND,
    ): PluginManifest = PluginManifest(
        id = "skin-plugin",
        name = "Skin Plugin",
        kind = kind,
        trust = "sandboxed",
        capabilities = capabilities,
        tools = tools,
    )

    private fun toolDeclaration(): ToolDeclaration = ToolDeclaration(
        name = "hello",
        description = "Say hello",
        functionName = "hello",
    )

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun packageZip(
        manifest: PluginManifest = uiSkinManifest(),
        skinJson: String? = AppJson.encodeToString(BubbleSkin.serializer(), validSkin()),
    ): ByteArray {
        val entries = mutableListOf(
            "manifest.json" to AppJson.encodeToString(PluginManifest.serializer(), manifest),
        )
        if (skinJson != null) entries += PluginPackageLoader.UI_SKIN_ENTRY to skinJson
        return zipOf(*entries.toTypedArray())
    }

    @Test
    fun uiSkinPackageWithoutToolsLoadsDeclarativeSkin() {
        val result = PluginPackageLoader.loadFromZip(packageZip())

        val loaded = (result as PluginPackageLoader.Result.Ok).package_
        assertEquals(PluginSecurityGate.UI_SKIN_KIND, loaded.manifest.kind)
        assertTrue(loaded.manifest.tools.isEmpty())
        // 声明式包没有可执行入口
        assertEquals("", loaded.entryCode)
        assertEquals("night-skin", loaded.skin?.id)
        assertEquals(validStyle.surfaceArgb, loaded.skin?.light?.get(BubbleRole.USER)?.surfaceArgb)
        assertTrue(loaded.extraFiles.containsKey(PluginPackageLoader.UI_SKIN_ENTRY))
    }

    @Test
    fun uiSkinPackageWithJavaScriptIsRejected() {
        val zip = zipOf(
            "manifest.json" to AppJson.encodeToString(PluginManifest.serializer(), uiSkinManifest()),
            PluginPackageLoader.UI_SKIN_ENTRY to AppJson.encodeToString(BubbleSkin.serializer(), validSkin()),
            "main.js" to "function hello() { return 'ok'; }",
        )

        val result = PluginPackageLoader.loadFromZip(zip)

        assertTrue((result as PluginPackageLoader.Result.Err).reason.contains("JS"))
    }

    @Test
    fun uiSkinPackageWithToolsIsRejected() {
        val zip = packageZip(manifest = uiSkinManifest(tools = listOf(toolDeclaration())))

        val result = PluginPackageLoader.loadFromZip(zip)

        assertTrue((result as PluginPackageLoader.Result.Err).reason.contains("可执行工具"))
    }

    @Test
    fun uiSkinPackageWithoutCapabilityIsRejected() {
        val zip = packageZip(manifest = uiSkinManifest(capabilities = listOf("resource.read")))

        val result = PluginPackageLoader.loadFromZip(zip)

        assertTrue((result as PluginPackageLoader.Result.Err).reason.contains("ui.skin"))
    }

    @Test
    fun uiSkinPackageWithoutSkinResourceIsRejected() {
        val result = PluginPackageLoader.loadFromZip(packageZip(skinJson = null))

        assertTrue((result as PluginPackageLoader.Result.Err).reason.contains("skin.json"))
    }

    @Test
    fun uiSkinPackageWithUnparsableSkinResourceIsRejected() {
        val result = PluginPackageLoader.loadFromZip(packageZip(skinJson = "{ not json"))

        assertTrue((result as PluginPackageLoader.Result.Err).reason.contains("解析失败"))
    }

    @Test
    fun invalidSchemaOrContrastSkinStillFallsBackToBuiltinDefault() {
        val futureSchema = AppJson.encodeToString(
            BubbleSkin.serializer(),
            validSkin("future-skin").copy(schemaVersion = 99),
        )
        val lowContrastStyle = BubbleRoleStyle(surfaceArgb = 0xFFF2F2F2L, contentArgb = 0xFFE8E8E8L)
        val lowContrast = AppJson.encodeToString(
            BubbleSkin.serializer(),
            BubbleSkin(
                id = "washed-out",
                name = "Washed out",
                light = mapOf(BubbleRole.USER to lowContrastStyle),
            ),
        )

        listOf(futureSchema, lowContrast).forEach { json ->
            val loaded = (PluginPackageLoader.loadFromZip(packageZip(skinJson = json)) as PluginPackageLoader.Result.Ok).package_
            val resolved = BubbleSkinResolver.resolve(loaded.skin, BubbleRole.USER, darkTheme = false)

            // 皮肤本身被加载,但渲染解析回退内置 default,消息正文不受影响
            assertEquals("builtin-default", resolved.sourceSkinId)
            assertEquals(
                BubbleSkinResolver.defaultSkin(darkTheme = false).light.getValue(BubbleRole.USER),
                resolved.style,
            )
        }
    }

    @Test
    fun toolPackageStillRequiresDeclaredToolsAndEntry() {
        val withoutTools = zipOf(
            "manifest.json" to AppJson.encodeToString(
                PluginManifest.serializer(),
                uiSkinManifest(tools = emptyList(), kind = "tool"),
            ),
        )

        val result = PluginPackageLoader.loadFromZip(withoutTools)

        assertTrue((result as PluginPackageLoader.Result.Err).reason.contains("未声明任何工具"))
    }

    @Test
    fun toolPackageStillLoadsEntryCodeAsBefore() {
        val zip = zipOf(
            "manifest.json" to AppJson.encodeToString(
                PluginManifest.serializer(),
                uiSkinManifest(capabilities = listOf("resource.read"), tools = listOf(toolDeclaration()), kind = "tool"),
            ),
            "main.js" to "function hello() { return 'ok'; }",
        )

        val loaded = (PluginPackageLoader.loadFromZip(zip) as PluginPackageLoader.Result.Ok).package_

        assertEquals("function hello() { return 'ok'; }", loaded.entryCode)
        assertNull(loaded.skin)
        assertFalse(loaded.manifest.tools.isEmpty())
    }
}
