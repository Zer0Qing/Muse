package io.zer0.common

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 摘要计算 — SHA-256 的单一真源。
 *
 * 收敛前全仓有 9 个私有实现，形态三种：`ByteArray → hex`、`String → hex`、
 * `File → hex`（流式，不把整个文件读进内存）。它们算的是同一个东西，
 * 但各自写了一遍十六进制转换。
 *
 * 十六进制一律小写无分隔 —— 这是 OAuth PKCE、S3 签名、备份校验、
 * 插件包校验共同依赖的格式，任何一处改成大写都会让校验静默失配。
 */
object Hashing {

    private const val ALGORITHM = "SHA-256"

    /** 流式读取的缓冲区大小。8KB 在内存占用与系统调用次数之间取平衡。 */
    private const val BUFFER_SIZE = 8 * 1024

    /** 字节数组的 SHA-256，小写十六进制。 */
    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance(ALGORITHM)
        .digest(bytes)
        .toHex()

    /** 字符串按 UTF-8 编码后的 SHA-256，小写十六进制。 */
    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /**
     * 文件的 SHA-256，小写十六进制。
     *
     * 流式读取：安装包动辄几十 MB，整个读进内存只为算摘要不值得。
     */
    fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

    /** 输入流的 SHA-256，小写十六进制。读完不关闭（由调用方负责）。 */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}
