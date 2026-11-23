package io.zer0.muse.runtime

import io.zer0.common.Logger
import java.io.File
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 极简 tar.gz 解包器。
 *
 * 为什么自己写：Android 没有系统 tar，而运行时包里全是**符号链接**（`/bin` → `/usr/bin` 这类），
 * 用第三方库要么体积大，要么不保留链接语义。这里只实现 POSIX tar 里运行环境实际用到的几种类型，
 * 够用且可审计。
 *
 * 安全约束：拒绝绝对路径与 `..` 路径穿越 —— 包是我们自己构建的，但解包逻辑仍按不可信输入写，
 * 避免将来换成第三方源时留下一个写任意路径的洞。
 */
object TarGzExtractor {

    /** 解出 [archive] 到 [destDir]，[onProgress] 收到的是已写出的字节数。 */
    fun extract(archive: File, destDir: File, onProgress: (Long) -> Unit = {}): Long {
        destDir.mkdirs()
        var written = 0L
        // 2026-11-20(P1-7):链接创建失败不能静默跳过。
        // 真机事故:运行环境安装报「motd-news.timer 缺失」,根因就是 systemd 单元里的
        // 符号链接创建失败被 onFailure 吞掉,包"解完了"但目录不完整,自检才在别处炸。
        // 现在把失败条目攒起来,解包结束后统一抛出,让失败停在安装步骤且说清是哪几条。
        val failedLinks = mutableListOf<String>()
        val root = destDir.canonicalFile
        var endBlocks = 0
        archive.inputStream().use { raw ->
            GZIPInputStream(raw, BUFFER_SIZE).use { gz ->
                val header = ByteArray(HEADER_SIZE)
                var longName: String? = null
                var longLink: String? = null
                while (true) {
                    if (!readFully(gz, header, HEADER_SIZE)) {
                        if (endBlocks >= REQUIRED_END_BLOCKS) break
                        throw EOFException("tar 归档在结束块之前被截断")
                    }
                    if (header.all { it == 0.toByte() }) {
                        endBlocks++
                        if (endBlocks >= REQUIRED_END_BLOCKS) break
                        continue
                    }
                    if (endBlocks > 0) throw IOException("tar 结束块后仍出现成员")
                    validateChecksum(header)

                    val magic = String(header, 257, 6, Charsets.US_ASCII).trim('\u0000', ' ')
                    if (magic != "ustar") {
                        // 非 POSIX tar（极少见）——宁可失败，也不要解出半个环境
                        val head = header.take(16).joinToString(" ") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
                        throw IllegalStateException("不是有效的 tar 包（magic=$magic, head=$head）")
                    }

                    val nameRaw = readString(header, 0, 100)
                    val prefix = readString(header, 345, 155)
                    var name = if (prefix.isNotEmpty()) "$prefix/$nameRaw" else nameRaw
                    val mode = readOctal(header, 100, 8)
                    val size = readOctal(header, 124, 12).toLong()
                    var payload = size
                    val type = header[156].toInt().toChar()
                    val linkNameRaw = readString(header, 157, 100)

                    when (type) {
                        'x', 'g' -> {
                            val data = readExactly(gz, payload)
                            skipPadding(gz, payload)
                            val attributes = parsePax(data)
                            if (type == 'x') {
                                attributes.path?.let { longName = it }
                                attributes.linkPath?.let { longLink = it }
                            }
                            continue
                        }
                        'L' -> {
                            longName = String(readExactly(gz, payload), Charsets.UTF_8).trimEnd('\u0000')
                            skipPadding(gz, payload)
                            continue
                        }
                        'K' -> {
                            longLink = String(readExactly(gz, payload), Charsets.UTF_8).trimEnd('\u0000')
                            skipPadding(gz, payload)
                            continue
                        }
                    }

                    if (longName != null) {
                        name = longName
                        longName = null
                    }
                    val target = resolveInside(root, name, "包内路径越界")

                    when (type) {
                        '5' -> target.mkdirs()
                        '2' -> {
                            val link = longLink ?: linkNameRaw
                            longLink = null
                            val linkTarget = resolveLinkInside(root, target.parentFile, link)
                            target.parentFile?.mkdirs()
                            removeExistingTarget(target)
                            runCatching { java.nio.file.Files.createSymbolicLink(target.toPath(), linkTarget) }
                                .onFailure { e ->
                                    Logger.w(TAG, "符号链接创建失败: $name -> $link (${e.message})")
                                    failedLinks += "$name -> $link"
                                }
                            payload = 0
                        }
                        '1' -> {
                            // 硬链接：我们的包用 --hard-dereference 生成，理论上不会出现；
                            // 真遇到就退回复制内容，语义接近且不会失败。
                            val link = longLink ?: linkNameRaw
                            longLink = null
                            val source = resolveInside(root, link, "硬链接目标越界")
                            target.parentFile?.mkdirs()
                            removeExistingTarget(target)
                            if (source.isFile) {
                                source.copyTo(target, overwrite = true)
                            } else {
                                // 硬链接目标缺失同样意味着包不完整,不能当成功
                                Logger.w(TAG, "硬链接源不存在: $name -> $link")
                                failedLinks += "$name -> $link (硬链接源缺失)"
                            }
                            payload = 0
                        }
                        '0', '\u0000', '7' -> {
                            target.parentFile?.mkdirs()
                            removeExistingTarget(target)
                            target.outputStream().use { out ->
                                copyExactly(gz, out, payload) { written += it }
                            }
                            applyMode(target, mode)
                            // 注意：不要把 payload 置 0 —— 文件内容后面还有块对齐填充，
                            // 留给下面的 skipPadding 按原始 size 算。置 0 会让后续块错位，
                            // 下一个「头」落在零填充上，报 magic 为空。
                        }
                        else -> {
                            // 设备节点、FIFO 等在 Android 上没有意义，跳过内容
                        }
                    }

                    // 跳过剩余的块对齐填充
                    skipPadding(gz, payload)
                    onProgress(written)
                }
            }
        }
        if (endBlocks < REQUIRED_END_BLOCKS) throw EOFException("tar 归档缺少结束块")
        if (failedLinks.isNotEmpty()) {
            throw IllegalStateException(
                "解包不完整:${failedLinks.size} 条链接创建失败 -> " +
                    failedLinks.joinToString(limit = 10),
            )
        }
        return written
    }

