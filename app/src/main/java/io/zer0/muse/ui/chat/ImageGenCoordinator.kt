package io.zer0.muse.ui.chat

import android.content.Context
import android.net.Uri
import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.UIMessage
import io.zer0.ai.image.ImageService
import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.doc.OcrManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.uuid.Uuid

/**
 * v1.135: 视频附件处理回调类型。
 */
private typealias VideoHandler = io.zer0.muse.ui.chat.VideoAttachmentHandler

/**
 * 选出真正能出图的模型。
 *
 * 优先用调用方指定的模型;它不支持图片输出时,退回该供应商第一个支持图片输出的模型。
 * 返回 null 表示这个供应商没有可出图模型 —— 调用方应报错,而不是把一个文本模型
 * 推给图片接口去拿一个看不懂的上游错误。
 *
 * @param provider 本次绘图使用的供应商(可能为 null,此时无从兜底)
 * @param requested 调用方首选模型(设置里的绘图模型或当前选中模型,可能为 null)
 */
internal fun resolveImageCapableModel(provider: ProviderConfig?, requested: Model?): Model? = requested?.takeIf { it.supportsImageOutput() }
    ?: provider?.models?.firstOrNull { it.supportsImageOutput() }

internal suspend fun readImageContentBase64(uri: Uri, context: Context, onFailure: (Throwable) -> Unit = {}): String =
    withContext(AppDispatchers.io) {
        try {
            val resolver = context.contentResolver
            val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val source = android.graphics.ImageDecoder.createSource(resolver, uri)
                android.graphics.ImageDecoder.decodeBitmap(
                    source,
                    object : android.graphics.ImageDecoder.OnHeaderDecodedListener {
                        override fun onHeaderDecoded(
                            decoder: android.graphics.ImageDecoder,
                            info: android.graphics.ImageDecoder.ImageInfo,
                            src: android.graphics.ImageDecoder.Source,
                        ) {
                            var sample = 1
                            while (info.size.width / sample > IMAGE_INPUT_SCALE_TARGET ||
                                info.size.height / sample > IMAGE_INPUT_SCALE_TARGET
                            ) {
                                sample *= 2
                            }
                            decoder.setTargetSampleSize(sample)
                        }
                    },
                )
            } else {
                @Suppress("DEPRECATION")
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(input, null, bounds)
                }
                var sample = 1
                while (bounds.outWidth / sample > IMAGE_INPUT_SCALE_TARGET ||
                    bounds.outHeight / sample > IMAGE_INPUT_SCALE_TARGET
                ) {
                    sample *= 2
                }
                val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                resolver.openInputStream(uri)?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(input, null, options)
                }
            } ?: return@withContext ""

            try {
                val bytes = java.io.ByteArrayOutputStream().use { output ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, IMAGE_INPUT_JPEG_QUALITY, output)
                    output.toByteArray()
                }
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            } finally {
                bitmap.recycle()
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: IOException) {
            Logger.w("ImageGenCoordinator", "图片读取失败: ${error.message}", error)
            onFailure(error)
            ""
        } catch (error: SecurityException) {
            Logger.w("ImageGenCoordinator", "图片读取失败: ${error.message}", error)
            onFailure(error)
            ""
        } catch (error: IllegalArgumentException) {
            Logger.w("ImageGenCoordinator", "图片读取失败: ${error.message}", error)
            onFailure(error)
            ""
        }
    }

private const val IMAGE_INPUT_SCALE_TARGET = 1024
private const val IMAGE_INPUT_JPEG_QUALITY = 85

/**
 * v1.105 阶段 1: 从 ChatViewModel 抽离的图片相关 Coordinator。
 *
 * 职责:
 *  - pickImage: 相册选图(视觉输入 base64 / OCR 识别文字)
 *  - removePendingImage: 移除待发送图片
 *  - readImageAsBase64: 内部工具方法
 *  - generateImage: 绘图入口(按 Provider 分支)
 *  - generateImageViaOpenAi / generateImageViaGemini: 各 Provider 绘图实现
 *
 * 不持有 state,通过 [accessor] 读写 ChatViewModel 的 state;
 * 持有 [imageJob] 控制单次绘图任务(可被取消)。
 *
 * 公开方法签名与原 ChatViewModel 完全一致,UI 层零改动。
 */
