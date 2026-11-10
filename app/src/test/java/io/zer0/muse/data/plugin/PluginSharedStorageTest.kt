package io.zer0.muse.data.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 插件间共享 KV（`storage.shared:<名字>`）。
 *
 * 三条约束的测试重点都在"拒绝面"：不声明不能碰、名字非法不能拼路径、
 * 未声明名字空间的动作不能被放行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginSharedStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `declared namespaces are extracted from capabilities`() {
        val declared = PluginSharedStorage.declaredNamespaces(
            listOf("storage", "network", "storage.shared:notes", "storage.shared: 任务清单 ", "storage.shared:notes"),
        )

        assertEquals(setOf("notes", "任务清单"), declared)
    }

    @Test
    fun `illegal namespace names are dropped instead of being trusted`() {
        val declared = PluginSharedStorage.declaredNamespaces(
            listOf(
                "storage.shared:../escape",
                "storage.shared:",
                "storage.shared:a/b",
                "storage.shared:" + "x".repeat(33),
            ),
        )

        assertTrue("非法名字一个都不该留下: $declared", declared.isEmpty())
    }

    @Test
    fun `names that are merely unusual are accepted`() {
        // 大写与点号对路径是安全的；中文名同理（中文优先的应用里这是最常见的写法）
        val declared = PluginSharedStorage.declaredNamespaces(
            listOf("storage.shared:UPPER", "storage.shared:my.app"),
        )

        assertEquals(setOf("UPPER", "my.app"), declared)
    }

    @Test
    fun `an illegal namespace cannot produce a path`() {
        assertNull(PluginSharedStorage.fileFor(context, "../escape"))
        assertNull(PluginSharedStorage.fileFor(context, "  padded  "))
        assertNull(PluginSharedStorage.fileFor(context, ""))
    }

    @Test
    fun `a legal namespace resolves under the shared root`() {
        val file = PluginSharedStorage.fileFor(context, "notes")

        assertTrue(
            "要落在 plugin-shared-kv 下: ${file?.absolutePath}",
            file!!.absolutePath.replace('\\', '/').contains("/${PluginSharedStorage.ROOT_DIR_NAME}/notes/"),
        )
    }

    // ── 网关 ────────────────────────────────────────────────────────────

    private fun gateway(): PluginCapabilityGateway = PluginCapabilityGateway(File(context.filesDir, "plugin_permissions.json"))

    @Test
    fun `a shared action is refused when nothing is declared`() {
        val decision = gateway().check("p1", listOf("storage"), "storage_shared_get")

        assertTrue("应当拒绝: $decision", decision is PluginCapabilityGateway.Decision.Deny)
        assertTrue(
            "理由要指向声明方式: ${(decision as PluginCapabilityGateway.Decision.Deny).reason}",
            decision.reason.contains("storage.shared"),
        )
    }

    @Test
    fun `a shared action is allowed once a namespace is declared`() {
        val decision = gateway().check("p1", listOf("storage.shared:notes"), "storage_shared_set")

        assertTrue("应当放行: $decision", decision is PluginCapabilityGateway.Decision.Allow)
    }

    @Test
    fun `unrelated actions are unaffected by the shared rule`() {
        // 共享规则不能顺带把别的动作放进来
        val decision = gateway().check("p1", listOf("storage.shared:notes"), "fs_write")

        assertTrue(decision is PluginCapabilityGateway.Decision.Deny)
    }
}
