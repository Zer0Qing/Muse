package io.zer0.muse.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeManifestSourceTest {

    @Test
    fun `remote candidate stays first and identical bundled content is removed`() {
        val remote = ManifestCandidate(document("r1"), "remote:manifest-arm64-v8a.json")
        val identicalBundled = ManifestCandidate(document("r1"), "bundled:manifest-arm64-v8a.json")
        val olderBundled = ManifestCandidate(document("r0"), "bundled:manifest-arm64-v8a.json")

        val ordered = RuntimeManifestSource.orderCandidates(listOf(remote, identicalBundled, olderBundled))

        assertEquals(listOf(remote, olderBundled), ordered)
        assertEquals(2, ordered.map { it.fingerprint }.distinct().size)
        assertNotEquals(remote.fingerprint, olderBundled.fingerprint)
    }

    @Test
    fun `candidate fingerprint does not depend on its source`() {
        val document = document("r1")
        val first = ManifestCandidate(document, "remote")
        val second = ManifestCandidate(document, "bundled")

        assertEquals(first.fingerprint, second.fingerprint)
        assertTrue(RuntimeManifestSource.orderCandidates(listOf(first, second)).single().source == "remote")
    }

    private fun document(revision: String) = RuntimeManifestDocument(
        runtimeId = "ubuntu-node",
        abi = "arm64-v8a",
        versions = listOf(
            RuntimeVersion(
                revision = revision,
                archive = RuntimeManifest.Archive(
                    name = "rootfs.tar.gz",
                    sizeBytes = 10,
                    sha256 = SHA,
                    urls = listOf(
                        "https://gh-proxy.com/https://github.com/Zer0Qing/Muse/releases/download/runtime-$revision/rootfs-arm64-v8a.tar.gz",
                        "https://ghproxy.net/https://github.com/Zer0Qing/Muse/releases/download/runtime-$revision/rootfs-arm64-v8a.tar.gz",
                        "https://github.com/Zer0Qing/Muse/releases/download/runtime-$revision/rootfs-arm64-v8a.tar.gz",
                    ),
                ),
                installedBytes = 20,
                bins = BIN_HASHES,
            ),
        ),
    )

    private companion object {
        const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val BIN_HASHES = mapOf(
            "proot" to SHA,
            "loader" to SHA,
            "libtalloc.so.2" to SHA,
            "libandroid-shmem.so" to SHA,
        )
    }
}
