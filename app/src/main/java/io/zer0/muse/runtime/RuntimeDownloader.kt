package io.zer0.muse.runtime

import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 运行时包下载器：断点续传 + 多源重试 + SHA-256 校验。
 *
 * 和语音模型下载器（[io.zer0.muse.asr.local.LocalModelDownloader]）同一套路，
 * 但这里必须支持多个源：运行时包比模型大，单一源挂掉会让首次安装直接失败。
 *
 * 约定：
 *  - 先写 `.part`，校验通过才 rename 成正式文件；
 *  - 校验失败删 `.part` —— 对坏文件续传只会拼出更大的坏文件；
 *  - 协程取消后 `.part` 保留，下次从断点继续。
 */
class RuntimeDownloader {

    /**
     * 下载 [RuntimeManifest.archive]，落到 [dest]。
     *
     * @param onProgress (已下载字节, 总字节)；总字节未知时给 -1
     * @return 校验通过的文件；失败返回 Result.failure，异常消息可直接展示给用户
     */
    suspend fun download(
        manifest: RuntimeManifest,
        dest: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        val archive = manifest.archive
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")

        // 已经下完并校验过：直接复用。
        if (dest.isFile && dest.length() == archive.sizeBytes && sha256(dest) == archive.sha256) {
            onProgress(archive.sizeBytes, archive.sizeBytes)
            return@withContext Result.success(dest)
        }

        var lastError: Exception? = null
        for ((index, url) in archive.urls.withIndex()) {
            ensureActive()
            Logger.i(TAG, "下载 ${archive.name}: 源 ${index + 1}/${archive.urls.size} $url")
            try {
                fetch(url, part, archive.sizeBytes, onProgress)
                val actual = sha256(part)
                if (actual != archive.sha256) {
                    part.delete()
                    lastError = IllegalStateException("安装包校验失败（sha256 不匹配），已删除并准备重试")
                    Logger.w(TAG, "sha256 不匹配: 期望 ${archive.sha256}, 实际 $actual")
                    continue
                }
                if (!part.renameTo(dest)) {
                    // rename 失败（跨分区等）时退回拷贝
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                Logger.i(TAG, "下载完成: ${dest.absolutePath} (${dest.length()} bytes)")
                return@withContext Result.success(dest)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Logger.w(TAG, "源 $index 失败: ${e.message}")
            }
        }
        Result.failure(lastError ?: IllegalStateException("没有可用的下载源"))
    }

    private fun fetch(url: String, part: File, expectedTotal: Long, onProgress: (Long, Long) -> Unit) {
        val existing = if (part.isFile) part.length() else 0L
        if (existing > 0 && expectedTotal in 1..existing) {
            // 已经比目标还大：数据不可信，重来
            part.delete()
        }
        val start = if (part.isFile) part.length() else 0L

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (start > 0) setRequestProperty("Range", "bytes=$start-")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val total = if (code == HttpURLConnection.HTTP_PARTIAL) {
                parseContentRangeTotal(conn.getHeaderField("Content-Range")) ?: expectedTotal
            } else {
                start.let { if (it > 0) part.delete() } // 服务器不支持 Range，从头写
                conn.contentLengthLong.takeIf { it > 0 } ?: expectedTotal
            }
            var written = if (code == HttpURLConnection.HTTP_PARTIAL) start else 0L
            onProgress(written, total)
            var lastProgressAt = System.currentTimeMillis()
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    if (code != HttpURLConnection.HTTP_PARTIAL) {
                        // 不支持断点时覆盖写
                        out.channel.truncate(0)
                    }
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        lastProgressAt = System.currentTimeMillis()
                        onProgress(written, total)
                        // 停滞检测：有的镜像会「吊着不回数据也不关连接」，
                        // 光靠 socket 读超时拦不住（只要隔半分钟滴一个字节就能续命）。
                        // 这里按「多久没进展」判，超时就换下一个源。
                        if (System.currentTimeMillis() - lastProgressAt > STALL_TIMEOUT_MS) {
                            throw IllegalStateException("下载停滞超过 ${STALL_TIMEOUT_MS / 1000}s，切换下一个源")
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseContentRangeTotal(header: String?): Long? {
        // 形如 "bytes 1024-2047/2048"
        val total = header?.substringAfterLast('/')?.trim()?.toLongOrNull()
        return total?.takeIf { it > 0 }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private companion object {
        const val TAG = "RuntimeDownloader"
        const val BUFFER_SIZE = 64 * 1024

        /** 多久没收到新数据就判定为停滞并换源。 */
        const val STALL_TIMEOUT_MS = 45_000L
    }
}