class ImageGenCoordinator(
    private val accessor: ChatStateAccessor,
    private val imageService: ImageService,
    private val settings: SettingsRepository,
    private val sessionRepository: SessionRepository,
    private val ocrManager: OcrManager,
    @Suppress("UNUSED_PARAMETER") private val chatService: ChatService,
    private val appContext: Context,
    private val mediaGenerationManager: io.zer0.muse.data.media.MediaGenerationManager? = null,
) {

    private val tag = "ChatVM"

    /** 当前图片生成任务(可被新任务取消)。 */
    private var imageJob: Job? = null

    /** 多选相册图片会并发读取,用锁保证 pendingImages 追加不会互相覆盖。 */
    private val pendingImageMutex = Mutex()

    /**
     * F-09: 当前媒体任务 id(每次生成分配独立 UUID)。
     * 旧任务被取消后其 finally 不得覆盖新任务状态(isGeneratingImage 竞态守卫)。
     */
    private var currentMediaTaskId: String? = null

    companion object {
        /**
         * 单次待发送图片上限。
         *
         * 旧值是硬编码的 4（写在判断里），一次只能选四张，想多发得反复打开相册。
         * 提到 10 的原因：
         *  - 内存：单张压缩到长边 1024px / JPEG 85，约 200-400KB（base64 后），
         *    10 张约 2-4MB，在可接受范围；
         *  - 上游：多数视觉模型单次支持 10-20 张，取 10 能覆盖主流模型，
         *    再高会撞到部分模型的单请求上限，用户看到的是发送失败而不是“选多了”。
         *
         * 相册选择器的多选上限与本值保持一致，避免“选得到但发不了”。
         */
        const val MAX_PENDING_IMAGES = 10
    }

    /**
     * Phase 8.6: 选取图片(相册 / OCR)。
     *
     * @param asOcr true=OCR 识别文字追加到输入框;false=视觉输入模式,读 base64 加入 pendingImages
     */
    fun pickImage(uri: Uri, context: Context, asOcr: Boolean, reportError: (String) -> Unit, addError: (ChatErrorType, String) -> Unit) {
        if (accessor.snapshot.isStreaming) return
        accessor.coroutineScope.launch(AppDispatchers.io) {
            try {
                if (asOcr) {
                    // v1.0.4 (P0): OCR 识别期间设置 isOcrProcessing=true,UI 显示"正在识别图片文字…"
                    accessor.update { it.copy(isOcrProcessing = true) }
                    try {
                        val text = ocrManager.recognize(uri, context)
                        if (text.isBlank()) {
                            reportError(context.getString(R.string.err_image_gen_ocr_failed))
                            return@launch
                        }
                        // 原子读取最新输入，避免 OCR 完成前用户输入被旧快照覆盖。
                        accessor.appendInputAtomically(text)
                    } finally {
                        accessor.update { it.copy(isOcrProcessing = false) }
                    }
                } else {
                    val base64 = readImageAsBase64(uri, context, addError)
                    if (base64.isBlank()) {
                        reportError(context.getString(R.string.err_image_gen_read_failed))
                        return@launch
                    }
                    pendingImageMutex.withLock {
                        if (accessor.snapshot.pendingImages.size >= MAX_PENDING_IMAGES) {
                            reportError(context.getString(R.string.err_image_gen_max_images))
                        } else {
                            accessor.update { state ->
                                state.copy(pendingImages = state.pendingImages + base64)
                            }
                        }
                    }
                }
            } catch (t: Exception) {
                Logger.e(tag, "pickImage failed", t)
                reportError(context.getString(R.string.err_image_gen_process_failed, t.message ?: ""))
            }
        }
    }

    /**
     * v1.135: 选取视频并提取关键帧,作为多张图片加入 pendingImages。
     *
     * 当前实现:对不支持视频直传的 Provider,把视频降级为 3 张关键帧图片发送,
     *          让视觉模型"看到"视频内容。后续可扩展为视频直传。
     */
    fun pickVideo(uri: Uri, context: Context, reportError: (String) -> Unit) {
        if (accessor.snapshot.isStreaming) return
        accessor.coroutineScope.launch(AppDispatchers.io) {
            try {
                // 1. 提取关键帧(首帧/中间/末帧)
                val keyFrames = VideoHandler().extractKeyFrames(uri, context, frameCount = 3)
                if (keyFrames.isEmpty()) {
                    reportError(context.getString(R.string.err_image_gen_video_no_frames))
                    return@launch
                }
                // 2. 检查待发送图片上限(加上关键帧后不超过 4 张)
                val current = accessor.snapshot.pendingImages
                if (current.size + keyFrames.size > 4) {
                    reportError(context.getString(R.string.err_image_gen_video_too_many, keyFrames.size, current.size))
                    return@launch
                }
                accessor.update { it.copy(pendingImages = current + keyFrames) }
                Logger.i(tag, "pickVideo: 已提取 ${keyFrames.size} 张关键帧加入 pendingImages")
            } catch (t: Exception) {
                Logger.e(tag, "pickVideo failed", t)
                reportError(context.getString(R.string.err_image_gen_video_failed, t.message ?: ""))
            }
        }
    }

    /** Phase 8.6: 移除指定索引的待发送图片。 */
    fun removePendingImage(index: Int) {
        val current = accessor.snapshot.pendingImages
        if (index < 0 || index >= current.size) return
        accessor.update { it.copy(pendingImages = current.toMutableList().also { list -> list.removeAt(index) }) }
    }

    /** Phase 8.6: 把图片 URI 读为 base64(无 data: 前缀)。 */
    private suspend fun readImageAsBase64(uri: Uri, context: Context, addError: (ChatErrorType, String) -> Unit): String {
        return readImageContentBase64(uri, context) { error ->
            addError(
                ChatErrorType.UNKNOWN,
                context.getString(
                    R.string.err_image_gen_read_failed_msg,
                    error.message ?: context.getString(R.string.err_chat_unknown),
                ),
            )
        }
    }

    /** Cancel any active Draw Mode image generation job and clear loading flag. */
    fun cancelImageGeneration() {
        imageJob?.cancel()
        imageJob = null
        currentMediaTaskId = null
        accessor.update { it.copy(isGeneratingImage = false) }
    }

    /**
     * P5-G + Phase 10.2: 生成图片。
     *
     * 按 Provider 类型分支:
     *  - OpenAI 兼容: 走 ImageService(独立 /images/generations 端点,返回 URL)
     *  - Gemini:      走 streamChat 多模态路径(responseModalities 含 image,收集 ImageDelta base64)
     *  - Anthropic:   不支持
     *
     * [updateAssistant] 由 ChatViewModel 提供(因为该方法内部调用了 extractThinkContent 等流式相关逻辑,
     * 与流式 Coordinator 强耦合,留在 ViewModel 中转调)。
     */
    fun generateImage(
        prompt: String,
        sessionId: String,
        addError: (ChatErrorType, String) -> Unit,
        @Suppress("UNUSED_PARAMETER")
        updateAssistant: (
            id: Uuid,
            content: String,
            reasoning: String?,
            imageBase64List: List<String>?,
            imageUrls: List<String>?,
            isStreaming: Boolean,
        ) -> Unit,
    ) {
        val userMsg = UIMessage(role = MessageRole.USER, content = "[绘图] $prompt")
        val assistantMsg = UIMessage(role = MessageRole.ASSISTANT, content = "")
        accessor.updateMessages { it + userMsg + assistantMsg }
        accessor.update {
            it.copy(
                input = "",
                isGeneratingImage = true,
                errors = emptyList(),
            )
        }
        accessor.coroutineScope.launch {
            sessionRepository.appendMessage(sessionId, userMsg)
        }
        imageJob?.cancel()
        // F-09: 独立媒体任务 id 管理生成生命周期 — 消息只引用结果,
        // 状态更新(isGeneratingImage)以当前任务为准, 旧任务 finally 不得覆盖新任务
        val mediaTaskId = kotlin.uuid.Uuid.random().toString()
        currentMediaTaskId = mediaTaskId
        Logger.i(tag, "绘图任务开始 mediaTask=$mediaTaskId | prompt=${prompt.take(60)}")
        imageJob = accessor.coroutineScope.launch(AppDispatchers.io) {
            try {
                val imageGenConfig = settings.imageGenConfigFlow.first()
                val providers = settings.providersFlow.first()
                // v1.0.18: 放宽绘图供应商检查 — OpenAI / OpenAI Responses / Agnes / 任意 OpenAI 兼容
                // 均可通过 ImageProviderRegistry 路由;Gemini 走 streamChat 多模态路径。
                val hasImageProvider = providers.any {
                    it.type == ProviderType.OPENAI ||
                        it.type == ProviderType.OPENAI_RESPONSES ||
                        it.type == ProviderType.GEMINI ||
                        it.baseUrl.contains("agnes", ignoreCase = true)
                }
                if (!hasImageProvider) {
                    if (currentMediaTaskId == mediaTaskId) {
                        accessor.update {
                            it.copy(
                                errors =
                                listOf(
                                    ChatError(
                                        type = ChatErrorType.UNKNOWN,
                                        message =
                                        appContext.getString(
                                            R.string.err_image_gen_no_provider,
                                        ),
                                    ),
                                ),
                                isGeneratingImage = false,
                            )
                        }
                    }
                    return@launch
                }

                val explicitProvider = if (imageGenConfig.providerId.isNotBlank()) {
                    settings.getProviderById(imageGenConfig.providerId)
                } else {
                    null
                }

                val config = explicitProvider ?: settings.get()
                val requestedModel = if (explicitProvider != null) {
                    explicitProvider.models.firstOrNull { it.id == imageGenConfig.modelId }
                } else {
                    settings.getSelectedModel()
                }
                val model = resolveImageCapableModel(config, requestedModel)
                if (requestedModel != null && model != null && requestedModel.id != model.id) {
                    Logger.w(tag, "图片模型 ${requestedModel.id} 不支持图片输出,改用 ${model.id}")
                }

                if (config == null) {
                    if (currentMediaTaskId == mediaTaskId) {
                        accessor.update {
                            it.copy(
                                errors =
                                listOf(
                                    ChatError(
                                        type = ChatErrorType.UNKNOWN,
                                        message =
                                        appContext.getString(
                                            R.string.err_image_gen_no_provider,
                                        ),
                                    ),
                                ),
                                isGeneratingImage = false,
                            )
                        }
                    }
                    return@launch
                }
                if (model == null) {
                    if (currentMediaTaskId == mediaTaskId) {
                        accessor.update {
                            it.copy(
                                errors =
                                listOf(
                                    ChatError(
                                        type = ChatErrorType.UNKNOWN,
                                        message =
                                        appContext.getString(
                                            R.string.err_image_gen_model_no_output,
                                        ),
                                    ),
                                ),
                                isGeneratingImage = false,
                            )
                        }
                    }
                    return@launch
                }

                // All image providers (OpenAI / Gemini / Agnes / custom compatible endpoints) route
                // through ImageService -> ImageProviderRegistry and record local assets cleanly.
                generateImageViaService(prompt, sessionId, assistantMsg.id, config, model, addError)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Exception) {
                Logger.e(tag, "image gen failed mediaTask=$mediaTaskId", t)
                if (currentMediaTaskId == mediaTaskId) {
                    accessor.update {
                        it.copy(
                            errors =
                            listOf(
                                ChatError(
                                    type = ChatErrorType.UNKNOWN,
                                    message =
                                    appContext.getString(
                                        R.string.err_image_gen_failed,
                                        t.message
                                            ?: appContext.getString(R.string.err_chat_unknown),
                                    ),
                                ),
                            ),
                            isGeneratingImage = false,
                        )
                    }
                }
            } finally {
                // 9.1 修复: 任何路径下都重置 isGeneratingImage,避免错误路径早返回时 loading 卡死
                // F-09: 仅当前任务可重置 — 旧任务 finally 不得覆盖新任务的 isGeneratingImage
                if (currentMediaTaskId == mediaTaskId) {
                    accessor.update { it.copy(isGeneratingImage = false) }
                }
            }
        }
    }

    /** Unified image generation path via ImageService + local Media Studio persistence. */
    private suspend fun generateImageViaService(
        prompt: String,
        sessionId: String,
        assistantId: Uuid,
        providerConfig: ProviderConfig,
        model: Model,
        addError: (ChatErrorType, String) -> Unit,
    ) {
        val rawUrls = imageService.generate(
            prompt,
            accessor.snapshot.imageGenParams.copy(model = model.id),
            providerConfig,
        )
        if (rawUrls.isEmpty()) {
            accessor.update {
                it.copy(
                    errors = listOf(
                        ChatError(type = ChatErrorType.UNKNOWN, message = appContext.getString(R.string.err_image_gen_no_result)),
                    ),
                    isGeneratingImage = false,
                )
            }
            return
        }
        val urls = mediaGenerationManager?.recordExternalImageResult(
            prompt = prompt,
            providerId = providerConfig.id,
            modelId = model.id,
            rawSources = rawUrls,
            source = io.zer0.muse.data.media.MediaGenTaskEntity.SOURCE_CHAT,
        )?.takeIf { it.isNotEmpty() } ?: rawUrls
        val md = urls.joinToString("\n\n") { "![]($it)" }
        accessor.updateMessages { messages ->
            messages.map { msg ->
                if (msg.id == assistantId) {
                    msg.copy(content = md, imageUrls = urls)
                } else {
                    msg
                }
            }
        }
        accessor.update {
            it.copy(
                isGeneratingImage = false,
                imageGenParams = it.imageGenParams.copy(referenceImageUri = null),
            )
        }
        val finalAssistant = accessor.messagesSnapshot.firstOrNull { it.id == assistantId }
        if (finalAssistant != null) {
            try {
                sessionRepository.upsertMessage(sessionId, finalAssistant)
            } catch (e: Exception) {
                Logger.e(tag, "generateImageViaService upsertMessage failed", e)
                addError(
                    ChatErrorType.UNKNOWN,
                    appContext.getString(R.string.err_image_gen_save_failed, e.message ?: appContext.getString(R.string.err_chat_unknown)),
                )
            }
        }
    }
}