    private fun applyMode(file: File, mode: Int) {
        val exec = (mode and 0b001_000_000) != 0 || (mode and 0b000_001_000) != 0
        file.setReadable(true, false)
        file.setWritable(true, true)
        file.setExecutable(exec, false)
    }

    private data class PaxAttributes(val path: String? = null, val linkPath: String? = null)

    private fun parsePax(data: ByteArray): PaxAttributes {
        var index = 0
        var path: String? = null
        var linkPath: String? = null
        while (index < data.size) {
            var space = index
            while (space < data.size && data[space] != ' '.code.toByte()) space++
            if (space == data.size) throw IOException("pax 记录缺少长度分隔符")
            val lengthText = String(data, index, space - index, Charsets.US_ASCII)
            val length = lengthText.toIntOrNull() ?: throw IOException("pax 记录长度非法")
            val end = index + length
            if (length <= space - index + 2 || end > data.size) throw EOFException("pax 记录长度越界或被截断")
            if (data[end - 1] != '\n'.code.toByte()) throw IOException("pax 记录缺少换行结束符")
            val valueStart = space + 1
            val record = String(data, valueStart, end - valueStart - 1, Charsets.UTF_8)
            when {
                record.startsWith("path=") -> path = record.removePrefix("path=")
                record.startsWith("linkpath=") -> linkPath = record.removePrefix("linkpath=")
            }
            index = end
        }
        return PaxAttributes(path, linkPath)
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) {
                if (offset == 0) return false
                throw EOFException("tar header 被截断：$offset/$length bytes")
            }
            if (read == 0) continue
            offset += read
        }
        return true
    }

    private fun readExactly(input: InputStream, length: Long): ByteArray {
        if (length <= 0) return ByteArray(0)
        if (length > Int.MAX_VALUE) throw IOException("tar 扩展头过大: $length bytes")
        val out = ByteArray(length.toInt())
        var offset = 0
        while (offset < out.size) {
            val read = input.read(out, offset, out.size - offset)
            if (read < 0) throw EOFException("tar payload 被截断：$offset/${out.size} bytes")
            if (read == 0) continue
            offset += read
        }
        return out
    }

    private fun copyExactly(input: InputStream, out: java.io.OutputStream, length: Long, onChunk: (Int) -> Unit) {
        var remaining = length
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            val want = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, want)
            if (read < 0) throw EOFException("tar 文件 payload 被截断：还差 $remaining bytes")
            if (read == 0) continue
            out.write(buffer, 0, read)
            onChunk(read)
            remaining -= read
        }
    }

    private fun skipPadding(input: InputStream, alreadyRead: Long) {
        val remainder = (HEADER_SIZE - (alreadyRead % HEADER_SIZE)) % HEADER_SIZE
        if (remainder > 0L) readExactly(input, remainder)
    }

    private fun validateChecksum(header: ByteArray) {
        val expected = readOctal(header, CHECKSUM_OFFSET, CHECKSUM_LENGTH).toLong()
        var actual = 0L
        header.forEachIndexed { index, byte ->
            actual += if (index in CHECKSUM_OFFSET until CHECKSUM_OFFSET + CHECKSUM_LENGTH) {
                ' '.code.toLong()
            } else {
                (byte.toInt() and 0xff).toLong()
            }
        }
        if (expected != actual) throw IOException("tar header 校验和错误：期望 $expected，实际 $actual")
    }

    private fun resolveInside(root: File, path: String, failure: String): File {
        require(path.isNotBlank()) { "$failure: 路径为空" }
        val candidate = File(root, path)
        val canonicalCandidate = candidate.canonicalFile
        val rootPath = root.path
        require(canonicalCandidate.path == rootPath || canonicalCandidate.path.startsWith(rootPath + File.separator)) {
            "$failure: $path"
        }
        if (canonicalCandidate.path == rootPath) return root
        val parent = candidate.parentFile?.canonicalFile ?: root
        require(parent.path == rootPath || parent.path.startsWith(rootPath + File.separator)) {
            "$failure: $path"
        }
        return File(parent, candidate.name)
    }

    private fun resolveLinkInside(root: File, parent: File?, link: String): java.nio.file.Path {
        require(link.isNotBlank()) { "符号链接目标为空" }
        val resolved = if (File(link).isAbsolute) File(link).canonicalFile else File(parent ?: root, link).canonicalFile
        val rootPath = root.path
        require(resolved.path == rootPath || resolved.path.startsWith(rootPath + File.separator)) {
            "符号链接目标越界: $link"
        }
        return File(link).toPath()
    }

    private fun removeExistingTarget(target: File) {
        val exists = target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())
        if (exists && !target.delete()) throw IOException("无法替换归档目标: ${target.name}")
    }

    private fun readString(bytes: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && bytes[end] != 0.toByte()) end++
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }

    private fun readOctal(bytes: ByteArray, offset: Int, length: Int): Int {
        var value = 0
        for (i in offset until offset + length) {
            val b = bytes[i].toInt() and 0xff
            if (b == 0 || b == ' '.code) {
                if (value != 0) break else continue
            }
            if (b < '0'.code || b > '7'.code) continue
            value = value * 8 + (b - '0'.code)
        }
        return value
    }

    private const val HEADER_SIZE = 512
    private const val CHECKSUM_OFFSET = 148
    private const val CHECKSUM_LENGTH = 8
    private const val REQUIRED_END_BLOCKS = 2
    private const val BUFFER_SIZE = 64 * 1024
    private const val TAG = "TarGzExtractor"
}
