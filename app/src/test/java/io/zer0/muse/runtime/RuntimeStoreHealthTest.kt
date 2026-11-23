package io.zer0.muse.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeStoreHealthTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `health requires rootfs node npm and all proot libraries`() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val native = temporaryFolder.newFolder("native")
        createRequiredRootfs(rootfs)
        createRequiredNative(native)

        val ready = RuntimeStore.inspectHealth(rootfs, native, "r1")
        assertTrue(ready is RuntimeHealth.Ready)

        File(native, "libandroid-shmem.so").delete()
        val broken = RuntimeStore.inspectHealth(rootfs, native, "r1")
        when (broken) {
            is RuntimeHealth.Ready -> throw AssertionError("Expected a missing native library")
            is RuntimeHealth.Broken -> assertEquals(listOf("native/libandroid-shmem.so"), broken.missingPaths)
        }
    }

    @Test
    fun `health rejects missing node and unsafe revision components`() {
        val rootfs = temporaryFolder.newFolder("missing-rootfs")
        val native = temporaryFolder.newFolder("complete-native")
        createRequiredNative(native)

        val health = RuntimeStore.inspectHealth(rootfs, native, "r2")

        when (health) {
            is RuntimeHealth.Ready -> throw AssertionError("Expected missing rootfs paths")
            is RuntimeHealth.Broken -> assertTrue(health.missingPaths.contains("rootfs/usr/local/bin/node"))
        }
        assertTrue(RuntimeStore.isSafeRevision("r2.1"))
        assertTrue(!RuntimeStore.isSafeRevision("../r2"))
        assertTrue(!RuntimeStore.isSafeRevision("r2/other"))
    }

    private fun createRequiredRootfs(root: File) {
        listOf(
            "usr/local/bin/node",
            "usr/local/lib/node_modules/npm/bin/npm-cli.js",
            "usr/bin/.keep",
            "tmp/.keep",
        ).forEach { path -> File(root, path).apply { parentFile?.mkdirs(); writeText("ok") } }
    }

    private fun createRequiredNative(root: File) {
        listOf("libmuse_proot.so", "libmuse_proot_loader.so", "libtalloc.so", "libandroid-shmem.so")
            .forEach { File(root, it).writeText("bin") }
    }
}
