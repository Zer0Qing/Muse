package io.zer0.muse.backup

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Version 2 keeps each AES-GCM operation bounded and authenticates the end of the archive. */
internal object FramedBackupCrypto {
    const val VERSION = 2
    private const val FRAME_BYTES = 256 * 1024
    private const val SALT_BYTES = 16
    private const val NONCE_PREFIX_BYTES = 8
    private const val TAG_BYTES = 16

    fun encryptingStream(output: OutputStream, password: String): OutputStream {
        require(password.isNotBlank()) { "Backup password is required" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes)
        val key = BackupCrypto.deriveKey(password, salt)
        val data = DataOutputStream(output)
        data.writeBytes("MENC")
        data.writeByte(VERSION)
        data.write(salt)
        data.write(noncePrefix)
        return BufferedOutputStream(FrameOutput(data, key, noncePrefix), FRAME_BYTES)
    }

    /** The caller has consumed the magic and version. EOF is exposed only after a valid final frame. */
    fun decryptingStream(input: InputStream, password: String): InputStream {
        require(password.isNotBlank()) { "Backup password is required" }
        val data = DataInputStream(input)
        val salt = ByteArray(SALT_BYTES).also(data::readFully)
        val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(data::readFully)
        return FrameInput(data, BackupCrypto.deriveKey(password, salt), noncePrefix)
    }

    private fun cipher(mode: Int, key: SecretKeySpec, prefix: ByteArray, frame: Int, length: Int): Cipher {
        // A unique nonce and authenticated length bind every frame to its position, including the terminator.
        require(frame >= 0) { "Too many encrypted backup frames" }
        val nonce = ByteBuffer.allocate(NONCE_PREFIX_BYTES + Int.SIZE_BYTES).put(prefix).putInt(frame).array()
        val aad = ByteBuffer.allocate(1 + Int.SIZE_BYTES).put(VERSION.toByte()).putInt(length).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BYTES * Byte.SIZE_BITS, nonce))
            updateAAD(aad)
        }
    }

    private class FrameOutput(
        private val sink: DataOutputStream,
        private val key: SecretKeySpec,
        private val prefix: ByteArray,
    ) : OutputStream() {
        private var frame = 0

        override fun write(value: Int) {
            write(byteArrayOf(value.toByte()), 0, 1)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            var consumed = 0
            while (consumed < length) {
                val count = minOf(FRAME_BYTES, length - consumed)
                writeFrame(bytes, offset + consumed, count)
                consumed += count
            }
        }

        private fun writeFrame(bytes: ByteArray, offset: Int, length: Int) {
            val encrypted = cipher(Cipher.ENCRYPT_MODE, key, prefix, frame++, length).doFinal(bytes, offset, length)
            sink.writeInt(length)
            sink.write(encrypted)
        }

        override fun flush() = sink.flush()

        override fun close() {
            sink.use { writeFrame(ByteArray(0), 0, 0) }
        }
    }

    private class FrameInput(
        private val source: DataInputStream,
        private val key: SecretKeySpec,
        private val prefix: ByteArray,
    ) : InputStream() {
        private var frame = 0
        private var current = ByteArray(0)
        private var offset = 0
        private var finished = false

        private fun availableFrame(): Boolean {
            if (offset < current.size) return true
            if (finished) return false
            val length = source.readInt()
            require(length in 0..FRAME_BYTES) { "Invalid encrypted backup frame length" }
            val encrypted = ByteArray(length + TAG_BYTES).also(source::readFully)
            current = cipher(Cipher.DECRYPT_MODE, key, prefix, frame++, length).doFinal(encrypted)
            offset = 0
            if (length == 0) {
                require(source.read() == -1) { "Encrypted backup contains data after its final frame" }
                finished = true
            }
            return !finished
        }

        override fun read(): Int = if (availableFrame()) current[offset++].toInt() and 0xff else -1

        override fun read(bytes: ByteArray, start: Int, length: Int): Int {
            require(start >= 0 && length >= 0 && start <= bytes.size - length)
            if (length == 0) return 0
            if (!availableFrame()) return -1
            val count = minOf(length, current.size - offset)
            current.copyInto(bytes, start, offset, offset + count)
            offset += count
            return count
        }

        override fun close() = source.close()
    }
}
