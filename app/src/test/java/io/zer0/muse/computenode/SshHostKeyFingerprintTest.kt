package io.zer0.muse.computenode

import io.zer0.muse.data.SecureKeyCipher
import io.zer0.muse.data.computenode.RemoteComputeNodeEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class SshHostKeyFingerprintTest {

    @Test
    fun matchingOpenSshSha256FingerprintAcceptsTheRawHostKey() {
        val rawKey = byteArrayOf(0, 1, 2, 3, 4, 5)
        val expected = "SHA256:" + java.util.Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(rawKey))

        assertEquals(expected, SshHostKeyFingerprint.fromRawKey(rawKey))
        assertTrue(SshHostKeyFingerprint.matches(rawKey, expected))
    }

    @Test
    fun mismatchedHostKeyIsRejected() {
        val pin = SshHostKeyFingerprint.fromRawKey(byteArrayOf(1, 2, 3))

        assertFalse(SshHostKeyFingerprint.matches(byteArrayOf(3, 2, 1), pin))
    }

    @Test
    fun missingOrMalformedPinIsRejectedIncludingLegacyEmptyConfiguration() {
        assertNull(SshHostKeyFingerprint.normalize(""))
        assertFalse(SshHostKeyFingerprint.matches(byteArrayOf(1), ""))
        assertNull(SshHostKeyFingerprint.normalize("SHA256:not-a-fingerprint"))
    }

    @Test
    fun legacySshNodeWithoutStoredPinCannotProduceAConnectionSpec() = runTest {
        val cipher = object : SecureKeyCipher {
            override suspend fun encrypt(plain: String): String = plain
            override suspend fun decrypt(stored: String): String = stored
        }
        val oldNode = RemoteComputeNodeEntity(
            nodeId = "legacy",
            host = "example.invalid",
            username = "user",
            encryptedCredential = "credential",
            tlsFingerprint = "",
        )

        val failure = runCatching { oldNode.toSshConnectionSpec(cipher) }.exceptionOrNull()

        assertTrue(failure is SshBootstrapException)
        assertEquals("SSH host key fingerprint is missing or invalid", failure?.message)
    }
}
