package io.zer0.muse.data.media

import android.content.Context
import io.zer0.ai.image.ImageGenParams
import io.zer0.ai.image.ImageService
import io.zer0.ai.video.VideoGenRequest
import io.zer0.ai.video.VideoGenerationService
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Parameters captured for an image task submitted from Media Studio or Chat. */
@Serializable
data class StudioImageRequest(
    val prompt: String,
    val providerId: String,
    val modelId: String,
    val size: String = "1024x1024",
    val quality: String = "standard",
    val style: String = "",
    val n: Int = 1,
    val referenceImageUri: String? = null,
)

/** Parameters captured for a video task submitted from Media Studio or Chat. */
@Serializable
data class StudioVideoRequest(
    val prompt: String,
    val providerId: String,
    val modelId: String,
    val duration: Int = 5,
    val resolution: String = "720p",
    val aspectRatio: String = "16:9",
    val referenceImageUri: String? = null,
)

/**
 * Process-surviving coordinator for image and video generation tasks.
 *
 * Persists task lifecycle and downloaded local assets in Room so users can leave the screen,
 * cancel/retry tasks, browse the Gallery, and unify outputs from Studio, Chat Draw Mode, and LLM tools.
 */
class MediaGenerationManager(
    private val context: Context,
    private val dao: MediaGenDao,
    private val storage: MediaAssetStorage,
    private val settings: SettingsRepository,
    private val imageService: ImageService,
    private val videoGenerationService: VideoGenerationService,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    init {
        scope.launch {
            runCatching {
                dao.markRunningTasksInterrupted(
                    errorMessage = context.getString(R.string.media_studio_task_interrupted),
                    updatedAt = System.currentTimeMillis(),
                )
            }.onFailure { e ->
                Logger.w(TAG, "markRunningTasksInterrupted failed: ${e.message}")
            }
        }
    }

    fun observeTasks(): Flow<List<MediaGenTaskEntity>> = dao.observeAllTasks()

    fun observeActiveTasks(): Flow<List<MediaGenTaskEntity>> = dao.observeActiveTasks()

    fun observeAssets(): Flow<List<MediaGenAssetEntity>> = dao.observeAllAssets()

    /** Enqueue and run a background image generation task; returns immediately with the new task ID. */
    suspend fun submitImageTask(request: StudioImageRequest, source: String = MediaGenTaskEntity.SOURCE_STUDIO): String {
        val taskId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val cachedRefPath = request.referenceImageUri?.let { storage.persistReferenceImage(it, taskId) }
        val sanitizedRequest = request.copy(referenceImageUri = null)
        val task = MediaGenTaskEntity(
            id = taskId,
            kind = MediaGenTaskEntity.KIND_IMAGE,
            status = MediaGenTaskEntity.STATUS_RUNNING,
            prompt = request.prompt,
            providerId = request.providerId,
            modelId = request.modelId,
            paramsJson = AppJson.encodeToString(StudioImageRequest.serializer(), sanitizedRequest),
            referenceImagePath = cachedRefPath,
            elapsedSeconds = 0,
            errorMessage = null,
            source = source,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsertTask(task)

        val job = scope.launch {
            runImageJob(taskId, request, cachedRefPath)
        }
        activeJobs[taskId] = job
        return taskId
    }

    /** Enqueue and run a background video generation task; returns immediately with the new task ID. */
    suspend fun submitVideoTask(request: StudioVideoRequest, source: String = MediaGenTaskEntity.SOURCE_STUDIO): String {
        val taskId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val cachedRefPath = request.referenceImageUri?.let { storage.persistReferenceImage(it, taskId) }
        val sanitizedRequest = request.copy(referenceImageUri = null)
        val task = MediaGenTaskEntity(
            id = taskId,
            kind = MediaGenTaskEntity.KIND_VIDEO,
            status = MediaGenTaskEntity.STATUS_RUNNING,
            prompt = request.prompt,
            providerId = request.providerId,
            modelId = request.modelId,
            paramsJson = AppJson.encodeToString(StudioVideoRequest.serializer(), sanitizedRequest),
            referenceImagePath = cachedRefPath,
            elapsedSeconds = 0,
            errorMessage = null,
            source = source,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsertTask(task)

        val job = scope.launch {
            runVideoJob(taskId, request, cachedRefPath)
        }
        activeJobs[taskId] = job
        return taskId
    }

    /**
     * Record and locally cache images generated externally (e.g. Chat Draw Mode or LLM `generate_image`).
     * Returns `file://` URIs for persisted files, falling back to original sources on storage failure.
     */
    suspend fun recordExternalImageResult(
        prompt: String,
        providerId: String,
        modelId: String,
        rawSources: List<String>,
        paramsJson: String = "{}",
        source: String = MediaGenTaskEntity.SOURCE_CHAT,
    ): List<String> {
        if (rawSources.isEmpty()) return emptyList()
        val taskId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.upsertTask(
            MediaGenTaskEntity(
                id = taskId,
                kind = MediaGenTaskEntity.KIND_IMAGE,
                status = MediaGenTaskEntity.STATUS_SUCCEEDED,
                prompt = prompt,
                providerId = providerId,
                modelId = modelId,
                paramsJson = paramsJson,
                source = source,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val assets = mutableListOf<MediaGenAssetEntity>()
        val resolvedUris = rawSources.map { raw ->
            val assetId = UUID.randomUUID().toString()
            val stored = storage.persistImage(raw, assetId)
            if (stored != null) {
                assets.add(
                    MediaGenAssetEntity(
                        id = assetId,
                        taskId = taskId,
                        kind = MediaGenTaskEntity.KIND_IMAGE,
                        localPath = stored.localPath,
                        remoteUrl = stored.remoteUrl,
                        mimeType = stored.mimeType,
                        width = stored.width,
                        height = stored.height,
                        prompt = prompt,
                        modelId = modelId,
                        providerId = providerId,
                        paramsJson = paramsJson,
                        createdAt = now,
                    ),
                )
                "file://${stored.localPath}"
            } else {
                raw
            }
        }
        if (assets.isNotEmpty()) {
            dao.insertAssets(assets)
        }
        return resolvedUris
    }

    /**
     * Record and locally cache a video generated externally (e.g. LLM `generate_video`).
     * Returns `file://` URI for the downloaded local file, falling back to [rawSource] on failure.
     */
    suspend fun recordExternalVideoResult(
        prompt: String,
        providerId: String,
        modelId: String,
        rawSource: String,
        paramsJson: String = "{}",
        source: String = MediaGenTaskEntity.SOURCE_TOOL,
    ): String {
        val taskId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.upsertTask(
            MediaGenTaskEntity(
                id = taskId,
                kind = MediaGenTaskEntity.KIND_VIDEO,
                status = MediaGenTaskEntity.STATUS_SUCCEEDED,
                prompt = prompt,
                providerId = providerId,
                modelId = modelId,
                paramsJson = paramsJson,
                source = source,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val stored = storage.persistVideo(rawSource, assetId) ?: return rawSource
        dao.insertAssets(
            listOf(
                MediaGenAssetEntity(
                    id = assetId,
                    taskId = taskId,
                    kind = MediaGenTaskEntity.KIND_VIDEO,
                    localPath = stored.localPath,
                    thumbnailPath = stored.thumbnailPath,
                    remoteUrl = stored.remoteUrl,
                    mimeType = stored.mimeType,
                    width = stored.width,
                    height = stored.height,
                    durationMs = stored.durationMs,
                    prompt = prompt,
                    modelId = modelId,
                    providerId = providerId,
                    paramsJson = paramsJson,
                    createdAt = now,
                ),
            ),
        )
        return "file://${stored.localPath}"
    }

    fun cancelTask(taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        scope.launch {
            dao.updateTaskStatus(
                id = taskId,
                status = MediaGenTaskEntity.STATUS_CANCELLED,
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    suspend fun retryTask(taskId: String): String? {
        val prev = dao.getTaskById(taskId) ?: return null
        val refDataUri = prev.referenceImagePath?.let { storage.readFileAsDataUri(it) }
        return when (prev.kind) {
            MediaGenTaskEntity.KIND_IMAGE -> {
                val parsed = runCatching {
                    AppJson.decodeFromString(StudioImageRequest.serializer(), prev.paramsJson)
                }.getOrDefault(
                    StudioImageRequest(
                        prompt = prev.prompt,
                        providerId = prev.providerId,
                        modelId = prev.modelId,
                    ),
                )
                submitImageTask(parsed.copy(referenceImageUri = refDataUri), source = prev.source)
            }
            MediaGenTaskEntity.KIND_VIDEO -> {
                val parsed = runCatching {
                    AppJson.decodeFromString(StudioVideoRequest.serializer(), prev.paramsJson)
                }.getOrDefault(
                    StudioVideoRequest(
                        prompt = prev.prompt,
                        providerId = prev.providerId,
                        modelId = prev.modelId,
                    ),
                )
                submitVideoTask(parsed.copy(referenceImageUri = refDataUri), source = prev.source)
            }
            else -> null
        }
    }

    suspend fun deleteTask(taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        dao.deleteTask(taskId)
    }

    suspend fun toggleFavorite(assetId: String) {
        val asset = dao.getAssetById(assetId) ?: return
        dao.setAssetFavorite(assetId, !asset.favorite)
    }

    suspend fun deleteAsset(assetId: String) {
        val asset = dao.getAssetById(assetId) ?: return
        storage.deleteFilesFor(asset)
        dao.deleteAsset(assetId)
    }

    private suspend fun runImageJob(taskId: String, request: StudioImageRequest, cachedRefPath: String?) {
        try {
            val provider = settings.getProviderById(request.providerId)
                ?: settings.get()
                ?: error(context.getString(R.string.err_image_gen_no_provider))
            val refInput = request.referenceImageUri
                ?: cachedRefPath?.let { storage.readFileAsDataUri(it) }
            val params = ImageGenParams(
                model = request.modelId,
                size = request.size,
                quality = request.quality,
                style = request.style,
                n = request.n.coerceAtLeast(1),
                referenceImageUri = refInput,
            )
            val urls = imageService.generate(request.prompt, params, provider)
            if (urls.isEmpty()) error(context.getString(R.string.err_image_gen_no_result))

            val now = System.currentTimeMillis()
            val assets = urls.mapNotNull { raw ->
                val assetId = UUID.randomUUID().toString()
                val stored = storage.persistImage(raw, assetId) ?: return@mapNotNull null
                MediaGenAssetEntity(
                    id = assetId,
                    taskId = taskId,
                    kind = MediaGenTaskEntity.KIND_IMAGE,
                    localPath = stored.localPath,
                    remoteUrl = stored.remoteUrl,
                    mimeType = stored.mimeType,
                    width = stored.width,
                    height = stored.height,
                    prompt = request.prompt,
                    modelId = request.modelId,
                    providerId = provider.id,
                    paramsJson = AppJson.encodeToString(StudioImageRequest.serializer(), request.copy(referenceImageUri = null)),
                    createdAt = now,
                )
            }
            if (assets.isEmpty()) error(context.getString(R.string.err_image_gen_no_result))
            dao.insertAssets(assets)
            dao.updateTaskStatus(taskId, MediaGenTaskEntity.STATUS_SUCCEEDED, null, now)
        } catch (e: CancellationException) {
            dao.updateTaskStatus(taskId, MediaGenTaskEntity.STATUS_CANCELLED, null, System.currentTimeMillis())
            throw e
        } catch (e: RuntimeException) {
            Logger.w(TAG, "runImageJob failed taskId=$taskId: ${e.message}")
            dao.updateTaskStatus(
                id = taskId,
                status = MediaGenTaskEntity.STATUS_FAILED,
                errorMessage = e.message ?: context.getString(R.string.err_chat_unknown),
                updatedAt = System.currentTimeMillis(),
            )
        } finally {
            activeJobs.remove(taskId)
        }
    }

    private suspend fun runVideoJob(taskId: String, request: StudioVideoRequest, cachedRefPath: String?) {
        try {
            val provider = settings.getProviderById(request.providerId)
                ?: error(context.getString(R.string.media_studio_no_video_provider))
            val refInput = request.referenceImageUri
                ?: cachedRefPath?.let { storage.readFileAsDataUri(it) }
            val videoReq = VideoGenRequest(
                prompt = request.prompt,
                model = request.modelId,
                duration = request.duration,
                resolution = request.resolution,
                aspectRatio = request.aspectRatio,
                referenceImages = listOfNotNull(refInput?.takeIf { it.isNotBlank() }),
            )
            val videoUrl = videoGenerationService.generateVideo(
                providerConfig = provider,
                request = videoReq,
                onProgress = { elapsedSec ->
                    scope.launch {
                        dao.updateTaskProgress(taskId, elapsedSec.toInt(), System.currentTimeMillis())
                    }
                },
            ).getOrThrow()

            val now = System.currentTimeMillis()
            val assetId = UUID.randomUUID().toString()
            val stored = storage.persistVideo(videoUrl, assetId)
                ?: error(context.getString(R.string.media_studio_video_save_failed))
            dao.insertAssets(
                listOf(
                    MediaGenAssetEntity(
                        id = assetId,
                        taskId = taskId,
                        kind = MediaGenTaskEntity.KIND_VIDEO,
                        localPath = stored.localPath,
                        thumbnailPath = stored.thumbnailPath,
                        remoteUrl = stored.remoteUrl ?: videoUrl,
                        mimeType = stored.mimeType,
                        width = stored.width,
                        height = stored.height,
                        durationMs = stored.durationMs,
                        prompt = request.prompt,
                        modelId = request.modelId,
                        providerId = provider.id,
                        paramsJson = AppJson.encodeToString(StudioVideoRequest.serializer(), request.copy(referenceImageUri = null)),
                        createdAt = now,
                    ),
                ),
            )
            dao.updateTaskStatus(taskId, MediaGenTaskEntity.STATUS_SUCCEEDED, null, now)
        } catch (e: CancellationException) {
            dao.updateTaskStatus(taskId, MediaGenTaskEntity.STATUS_CANCELLED, null, System.currentTimeMillis())
            throw e
        } catch (e: RuntimeException) {
            Logger.w(TAG, "runVideoJob failed taskId=$taskId: ${e.message}")
            dao.updateTaskStatus(
                id = taskId,
                status = MediaGenTaskEntity.STATUS_FAILED,
                errorMessage = e.message ?: context.getString(R.string.err_chat_unknown),
                updatedAt = System.currentTimeMillis(),
            )
        } finally {
            activeJobs.remove(taskId)
        }
    }

    companion object {
        private const val TAG = "MediaGenerationManager"
    }
}
