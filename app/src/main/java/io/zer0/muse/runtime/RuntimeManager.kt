package io.zer0.muse.runtime

import android.content.Context
import io.zer0.common.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 运行环境的唯一入口。
 *
 * 上层（设置页、安装卡、工具层）都只跟它打交道，好处是：
 *  - 同一时刻只有一次安装在跑（[installMutex]），重复点安装不会下两遍；
 *  - 状态只有一份（[stateFlow]），界面与工具层不会各说各话；
 *  - 安装失败不需要调用方处理回滚，[RuntimeInstaller] 已经把目录清干净。
 */
class RuntimeManager(
    private val context: Context,
    internal val store: RuntimeStore = RuntimeStore(context),
    private val installer: RuntimeInstaller,
    private val manifestSource: RuntimeManifestSource = RuntimeManifestSource(context),
) {

    private val _progress = MutableStateFlow<RuntimeInstaller.Progress?>(null)

    /** 正在进行的安装进度；null 表示没有安装任务。 */
    val progress: StateFlow<RuntimeInstaller.Progress?> = _progress.asStateFlow()

    fun operation(id: RuntimeId = RuntimeId.UBUNTU_NODE): RuntimeInstallOperation? =
        RuntimeInstallOperationStore(store.operationFile(id)).load()

    /** 当前 ABI 的清单（远程优先、内置兜底）；没有对应包时返回 null。 */
    suspend fun manifest(id: RuntimeId = RuntimeId.UBUNTU_NODE): RuntimeManifest? = selectedManifest(manifestSource.loadCandidates(id))

    suspend fun versions(id: RuntimeId = RuntimeId.UBUNTU_NODE): List<RuntimeVersion> = manifestSource.loadCandidates(id)
        .flatMap { it.document.versionsOrLegacy() }
        .distinctBy(RuntimeVersion::revision)

    /**
     * 安装（幂等）。返回 Result 里的错误消息可直接展示给用户。
     */
    suspend fun install(id: RuntimeId = RuntimeId.UBUNTU_NODE): Result<Unit> = lockFor(id).withLock {
        val candidates = manifestSource.loadCandidates(id)
        val manifest = selectedManifest(candidates)
            ?: return@withLock Result.failure(IllegalStateException("这个设备架构没有对应的运行环境包"))
        installResolved(id, manifest, candidates)
    }

    suspend fun installSelected(id: RuntimeId, revision: String): Result<Unit> = lockFor(id).withLock {
        val candidates = manifestSource.loadCandidates(id)
        val manifest = selectedManifest(candidates, revision)
            ?: return@withLock Result.failure(IllegalArgumentException("清单中没有版本 $revision"))
        installResolved(id, manifest, candidates)
    }

    suspend fun installCustomUrl(id: RuntimeId, revision: String, url: String, sha256: String?): Result<Unit> = lockFor(id).withLock {
        val candidates = manifestSource.loadCandidates(id)
        val manifest = selectedManifest(candidates, revision)
            ?: return@withLock Result.failure(IllegalArgumentException("清单中没有版本 $revision"))
        if (!RuntimeManifestDocument.isAllowedCustomUrl(url)) {
            return@withLock Result.failure(IllegalArgumentException("自定义来源必须是公开 HTTPS 地址"))
        }
        val normalizedSha = sha256?.trim()?.takeIf(String::isNotEmpty)?.lowercase()
        if (normalizedSha != null && !RuntimeManifestDocument.isValidSha256(normalizedSha)) {
            return@withLock Result.failure(IllegalArgumentException("SHA-256 格式非法"))
        }
        val custom = manifest.copy(
            archive = manifest.archive.copy(sha256 = normalizedSha.orEmpty(), urls = listOf(url)),
            sourceTrust = if (normalizedSha == null) RuntimeSourceTrust.UNVERIFIED else RuntimeSourceTrust.CHECKSUM_PINNED,
        )
        installResolved(id, custom, emptyList())
    }

    suspend fun installFromArchive(id: RuntimeId, revision: String, archiveFile: java.io.File): Result<Unit> = lockFor(id).withLock {
        val candidates = manifestSource.loadCandidates(id)
        val manifest = selectedManifest(candidates, revision)
            ?: return@withLock Result.failure(IllegalArgumentException("清单中没有版本 $revision"))
        RuntimeInstallStatus.begin()
        RuntimeInstallStatus.setRevision(manifest.revision)
        val result = try {
            installer.installFromArchive(id, manifest, archiveFile) { progress ->
                _progress.value = progress
                RuntimeInstallStatus.updateProgress(progress)
            }
        } finally {
            _progress.value = null
            RuntimeInstallStatus.updateProgress(null)
            RuntimeInstallStatus.refresh(context)
        }
        result.onFailure { RuntimeInstallStatus.fail(it.message) }
        result
    }

    /**
     * 交给前台服务在后台安装（界面与工具层的推荐入口）。
     *
     * 不直接在当前协程里下 76MB：用户可能随时退出应用，前台服务能把它跑完。
     */
    fun startInstall(id: RuntimeId = RuntimeId.UBUNTU_NODE) {
        RuntimeInstallService.start(context, id)
    }

    /** 卸载并释放空间。 */
    suspend fun uninstall(id: RuntimeId = RuntimeId.UBUNTU_NODE) {
        lockFor(id).withLock {
            store.uninstall(id)
            Logger.i(TAG, "已卸载 ${id.manifestKey}")
        }
    }

    suspend fun rollback(id: RuntimeId = RuntimeId.UBUNTU_NODE): Result<Unit> = lockFor(id).withLock {
        store.rollback(id)
    }

    suspend fun repair(id: RuntimeId = RuntimeId.UBUNTU_NODE): Result<Unit> = lockFor(id).withLock {
        val candidates = manifestSource.loadCandidates(id)
        val revision = store.currentRevision(id)
        val manifest = revision?.let { selectedManifest(candidates, it) }
            ?: selectedManifest(candidates)
            ?: return@withLock Result.failure(IllegalStateException("没有可用于修复的运行环境版本"))
        installResolved(id, manifest, candidates, forceRepair = true)
    }

    suspend fun resume(id: RuntimeId = RuntimeId.UBUNTU_NODE): Result<Unit> {
        return lockFor(id).withLock {
            val operation = operation(id)
                ?: return@withLock Result.failure(IllegalStateException("没有可继续的安装操作"))
            val candidates = manifestSource.loadCandidates(id)
            val manifest = selectedManifest(candidates, operation.revision)
                ?: return@withLock Result.failure(IllegalArgumentException("清单中没有版本 ${operation.revision}"))
            if (operation.sourceUrl == "local-file") {
                val localRoot = File(context.filesDir, "runtime/imports").canonicalFile
                val localArchive = File(operation.archivePath).canonicalFile
                if (localArchive.isFile && localArchive.path.startsWith(localRoot.path + File.separator)) {
                    return@withLock installArchiveResolved(id, manifest, localArchive)
                }
                if (store.state(id) is RuntimeState.Ready) return@withLock Result.success(Unit)
                return@withLock Result.failure(IllegalStateException("原本地归档已清理，请重新选择文件"))
            }
            val isCustomSource = operation.sourceTrust == RuntimeSourceTrust.UNVERIFIED ||
                (operation.sourceUrl.isNotBlank() && operation.sourceUrl !in manifest.archive.urls)
            val resumedManifest = if (isCustomSource) {
                manifest.copy(
                    archive = manifest.archive.copy(
                        sha256 = operation.expectedSha256.orEmpty(),
                        urls = listOf(operation.sourceUrl),
                    ),
                    sourceTrust = operation.sourceTrust,
                )
            } else {
                manifest
            }
            installResolved(id, resumedManifest, if (isCustomSource) emptyList() else candidates)
        }
    }

    /** 已安装时占用的字节数（用于设置页展示与空间统计）。 */
    private fun selectedManifest(candidates: List<ManifestCandidate>): RuntimeManifest? {
        val candidate = candidates.firstOrNull() ?: return null
        val version = candidate.document.versionsOrLegacy().firstOrNull() ?: return null
        return candidate.document.toManifest(version)
    }

    private fun selectedManifest(candidates: List<ManifestCandidate>, revision: String): RuntimeManifest? {
        val candidate = candidates.firstOrNull { source ->
            source.document.versionsOrLegacy().any { it.revision == revision }
        } ?: return null
        val version = candidate.document.versionsOrLegacy().firstOrNull { it.revision == revision } ?: return null
        return candidate.document.toManifest(version)
    }

    private suspend fun installResolved(
        id: RuntimeId,
        manifest: RuntimeManifest,
        candidates: List<ManifestCandidate>,
        forceRepair: Boolean = false,
    ): Result<Unit> {
        Logger.i(TAG, "开始安装 ${id.manifestKey} ${manifest.revision}（${manifest.archive.sizeBytes / 1024 / 1024} MB）")
        RuntimeInstallStatus.begin()
        RuntimeInstallStatus.setRevision(manifest.revision)
        val result = try {
            installer.install(id, manifest, candidates, forceRepair) { progress ->
                _progress.value = progress
                RuntimeInstallStatus.updateProgress(progress)
            }
        } finally {
            _progress.value = null
            RuntimeInstallStatus.updateProgress(null)
            RuntimeInstallStatus.refresh(context)
        }
        result.onFailure { RuntimeInstallStatus.fail(it.message) }
        return result
    }

    private suspend fun installArchiveResolved(id: RuntimeId, manifest: RuntimeManifest, archiveFile: java.io.File): Result<Unit> {
        RuntimeInstallStatus.begin()
        RuntimeInstallStatus.setRevision(manifest.revision)
        return try {
            installer.installFromArchive(id, manifest, archiveFile) { progress ->
                _progress.value = progress
                RuntimeInstallStatus.updateProgress(progress)
            }.also { result -> result.onFailure { RuntimeInstallStatus.fail(it.message) } }
        } finally {
            _progress.value = null
            RuntimeInstallStatus.updateProgress(null)
            RuntimeInstallStatus.refresh(context)
        }
    }

    private fun lockFor(id: RuntimeId): Mutex = locks.computeIfAbsent(id) { Mutex() }

    private companion object {
        const val TAG = "RuntimeManager"
        val locks = ConcurrentHashMap<RuntimeId, Mutex>()
    }
}

/** 已安装状态（由指针推导，不缓存，避免界面与磁盘不一致）。 */
fun RuntimeManager.state(id: RuntimeId = RuntimeId.UBUNTU_NODE): RuntimeState = store.state(id)

fun RuntimeManager.currentRevision(id: RuntimeId = RuntimeId.UBUNTU_NODE): String? = store.currentRevision(id)

fun RuntimeManager.hasRollbackVersion(id: RuntimeId = RuntimeId.UBUNTU_NODE): Boolean = store.previousRevision(id) != null

fun RuntimeManager.installedBytes(id: RuntimeId = RuntimeId.UBUNTU_NODE): Long =
    (store.state(id) as? RuntimeState.Ready)?.installedBytes ?: 0L
