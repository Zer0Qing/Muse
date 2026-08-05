package io.zer0.muse.data.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coVerify
import io.mockk.mockk
import io.zer0.muse.data.skill.SkillRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * B6-01: 外部插件安装/卸载/安全校验测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginManagerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun install_registersSkillAndPersistsPlugin() = runBlocking {
        val skillRepo = mockk<SkillRepository>(relaxed = true)
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

        val result = manager.installFromFile(zip)
        assertTrue(result.isSuccess)
        assertEquals("test-plugin", manager.list().single().id)
        assertTrue(File(context.filesDir, "plugins/test-plugin/main.js").exists())
        coVerify { skillRepo.upsert(match { it.id == "plugin_test-plugin_hello" }) }
    }

    @Test
    fun uninstall_removesDirAndSkill() = runBlocking {
        val skillRepo = mockk<SkillRepository>(relaxed = true)
        val manager = PluginManager(context, skillRepo)
        val result = manager.installFromFile(zip())
        assertTrue(result.isSuccess)

        manager.uninstall("test-plugin")

        assertTrue(manager.list().isEmpty())
        assertFalse(File(context.filesDir, "plugins/test-plugin").exists())
        coVerify { skillRepo.delete("plugin_test-plugin_hello") }
    }

    @Test
    fun install_rejectsUndeclaredCapability() = runBlocking {
        val skillRepo = mockk<SkillRepository>(relaxed = true)
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

        val result = manager.installFromFile(zip)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("不允许的能力") == true)
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun install_pluginWithJsEntry_keepsEntryCode() = runBlocking {
        val skillRepo = mockk<SkillRepository>(relaxed = true)
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

        val result = manager.installFromFile(zip)
        assertTrue(result.isSuccess)
        val code = manager.loadEntryCode("todo-summary")
        assertTrue(code?.contains("function summarizeTodos") == true)
    }

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
}
