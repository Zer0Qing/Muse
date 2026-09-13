package io.zer0.muse.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AtomicFileStore 的原子写入和损坏文件隔离测试。 */
class AtomicFileStoreTest {

    @Test
    fun writeText_createsParentAndReplacesCompleteFile() {
        val root = Files.createTempDirectory("muse-atomic-test").toFile()
        val target = File(root, "atomic-store/state.json")

        AtomicFileStore.writeText(target, "first")
        assertEquals("first", target.readText())

        AtomicFileStore.writeText(target, "second")
        assertEquals("second", target.readText())
        assertTrue(target.parentFile?.isDirectory == true)
        root.deleteRecursively()
    }

    @Test
    fun quarantine_movesCorruptFileAndPreservesContent() {
        val root = Files.createTempDirectory("muse-atomic-test").toFile()
        val target = File(root, "atomic-store/corrupt.json")
        target.parentFile?.mkdirs()
        target.writeText("{broken")

        val quarantined = AtomicFileStore.quarantine(target, "json parse")

        assertNotNull(quarantined)
        assertFalse(target.exists())
        assertEquals("{broken", quarantined?.readText())
        assertTrue(quarantined?.name?.contains("corrupt") == true)
        root.deleteRecursively()
    }
}
