package io.zer0.muse.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeInstallOperationStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `operation is atomically saved and can be resumed after a new store instance`() {
        val file = temporaryFolder.newFile("operation.json")
        val firstStore = RuntimeInstallOperationStore(file)
        val operation = operation().copy(
            phase = RuntimeInstallPhase.DOWNLOADING,
            downloadedBytes = 4096,
            totalBytes = 8192,
            sourceUrl = "https://mirror.example/rootfs.tar.gz",
            etag = "etag-1",
        )

        firstStore.saveAtomic(operation)

        assertEquals(operation, RuntimeInstallOperationStore(file).load())
        assertTrue(file.readText().contains("DOWNLOADING"))
    }

    @Test
    fun `corrupt operation is quarantined and does not prevent a new operation`() {
        val file = temporaryFolder.newFile("operation.json").apply { writeText("not json") }
        val store = RuntimeInstallOperationStore(file)

        assertNull(store.load())
        assertFalse(file.exists())
        assertTrue(temporaryFolder.root.listFiles().orEmpty().any { it.name.startsWith("operation.json.corrupt-") })

        store.saveAtomic(operation())
        assertNotNull(store.load())
    }

    @Test
    fun `clear removes persisted state`() {
        val file = temporaryFolder.newFile("operation.json")
        val store = RuntimeInstallOperationStore(file)
        store.saveAtomic(operation())

        store.clear()

        assertNull(store.load())
    }

    private fun operation() = RuntimeInstallOperation(
        operationId = "op-1",
        runtimeId = "ubuntu-node",
        abi = "arm64-v8a",
        revision = "r1",
        phase = RuntimeInstallPhase.SELECTING,
        updatedAt = 1L,
    )
}
