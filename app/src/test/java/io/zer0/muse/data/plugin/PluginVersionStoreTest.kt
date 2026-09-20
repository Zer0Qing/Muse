package io.zer0.muse.data.plugin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 历史版本保留与剪枝测试（回滚能力的数据层）。 */
class PluginVersionStoreTest {

    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("plugin-versions-test").toFile().also { tempDirs += it }

    private fun packageDir(vararg files: Pair<String, String>): File {
        val dir = tempDir()
        files.forEach { (name, content) ->
            val target = File(dir, name)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }
        return dir
    }

    private fun record(version: String, contentSha: String = "a".repeat(64)) = RetainedVersionRecord(
        version = version,
        publisherId = "museai",
        publisherKeyFingerprint = "b".repeat(64),
        contentSha256 = contentSha,
        retainedAtEpochMs = 0L,
    )

    @Test
    fun retainsPackageCopyWithMetadata() {
        val store = PluginVersionStore(tempDir())
        val source = packageDir("manifest.json" to "{}", "main.js" to "function a(){}")

        assertTrue(store.retain("math-toolkit", "1.0.0", source, record("1.0.0")).isSuccess)

        val retained = store.list("math-toolkit")
        assertEquals(1, retained.size)
        assertEquals("1.0.0", retained.first().version)
        assertEquals("museai", retained.first().record.publisherId)
        // 包内文件被完整复制，回滚时才能重算内容摘要。
        assertEquals("function a(){}", File(retained.first().directory, "main.js").readText())
        assertEquals("{}", File(retained.first().directory, "manifest.json").readText())
    }

    @Test
    fun keepsSubdirectoriesOfThePackage() {
        val store = PluginVersionStore(tempDir())
        val source = packageDir("manifest.json" to "{}", "main.js" to "x")

        assertTrue(store.retain("ext-toolkit", "1.0.0", source, record("1.0.0")).isSuccess)
        val files = store.list("ext-toolkit").first().directory
        assertTrue(File(files, "main.js").isFile)
    }

    @Test
    fun listsNewestVersionFirst() {
        val store = PluginVersionStore(tempDir())
        listOf("1.0.0", "1.2.0", "1.1.0").forEach { version ->
            store.retain("math-toolkit", version, packageDir("manifest.json" to version), record(version))
        }

        assertEquals(listOf("1.2.0", "1.1.0", "1.0.0"), store.list("math-toolkit").map { it.version })
    }

    @Test
    fun retainsAtMostConfiguredNumberOfVersions() {
        val store = PluginVersionStore(tempDir(), maxRetained = 2)
        listOf("1.0.0", "1.1.0", "1.2.0", "1.3.0").forEach { version ->
            store.retain("math-toolkit", version, packageDir("manifest.json" to version), record(version))
        }

        assertEquals(listOf("1.3.0", "1.2.0"), store.list("math-toolkit").map { it.version })
    }

    @Test
    fun retainingSameVersionReplacesPreviousCopy() {
        val store = PluginVersionStore(tempDir())
        store.retain("math-toolkit", "1.0.0", packageDir("manifest.json" to "old"), record("1.0.0"))
        store.retain(
            "math-toolkit",
            "1.0.0",
            packageDir("manifest.json" to "new"),
            record("1.0.0", contentSha = "c".repeat(64)),
        )

        val retained = store.list("math-toolkit")
        assertEquals(1, retained.size)
        assertEquals("new", File(retained.first().directory, "manifest.json").readText())
        assertEquals("c".repeat(64), retained.first().record.contentSha256)
    }

    @Test
    fun findDeleteAndDeleteAllBehaveAsExpected() {
        val store = PluginVersionStore(tempDir())
        store.retain("math-toolkit", "1.0.0", packageDir("manifest.json" to "{}"), record("1.0.0"))
        store.retain("math-toolkit", "2.0.0", packageDir("manifest.json" to "{}"), record("2.0.0"))

        assertNotNull(store.find("math-toolkit", "1.0.0"))
        assertNull(store.find("math-toolkit", "9.9.9"))

        store.delete("math-toolkit", "1.0.0")
        assertEquals(listOf("2.0.0"), store.list("math-toolkit").map { it.version })

        store.deleteAll("math-toolkit")
        assertTrue(store.list("math-toolkit").isEmpty())
    }

    @Test
    fun rejectsInvalidIdentifiersAndMissingSource() {
        val store = PluginVersionStore(tempDir())
        val source = packageDir("manifest.json" to "{}")

        assertFalse(store.retain("../escape", "1.0.0", source, record("1.0.0")).isSuccess)
        assertFalse(store.retain("math-toolkit", "not-a-version", source, record("not-a-version")).isSuccess)
        assertFalse(store.retain("math-toolkit", "1.0.0", File(tempDir(), "missing"), record("1.0.0")).isSuccess)
        // 元数据里的版本必须与保留的版本一致，否则回滚会取到错误的目标。
        assertFalse(store.retain("math-toolkit", "1.0.0", source, record("2.0.0")).isSuccess)
    }

    @Test
    fun corruptMetadataIsSkippedInsteadOfCrashing() {
        val store = PluginVersionStore(tempDir())
        store.retain("math-toolkit", "1.0.0", packageDir("manifest.json" to "{}"), record("1.0.0"))
        File(store.list("math-toolkit").first().directory.parentFile, "meta.json").writeText("{broken")

        assertTrue(store.list("math-toolkit").isEmpty())
    }
}
