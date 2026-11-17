package io.zer0.muse.runtime

import android.content.Context
import android.os.StatFs
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 运行环境安装器：下载 → 校验 → 解压 → 自检 → 原子切换。
 *
 * 每一步都可以中断，而且中断后不会留下「看起来装好了」的状态：
 * 真正生效的只有最后写 `current.json` 那一下。
 */
class RuntimeInstaller(
    private val context: Context,
    private val store: RuntimeStore,
    private val downloader: RuntimeDownloader = RuntimeDownloader(),
    private val prootRuntime: ProotRuntime = ProotRuntime(context, store),
) {

    /** 安装进度。以字节为单位，总字节未知时给 -1，UI 显示不确定进度条。 */
    sealed interface Progress {
        data class Downloading(val downloaded: Long, val total: Long) : Progress
        data object Verifying : Progress
        data class Extracting(val writtenBytes: Long) : Progress
        data object SelfCheck : Progress
        data object Done : Progress
    }

    /**
     * 按 [manifest] 安装 [id]。
     *
     * 已装且版本一致时直接返回成功（幂等），重复点安装不会重复下载。
     */
    suspend fun install(
        id: RuntimeId,
        manifest: RuntimeManifest,
        onProgress: (Progress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val current = store.state(id)
            if (current is RuntimeState.Ready && current.revision == manifest.revision) {
                Logger.i(TAG, "${id.manifestKey} 已是 ${manifest.revision}，跳过安装")
                onProgress(Progress.Done)
                return@withContext Result.success(Unit)
            }

            val need = manifest.installedBytes + manifest.archive.sizeBytes + SPACE_MARGIN_BYTES
            val available = availableBytes(store.rootDir())
            if (available in 1 until need) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "存储空间不够：还需要约 ${need / MB} MB，当前可用 ${available / MB} MB",
                    ),
                )
            }

            // 1. 下载
            val archiveFile = File(store.rootDir(), "${id.manifestKey}-${manifest.revision}.tar.gz")
            val downloaded = downloader.download(manifest, archiveFile) { done, total ->
                onProgress(Progress.Downloading(done, total))
            }.getOrElse { error ->
                return@withContext Result.failure(error)
            }

            // 2. 解压到 staging
            return@withContext installFromArchive(id, manifest, downloaded, onProgress).also {
                downloaded.delete()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "安装失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 从一个已就绪的压缩包安装（跳过下载）。
     *
     * 两个用途：安装器内部下载完成后调它；以及离线/侧载场景（包已经躺在本地）。
     */
    suspend fun installFromArchive(
        id: RuntimeId,
        manifest: RuntimeManifest,
        archiveFile: java.io.File,
        onProgress: (Progress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            onProgress(Progress.Extracting(0))
            val staging = store.stagingDir(id)
            staging.deleteRecursively()
            staging.mkdirs()
            val extracted = runCatching {
                TarGzExtractor.extract(archiveFile, staging) { written ->
                    onProgress(Progress.Extracting(written))
                }
            }.getOrElse { error ->
                staging.deleteRecursively()
                return@withContext Result.failure(
                    IllegalStateException("解压失败：${error.message}", error),
                )
            }
            ensureActive()

            // 3. staging → 正式目录（先删旧版本，再改名，避免半新半旧）
            val target = store.installDir(id, manifest.revision)
            target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!staging.renameTo(target)) {
                // 跨分区或已存在时退回递归移动
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
            }
            Logger.i(TAG, "解压完成: ${target.absolutePath} ($extracted bytes)")

            // 4. 自检：跑不通就当没装，指针不写
            onProgress(Progress.SelfCheck)
            val selfCheckPassed = ProotProbe(context).nodeVersion(target)
            if (selfCheckPassed == null) {
                target.deleteRecursively()
                return@withContext Result.failure(
                    IllegalStateException("环境装好了但跑不起来（node -v 失败），已回滚"),
                )
            }

            // 5. 写指针 + 清理旧版本
            store.markReady(id, manifest.revision, manifest.installedBytes)
            store.pruneOld(id, manifest.revision)
            onProgress(Progress.Done)
            Logger.i(TAG, "${id.manifestKey} 安装完成，node=$selfCheckPassed")
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "安装失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun availableBytes(dir: File): Long = runCatching {
        StatFs(dir.absolutePath).availableBytes
    }.getOrDefault(-1L)

    private companion object {
        const val TAG = "RuntimeInstaller"
        const val MB = 1024L * 1024L

        /** 解压时会额外占用，留 64MB 余量，避免卡在 99% 报 IO 错误。 */
        const val SPACE_MARGIN_BYTES = 64L * MB
    }
}
