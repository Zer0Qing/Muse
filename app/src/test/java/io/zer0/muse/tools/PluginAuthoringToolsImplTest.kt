package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.muse.data.plugin.LocalAuthorKeyStore
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.data.plugin.PluginTrustStore
import io.zer0.muse.data.skill.SkillRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * author_plugin 工具层测试：参数解析、结构化失败、草稿语义与审批等级。
 *
 * 数据层边界见 `data/plugin/PluginAuthoringTest`；这里只锁「模型能看到的返回文本」
 * 与「工具确实以 HIGH 风险进入审批」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginAuthoringToolsImplTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun authorPluginIsRegisteredAsBuiltInSkillAndRoutable() {
        val skills = SkillExecutor.BUILT_IN_SKILLS
        val definition = skills.firstOrNull { it.id == "author_plugin" }

        assertTrue("author_plugin 必须是内置 skill", definition != null)
        assertEquals("author_plugin", definition?.implementationKotlin)
        assertTrue("author_plugin 必须可被 SkillExecutor 路由", "author_plugin" in SkillExecutor.ROUTABLE_SKILL_IMPL)
    }

    @Test
    fun authorPluginRequiresApprovalInAskAndStrictModes() {
        // 草稿本身不可执行，但「让模型写将被用户签名的代码」必须先经过用户审批。
        assertEquals(
            ToolApprovalState.Pending,
            ToolPermissionResolver.resolve("author_plugin", null, SessionPermissionMode.ASK, null),
        )
        assertEquals(
            ToolApprovalState.Pending,
            ToolPermissionResolver.resolve("author_plugin", null, SessionPermissionMode.STRICT, null),
        )
    }

    @Test
    fun malformedToolsReturnStructuredError() = runBlocking {
        val impl = PluginAuthoringToolsImpl(context, manager())

        val result = impl.authorPlugin(
            mapOf(
                "id" to "authored-tool",
                "name" to "助手工具",
                "version" to "1.0.0",
                "code" to "function hello() { return 'hi'; }",
                "tools" to "not-json",
            ),
        )

        assertTrue("失败必须返回结构化 JSON: $result", result.contains("\"error\""))
    }

    @Test
    fun validationFailureReturnsReadableStructuredError() = runBlocking {
        val impl = PluginAuthoringToolsImpl(context, manager())

        val result = impl.authorPlugin(
            mapOf(
                "id" to "authored-tool",
                "name" to "助手工具",
                "version" to "not-semver",
                "code" to "function hello() { return 'hi'; }",
                "tools" to TOOLS_JSON,
            ),
        )

        assertTrue("失败必须返回结构化 JSON: $result", result.contains("\"error\""))
        assertTrue("原因应说明版本号问题: $result", result.contains("语义化版本"))
    }

    @Test
    fun successfulDraftTellsModelItIsDisabledAndNeedsUserSigning() = runBlocking {
        val manager = manager()
        val impl = PluginAuthoringToolsImpl(context, manager)

        val result = impl.authorPlugin(
            mapOf(
                "id" to "authored-tool",
                "name" to "助手工具",
                "description" to "由助手起草",
                "version" to "1.0.0",
                "code" to "function hello() { return 'hi'; }",
                "tools" to TOOLS_JSON,
            ),
        )

        // 返回给模型的文本必须点明：未签名草稿、已禁用、需用户在插件管理里签名并启用。
        // 断言同时接受中英文文案（Robolectric 默认 en 区域）。
        assertTrue(result.contains("authored-tool"))
        assertTrue("应说明草稿未签名: $result", result.contains("未签名") || result.contains("Unsigned"))
        assertTrue(
            "应要求用户签名并启用: $result",
            result.contains("签名并启用") || result.contains("Sign and enable"),
        )
        val draft = requireNotNull(manager.findPlugin("authored-tool"))
        assertFalse(draft.enabled)
        assertFalse(draft.installationConfirmed)
        assertEquals(null, manager.loadVerifiedPlugin("authored-tool"))
    }

    @Test
    fun missingPluginManagerReturnsStructuredError() = runBlocking {
        val impl = PluginAuthoringToolsImpl(context, pluginManager = null)

        val result = impl.authorPlugin(mapOf("id" to "x", "code" to "function f() {}"))

        assertTrue(result.contains("\"error\""))
    }

    private fun manager(): PluginManager = PluginManager(
        context = context,
        skillRepository = mockk<SkillRepository>(relaxed = true).also { repo ->
            coEvery { repo.getById(any()) } returns null
        },
        trustStore = PluginTrustStore(File(context.cacheDir, "trust_${System.nanoTime()}.json")),
        appVersionName = "1.0.89",
        localAuthorKeyStore = LocalAuthorKeyStore(File(context.cacheDir, "author_key_${System.nanoTime()}.json")),
    )

    private companion object {
        const val TOOLS_JSON =
            """[{"name":"hello","description":"打招呼","parametersJson":"{}","requiredJson":"[]","functionName":"hello"}]"""
    }
}
