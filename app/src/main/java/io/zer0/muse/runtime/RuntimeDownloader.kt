package io.zer0.muse.runtime

import io.zer0.common.Logger
import io.zer0.common.AppJson
import io.zer0.muse.data.AtomicFileStore
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RuntimeDownloadRequest(
    val version: RuntimeVersion,
    val destination: File,
    val expectedSha256: String?,
    val expectedSize: Long,
    val sourceUrls: List<String>,
    val operationId: String,
    val targetAbi: String,
    val manifestCandidates: List<ManifestCandidate> = emptyList(),
    val expectedEtag: String? = null,
    val expectedLastModified: String? = null,
)

data class RuntimeDownloadProgress(
    val operationId: String,
    val phase: Phase,
    val sourceIndex: Int,
    val sourceUrl: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSecond: Long,
    val etaSeconds: Long?,
) {
    enum class Phase { DOWNLOADING, VERIFYING, COMPLETED }
}

data class RuntimeDownloadResult(
    val archiveFile: File,
    val sourceUrl: String,
    val resumed: Boolean,
    val bytes: Long,
    val sha256: String,
    val etag: String? = null,
    val lastModified: String? = null,
)

class RuntimeProgressThrottle(
    private val intervalMs: Long = 500L,
) {
    private var lastAtMs = Long.MIN_VALUE
    private var lastPercent = Int.MIN_VALUE
    private var lastPhase: RuntimeDownloadProgress.Phase? = null

    fun shouldEmit(phase: RuntimeDownloadProgress.Phase, downloaded: Long, total: Long, nowMs: Long): Boolean {
        val percent = if (total > 0L) ((downloaded.coerceAtLeast(0L) * 100L) / total).toInt() else -1
        val changed = percent != lastPercent
        val phaseChanged = phase != lastPhase
        val elapsed = lastAtMs == Long.MIN_VALUE || nowMs - lastAtMs >= intervalMs
        if (!changed && !phaseChanged && !elapsed) return false
        lastAtMs = nowMs
        lastPercent = percent
        lastPhase = phase
        return true
    }
}

