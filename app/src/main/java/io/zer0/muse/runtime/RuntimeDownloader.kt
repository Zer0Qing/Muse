package io.zer0.muse.runtime

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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
                    ProgressEvent(
                        request.operationId,
                        RuntimeDownloadProgress.Phase.COMPLETED,
                        0,
                        source,
                        request.expectedSize,
                        request.expectedSize,
                        0L,
                        null,
                    ),
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
            downloadTarget(request, target, part, partMetadata, throttle, onProgress, failures)?.let {
                return@withContext Result.success(it)
            }
        }
        Result.failure(IOException("所有运行环境下载源均失败：${failures.joinToString("；")}"))
    }

    private suspend fun downloadTarget(
        request: RuntimeDownloadRequest,
        target: DownloadTarget,
        part: File,
        partMetadata: File,
        throttle: RuntimeProgressThrottle,
        onProgress: (RuntimeDownloadProgress) -> Unit,
        failures: MutableList<String>,
    ): RuntimeDownloadResult? {
        if (target.urls.isEmpty()) {
            failures += "${target.source}: 没有下载 URL"
            return null
        }
        for ((index, url) in target.urls.withIndex()) {
            currentCoroutineContext().ensureActive()
            val expectedSize = target.version.archive.sizeBytes
            if (part.length() > expectedSize) {
                part.delete()
                partMetadata.delete()
            }
            val attempt = DownloadAttempt(request, target, part, partMetadata, index, url, throttle, onProgress)
            resumeIfComplete(attempt)?.let { return it }
            Logger.i(TAG, "下载 ${target.version.archive.name}: 源 ${index + 1}/${target.urls.size} $url")
            val result = downloadUrl(attempt)
            if (result != null) return result
            failures += "${target.source} [$url]: 下载失败"
        }
        return null
    }

    private suspend fun resumeIfComplete(attempt: DownloadAttempt): RuntimeDownloadResult? {
        val expectedSize = attempt.target.version.archive.sizeBytes
        if (!attempt.part.isFile || attempt.part.length() != expectedSize) return null
        val actual = sha256(attempt.part)
        if (attempt.target.sha256 != null && !actual.equals(attempt.target.sha256, ignoreCase = true)) {
            attempt.part.delete()
            attempt.partMetadata.delete()
            return null
        }
        val savedMetadata = readPartMetadata(attempt.partMetadata)
        promote(attempt.part, attempt.request.destination)
        attempt.partMetadata.delete()
        emit(
            attempt.onProgress,
            attempt.throttle,
            ProgressEvent(
                attempt.request.operationId,
                RuntimeDownloadProgress.Phase.COMPLETED,
                attempt.index,
                attempt.url,
                expectedSize,
                expectedSize,
                0L,
                null,
            ),
        )
        return RuntimeDownloadResult(
            archiveFile = attempt.request.destination,
            sourceUrl = attempt.url,
            resumed = true,
            bytes = expectedSize,
            sha256 = actual,
            etag = savedMetadata?.etag,
            lastModified = savedMetadata?.lastModified,
        )
    }

    private suspend fun downloadUrl(attempt: DownloadAttempt): RuntimeDownloadResult? {
        var forceFullRestart = false
        while (true) {
            try {
                val savedMetadata = readPartMetadata(attempt.partMetadata)
                val fetchResult = fetch(
                    FetchRequest(
                        url = attempt.url,
                        part = attempt.part,
                        partMetadata = attempt.partMetadata,
                        expectedSize = attempt.target.version.archive.sizeBytes,
                        expectedEtag = if (forceFullRestart) null else savedMetadata?.etag ?: attempt.request.expectedEtag,
                        expectedLastModified = if (forceFullRestart) {
                            null
                        } else {
                            savedMetadata?.lastModified ?: attempt.request.expectedLastModified
                        },
                    ),
                    onProgress = { downloaded, total, speed ->
                        emit(
                            attempt.onProgress,
                            attempt.throttle,
                            ProgressEvent(
                                attempt.request.operationId,
                                RuntimeDownloadProgress.Phase.DOWNLOADING,
                                attempt.index,
                                attempt.url,
                                downloaded,
                                total,
                                speed,
                                if (speed > 0L && total >= downloaded) (total - downloaded) / speed else null,
                            ),
                        )
                    },
                )
                val expectedSize = attempt.target.version.archive.sizeBytes
                emit(
                    attempt.onProgress,
                    attempt.throttle,
                    ProgressEvent(
                        attempt.request.operationId,
                        RuntimeDownloadProgress.Phase.VERIFYING,
                        attempt.index,
                        attempt.url,
                        attempt.part.length(),
                        expectedSize,
                        0L,
                        null,
                    ),
                )
                val actual = sha256(attempt.part)
                checkArchive(attempt.part, attempt.partMetadata, expectedSize, attempt.target.sha256, actual)
                promote(attempt.part, attempt.request.destination)
                attempt.partMetadata.delete()
                emit(
                    attempt.onProgress,
                    attempt.throttle,
                    ProgressEvent(
                        attempt.request.operationId,
                        RuntimeDownloadProgress.Phase.COMPLETED,
                        attempt.index,
                        attempt.url,
                        expectedSize,
                        expectedSize,
                        0L,
                        null,
                    ),
                )
                Logger.i(TAG, "下载完成: ${attempt.request.destination.absolutePath} (${attempt.request.destination.length()} bytes)")
                return RuntimeDownloadResult(
                    archiveFile = attempt.request.destination,
                    sourceUrl = attempt.url,
                    resumed = fetchResult.resumed,
                    bytes = expectedSize,
                    sha256 = actual,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (restart: RestartFromZeroException) {
                if (forceFullRestart) {
                    Logger.w(TAG, "下载源失败: ${attempt.target.source} [${attempt.url}]: ${restart.message}")
                    return null
                }
                forceFullRestart = true
            } catch (error: IOException) {
                Logger.w(TAG, "下载源失败: ${attempt.target.source} [${attempt.url}]: ${error.message ?: error.javaClass.simpleName}")
                return null
            } catch (error: IllegalStateException) {
                Logger.w(TAG, "下载源失败: ${attempt.target.source} [${attempt.url}]: ${error.message ?: error.javaClass.simpleName}")
                return null
            }
        }
    }

    private fun checkArchive(
        part: File,
        partMetadata: File,
        expectedSize: Long,
        expectedSha: String?,
        actual: String,
    ) {
        if (part.length() == expectedSize &&
            (expectedSha == null || actual.equals(expectedSha, ignoreCase = true))
        ) return
        val actualSize = part.length()
        part.delete()
        partMetadata.delete()
        throw IOException("校验失败：期望 $expectedSize bytes/${expectedSha ?: "未提供 SHA-256"}，实际 $actualSize bytes/$actual")
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

    suspend fun verifyArchive(file: File, expectedSize: Long, expectedSha256: String): Result<Unit> = withContext(Dispatchers.IO) {
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
        request: FetchRequest,
        onProgress: (Long, Long, Long) -> Unit,
    ): FetchResult = suspendCancellableCoroutine { continuation ->
        val activeConnection = AtomicReference<HttpURLConnection?>(null)
        val worker = CoroutineScope(Dispatchers.IO + GlobalCoroutineExceptionHandler).launch {
            try {
                val result = fetchBlocking(
                    request,
                    activeConnection,
                    onProgress,
                )
                if (continuation.isActive) continuation.resume(result)
            } catch (error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } catch (error: IllegalStateException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation {
            activeConnection.getAndSet(null)?.disconnect()
            worker.cancel()
        }
    }

    private fun fetchBlocking(
        request: FetchRequest,
        activeConnection: AtomicReference<HttpURLConnection?>,
        onProgress: (Long, Long, Long) -> Unit,
    ): FetchResult {
        val start = request.part.takeIf(File::isFile)?.length() ?: 0L
        val connection = (URL(request.url).openConnection() as? HttpURLConnection)
            ?: throw IOException("运行环境包下载只支持 HTTP(S) 地址")
        connection.apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            if (start > 0L) {
                setRequestProperty("Range", "bytes=$start-")
                (request.expectedEtag ?: request.expectedLastModified)?.let { setRequestProperty("If-Range", it) }
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
                    if (start > 0L) request.part.delete()
                    false
                }
                HttpURLConnection.HTTP_PARTIAL -> {
                    validateContentRange(connection.getHeaderField("Content-Range"), start, request.expectedSize)
                    true
                }
                else -> throw IOException("HTTP $code")
            }
            val responseEtag = connection.getHeaderField("ETag")
            val responseLastModified = connection.getHeaderField("Last-Modified")
            if (append && request.expectedEtag != null && responseEtag != request.expectedEtag) {
                request.part.delete()
                request.partMetadata.delete()
                throw RestartFromZeroException("ETag 已变化，清除断点后重新下载")
            }
            if (append && request.expectedEtag == null && request.expectedLastModified != null &&
                responseLastModified != request.expectedLastModified
            ) {
                request.part.delete()
                request.partMetadata.delete()
                throw RestartFromZeroException("Last-Modified 已变化，清除断点后重新下载")
            }
            AtomicFileStore.writeText(
                request.partMetadata,
                AppJson.encodeToString(PartMetadata(responseEtag, responseLastModified)),
            )
            val total = request.expectedSize
            var written = if (append) start else 0L
            val begunAt = System.nanoTime()
            lastProgressNanos.set(begunAt)
            onProgress(written, total, 0L)
            written = copyResponseBody(
                connection,
                CopyContext(
                    request,
                    append,
                    start,
                    written,
                    begunAt,
                    lastProgressNanos,
                    watchdogFailure,
                    onProgress,
                ),
            )
            watchdogFailure.get()?.let { throw it }
            if (written != request.expectedSize) throw IOException("下载长度不符：$written/${request.expectedSize} bytes")
            return FetchResult(append, responseEtag, responseLastModified)
        } catch (error: IOException) {
            throw watchdogFailure.get() ?: error
        } catch (error: IllegalStateException) {
            throw watchdogFailure.get() ?: error
        } finally {
            watchdogTask.cancel(true)
            watchdog.shutdownNow()
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    private fun copyResponseBody(connection: HttpURLConnection, context: CopyContext): Long {
        var written = context.initialWritten
        var eof = false
        connection.inputStream.use { input ->
            FileOutputStream(context.request.part, context.append).use { output ->
                if (context.append) output.channel.position(context.start)
                val buffer = ByteArray(BUFFER_SIZE)
                while (!eof && written < context.request.expectedSize) {
                    val read = input.read(buffer)
                    when {
                        read < 0 -> eof = true
                        read > 0 -> {
                            output.write(buffer, 0, read)
                            written += read
                            val now = System.nanoTime()
                            context.lastProgressNanos.set(now)
                            val elapsed = TimeUnit.NANOSECONDS.toSeconds(now - context.begunAt).coerceAtLeast(1L)
                            context.onProgress(
                                written,
                                context.request.expectedSize,
                                (written - if (context.append) context.start else 0L) / elapsed,
                            )
                            context.watchdogFailure.get()?.let { throw it }
                            check(written <= context.request.expectedSize) {
                                "下载大小超过清单声明的 ${context.request.expectedSize} bytes"
                            }
                        }
                    }
                }
            }
        }
        return written
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

    private fun emit(callback: (RuntimeDownloadProgress) -> Unit, throttle: RuntimeProgressThrottle, event: ProgressEvent) {
        val now = System.currentTimeMillis()
        if (throttle.shouldEmit(event.phase, event.downloaded, event.total, now)) {
            callback(
                RuntimeDownloadProgress(
                    operationId = event.operationId,
                    phase = event.phase,
                    sourceIndex = event.sourceIndex,
                    sourceUrl = event.sourceUrl,
                    downloadedBytes = event.downloaded,
                    totalBytes = event.total,
                    speedBytesPerSecond = event.speed,
                    etaSeconds = event.eta,
                ),
            )
        }
    }

    private fun validateRequest(request: RuntimeDownloadRequest): Exception? {
        return listOfNotNull(
            IllegalArgumentException("operationId 不能为空").takeIf { request.operationId.isBlank() },
            IllegalArgumentException("下载大小与所选版本清单不一致").takeIf {
                request.expectedSize <= 0L || request.version.archive.sizeBytes != request.expectedSize
            },
            IllegalArgumentException("缺少 SHA-256 的来源必须标记为未验证").takeIf {
                request.expectedSha256 == null && request.version.sourceTrust != RuntimeSourceTrust.UNVERIFIED
            },
            IllegalArgumentException("下载 SHA-256 与所选版本清单不一致").takeIf {
                request.expectedSha256 != null &&
                    !request.expectedSha256.equals(request.version.archive.sha256, ignoreCase = true)
            },
            IllegalArgumentException("下载 SHA-256 格式非法").takeIf {
                request.expectedSha256 != null && !RuntimeManifestDocument.isValidSha256(request.expectedSha256)
            },
            IllegalArgumentException("目标 ABI 不能为空").takeIf { request.targetAbi.isBlank() },
        ).firstOrNull()
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

    private data class DownloadAttempt(
        val request: RuntimeDownloadRequest,
        val target: DownloadTarget,
        val part: File,
        val partMetadata: File,
        val index: Int,
        val url: String,
        val throttle: RuntimeProgressThrottle,
        val onProgress: (RuntimeDownloadProgress) -> Unit,
    )

    private data class FetchResult(
        val resumed: Boolean,
        val etag: String?,
        val lastModified: String?,
    )

    private data class FetchRequest(
        val url: String,
        val part: File,
        val partMetadata: File,
        val expectedSize: Long,
        val expectedEtag: String?,
        val expectedLastModified: String?,
    )

    private data class CopyContext(
        val request: FetchRequest,
        val append: Boolean,
        val start: Long,
        val initialWritten: Long,
        val begunAt: Long,
        val lastProgressNanos: AtomicLong,
        val watchdogFailure: AtomicReference<SocketTimeoutException?>,
        val onProgress: (Long, Long, Long) -> Unit,
    )

    private data class ProgressEvent(
        val operationId: String,
        val phase: RuntimeDownloadProgress.Phase,
        val sourceIndex: Int,
        val sourceUrl: String,
        val downloaded: Long,
        val total: Long,
        val speed: Long,
        val eta: Long?,
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
