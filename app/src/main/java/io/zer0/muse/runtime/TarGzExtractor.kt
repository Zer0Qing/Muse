package io.zer0.muse.runtime

import io.zer0.common.Logger
import java.io.EOFException
import java.io.File
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
        val skippedLinks = mutableListOf<String>()
        val failedLinks = mutableListOf<String>()
        val root = destDir.canonicalFile
        var endBlocks = 0
        archive.inputStream().use { raw ->
            GZIPInputStream(raw, BUFFER_SIZE).use { gz ->
                val header = ByteArray(HEADER_SIZE)
                var longName: String? = null
                var longLink: String? = null
                var done = false
                while (!done) {
                    if (!readFully(gz, header, HEADER_SIZE)) {
                        check(endBlocks >= REQUIRED_END_BLOCKS) { "tar 归档在结束块之前被截断" }
                        done = true
                    } else if (header.all { it == 0.toByte() }) {
                        endBlocks++
                        done = endBlocks >= REQUIRED_END_BLOCKS
                    } else {
                        check(endBlocks == 0) { "tar 结束块后仍出现成员" }
                        validateChecksum(header)
                        val state = processMember(
                            MemberInput(gz, root, header),
                            MemberPending(longName, longLink, written),
                            LinkFailures(skippedLinks, failedLinks),
                            onProgress,
                        )
                        longName = state.longName
                        longLink = state.longLink
                        written = state.written
                    }
                }
            }
        }
        if (endBlocks < REQUIRED_END_BLOCKS) throw EOFException("tar 归档缺少结束块")
        // 分两类：包不完整（硬链接源缺失）仍失败；宿主建不了链接（权限/文件系统）降级跳过。
        if (failedLinks.isNotEmpty()) {
            throw IllegalStateException(
                "解包不完整:${failedLinks.size} 条链接失败 -> " +
                    failedLinks.joinToString(limit = 10),
            )
        }
        if (skippedLinks.isNotEmpty()) {
            Logger.w(
                TAG,
                "解包完成但有 ${skippedLinks.size} 条链接被跳过（不影响运行则忽略）: " +
                    skippedLinks.joinToString(limit = 10),
            )
        }
        return written
    }

    private data class MemberInput(val input: InputStream, val root: File, val header: ByteArray)

    private data class MemberPending(val longName: String?, val longLink: String?, val written: Long)

    private data class LinkFailures(val skipped: MutableList<String>, val failed: MutableList<String>)

    private data class HeaderFields(
        val name: String,
        val mode: Int,
        val payload: Long,
        val type: Char,
        val linkName: String,
    )

    private data class MemberState(val longName: String?, val longLink: String?, val written: Long)

    private fun processMember(
        member: MemberInput,
        pending: MemberPending,
        failures: LinkFailures,
        onProgress: (Long) -> Unit,
    ): MemberState {
        val magic = String(member.header, 257, 6, Charsets.US_ASCII).trim('\u0000', ' ')
        val head = member.header.take(16).joinToString(" ") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        check(magic == "ustar") { "不是有效的 tar 包（magic=$magic, head=$head）" }
        val nameRaw = readTarString(member.header, 0, 100)
        val prefix = readTarString(member.header, 345, 155)
        val headerName = if (prefix.isNotEmpty()) "$prefix/$nameRaw" else nameRaw
        val fields = HeaderFields(
            name = headerName,
            mode = readTarOctal(member.header, 100, 8),
            payload = readTarOctal(member.header, 124, 12).toLong(),
            type = member.header[156].toInt().toChar(),
            linkName = readTarString(member.header, 157, 100),
        )
        return when (fields.type) {
            'x', 'g' -> processPax(member.input, fields.payload, fields.type, pending.longName, pending.longLink, pending.written)
            'L' -> processLongName(member.input, fields.payload, pending.longLink, pending.written)
            'K' -> processLongLink(member.input, fields.payload, pending.longName, pending.written)
            else -> processRegularMember(
                member,
                pending,
                fields,
                failures,
                onProgress,
            )
        }
    }

    private fun processPax(
        input: InputStream,
        payload: Long,
        type: Char,
        pendingName: String?,
        pendingLink: String?,
        writtenBefore: Long,
    ): MemberState {
        val attributes = parsePax(readExactly(input, payload))
        skipPadding(input, payload)
        return if (type == 'x') {
            MemberState(attributes.path ?: pendingName, attributes.linkPath ?: pendingLink, writtenBefore)
        } else {
            MemberState(pendingName, pendingLink, writtenBefore)
        }
    }

    private fun processLongName(input: InputStream, payload: Long, pendingLink: String?, writtenBefore: Long): MemberState {
        val name = String(readExactly(input, payload), Charsets.UTF_8).trimEnd('\u0000')
        skipPadding(input, payload)
        return MemberState(name, pendingLink, writtenBefore)
    }

    private fun processLongLink(input: InputStream, payload: Long, pendingName: String?, writtenBefore: Long): MemberState {
        val link = String(readExactly(input, payload), Charsets.UTF_8).trimEnd('\u0000')
        skipPadding(input, payload)
        return MemberState(pendingName, link, writtenBefore)
    }

    private fun processRegularMember(
        member: MemberInput,
        pending: MemberPending,
        fields: HeaderFields,
        failures: LinkFailures,
        onProgress: (Long) -> Unit,
    ): MemberState {
        val name = pending.longName ?: fields.name
        val target = resolveInside(member.root, name, "包内路径越界")
        val link = pending.longLink ?: fields.linkName
        var written = pending.written
        when (fields.type) {
            '5' -> target.mkdirs()
            '2' -> createSymbolicLink(member.root, target, name, link, failures.skipped)
            '1' -> createHardLink(member.root, target, name, link, failures.failed)
            '0', '\u0000', '7' -> {
                target.parentFile?.mkdirs()
                removeTarTarget(target)
                target.outputStream().use { out -> copyExactly(member.input, out, fields.payload) { written += it } }
                applyTarMode(target, fields.mode)
            }
        }
        skipPadding(member.input, fields.payload)
        onProgress(written)
        return MemberState(null, null, written)
    }

    private fun createSymbolicLink(
        root: File,
        target: File,
        name: String,
        link: String,
        skippedLinks: MutableList<String>,
    ) {
        val linkTarget = resolveLinkTarget(root, target.parentFile, link)
            ?: throw IOException("符号链接目标越界: $link")
        target.parentFile?.mkdirs()
        removeTarTarget(target)
        runCatching { java.nio.file.Files.createSymbolicLink(target.toPath(), linkTarget) }
            .onFailure { error ->
                Logger.w(TAG, "符号链接创建失败，已跳过: $name -> $link (${error.message})")
                skippedLinks += "$name -> $link (${error.message})"
            }
    }

    private fun createHardLink(root: File, target: File, name: String, link: String, failedLinks: MutableList<String>) {
        val source = resolveInside(root, link, "硬链接目标越界")
        target.parentFile?.mkdirs()
        removeTarTarget(target)
        if (source.isFile) {
            source.copyTo(target, overwrite = true)
        } else {
            Logger.w(TAG, "硬链接源不存在: $name -> $link")
            failedLinks += "$name -> $link (硬链接源缺失)"
        }
    }

    private data class PaxAttributes(val path: String? = null, val linkPath: String? = null)

    private fun parsePax(data: ByteArray): PaxAttributes {
        var index = 0
        var path: String? = null
        var linkPath: String? = null
        while (index < data.size) {
            var space = index
            while (space < data.size && data[space] != ' '.code.toByte()) space++
            require(space < data.size) { "pax 记录缺少长度分隔符" }
            val lengthText = String(data, index, space - index, Charsets.US_ASCII)
            val length = requireNotNull(lengthText.toIntOrNull()) { "pax 记录长度非法" }
            val end = index + length
            require(length > space - index + 2 && end <= data.size) { "pax 记录长度越界或被截断" }
            require(data[end - 1] == '\n'.code.toByte()) { "pax 记录缺少换行结束符" }
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
        val expected = readTarOctal(header, CHECKSUM_OFFSET, CHECKSUM_LENGTH).toLong()
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

    /**
     * 绝对符号链接是否逃出 proot 虚拟根（纯字面量判断，不碰宿主文件系统）。
     *
     * 绝对链接（如 Ubuntu rootfs 自带的 `var/run -> /run`）在 proot 虚拟根内解析：
     * `/run` 指的是 rootfs 里的 run，不是 Android 宿主机。此前拿宿主机
     * `File("/run").canonicalFile` 去比 root 前缀，会让这类标准链接 100% 误判为越界，
     * 安装停在解包中途（只解出半个 rootfs）。
     *
     * 只看 `/` 分段里的 `..`：`/run/../etc` 仍在根内，`/../outside` 会掉到根外。
     * 相对链接不走这里，仍由 canonical 路径校验。
     */
    internal fun absoluteLinkEscapesRoot(link: String): Boolean {
        if (!link.startsWith("/")) return false
        var depth = 0
        for (segment in link.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    depth--
                    if (depth < 0) return true
                }
                else -> depth++
            }
        }
        return false
    }

    /**
     * 判断符号链接目标是否越出 [root]；越界返回 null，合法则返回要写入的链接目标。
     *
     * 绝对链接按 [absoluteLinkEscapesRoot] 的虚拟根语义校验，并保留绝对形式 ——
     * 运行时由 proot 映射回 rootfs 内同名路径，语义与原镜像一致。
     * 相对链接按宿主机真实路径校验，防止 `../../outside` 逃出解包目录。
     */
    internal fun resolveLinkTarget(root: File, parent: File?, link: String): java.nio.file.Path? {
        if (link.isBlank()) return null
        if (link.startsWith("/")) {
            return if (absoluteLinkEscapesRoot(link)) null else java.nio.file.Paths.get(link)
        }
        val rootPath = root.path
        val resolved = File(parent ?: root, link).canonicalFile
        val inside = resolved.path == rootPath || resolved.path.startsWith(rootPath + File.separator)
        return if (inside) java.nio.file.Paths.get(link) else null
    }


    private const val HEADER_SIZE = 512
    private const val CHECKSUM_OFFSET = 148
    private const val CHECKSUM_LENGTH = 8
    private const val REQUIRED_END_BLOCKS = 2
    private const val BUFFER_SIZE = 64 * 1024
    private const val TAG = "TarGzExtractor"
}
