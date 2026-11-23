package io.zer0.muse.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeManifestContractTest {

    @Test
    fun `legacy flat archive is promoted to a version and existing mirror order is valid`() {
        val legacy = RuntimeManifestDocument.parse(
            """
            {
              "runtimeId":"ubuntu-node",
              "revision":"r1",
              "abi":"arm64-v8a",
              "upstream":{"node":"24.22.1"},
              "archive":{"name":"rootfs.tar.gz","sizeBytes":10,"sha256":"$SHA","urls":$ARM_URLS},
              "installedBytes":20,
              "bins":$BINS
            }
            """.trimIndent(),
        )

        assertEquals(1, legacy?.versionsOrLegacy()?.size)
        assertTrue(legacy?.validateFor("arm64-v8a") is ManifestValidation.Valid)
        assertEquals(
            listOf(
                "https://gh-proxy.com/https://github.com/Zer0Qing/Muse/releases/download/runtime-r1/rootfs-arm64-v8a.tar.gz",
                "https://ghproxy.net/https://github.com/Zer0Qing/Muse/releases/download/runtime-r1/rootfs-arm64-v8a.tar.gz",
                "https://github.com/Zer0Qing/Muse/releases/download/runtime-r1/rootfs-arm64-v8a.tar.gz",
            ),
            legacy?.versionsOrLegacy()?.single()?.archive?.urls,
        )
    }

    @Test
    fun `contract rejects abi versions urls hashes revisions and local hosts`() {
        val valid = document()
        assertInvalid(valid.copy(abi = "x86_64"), "ABI")
        assertInvalid(valid.copy(versions = emptyList(), revision = null, archive = null, installedBytes = null), "版本")
        assertInvalid(valid.withArchive { copy(urls = emptyList()) }, "urls")
        assertInvalid(valid.withArchive { copy(sha256 = "bad") }, "sha256")
        assertInvalid(valid.withArchive { copy(urls = listOf("http://mirror.example/rootfs.tar.gz")) }, "HTTPS")
        assertInvalid(valid.withRevision("../r1"), "revision")
        assertInvalid(valid.withArchive { copy(urls = listOf("https://localhost/rootfs.tar.gz")) }, "HTTPS")
        assertInvalid(valid.withArchive { copy(urls = listOf("https://mirror.example/rootfs.tar.gz")) }, "GitHub")
    }

    @Test
    fun `version list supports explicit metadata and per version validation`() {
        val version = document().versions.single().copy(
            label = "Node 24.22.1",
            releasedAt = "2026-11-01T00:00:00Z",
        )
        val listed = document().copy(versions = listOf(version, version.copy(revision = "r2")))

        assertEquals(2, listed.versionsOrLegacy().size)
        assertTrue(listed.validateFor("arm64-v8a") is ManifestValidation.Valid)
        assertFalse(listed.validateFor("x86_64") is ManifestValidation.Valid)
    }

    private fun document(): RuntimeManifestDocument = RuntimeManifestDocument(
        runtimeId = "ubuntu-node",
        abi = "arm64-v8a",
        versions = listOf(
            RuntimeVersion(
                revision = "r1",
                archive = RuntimeManifest.Archive("rootfs.tar.gz", 10, SHA, ARM_SOURCE_URLS),
                installedBytes = 20,
                bins = BIN_HASHES,
            ),
        ),
    )

    private fun RuntimeManifestDocument.withArchive(
        transform: RuntimeManifest.Archive.() -> RuntimeManifest.Archive,
    ): RuntimeManifestDocument = copy(
        versions = versions.map { it.copy(archive = it.archive.transform()) },
    )

    private fun RuntimeManifestDocument.withRevision(value: String): RuntimeManifestDocument = copy(
        versions = versions.map { it.copy(revision = value) },
    )

    private fun assertInvalid(document: RuntimeManifestDocument, expectedText: String) {
        when (val validation = document.validateFor("arm64-v8a")) {
            ManifestValidation.Valid -> throw AssertionError("Expected invalid result for $expectedText")
            is ManifestValidation.Invalid -> assertTrue(
                validation.reasons.joinToString().contains(expectedText),
            )
        }
    }

    private companion object {
        const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ARM_URLS = """["https://gh-proxy.com/https://github.com/Zer0Qing/Muse/releases/download/runtime-r1/rootfs-arm64-v8a.tar.gz","https://ghproxy.net/https://github.com/Zer0Qing/Muse/releases/download/runtime-r1/rootfs-arm64-v8a.tar.gz","https://github.com/Zer0Qing/Muse/releases/download/runtime-r1/rootfs-arm64-v8a.tar.gz"]"""
        val ARM_SOURCE_URLS = listOf(
            "https://gh-proxy.com/https://github.com/Zer0Qing/Muse/releases/download/runtime-r1/rootfs-arm64-v8a.tar.gz",
            "https://ghproxy.net/https://github.com/Zer0Qing/Muse/releases/download/runtime-r1/rootfs-arm64-v8a.tar.gz",
            "https://github.com/Zer0Qing/Muse/releases/download/runtime-r1/rootfs-arm64-v8a.tar.gz",
        )
        const val BINS = """{"proot":"$SHA","loader":"$SHA","libtalloc.so.2":"$SHA","libandroid-shmem.so":"$SHA"}"""
        val BIN_HASHES = mapOf(
            "proot" to SHA,
            "loader" to SHA,
            "libtalloc.so.2" to SHA,
            "libandroid-shmem.so" to SHA,
        )
    }
}
