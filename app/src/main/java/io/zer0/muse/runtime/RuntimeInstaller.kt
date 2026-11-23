package io.zer0.muse.runtime

import android.content.Context
import android.os.StatFs
import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID

class RuntimeInstaller(
    private val context: Context,
    private val store: RuntimeStore,
    private val downloader: RuntimeDownloader = RuntimeDownloader(),
    private val prootRuntime: ProotRuntime = ProotRuntime(context, store),
    private val operationStore: RuntimeInstallOperationStore = RuntimeInstallOperationStore(store.operationFile()),
) {

    sealed interface Progress {
        data class Downloading(val downloaded: Long, val total: Long) : Progress
        data object Verifying : Progress
        data class Extracting(val writtenBytes: Long) : Progress
        data object SelfCheck : Progress
        data object Done : Progress
    }

    suspend fun install(
        id: RuntimeId,
        manifest: RuntimeManifest,
        manifestCandidates: List<ManifestCandidate> = emptyList(),
        forceRepair: Boolean = false,
        onProgress: (Progress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var operation: RuntimeInstallOperation? = null
        try {
            val current = store.state(id)
            if (!forceRepair && current is RuntimeState.Ready && current.revision == manifest.revision) {
                operationStore.load()
                    ?.takeIf { it.runtimeId == id.manifestKey && it.revision == manifest.revision }
                    ?.let { operationStore.saveAtomic(it.copy(phase = RuntimeInstallPhase.COMPLETED, lastError = null, updatedAt = System.currentTimeMillis())) }
                onProgress(Progress.Done)
                return@withContext Result.success(Unit)
            }

            val need = manifest.installedBytes + manifest.archive.sizeBytes + SPACE_MARGIN_BYTES
            val available = availableBytes(store.rootDir())
            if (available in 1 until need) {
                return@withContext Result.failure(
                    IllegalStateException("存储空间不足：需要约 ${need / MB} MB，可用 ${available / MB} MB"),
                )
            }

            val operationId = UUID.randomUUID().toString()
            val archiveFile = File(store.rootDir(), "${id.manifestKey}-${manifest.revision}.tar.gz")
            val partFile = File(archiveFile.parentFile, archiveFile.name + ".part")
            val previousOperation = operationStore.load()
                ?.takeIf { it.runtimeId == id.manifestKey && it.revision == manifest.revision }
            operation = RuntimeInstallOperation(
                operationId = operationId,
                runtimeId = id.manifestKey,
                abi = manifest.abi,
                revision = manifest.revision,
                phase = RuntimeInstallPhase.SELECTING,
                sourceTrust = manifest.sourceTrust,
                expectedSha256 = manifest.archive.sha256.takeIf(String::isNotBlank),
                sourceUrl = manifest.archive.urls.firstOrNull().orEmpty(),
                archivePath = archiveFile.absolutePath,
                partPath = partFile.absolutePath,
                downloadedBytes = partFile.takeIf(File::isFile)?.length() ?: 0L,
                totalBytes = manifest.archive.sizeBytes,
                attemptCount = (previousOperation?.attemptCount ?: 0) + 1,
                updatedAt = System.currentTimeMillis(),
                etag = previousOperation?.etag,
                lastModified = previousOperation?.lastModified,
            )
            operationStore.saveAtomic(operation)
            operation = operation.copy(phase = RuntimeInstallPhase.DOWNLOADING, updatedAt = System.currentTimeMillis())
            operationStore.saveAtomic(operation)

            val version = RuntimeVersion(
                revision = manifest.revision,
                upstream = manifest.upstream,
                archive = manifest.archive,
                installedBytes = manifest.installedBytes,
                bins = manifest.bins,
                sourceTrust = manifest.sourceTrust,
            )
            val download = downloader.download(
                RuntimeDownloadRequest(
                    version = version,
                    destination = archiveFile,
                    expectedSha256 = manifest.archive.sha256.takeIf(String::isNotBlank),
                    expectedSize = manifest.archive.sizeBytes,
                    sourceUrls = manifest.archive.urls,
                    operationId = operationId,
                    targetAbi = manifest.abi,
                    manifestCandidates = manifestCandidates,
                    expectedEtag = operation?.etag,
                    expectedLastModified = operation?.lastModified,
                ),
            ) { progress ->
                when (progress.phase) {
                    RuntimeDownloadProgress.Phase.DOWNLOADING -> onProgress(
                        Progress.Downloading(progress.downloadedBytes, progress.totalBytes),
                    )
                    RuntimeDownloadProgress.Phase.VERIFYING -> onProgress(Progress.Verifying)
                    RuntimeDownloadProgress.Phase.COMPLETED -> Unit
                }
                val phase = if (progress.phase == RuntimeDownloadProgress.Phase.DOWNLOADING) {
                    RuntimeInstallPhase.DOWNLOADING
                } else {
                    RuntimeInstallPhase.VERIFYING
                }
                operation = operation?.copy(
                    phase = phase,
                    sourceUrl = progress.sourceUrl,
                    sourceIndex = progress.sourceIndex,
                    downloadedBytes = progress.downloadedBytes,
                    totalBytes = progress.totalBytes,
                    updatedAt = System.currentTimeMillis(),
                )
                operation?.let(operationStore::saveAtomic)
            }.getOrElse { error ->
                operation = failedOperation(operation, error)
                operation?.let(operationStore::saveAtomic)
                return@withContext Result.failure(error)
            }

            operation = operation?.copy(
                phase = RuntimeInstallPhase.VERIFYING,
                sourceUrl = download.sourceUrl,
                downloadedBytes = download.bytes,
                totalBytes = download.bytes,
                etag = download.etag,
                lastModified = download.lastModified,
                updatedAt = System.currentTimeMillis(),
            )
            operation?.let(operationStore::saveAtomic)
            val directoryRevision = if (forceRepair) {
                "${manifest.revision}-repair-${operationId.take(8)}"
            } else {
                manifest.revision
            }
            installVerifiedArchive(
                id,
                manifest,
                download.archiveFile,
                operation,
                onProgress,
                directoryRevision,
            ).also { result ->
                if (result.isSuccess) download.archiveFile.delete()
            }
        } catch (cancelled: CancellationException) {
            operation = operation?.copy(
                phase = RuntimeInstallPhase.CANCELLED,
                cancelReason = "安装协程已取消",
                updatedAt = System.currentTimeMillis(),
            )
            operation?.let(operationStore::saveAtomic)
            throw cancelled
        } catch (error: Exception) {
            operation = failedOperation(operation, error)
            operation?.let(operationStore::saveAtomic)
            Logger.e(TAG, "安装失败: ${error.message}", error)
            Result.failure(error)
        }
    }

    suspend fun installFromArchive(
        id: RuntimeId,
        manifest: RuntimeManifest,
        archiveFile: File,
        onProgress: (Progress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val operation = RuntimeInstallOperation(
            operationId = UUID.randomUUID().toString(),
            runtimeId = id.manifestKey,
            abi = manifest.abi,
            revision = manifest.revision,
            phase = RuntimeInstallPhase.VERIFYING,
            sourceTrust = manifest.sourceTrust,
            expectedSha256 = manifest.archive.sha256.takeIf(String::isNotBlank),
            sourceUrl = "local-file",
            archivePath = archiveFile.absolutePath,
            totalBytes = archiveFile.length(),
            updatedAt = System.currentTimeMillis(),
        )
        operationStore.saveAtomic(operation)
        val verification = downloader.verifyArchive(
            archiveFile,
            manifest.archive.sizeBytes,
            manifest.archive.sha256,
        )
        verification.exceptionOrNull()?.let { error ->
            operationStore.saveAtomic(
                operation.copy(
                    phase = RuntimeInstallPhase.FAILED_PERMANENT,
                    lastError = error.message ?: error.javaClass.simpleName,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            return@withContext Result.failure(error)
        }
        installVerifiedArchive(id, manifest, archiveFile, operation, onProgress, manifest.revision)
    }

    private suspend fun installVerifiedArchive(
        id: RuntimeId,
        manifest: RuntimeManifest,
        archiveFile: File,
        initialOperation: RuntimeInstallOperation?,
        onProgress: (Progress) -> Unit,
        directoryRevision: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var operation = initialOperation
        val staging = store.stagingDir(id)
        try {
            currentCoroutineContext().ensureActive()
            operation = operation?.copy(phase = RuntimeInstallPhase.EXTRACTING, updatedAt = System.currentTimeMillis())
            operation?.let(operationStore::saveAtomic)
            onProgress(Progress.Extracting(0))
            staging.deleteRecursively()
            if (!staging.mkdirs() && !staging.isDirectory) throw IOException("无法创建 runtime staging 目录")
            TarGzExtractor.extract(archiveFile, staging) { written -> onProgress(Progress.Extracting(written)) }
            currentCoroutineContext().ensureActive()

            operation = operation?.copy(phase = RuntimeInstallPhase.SELF_CHECKING, updatedAt = System.currentTimeMillis())
            operation?.let(operationStore::saveAtomic)
            onProgress(Progress.SelfCheck)
            val health = ProotProbe(context).healthCheck(staging, manifest.revision)
            if (!health.healthy) {
                throw RuntimeInstallException(
                    message = "运行环境自检失败: ${health.failureKind}: ${health.stderrTail}",
                    failureKind = health.failureKind ?: RuntimeFailureKind.SELF_CHECK,
                )
            }

            operation = operation?.copy(phase = RuntimeInstallPhase.READY_TO_SWITCH, updatedAt = System.currentTimeMillis())
            operation?.let(operationStore::saveAtomic)
            val target = File(store.dirFor(id), directoryRevision)
            target.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) throw IOException("无法创建运行环境版本目录")
            }
            if (target.exists() && !target.deleteRecursively()) throw IOException("无法替换不完整的同版本目录")
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
                if (!staging.deleteRecursively()) Logger.w(TAG, "切换后未能删除 staging 目录")
            }
            Logger.i(TAG, "运行环境解包完成: ${target.absolutePath}")

            val installedHealth = store.healthAt(id, directoryRevision, manifest.revision)
            if (installedHealth is RuntimeHealth.Broken) {
                target.deleteRecursively()
                throw RuntimeInstallException(
                    "切换前完整性检查失败: ${installedHealth.missingPaths.joinToString()}",
                    RuntimeFailureKind.BROKEN,
                )
            }

            operation = operation?.copy(phase = RuntimeInstallPhase.SWITCHING, updatedAt = System.currentTimeMillis())
            operation?.let(operationStore::saveAtomic)
            store.markReady(id, manifest.revision, manifest.installedBytes, directoryRevision)
            val postSwitchHealth = store.health(id, manifest.revision)
            if (postSwitchHealth is RuntimeHealth.Broken) {
                val rollback = store.rollback(id)
                if (rollback.isFailure) Logger.e(TAG, "新版本健康检查失败，恢复旧指针也失败", rollback.exceptionOrNull())
                target.deleteRecursively()
                throw RuntimeInstallException(
                    "新版本指针写入后健康检查失败: ${postSwitchHealth.missingPaths.joinToString()}",
                    RuntimeFailureKind.BROKEN,
                )
            }
            store.pruneOld(id, manifest.revision)
            operation = operation?.copy(phase = RuntimeInstallPhase.COMPLETED, lastError = null, updatedAt = System.currentTimeMillis())
            operation?.let(operationStore::saveAtomic)
            onProgress(Progress.Done)
            Logger.i(TAG, "${id.manifestKey} 安装完成，node=${health.nodeVersion}, npm=${health.npmVersion}")
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            operation = operation?.copy(
                phase = RuntimeInstallPhase.CANCELLED,
                cancelReason = "安装协程已取消",
                updatedAt = System.currentTimeMillis(),
            )
            operation?.let(operationStore::saveAtomic)
            staging.deleteRecursively()
            throw cancelled
        } catch (error: Exception) {
            staging.deleteRecursively()
            operation = failedOperation(operation, error)
            operation?.let(operationStore::saveAtomic)
            Logger.e(TAG, "运行环境解包或切换失败: ${error.message}", error)
            Result.failure(error)
        }
    }

    private fun failedOperation(
        operation: RuntimeInstallOperation?,
        error: Throwable,
    ): RuntimeInstallOperation? {
        val phase = when (error) {
            is SocketTimeoutException, is IOException -> RuntimeInstallPhase.FAILED_RETRYABLE
            else -> RuntimeInstallPhase.FAILED_PERMANENT
        }
        return operation?.copy(
            phase = phase,
            lastError = error.message ?: error.javaClass.simpleName,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun availableBytes(dir: File): Long = runCatching {
        StatFs(dir.absolutePath).availableBytes
    }.getOrDefault(-1L)

    private class RuntimeInstallException(
        message: String,
        val failureKind: RuntimeFailureKind,
    ) : IllegalStateException(message)

    private companion object {
        const val TAG = "RuntimeInstaller"
        const val MB = 1024L * 1024L
        const val SPACE_MARGIN_BYTES = 64L * MB
    }
}