class RuntimeDownloader(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
    private val sourceDeadlineMs: Long = DEFAULT_SOURCE_DEADLINE_MS,
) {

    suspend fun download(
        request: RuntimeDownloadRequest,
        onProgress: (RuntimeDownloadProgress) -> Unit = {},
    ): Result<RuntimeDownloadResult> = withContext(Dispatchers.IO) {
        val requestError = validateRequest(request)
        if (requestError != null) return@withContext Result.failure(requestError)

        request.destination.parentFile?.mkdirs()
        val part = File(request.destination.parentFile, request.destination.name + ".part")
        val partMetadata = File(part.parentFile, part.name + ".meta")
        val targets = targetsFor(request)
        val failures = mutableListOf<String>()
        val throttle = RuntimeProgressThrottle()

        if (request.expectedSha256 != null && request.destination.isFile && request.destination.length() == request.expectedSize) {
            val actual = sha256(request.destination)
            if (actual.equals(request.expectedSha256, ignoreCase = true)) {
                val source = request.sourceUrls.firstOrNull().orEmpty()
                val savedMetadata = readPartMetadata(partMetadata)
                emit(
                    onProgress,
                    throttle,
                    request.operationId,
                    RuntimeDownloadProgress.Phase.COMPLETED,
                    0,
                    source,
                    request.expectedSize,
                    request.expectedSize,
                    0L,
                    null,
                )
                partMetadata.delete()
                return@withContext Result.success(
                    RuntimeDownloadResult(
                        archiveFile = request.destination,
                        sourceUrl = source,
                        resumed = true,
                        bytes = request.expectedSize,
                        sha256 = actual,
                        etag = savedMetadata?.etag ?: request.expectedEtag,
                        lastModified = savedMetadata?.lastModified ?: request.expectedLastModified,
                    ),
                )
            }
            if (!request.destination.delete()) {
                return@withContext Result.failure(IOException("已有归档校验失败且无法删除"))
            }
        } else if (request.expectedSha256 == null && request.destination.exists() && !request.destination.delete()) {
            return@withContext Result.failure(IOException("无法移除未验证的旧归档"))
        }

        for (target in targets) {
            currentCoroutineContext().ensureActive()
            val urls = target.urls
            if (urls.isEmpty()) {
                failures += "${target.source}: 没有下载 URL"
                continue
            }
            for ((index, url) in urls.withIndex()) {
                currentCoroutineContext().ensureActive()
                val expectedSize = target.version.archive.sizeBytes
                val expectedSha = target.sha256
                if (part.length() > expectedSize) {
                    part.delete()
                    partMetadata.delete()
                }
                if (part.isFile && part.length() == expectedSize) {
                    val actual = sha256(part)
                    if (expectedSha == null || actual.equals(expectedSha, ignoreCase = true)) {
                        val savedMetadata = readPartMetadata(partMetadata)
                        promote(part, request.destination)
                        partMetadata.delete()
                        emit(onProgress, throttle, request.operationId, RuntimeDownloadProgress.Phase.COMPLETED, index, url, expectedSize, expectedSize, 0L, null)
                        return@withContext Result.success(
                            RuntimeDownloadResult(
                                archiveFile = request.destination,
                                sourceUrl = url,
                                resumed = true,
                                bytes = expectedSize,
                                sha256 = actual,
                                etag = savedMetadata?.etag,
                                lastModified = savedMetadata?.lastModified,
                            ),
                        )
                    }
                    part.delete()
                    partMetadata.delete()
                }

                Logger.i(TAG, "下载 ${target.version.archive.name}: 源 ${index + 1}/${urls.size} $url")
                var forceFullRestart = false
                var sourceFinished = false
                while (!sourceFinished) {
                    try {
                        val savedMetadata = readPartMetadata(partMetadata)
                        val fetchResult = fetch(
                            url = url,
                            part = part,
                            partMetadata = partMetadata,
                            expectedSize = expectedSize,
                            expectedEtag = if (forceFullRestart) null else savedMetadata?.etag ?: request.expectedEtag,
                            expectedLastModified = if (forceFullRestart) null else savedMetadata?.lastModified ?: request.expectedLastModified,
                            onProgress = { downloaded, total, speed ->
                                emit(
                                    onProgress,
                                    throttle,
                                    request.operationId,
                                    RuntimeDownloadProgress.Phase.DOWNLOADING,
                                    index,
                                    url,
                                    downloaded,
                                    total,
                                    speed,
                                    if (speed > 0L && total >= downloaded) (total - downloaded) / speed else null,
                                )
                            },
                        )
                        emit(onProgress, throttle, request.operationId, RuntimeDownloadProgress.Phase.VERIFYING, index, url, part.length(), expectedSize, 0L, null)
                        val actual = sha256(part)
                        if (part.length() != expectedSize ||
                            (expectedSha != null && !actual.equals(expectedSha, ignoreCase = true))
                        ) {
                            val actualSize = part.length()
                            part.delete()
                            partMetadata.delete()
                            throw IOException(
                            "校验失败：期望 ${expectedSize} bytes/${expectedSha ?: "未提供 SHA-256"}，实际 ${actualSize} bytes/$actual",
                            )
                        }
                        promote(part, request.destination)
                        partMetadata.delete()
                        emit(onProgress, throttle, request.operationId, RuntimeDownloadProgress.Phase.COMPLETED, index, url, expectedSize, expectedSize, 0L, null)
                        Logger.i(TAG, "下载完成: ${request.destination.absolutePath} (${request.destination.length()} bytes)")
                        return@withContext Result.success(
                            RuntimeDownloadResult(
                                archiveFile = request.destination,
                                sourceUrl = url,
                                resumed = fetchResult.resumed,
                                bytes = expectedSize,
                                sha256 = actual,
                                etag = fetchResult.etag,
                                lastModified = fetchResult.lastModified,
                            ),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (restart: RestartFromZeroException) {
                        if (forceFullRestart) {
                            failures += "${target.source} [$url]: ${restart.message}"
                            sourceFinished = true
                        } else {
                            forceFullRestart = true
                        }
                    } catch (error: Exception) {
                        val detail = "${target.source} [$url]: ${error.message ?: error.javaClass.simpleName}"
                        failures += detail
                        Logger.w(TAG, "下载源失败: $detail")
                        sourceFinished = true
                    }
                }
            }
        }
        Result.failure(IOException("所有运行环境下载源均失败：${failures.joinToString("；")}"))
    }

    suspend fun download(
        manifest: RuntimeManifest,
        dest: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> {
        val version = RuntimeVersion(
            revision = manifest.revision,
            upstream = manifest.upstream,
            archive = manifest.archive,
            installedBytes = manifest.installedBytes,
            bins = manifest.bins,
            sourceTrust = manifest.sourceTrust,
        )
        return download(
            RuntimeDownloadRequest(
                version = version,
                destination = dest,
                expectedSha256 = manifest.archive.sha256,
                expectedSize = manifest.archive.sizeBytes,
                sourceUrls = manifest.archive.urls,
                operationId = "legacy-${manifest.runtimeId}-${manifest.revision}",
                targetAbi = manifest.abi,
            ),
        ) { progress ->
            if (progress.phase == RuntimeDownloadProgress.Phase.DOWNLOADING) {
                onProgress(progress.downloadedBytes, progress.totalBytes)
            }
        }.map(RuntimeDownloadResult::archiveFile)
    }

    suspend fun verifyArchive(file: File, expectedSize: Long, expectedSha256: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext Result.failure(IOException("本地运行环境归档不存在"))
            if (file.length() != expectedSize) {
                return@withContext Result.failure(IOException("本地归档大小不符：${file.length()}/$expectedSize bytes"))
            }
            val actual = sha256(file)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                return@withContext Result.failure(IOException("本地归档 SHA-256 不匹配"))
            }
            Result.success(Unit)
        }

    private suspend fun fetch(
        url: String,
        part: File,
        partMetadata: File,
        expectedSize: Long,
        expectedEtag: String?,
        expectedLastModified: String?,
        onProgress: (Long, Long, Long) -> Unit,
    ): FetchResult = suspendCancellableCoroutine { continuation ->
        val activeConnection = AtomicReference<HttpURLConnection?>(null)
        val worker = CoroutineScope(Dispatchers.IO + GlobalCoroutineExceptionHandler).launch {
            try {
                val result = fetchBlocking(
                    url,
                    part,
                    partMetadata,
                    expectedSize,
                    expectedEtag,
                    expectedLastModified,
                    activeConnection,
                    onProgress,
                )
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation {
            activeConnection.getAndSet(null)?.disconnect()
            worker.cancel()
        }
    }

    private fun fetchBlocking(
        url: String,
        part: File,
        partMetadata: File,
        expectedSize: Long,
        expectedEtag: String?,
        expectedLastModified: String?,
        activeConnection: AtomicReference<HttpURLConnection?>,
        onProgress: (Long, Long, Long) -> Unit,
    ): FetchResult {
        val start = part.takeIf(File::isFile)?.length() ?: 0L
        val connection = (URL(url).openConnection() as? HttpURLConnection)
            ?: throw IOException("运行环境包下载只支持 HTTP(S) 地址")
        connection.apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            if (start > 0L) {
                setRequestProperty("Range", "bytes=$start-")
                (expectedEtag ?: expectedLastModified)?.let { setRequestProperty("If-Range", it) }
            }
        }
        activeConnection.set(connection)
        val lastProgressNanos = AtomicLong(System.nanoTime())
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(sourceDeadlineMs)
        val watchdogFailure = AtomicReference<SocketTimeoutException?>(null)
        val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "runtime-download-watchdog").apply { isDaemon = true }
        }
        val watchdogTask: ScheduledFuture<*> = watchdog.scheduleAtFixedRate(
            {
                val now = System.nanoTime()
                val reason = when {
                    now >= deadlineNanos -> "下载源超过总时限 ${sourceDeadlineMs}ms"
                    now - lastProgressNanos.get() >= TimeUnit.MILLISECONDS.toNanos(stallTimeoutMs) ->
                        "下载停滞超过 ${stallTimeoutMs}ms"
                    else -> null
                }
                if (reason != null && watchdogFailure.compareAndSet(null, SocketTimeoutException(reason))) {
                    connection.disconnect()
                }
            },
            WATCHDOG_INTERVAL_MS,
            WATCHDOG_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )

        try {
            val code = connection.responseCode
            val append = when (code) {
                HttpURLConnection.HTTP_OK -> {
                    if (start > 0L) part.delete()
                    false
                }
                HttpURLConnection.HTTP_PARTIAL -> {
                    validateContentRange(connection.getHeaderField("Content-Range"), start, expectedSize)
                    true
                }
                else -> throw IOException("HTTP $code")
            }
            val responseEtag = connection.getHeaderField("ETag")
            val responseLastModified = connection.getHeaderField("Last-Modified")
            if (append && expectedEtag != null && responseEtag != expectedEtag) {
                part.delete()
                partMetadata.delete()
                throw RestartFromZeroException("ETag 已变化，清除断点后重新下载")
            }
            if (append && expectedEtag == null && expectedLastModified != null && responseLastModified != expectedLastModified) {
                part.delete()
                partMetadata.delete()
                throw RestartFromZeroException("Last-Modified 已变化，清除断点后重新下载")
            }
            AtomicFileStore.writeText(
                partMetadata,
                AppJson.encodeToString(PartMetadata(responseEtag, responseLastModified)),
            )
            val total = expectedSize
            var written = if (append) start else 0L
            val begunAt = System.nanoTime()
            lastProgressNanos.set(begunAt)
            onProgress(written, total, 0L)
            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    if (append) output.channel.position(start)
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        written += read
                        val now = System.nanoTime()
                        lastProgressNanos.set(now)
                        val elapsed = TimeUnit.NANOSECONDS.toSeconds(now - begunAt).coerceAtLeast(1L)
                        onProgress(written, total, (written - if (append) start else 0L) / elapsed)
                        watchdogFailure.get()?.let { throw it }
                        if (written > expectedSize) throw IOException("下载大小超过清单声明的 $expectedSize bytes")
                    }
                }
            }
            watchdogFailure.get()?.let { throw it }
            if (written != expectedSize) throw IOException("下载长度不符：$written/$expectedSize bytes")
            return FetchResult(append, responseEtag, responseLastModified)
        } catch (error: Exception) {
            throw watchdogFailure.get() ?: error
        } finally {
            watchdogTask.cancel(true)
            watchdog.shutdownNow()
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    private fun validateContentRange(header: String?, expectedStart: Long, expectedTotal: Long) {
        val match = CONTENT_RANGE.matchEntire(header.orEmpty())
            ?: throw IOException("Content-Range 缺失或格式错误: $header")
        val start = match.groupValues[1].toLongOrNull()
        val end = match.groupValues[2].toLongOrNull()
        val total = match.groupValues[3].toLongOrNull()
        if (start != expectedStart || end == null || end < start || total != expectedTotal) {
            throw IOException("Content-Range 不匹配：$header，期望起点 $expectedStart、总长 $expectedTotal")
        }
    }

    private suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun promote(part: File, destination: File) {
        if (destination.exists() && !destination.delete()) throw IOException("无法替换已有归档文件")
        if (!part.renameTo(destination)) {
            part.copyTo(destination, overwrite = true)
            if (!part.delete()) Logger.w(TAG, "已安装归档但未能删除临时文件: ${part.absolutePath}")
        }
    }

    private fun readPartMetadata(file: File): PartMetadata? {
        if (!file.isFile) return null
        return runCatching { AppJson.decodeFromString<PartMetadata>(file.readText()) }
            .onFailure { error ->
                Logger.w(TAG, "下载断点元数据损坏，重新读取响应校验头", error)
                file.delete()
            }
            .getOrNull()
    }

    private fun emit(
        callback: (RuntimeDownloadProgress) -> Unit,
        throttle: RuntimeProgressThrottle,
        operationId: String,
        phase: RuntimeDownloadProgress.Phase,
        sourceIndex: Int,
        sourceUrl: String,
        downloaded: Long,
        total: Long,
        speed: Long,
        eta: Long?,
    ) {
        val now = System.currentTimeMillis()
        if (throttle.shouldEmit(phase, downloaded, total, now)) {
            callback(
                RuntimeDownloadProgress(
                    operationId = operationId,
                    phase = phase,
                    sourceIndex = sourceIndex,
                    sourceUrl = sourceUrl,
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    speedBytesPerSecond = speed,
                    etaSeconds = eta,
                ),
            )
        }
    }

    private fun validateRequest(request: RuntimeDownloadRequest): Exception? {
        if (request.operationId.isBlank()) return IllegalArgumentException("operationId 不能为空")
        if (request.expectedSize <= 0L || request.version.archive.sizeBytes != request.expectedSize) {
            return IllegalArgumentException("下载大小与所选版本清单不一致")
        }
        if (request.expectedSha256 == null && request.version.sourceTrust != RuntimeSourceTrust.UNVERIFIED) {
            return IllegalArgumentException("缺少 SHA-256 的来源必须标记为未验证")
        }
        if (request.expectedSha256 != null &&
            !request.expectedSha256.equals(request.version.archive.sha256, ignoreCase = true)
        ) {
            return IllegalArgumentException("下载 SHA-256 与所选版本清单不一致")
        }
        if (request.expectedSha256 != null && !RuntimeManifestDocument.isValidSha256(request.expectedSha256)) {
            return IllegalArgumentException("下载 SHA-256 格式非法")
        }
        if (request.targetAbi.isBlank()) return IllegalArgumentException("目标 ABI 不能为空")
        return null
    }

    private fun targetsFor(request: RuntimeDownloadRequest): List<DownloadTarget> {
        val targets = mutableListOf(
            DownloadTarget(
                version = request.version,
                urls = request.sourceUrls,
                source = "selected:${request.version.revision}",
                sha256 = request.expectedSha256,
            ),
        )
        request.manifestCandidates.forEach { candidate ->
            if (candidate.document.validateFor(request.targetAbi) != ManifestValidation.Valid) return@forEach
            val version = candidate.document.versionsOrLegacy().firstOrNull { it.revision == request.version.revision }
                ?: return@forEach
            targets += DownloadTarget(version, version.archive.urls, candidate.source, version.archive.sha256)
        }
        return targets.distinctBy {
            "${it.version.archive.sha256}:${it.version.archive.sizeBytes}:${it.urls.joinToString("|")}"
        }
    }

    private data class DownloadTarget(
        val version: RuntimeVersion,
        val urls: List<String>,
        val source: String,
        val sha256: String?,
    )

    private data class FetchResult(
        val resumed: Boolean,
        val etag: String?,
        val lastModified: String?,
    )

    private class RestartFromZeroException(message: String) : IOException(message)

    @Serializable
    private data class PartMetadata(
        val etag: String?,
        val lastModified: String?,
    )

    private companion object {
        const val TAG = "RuntimeDownloader"
        const val BUFFER_SIZE = 64 * 1024
        const val DEFAULT_CONNECT_TIMEOUT_MS = 20_000
        const val DEFAULT_READ_TIMEOUT_MS = 30_000
        const val DEFAULT_STALL_TIMEOUT_MS = 45_000L
        const val DEFAULT_SOURCE_DEADLINE_MS = 15 * 60 * 1_000L
        const val WATCHDOG_INTERVAL_MS = 250L
        val CONTENT_RANGE = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$", RegexOption.IGNORE_CASE)
    }
}
