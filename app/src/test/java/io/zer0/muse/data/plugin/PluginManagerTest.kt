package io.zer0.muse.data.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.zer0.common.AppJson
import io.zer0.muse.data.skill.SkillDao
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.skill.SkillRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * B6-01: 外部插件安装/卸载/安全校验测试。
 *
 * Phase 5 P0 追加：skill id 防碰撞编码与冲突拒绝、安装/更新/卸载的 skill 生命周期
 * 对账（卸载不再只依赖当前 manifest）、以及执行前基于重新验证 manifest 的入口校验。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginManagerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun corruptedRegistryIsQuarantinedInsteadOfAccepted() = runBlocking {
        val registry = File(context.filesDir, "plugin_registry.json")
        registry.parentFile?.mkdirs()
        registry.writeText("{not-json")

        val manager = PluginManager(context, mockk(relaxed = true))

        assertTrue(manager.list().isEmpty())
        assertFalse(registry.exists())
        assertTrue(
            registry.parentFile?.listFiles()?.any { it.name.startsWith("plugin_registry.json.corrupt-") } == true,
        )
    }

    // ── P0-9: 信任根可撤(与安装校验同一 store 实例) ──

    @Test
    fun revokePublisher_removesTrustRoot() = runBlocking {
        // 预置信任根文件(与 PluginTrustStore 持久化格式一致),验证 PluginManager
        // 的 revokePublisher 走的是同一 store:撤销后 trustedPublishers 为空。
        val trustFile = File(context.noBackupFilesDir, "muse_plugin_trust_roots.json")
        trustFile.parentFile?.mkdirs()
        val key = generateEcPublicKey()
        val fingerprint = PluginSecurityGate.publicKeyFingerprint(
            java.util.Base64.getDecoder().decode(key),
        )
        trustFile.writeText(
            """{"publishers":[{"publisherId":"publisher.test","publicKey":"$key","fingerprint":"$fingerprint"}]}""",
        )

        val manager = PluginManager(context, mockk(relaxed = true))
        try {
            assertEquals(1, manager.trustedPublishers().size)
            assertEquals("publisher.test", manager.trustedPublishers()[0].publisherId)

            assertEquals("publisher.test", manager.revokePublisher("publisher.test"))
            assertTrue("撤销后信任根列表应为空", manager.trustedPublishers().isEmpty())
            assertEquals("重复撤销应返回 null", null, manager.revokePublisher("publisher.test"))
        } finally {
            trustFile.delete()
        }
    }

    private fun generateEcPublicKey(): String {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return java.util.Base64.getEncoder().encodeToString(generator.generateKeyPair().public.encoded)
    }

    @Test
    fun install_rejectsOversizedPackageBeforeReadingIntoMemory() = runBlocking {
        val file = File(context.cacheDir, "oversized_${System.nanoTime()}.muse-plugin")
        java.io.RandomAccessFile(file, "rw").use { output ->
            output.setLength(PluginManager.MAX_PLUGIN_PACKAGE_BYTES + 1)
        }

        val manager = PluginManager(context, mockk(relaxed = true))
        val result = draftInstall(manager, file)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("超过限制") == true)
        file.delete()
        Unit
    }

    @Test
    fun install_registersSkillAndPersistsPlugin() = runBlocking {
        val skillRepo = skillRepoMock()
        val manager = PluginManager(context, skillRepo)
        val zip = zip(
            manifest = """
                {
                  "id": "test-plugin",
                  "name": "Test Plugin",
                  "version": "1.0.0",
                  "entry": "main.js",
                  "kind": "tool",
                  "capabilities": ["ui"],
                  "tools": [
                    {
                      "name": "hello",
                      "description": "say hello",
                      "parametersJson": "{}",
                      "requiredJson": "[]",
                      "functionName": "hello"
                    }
                  ]
                }
            """.trimIndent(),
        )

        val result = draftInstall(manager, zip)
        assertTrue("install failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("test-plugin", manager.list().single().id)
        assertTrue(File(context.filesDir, "plugins/test-plugin/main.js").exists())
        coVerify { skillRepo.upsert(match { it.id == PluginManager.skillId("test-plugin", "hello") }) }
    }

    @Test
    fun installedEntryRejectsContentTampering() = runBlocking {
        val skillRepo = skillRepoMock()
        val manager = PluginManager(context, skillRepo)
        val result = draftInstall(manager, zip())
        assertTrue("install failed: ${result.exceptionOrNull()}", result.isSuccess)

        File(context.filesDir, "plugins/test-plugin/main.js").writeText("function hello(){ return 'tampered'; }")

        assertEquals(null, manager.loadEntryCode("test-plugin"))
    }

    @Test
    fun uninstall_removesDirAndSkill() = runBlocking {
        val dao = FakeSkillDao()
        val manager = PluginManager(context, SkillRepository(dao))
        val result = draftInstall(manager, zip())
        assertTrue(result.isSuccess)
        assertEquals(setOf(PluginManager.skillId("test-plugin", "hello")), dao.rows.keys)

        manager.uninstall("test-plugin")

        assertTrue(manager.list().isEmpty())
        assertFalse(File(context.filesDir, "plugins/test-plugin").exists())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun uninstall_nonexistentPlugin_isHarmlessNoOp() = runBlocking {
        // P5-4: 卸载不存在的插件必须是无害 no-op(不抛异常、不产生注册表脏数据)
        val dao = FakeSkillDao()
        val manager = PluginManager(context, SkillRepository(dao))
        manager.uninstall("never-installed")
        assertTrue(manager.list().isEmpty())
        assertTrue(dao.rows.isEmpty())
        assertFalse(File(context.filesDir, "plugins/never-installed").exists())
    }

    @Test
    fun install_rejectsExternalFullAccessTrustClaim() = runBlocking {
        val skillRepo = skillRepoMock()
        val manager = PluginManager(context, skillRepo)
        val result = draftInstall(manager,
            zip(
                manifest = """
                    {
                      "id": "untrusted-full-access",
                      "name": "Untrusted",
                      "version": "1.0.0",
                      "trust": "full-access",
                      "tools": [{"name": "hello", "description": "x", "parametersJson": "{}", "requiredJson": "[]", "functionName": "hello"}]
                    }
                """.trimIndent(),
            ),
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("sandboxed") == true)
        assertTrue(manager.list().none { it.id == "untrusted-full-access" })
    }

    @Test
    fun install_rejectsUndeclaredCapability() = runBlocking {
        val skillRepo = skillRepoMock()
        val manager = PluginManager(context, skillRepo)
        val zip = zip(
            manifest = """
                {
                  "id": "evil-plugin",
                  "name": "Evil",
                  "version": "1.0.0",
                  "entry": "main.js",
                  "capabilities": ["system.exec"],
                  "tools": [
                    {
                      "name": "boom",
                      "description": "x",
                      "parametersJson": "{}",
                      "requiredJson": "[]",
                      "functionName": "boom"
                    }
                  ]
                }
            """.trimIndent(),
        )

        val result = draftInstall(manager, zip)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("不允许的能力") == true)
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun install_rejectsNetworkAndResourceWriteCapabilities() = runBlocking {
        val skillRepo = skillRepoMock()
        val manager = PluginManager(context, skillRepo)
        for (capability in listOf("network", "resource.write")) {
            val zip = zip(
                manifest = """
                    {
                      "id": "cap-${capability.replace('.', '-')}",
                      "name": "Capability Test",
                      "version": "1.0.0",
                      "entry": "main.js",
                      "capabilities": ["$capability"],
                      "tools": [{"name": "t", "description": "x", "parametersJson": "{}", "requiredJson": "[]", "functionName": "t"}]
                    }
                """.trimIndent(),
            )
            val result = draftInstall(manager, zip)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("不允许的能力") == true)
            assertTrue(manager.list().isEmpty())
        }
    }

    @Test
    fun install_pluginWithJsEntry_keepsEntryCode() = runBlocking {
        val skillRepo = skillRepoMock()
        val manager = PluginManager(context, skillRepo)
        val zip = zip(
            manifest = """
                {
                  "id": "todo-summary",
                  "name": "Todo Summary",
                  "version": "0.1.0",
                  "entry": "main.js",
                  "kind": "tool",
                  "capabilities": ["resource.read"],
                  "tools": [
                    {
                      "name": "summarize_todos",
                      "description": "summarize todos",
                      "parametersJson": "{}",
                      "requiredJson": "[]",
                      "functionName": "summarizeTodos"
                    }
                  ]
                }
            """.trimIndent(),
            entry = """
                function summarizeTodos(args) {
                  return "count=" + (args.text || "").split("\n").length;
                }
            """.trimIndent(),
        )

        val result = draftInstall(manager, zip)
        assertTrue("install failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertFalse(result.getOrThrow().installationConfirmed)
        assertEquals(null, manager.loadEntryCode("todo-summary"))
        val preview = manager.reviewFromFile(zip).getOrThrow().preview
        val confirmed = manager.installConfirmedFromFile(zip, preview)
        assertTrue(confirmed.isFailure)
        assertTrue(manager.loadEntryCode("todo-summary") == null)
    }

    @Test
    fun signedPluginRequiresExplicitTrustThenLoadsEntryCode() = runBlocking {
        val skillRepo = skillRepoMock()
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val trustStore = PluginTrustStore(trustFile)
        val manager = PluginManager(context, skillRepo, trustStore)
        val signed = signedZip("signed-todo")
        val preview = manager.reviewFromFile(signed.file).getOrThrow().preview

        assertEquals(PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED, preview.signatureStatus)
        assertTrue(manager.installConfirmedFromFile(signed.file, preview).isFailure)
        val trusted = manager.installConfirmedFromFile(signed.file, preview, trustPublisher = true)
        assertTrue("install failed: ${trusted.exceptionOrNull()}", trusted.isSuccess)
        assertEquals(PluginSecurityGate.SignatureStatus.VALID_TRUSTED, manager.list().single { it.id == "signed-todo" }.signatureStatus)
        assertTrue(manager.loadEntryCode("signed-todo")?.contains("function hello") == true)

        trustStore.revoke("publisher.test")
        assertEquals(null, manager.loadEntryCode("signed-todo"))
        trustFile.delete()
        Unit
    }

    // ── P0-1: skill id 防碰撞编码 + 安装时冲突拒绝 ─────────────────────────────

    @Test
    fun skillId_usesLengthPrefixSoUnderscoreValuesCannotCollide() {
        // 旧编码会把两个不同的 (pluginId, toolName) 组合映射成同一个 id。
        assertEquals(
            PluginManager.legacySkillId("a_b", "c"),
            PluginManager.legacySkillId("a", "b_c"),
        )

        val first = PluginManager.skillId("a_b", "c")
        val second = PluginManager.skillId("a", "b_c")
        assertNotEquals(first, second)
        // 长度前缀让 id 前缀可无歧义解码。
        assertTrue(first.startsWith("plugin_3_a_b_"))
        assertTrue(second.startsWith("plugin_1_a_b_c"))
    }

    @Test
    fun install_rejectsSkillIdOccupiedByExistingNonPluginSkill() = runBlocking {
        val dao = FakeSkillDao()
        val manager = PluginManager(context, SkillRepository(dao))
        val occupiedId = PluginManager.skillId("test-plugin", "hello")
        dao.rows[occupiedId] = SkillEntity(
            id = occupiedId,
            name = "用户自定义 skill",
            description = "",
            implementationKotlin = "custom_impl",
            category = "custom",
        )

        val result = draftInstall(manager, zip())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("冲突") == true)
        assertTrue(manager.list().isEmpty())
        assertFalse(File(context.filesDir, "plugins/test-plugin").exists())
        // 已有 skill 不被覆盖。
        assertEquals("custom_impl", dao.rows[occupiedId]?.implementationKotlin)
    }

    @Test
    fun install_rejectsDuplicateToolNamesThatWouldCollide() = runBlocking {
        val dao = FakeSkillDao()
        val manager = PluginManager(context, SkillRepository(dao))
        val manifest = """
            {
              "id": "dup-tools",
              "name": "Dup Tools",
              "version": "1.0.0",
              "entry": "main.js",
              "tools": [
                {"name": "hello", "description": "a", "parametersJson": "{}", "requiredJson": "[]", "functionName": "hello"},
                {"name": "hello", "description": "b", "parametersJson": "{}", "requiredJson": "[]", "functionName": "helloB"}
              ]
            }
        """.trimIndent()

        val result = draftInstall(manager, zip(manifest = manifest))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("重复") == true)
        assertTrue(dao.rows.isEmpty())
    }

    // ── P0-2: 安装/更新/卸载 skill 生命周期对账 ───────────────────────────────

    @Test
    fun update_removesSkillsDroppedFromNewManifest() = runBlocking {
        val dao = FakeSkillDao()
        val manager = PluginManager(context, SkillRepository(dao))
        assertTrue(
            draftInstall(manager, zip(manifest = pluginManifest("1.0.0", listOf("hello", "bye")))).isSuccess,
        )
        val helloId = PluginManager.skillId("test-plugin", "hello")
        val byeId = PluginManager.skillId("test-plugin", "bye")
        assertEquals(setOf(helloId, byeId), dao.rows.keys)

        // 旧编码时代注册的历史残留也要在更新时被迁移清理。
        val legacyByeId = PluginManager.legacySkillId("test-plugin", "bye")
        dao.rows[legacyByeId] = ownedSkill(legacyByeId, "test-plugin", "bye")

        assertTrue(
            draftInstall(manager, zip(manifest = pluginManifest("1.1.0", listOf("hello")))).isSuccess,
        )

        assertEquals(setOf(helloId), dao.rows.keys)
        assertFalse(dao.rows.containsKey(byeId))
        assertFalse(dao.rows.containsKey(legacyByeId))
    }

    @Test
    fun uninstall_removesSkillsRecordedFromPreviousManifestVersions() = runBlocking {
        val dao = FakeSkillDao()
        val manager = PluginManager(context, SkillRepository(dao))
        assertTrue(
            draftInstall(manager, zip(manifest = pluginManifest("1.0.0", listOf("hello", "bye")))).isSuccess,
        )
        assertTrue(
            draftInstall(manager, zip(manifest = pluginManifest("1.1.0", listOf("hello")))).isSuccess,
        )

        // 模拟历史清理中断：当前 manifest（v1.1.0）已不含 bye，但旧 skill 仍在库中。
        val legacyByeId = PluginManager.legacySkillId("test-plugin", "bye")
        dao.rows[legacyByeId] = ownedSkill(legacyByeId, "test-plugin", "bye")

        manager.uninstall("test-plugin")

        assertTrue(dao.rows.isEmpty())
        assertFalse(File(context.filesDir, "plugins/test-plugin").exists())
    }

    @Test
    fun uninstall_keepsSkillsNotOwnedByPlugin() = runBlocking {
        val dao = FakeSkillDao()
        val manager = PluginManager(context, SkillRepository(dao))
        assertTrue(draftInstall(manager, zip()).isSuccess)

        // 同一 id 但并非本插件的实现（用户/内置 skill）：卸载必须保留。
        val notOwnedId = PluginManager.legacySkillId("test-plugin", "hello")
        dao.rows[notOwnedId] = SkillEntity(
            id = notOwnedId,
            name = "用户 skill",
            description = "",
            implementationKotlin = "custom_impl",
            category = "custom",
        )

        manager.uninstall("test-plugin")

        assertEquals(setOf(notOwnedId), dao.rows.keys)
    }

    // ── P0-3: 执行前使用重新验证后的 manifest ────────────────────────────────

    @Test
    fun loadVerifiedPlugin_revalidatesManifestInsteadOfRegistryCache() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val manager = PluginManager(context, skillRepoMock(), PluginTrustStore(trustFile))
        val signed = signedZip("verified-plugin")
        val preview = manager.reviewFromFile(signed.file).getOrThrow().preview
        val trusted = manager.installConfirmedFromFile(signed.file, preview, trustPublisher = true)
        assertTrue("install failed: ${trusted.exceptionOrNull()}", trusted.isSuccess)

        // 篡改注册表缓存字段（模拟被污染的缓存/旧字段），执行路径必须忽略它。
        val registryFile = File(context.filesDir, "plugin_registry.json")
        val root = AppJson.parseToJsonElement(registryFile.readText()).jsonObject
        val plugins = root.getValue("plugins").jsonArray.map { element ->
            val plugin = element.jsonObject
            if (plugin.getValue("id").jsonPrimitive.content == "verified-plugin") {
                JsonObject(plugin + ("capabilities" to JsonArray(listOf(JsonPrimitive("network")))))
            } else {
                element
            }
        }
        registryFile.writeText(JsonObject(root + ("plugins" to JsonArray(plugins))).toString())

        val reloaded = PluginManager(context, skillRepoMock(), PluginTrustStore(trustFile))
        assertEquals(listOf("network"), reloaded.findPlugin("verified-plugin")?.capabilities)

        val verified = requireNotNull(reloaded.loadVerifiedPlugin("verified-plugin"))
        // capabilities/tools 来自磁盘重新验证的 manifest，而不是注册表缓存。
        assertEquals(listOf("resource.read"), verified.capabilities)
        assertEquals(listOf("hello"), verified.tools.map { it.name })
        assertTrue(verified.entryCode.contains("function hello"))
        // loadEntryCode 保持为薄包装，行为不变。
        assertEquals(verified.entryCode, reloaded.loadEntryCode("verified-plugin"))
        trustFile.delete()
        Unit
    }

    @Test
    fun loadVerifiedFunction_rejectsFunctionRemovedFromManifest() = runBlocking {
        val trustFile = File(context.cacheDir, "trust_${System.nanoTime()}.json")
        val manager = PluginManager(context, skillRepoMock(), PluginTrustStore(trustFile))
        val signed = signedZip("function-gate")
        val preview = manager.reviewFromFile(signed.file).getOrThrow().preview
        val trusted = manager.installConfirmedFromFile(signed.file, preview, trustPublisher = true)
        assertTrue("install failed: ${trusted.exceptionOrNull()}", trusted.isSuccess)

        assertNotNull(manager.loadVerifiedFunction("function-gate", "hello"))
        assertNull(manager.loadVerifiedFunction("function-gate", "removedFunction"))
        // 已禁用/未确认或内容不可验证时不返回执行视图。
        assertNull(manager.loadVerifiedFunction("missing-plugin", "hello"))
        trustFile.delete()
        Unit
    }

    /** 真实 DAO 对未占用的 id 返回 null；relaxed mock 默认返回非空 mock，会误触发冲突检测。 */
    private fun skillRepoMock(): SkillRepository = mockk<SkillRepository>(relaxed = true).also { repo ->
        coEvery { repo.getById(any()) } returns null
    }

    /** installFromFile 已删除：以「先预览、后按禁用草稿安装」的现行入口等价替代。 */
    private suspend fun draftInstall(
        manager: PluginManager,
        file: File,
    ): Result<PluginManager.InstalledPlugin> {
        val decision = manager.reviewFromFile(file)
        val preview = decision.getOrElse { error -> return Result.failure(error) }.preview
        return manager.installDraftFromFile(file, preview)
    }

    private data class SignedZip(
        val file: File,
    )

    private fun signedZip(id: String): SignedZip {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val entryCode = "function hello() { return 'signed'; }"
        val unsignedManifest = PluginManifest(
            id = id,
            name = "Signed Plugin",
            version = "1.0.0",
            entry = "main.js",
            capabilities = listOf("resource.read"),
            tools = listOf(
                io.zer0.muse.tools.script.ToolDeclaration(
                    name = "hello",
                    description = "say hello",
                    parametersJson = "{}",
                    requiredJson = "[]",
                    functionName = "hello",
                ),
            ),
            signature = PluginSignature("publisher.test", publicKey, ""),
        )
        val unsignedPackage = PluginPackageLoader.LoadedPluginPackage(unsignedManifest, entryCode)
        val signature = Signature.getInstance(PluginSecurityGate.SIGNATURE_ALGORITHM).apply {
            initSign(keyPair.private)
            update(PluginSecurityGate.signaturePayload(unsignedPackage))
        }.sign()
        val manifest = unsignedManifest.copy(
            signature = unsignedManifest.signature!!.copy(
                signature = Base64.getEncoder().encodeToString(signature),
            ),
        )
        return SignedZip(
            zip(
                manifest = AppJson.encodeToString(PluginManifest.serializer(), manifest),
                entry = entryCode,
            ),
        )
    }

    private fun pluginManifest(version: String, tools: List<String>): String {
        val toolsJson = tools.joinToString(",") { tool ->
            """{"name": "$tool", "description": "say $tool", "parametersJson": "{}", "requiredJson": "[]", "functionName": "$tool"}"""
        }
        return """
            {
              "id": "test-plugin",
              "name": "Test Plugin",
              "version": "$version",
              "entry": "main.js",
              "kind": "tool",
              "capabilities": ["ui"],
              "tools": [$toolsJson]
            }
        """.trimIndent()
    }

    private fun ownedSkill(id: String, pluginId: String, functionName: String): SkillEntity = SkillEntity(
        id = id,
        name = "stale",
        description = "",
        implementationKotlin = "plugin:$pluginId:$functionName",
        category = "plugin",
    )

    private fun zip(
        manifest: String = """
            {
              "id": "test-plugin",
              "name": "Test Plugin",
              "version": "1.0.0",
              "entry": "main.js",
              "tools": [
                {
                  "name": "hello",
                  "description": "say hello",
                  "parametersJson": "{}",
                  "requiredJson": "[]",
                  "functionName": "hello"
                }
              ]
            }
        """.trimIndent(),
        entry: String = "function hello(){ return 'ok'; }",
    ): File {
        val bytes = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(manifest.toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("main.js"))
                zos.write(entry.toByteArray())
                zos.closeEntry()
            }
            bos.toByteArray()
        }
        val file = File(context.cacheDir, "test_${System.nanoTime()}.muse-plugin")
        file.writeBytes(bytes)
        return file
    }

    /** 内存版 SkillDao：让安装/更新/卸载的 skill 生命周期断言基于真实行状态。 */
    private class FakeSkillDao : SkillDao {
        val rows = linkedMapOf<String, SkillEntity>()

        override fun observeAll(): Flow<List<SkillEntity>> = MutableStateFlow(rows.values.toList())

        override suspend fun listEnabled(): List<SkillEntity> = rows.values.filter { it.enabled }

        override suspend fun listEnabledByIds(ids: List<String>): List<SkillEntity> =
            rows.values.filter { it.enabled && it.id in ids }

        override suspend fun getById(id: String): SkillEntity? = rows[id]

        override suspend fun upsert(entity: SkillEntity) {
            rows[entity.id] = entity
        }

        override suspend fun seedIfAbsent(entity: SkillEntity) {
            rows.putIfAbsent(entity.id, entity)
        }

        override suspend fun update(entity: SkillEntity) {
            rows[entity.id] = entity
        }

        override suspend fun delete(id: String) {
            rows.remove(id)
        }

        override suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long) {
            rows[id]?.let { rows[id] = it.copy(enabled = enabled, updatedAt = updatedAt) }
        }

        override suspend fun getAll(): List<SkillEntity> = rows.values.toList()

        override suspend fun deleteAll() {
            rows.clear()
        }
    }
}
