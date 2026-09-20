package io.zer0.muse.data.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.common.AppJson
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.tools.script.ToolDeclaration
import io.zer0.muse.ui.theme.BubbleRole
import io.zer0.muse.ui.theme.BubbleRoleStyle
import io.zer0.muse.ui.theme.BubbleSkin
import io.zer0.muse.ui.theme.BubbleSkinValidator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 已安装 ui-skin 插件作为气泡皮肤来源的测试。
 *
 * 覆盖:合法皮肤可被列出并在缺失 JS 入口占位文件后仍能通过「重新校验」路径读取、
 * 非法皮肤(schema/对比度)、未确认草稿、禁用、卸载与信任撤销都不会产生可渲染皮肤,
 * 非 ui-skin 插件不产生皮肤条目。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginSkinListingTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** 每个插件包使用独立发行者 ID,避免同一测试方法内多把公钥争抢同一信任根条目。 */
    private fun publisherIdFor(pluginId: String): String = "publisher.$pluginId"

    private val validStyle = BubbleRoleStyle(
        surfaceArgb = 0xFF1B2A38L,
        contentArgb = 0xFFF2F7FBL,
        radiusDp = 16f,
    )

    private val lowContrastStyle = validStyle.copy(
        surfaceArgb = 0xFFEEEEEE,
        contentArgb = 0xFFDDDDDD,
    )

    @Test
    fun installedUiSkinIsListedAndReloadsWithoutJsEntryFile() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val trustStore = PluginTrustStore(trustFile)
        val manager = PluginManager(context, skillRepoMock(), trustStore)
        val skin = bubbleSkin("aurora-bubble")
        val zip = signedZip(
            id = "skin-pack",
            kind = PluginSecurityGate.UI_SKIN_KIND,
            capabilities = listOf(PluginSecurityGate.UI_SKIN_CAPABILITY),
            extraFiles = mapOf(PluginPackageLoader.UI_SKIN_ENTRY to encode(skin)),
            trustStore = trustStore,
        )

        val installed = installTrusted(manager, zip)
        val listed = manager.listInstalledSkins()
        assertEquals(1, listed.size)
        assertEquals("skin-pack", listed.single().pluginId)
        assertEquals("1.0.0", listed.single().version)
        assertTrue(listed.single().enabled)
        assertEquals("aurora-bubble", listed.single().skin.id)

        // 皮肤包没有 JS 入口:删除安装时留下的空占位文件后,重新校验路径仍必须可用,
        // 否则 skin.json 装得上却永远读不回来(回归测试)。
        val placeholder = File(context.filesDir, "plugins/skin-pack/main.js")
        assertTrue(placeholder.exists())
        assertTrue(placeholder.delete())

        val verified = manager.loadVerifiedPlugin("skin-pack")
        assertNotNull(verified)
        assertEquals("", verified!!.entryCode)
        // 重新读取时内容摘要仍与安装记录一致(loadVerifiedPlugin 内部的摘要校验是前提)。
        assertEquals(installed.contentSha256, manager.findPlugin("skin-pack")?.contentSha256)
        assertEquals("aurora-bubble", manager.listInstalledSkins().single().skin.id)
        trustFile.delete()
        Unit
    }

    @Test
    fun pluginWithoutEntryFileIsStillRejectedForNonSkinKinds() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val trustStore = PluginTrustStore(trustFile)
        val manager = PluginManager(context, skillRepoMock(), trustStore)
        val zip = signedZip(
            id = "tool-pack",
            kind = "tool",
            capabilities = listOf("resource.read"),
            tools = listOf(tool("hello", "hello")),
            entryCode = "function hello(){ return 'ok'; }",
            trustStore = trustStore,
        )
        installTrusted(manager, zip)

        val entry = File(context.filesDir, "plugins/tool-pack/main.js")
        assertTrue(entry.exists())
        assertTrue(entry.delete())

        // 非 ui-skin 插件缺少入口文件仍是错误,不能借皮肤包的容错放行。
        assertNull(manager.loadVerifiedPlugin("tool-pack"))
        assertTrue(manager.listInstalledSkins().isEmpty())
        trustFile.delete()
        Unit
    }

    @Test
    fun invalidPluginSkinsAreFilteredOut() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val trustStore = PluginTrustStore(trustFile)
        val manager = PluginManager(context, skillRepoMock(), trustStore)

        // 对比度不达标
        installTrusted(
            manager,
            signedZip(
                id = "low-contrast-pack",
                kind = PluginSecurityGate.UI_SKIN_KIND,
                capabilities = listOf(PluginSecurityGate.UI_SKIN_CAPABILITY),
                extraFiles = mapOf(
                    PluginPackageLoader.UI_SKIN_ENTRY to encode(
                        bubbleSkin("low-contrast", style = lowContrastStyle),
                    ),
                ),
                trustStore = trustStore,
            ),
        )
        // 不支持的 schemaVersion
        installTrusted(
            manager,
            signedZip(
                id = "future-schema-pack",
                kind = PluginSecurityGate.UI_SKIN_KIND,
                capabilities = listOf(PluginSecurityGate.UI_SKIN_CAPABILITY),
                extraFiles = mapOf(
                    PluginPackageLoader.UI_SKIN_ENTRY to encode(
                        bubbleSkin(
                            "future-schema",
                            schemaVersion = BubbleSkinValidator.CURRENT_SCHEMA + 1,
                        ),
                    ),
                ),
                trustStore = trustStore,
            ),
        )

        // 插件本身安装成功,但非法皮肤既不可选也不展示。
        assertEquals(2, manager.list().size)
        assertTrue(manager.listInstalledSkins().isEmpty())
        assertTrue(manager.listInstalledSkins(includeDisabled = true).isEmpty())
        trustFile.delete()
        Unit
    }

    @Test
    fun nonSkinPluginsProduceNoSkinEntry() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val trustStore = PluginTrustStore(trustFile)
        val manager = PluginManager(context, skillRepoMock(), trustStore)
        installTrusted(
            manager,
            signedZip(
                id = "tool-pack",
                kind = "tool",
                capabilities = listOf("resource.read"),
                tools = listOf(tool("hello", "hello")),
                entryCode = "function hello(){ return 'ok'; }",
                trustStore = trustStore,
            ),
        )

        assertTrue(manager.list().single().enabled)
        assertTrue(manager.listInstalledSkins().isEmpty())
        assertTrue(manager.listInstalledSkins(includeDisabled = true).isEmpty())
        trustFile.delete()
        Unit
    }

    @Test
    fun disabledOrUninstalledPluginSkinStopsBeingUsable() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val trustStore = PluginTrustStore(trustFile)
        val manager = PluginManager(context, skillRepoMock(), trustStore)
        val zip = signedZip(
            id = "skin-pack",
            kind = PluginSecurityGate.UI_SKIN_KIND,
            capabilities = listOf(PluginSecurityGate.UI_SKIN_CAPABILITY),
            extraFiles = mapOf(
                PluginPackageLoader.UI_SKIN_ENTRY to encode(bubbleSkin("aurora-bubble")),
            ),
            trustStore = trustStore,
        )
        val installed = installTrusted(manager, zip)
        assertEquals(1, manager.listInstalledSkins().size)

        // 禁用后不再可用,但仍能在设置页以「不可用」展示。
        manager.setEnabled(installed.id, false)
        assertTrue(manager.listInstalledSkins().isEmpty())
        val unavailable = manager.listInstalledSkins(includeDisabled = true)
        assertEquals(1, unavailable.size)
        assertFalse(unavailable.single().enabled)
        assertEquals("aurora-bubble", unavailable.single().skin.id)

        // 重新启用后恢复可用。
        manager.setEnabled(installed.id, true)
        assertEquals(1, manager.listInstalledSkins().size)

        // 卸载后彻底消失,消费侧回退内置 default。
        manager.uninstall(installed.id)
        assertTrue(manager.listInstalledSkins().isEmpty())
        assertTrue(manager.listInstalledSkins(includeDisabled = true).isEmpty())
        trustFile.delete()
        Unit
    }

    @Test
    fun revokedTrustMakesInstalledSkinUnavailable() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val trustStore = PluginTrustStore(trustFile)
        val manager = PluginManager(context, skillRepoMock(), trustStore)
        val zip = signedZip(
            id = "skin-pack",
            kind = PluginSecurityGate.UI_SKIN_KIND,
            capabilities = listOf(PluginSecurityGate.UI_SKIN_CAPABILITY),
            extraFiles = mapOf(
                PluginPackageLoader.UI_SKIN_ENTRY to encode(bubbleSkin("aurora-bubble")),
            ),
            trustStore = trustStore,
        )
        installTrusted(manager, zip)
        assertEquals(1, manager.listInstalledSkins().size)

        assertTrue(trustStore.revoke(publisherIdFor("skin-pack")))

        // 信任撤销后签名复核失败:不可渲染,只以不可用条目展示。
        assertTrue(manager.listInstalledSkins().isEmpty())
        assertFalse(manager.listInstalledSkins(includeDisabled = true).single().enabled)
        trustFile.delete()
        Unit
    }

    @Test
    fun unsignedDraftSkinNeverBecomesUsable() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val trustStore = PluginTrustStore(trustFile)
        val manager = PluginManager(context, skillRepoMock(), trustStore)
        val zip = unsignedSkinZip(
            id = "draft-skin",
            extraFiles = mapOf(
                PluginPackageLoader.UI_SKIN_ENTRY to encode(bubbleSkin("draft-bubble")),
            ),
        )
        val preview = manager.reviewFromFile(zip).getOrThrow().preview
        assertEquals(PluginSecurityGate.SignatureStatus.UNSIGNED, preview.signatureStatus)
        val draft = manager.installDraftFromFile(zip, preview).getOrThrow()
        assertFalse(draft.enabled)

        assertTrue(manager.listInstalledSkins().isEmpty())
        val unavailable = manager.listInstalledSkins(includeDisabled = true)
        assertEquals(1, unavailable.size)
        assertFalse(unavailable.single().enabled)
        trustFile.delete()
        Unit
    }

    @Test
    fun registryMutationsBumpSkinRevision() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val trustStore = PluginTrustStore(trustFile)
        val manager = PluginManager(context, skillRepoMock(), trustStore)
        val zip = signedZip(
            id = "skin-pack",
            kind = PluginSecurityGate.UI_SKIN_KIND,
            capabilities = listOf(PluginSecurityGate.UI_SKIN_CAPABILITY),
            extraFiles = mapOf(
                PluginPackageLoader.UI_SKIN_ENTRY to encode(bubbleSkin("aurora-bubble")),
            ),
            trustStore = trustStore,
        )
        val before = manager.revisionFlow.value

        val installed = installTrusted(manager, zip)
        assertTrue(manager.revisionFlow.value > before)

        val afterInstall = manager.revisionFlow.value
        manager.setEnabled(installed.id, false)
        assertTrue(manager.revisionFlow.value > afterInstall)

        val afterToggle = manager.revisionFlow.value
        manager.uninstall(installed.id)
        assertTrue(manager.revisionFlow.value > afterToggle)
        trustFile.delete()
        Unit
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun bubbleSkin(
        id: String,
        schemaVersion: Int = 1,
        style: BubbleRoleStyle = validStyle,
    ): BubbleSkin = BubbleSkin(
        schemaVersion = schemaVersion,
        id = id,
        name = "Skin $id",
        light = mapOf(BubbleRole.USER to style, BubbleRole.ASSISTANT to style),
        dark = mapOf(BubbleRole.USER to style, BubbleRole.ASSISTANT to style),
    )

    private fun encode(skin: BubbleSkin): String =
        AppJson.encodeToString(BubbleSkin.serializer(), skin)

    private fun tool(name: String, functionName: String): ToolDeclaration = ToolDeclaration(
        name = name,
        description = "test tool",
        parametersJson = "{}",
        requiredJson = "[]",
        functionName = functionName,
    )

    private suspend fun installTrusted(
        manager: PluginManager,
        file: File,
    ): PluginManager.InstalledPlugin {
        val preview = manager.reviewFromFile(file).getOrThrow().preview
        val result = manager.installConfirmedFromFile(file, preview)
        assertTrue("install failed: ${result.exceptionOrNull()}", result.isSuccess)
        return result.getOrThrow()
    }

    /**
     * 生成一个已写入本机信任根的签名插件包;签名覆盖 manifest + 入口内容 + 附加文件,
     * 与 PluginSecurityGate 的签名输入保持一致。
     */
    private fun signedZip(
        id: String,
        kind: String,
        capabilities: List<String>,
        tools: List<ToolDeclaration> = emptyList(),
        entryCode: String = "",
        extraFiles: Map<String, String> = emptyMap(),
        trustStore: PluginTrustStore,
    ): File {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val publisherId = publisherIdFor(id)
        val unsignedManifest = PluginManifest(
            id = id,
            name = "Test Plugin $id",
            version = "1.0.0",
            entry = "main.js",
            kind = kind,
            capabilities = capabilities,
            tools = tools,
            signature = PluginSignature(publisherId, publicKey, ""),
        )
        val unsignedPackage = PluginPackageLoader.LoadedPluginPackage(
            manifest = unsignedManifest,
            entryCode = entryCode,
            extraFiles = extraFiles,
        )
        val signature = Signature.getInstance(PluginSecurityGate.SIGNATURE_ALGORITHM).apply {
            initSign(keyPair.private)
            update(PluginSecurityGate.signaturePayload(unsignedPackage))
        }.sign()
        val manifest = unsignedManifest.copy(
            signature = unsignedManifest.signature!!.copy(
                signature = Base64.getEncoder().encodeToString(signature),
            ),
        )
        trustStore.trust(publisherId, publicKey).getOrThrow()
        return writeZip(
            manifestJson = AppJson.encodeToString(PluginManifest.serializer(), manifest),
            entryCode = entryCode,
            extraFiles = extraFiles,
        )
    }

    /** 无签名皮肤包:只允许作为禁用草稿安装,不能成为可渲染皮肤。 */
    private fun unsignedSkinZip(id: String, extraFiles: Map<String, String>): File {
        val manifest = PluginManifest(
            id = id,
            name = "Draft Skin $id",
            version = "1.0.0",
            kind = PluginSecurityGate.UI_SKIN_KIND,
            capabilities = listOf(PluginSecurityGate.UI_SKIN_CAPABILITY),
        )
        return writeZip(
            manifestJson = AppJson.encodeToString(PluginManifest.serializer(), manifest),
            entryCode = "",
            extraFiles = extraFiles,
        )
    }

    private fun writeZip(
        manifestJson: String,
        entryCode: String,
        extraFiles: Map<String, String>,
    ): File {
        val bytes = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                fun put(name: String, content: String) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray())
                    zos.closeEntry()
                }
                put("manifest.json", manifestJson)
                // ui-skin 包不携带 JS 入口,与 PluginPackageLoader 的约束一致。
                if (entryCode.isNotEmpty()) put("main.js", entryCode)
                extraFiles.forEach { (name, content) -> put(name, content) }
            }
            bos.toByteArray()
        }
        return File(context.cacheDir, "skin_test_${System.nanoTime()}.muse-plugin").apply {
            writeBytes(bytes)
        }
    }

    /** 真实 DAO 对未占用的 id 返回 null;relaxed mock 默认返回非空 mock,会误触发冲突检测。 */
    private fun skillRepoMock(): SkillRepository =
        mockk<SkillRepository>(relaxed = true).also { repo ->
            coEvery { repo.getById(any()) } returns null
        }
}
