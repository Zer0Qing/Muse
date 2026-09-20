package io.zer0.muse.data.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.tools.script.ToolDeclaration
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
import java.io.File
import java.util.Base64

/**
 * 助手自写插件（未签名草稿 → 本机作者签名 → 启用）的安全边界测试。
 *
 * 锁定四类回归：
 *  1. 草稿永远不可执行：禁用、未确认，loadVerifiedPlugin/loadVerifiedFunction 均为 null；
 *  2. 非法输入在写盘前被拒，不留下注册表或插件目录半成品；
 *  3. 签名启用后必须 VALID_TRUSTED/启用/可加载，且发行者指纹等于本机作者公钥指纹；
 *  4. 草稿被改动后拒绝签名；本机作者密钥只生成一次、指纹稳定；
 *     未签名草稿无论调用顺序如何都不能被执行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginAuthoringTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val authorId = LocalAuthorKeyStore.PUBLISHER_ID

    @Test
    fun authoredDraftIsDisabledUnconfirmedAndNotExecutable() = runBlocking {
        val env = env()
        val request = request(capabilities = listOf("resource.read"))

        val result = env.manager.createAuthoredDraft(request)

        assertTrue("draft failed: ${result.exceptionOrNull()}", result.isSuccess)
        val draft = result.getOrThrow()
        assertFalse(draft.enabled)
        assertFalse(draft.installationConfirmed)
        assertEquals(PluginSecurityGate.SignatureStatus.UNSIGNED, draft.signatureStatus)
        assertEquals("", draft.publisherId)
        // 草稿落盘了，但执行视图与入口代码一律不可用。
        assertTrue(File(context.filesDir, "plugins/${request.id}/main.js").isFile)
        assertNull(env.manager.loadVerifiedPlugin(request.id))
        assertNull(env.manager.loadVerifiedFunction(request.id, "hello"))
        assertNull(env.manager.loadEntryCode(request.id))
        // 未签名的草稿不写信任根，manifest 里也不能出现签名 envelope。
        assertTrue(env.trustStore.list().isEmpty())
        val manifestText = File(context.filesDir, "plugins/${request.id}/manifest.json").readText()
        assertFalse("草稿 manifest 不能带签名", manifestText.contains("\"signature\""))
    }

    @Test
    fun invalidRequestsAreRejectedWithoutPartialState() = runBlocking {
        val env = env()
        val cases = listOf(
            "id 非法" to request(id = "Bad_Id"),
            "名称空" to request(name = "  "),
            "版本非法" to request(version = "1.0"),
            "代码空" to request(code = "   "),
            "代码超限" to request(code = "var x = 1;" + "a".repeat(PluginAuthoringRules.MAX_CODE_BYTES)),
            "函数不存在" to request(
                code = "var x = 1;",
                tools = listOf(tool(functionName = "missingFunction")),
            ),
            "工具名为空" to request(tools = emptyList()),
            "工具名非法" to request(tools = listOf(tool(name = "bad tool!"))),
            "函数名非法" to request(tools = listOf(tool(functionName = "1bad"))),
            "工具名重复" to request(
                tools = listOf(tool(name = "hello", functionName = "hello"), tool(name = "hello", functionName = "hello")),
            ),
            "能力超白名单" to request(capabilities = listOf("network")),
        )

        cases.forEach { (label, request) ->
            val result = env.manager.createAuthoredDraft(request)
            assertTrue("$label 必须失败", result.isFailure)
            assertTrue(
                "$label 必须给出可读原因",
                result.exceptionOrNull()?.message?.isNotBlank() == true,
            )
            assertNull("$label 不应留下注册表条目", env.manager.findPlugin(request.id))
            assertFalse(
                "$label 不应留下插件目录",
                File(context.filesDir, "plugins/${request.id}").exists(),
            )
        }
        assertTrue(env.manager.list().isEmpty())
    }

    @Test
    fun signAndEnableDraftPublishesTrustedExecutablePlugin() = runBlocking {
        val env = env()
        val created = env.manager.createAuthoredDraft(request()).getOrThrow()
        assertNull(env.manager.loadVerifiedPlugin(created.id))

        val signed = env.manager.signAndEnableDraft(created.id)

        assertTrue("sign failed: ${signed.exceptionOrNull()}", signed.isSuccess)
        val plugin = requireNotNull(env.manager.findPlugin(created.id))
        assertEquals(PluginSecurityGate.SignatureStatus.VALID_TRUSTED, plugin.signatureStatus)
        assertTrue(plugin.enabled)
        assertTrue(plugin.installationConfirmed)
        assertEquals(authorId, plugin.publisherId)
        assertEquals(env.keyStore.fingerprint().getOrThrow(), plugin.publisherKeyFingerprint)

        val verified = requireNotNull(env.manager.loadVerifiedPlugin(created.id))
        assertTrue(verified.entryCode.contains("function hello"))
        assertNotNull(env.manager.loadVerifiedFunction(created.id, "hello"))
        assertNull(env.manager.loadVerifiedFunction(created.id, "removedFunction"))

        // 用户确认即代表信任本机作者公钥：信任根里必须是同一把公钥。
        val trusted = requireNotNull(env.trustStore.find(authorId))
        assertEquals(env.keyStore.publicKeyBase64().getOrThrow(), trusted.publicKey)
    }

    @Test
    fun tamperedDraftIsRejectedBeforeSigning() = runBlocking {
        val env = env()
        env.manager.createAuthoredDraft(request()).getOrThrow()
        File(context.filesDir, "plugins/assistant-tool/main.js")
            .writeText("function hello() { return 'tampered'; }")

        val result = env.manager.signAndEnableDraft("assistant-tool")

        assertTrue(result.isFailure)
        val reason = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("原因应指出内容被改动: $reason", reason.contains("修改") || reason.contains("摘要"))
        val plugin = requireNotNull(env.manager.findPlugin("assistant-tool"))
        assertEquals(PluginSecurityGate.SignatureStatus.UNSIGNED, plugin.signatureStatus)
        assertFalse(plugin.installationConfirmed)
        assertFalse(plugin.enabled)
        assertNull(env.manager.loadVerifiedPlugin("assistant-tool"))
        // 签名在复核之后发生；失败路径不得写信任根。
        assertTrue(env.trustStore.list().isEmpty())
    }

    @Test
    fun localAuthorKeyIsGeneratedOnceAndFingerprintIsStable() {
        val keyFile = tmpFile("author_key", ".json")
        val first = LocalAuthorKeyStore(keyFile)
        val publicKey = first.publicKeyBase64().getOrThrow()
        val fingerprint = first.fingerprint().getOrThrow()
        assertTrue(keyFile.isFile)

        val second = LocalAuthorKeyStore(keyFile)
        assertEquals(publicKey, second.publicKeyBase64().getOrThrow())
        assertEquals(fingerprint, second.fingerprint().getOrThrow())
        assertEquals(
            PluginSecurityGate.publicKeyFingerprint(Base64.getDecoder().decode(publicKey)),
            fingerprint,
        )
        keyFile.delete()
        Unit
    }

    @Test
    fun corruptedAuthorKeyFailsClosedWithReadableReason() {
        val keyFile = tmpFile("author_key", ".json")
        keyFile.writeText("{not-json")

        val result = LocalAuthorKeyStore(keyFile).publicKeyBase64()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.isNotBlank() == true)
        keyFile.delete()
        Unit
    }

    @Test
    fun unsignedDraftCannotExecuteRegardlessOfCallOrder() = runBlocking {
        val env = env()
        env.manager.createAuthoredDraft(request()).getOrThrow()

        // 直接加载、启用、确认、再次加载：任何顺序都不能产出可执行视图。
        assertNull(env.manager.loadEntryCode("assistant-tool"))
        env.manager.setEnabled("assistant-tool", true)
        assertFalse(requireNotNull(env.manager.findPlugin("assistant-tool")).enabled)
        assertNull(env.manager.loadVerifiedPlugin("assistant-tool"))

        assertTrue(env.manager.confirmInstallation("assistant-tool").isFailure)
        assertEquals(
            PluginSecurityGate.SignatureStatus.UNSIGNED,
            requireNotNull(env.manager.findPlugin("assistant-tool")).signatureStatus,
        )
        assertFalse(requireNotNull(env.manager.findPlugin("assistant-tool")).installationConfirmed)
        assertNull(env.manager.loadVerifiedPlugin("assistant-tool"))
        assertNull(env.manager.loadVerifiedFunction("assistant-tool", "hello"))
        assertTrue(env.trustStore.list().isEmpty())
    }

    // ── 测试脚手架 ──────────────────────────────────────────────────────────

    private data class Env(
        val manager: PluginManager,
        val trustStore: PluginTrustStore,
        val keyStore: LocalAuthorKeyStore,
    )

    private fun env(appVersion: String = "1.0.89"): Env {
        val keyStore = LocalAuthorKeyStore(tmpFile("author_key", ".json"))
        val trustStore = PluginTrustStore(tmpFile("trust", ".json"))
        val manager = PluginManager(
            context = context,
            skillRepository = skillRepoMock(),
            trustStore = trustStore,
            appVersionName = appVersion,
            localAuthorKeyStore = keyStore,
        )
        return Env(manager, trustStore, keyStore)
    }

    private fun skillRepoMock(): SkillRepository = mockk<SkillRepository>(relaxed = true).also { repo ->
        coEvery { repo.getById(any()) } returns null
    }

    private fun tmpFile(prefix: String, suffix: String): File =
        File(context.cacheDir, "${prefix}_${System.nanoTime()}_${(0..9999).random()}$suffix")

    private fun request(
        id: String = "assistant-tool",
        name: String = "助手工具",
        description: String = "由助手起草的示例插件",
        version: String = "1.0.0",
        code: String = DEFAULT_CODE,
        tools: List<ToolDeclaration> = listOf(tool()),
        capabilities: List<String> = emptyList(),
    ): AuthoredPluginRequest = AuthoredPluginRequest(
        id = id,
        name = name,
        description = description,
        version = version,
        code = code,
        tools = tools,
        capabilities = capabilities,
    )

    private fun tool(
        name: String = "hello",
        functionName: String = "hello",
    ): ToolDeclaration = ToolDeclaration(
        name = name,
        description = "示例工具",
        parametersJson = "{}",
        requiredJson = "[]",
        functionName = functionName,
    )

    private companion object {
        val DEFAULT_CODE = """
            function hello(args) {
              return "hello " + (args.name || "");
            }
        """.trimIndent()
    }
}
