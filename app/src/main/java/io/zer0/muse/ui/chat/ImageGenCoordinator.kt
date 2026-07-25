package io.zer0.muse.ui.chat

import android.content.Context
import android.net.Uri
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.UIMessage
import io.zer0.ai.image.ImageService
import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.R
import io.zer0.muse.doc.OcrManager
import io.zer0.muse.ui.ChatError
import io.zer0.muse.ui.ChatErrorType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * v1.135: 视频附件处理回调类型。
 */
private typealias VideoHandler = io.zer0.muse.ui.chat.VideoAttachmentHandler

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
    private val chatService: ChatService,
    private val appContext: Context,
) {

    private val tag = "ChatVM"

    /** 当前图片生成任务(可被新任务取消)。 */
    private var imageJob: Job? = null

    companion object {
        /** 图片缩放最长边(视觉模型不需要超清,控制 base64 体积)。 */
        private const val IMAGE_SCALE_TARGET = 1024
        /** 图片 JPEG 压缩质量(0-100)。 */
        private const val IMAGE_JPEG_QUALITY = 85
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
                        val current = accessor.snapshot.input
                        val merged = if (current.isBlank()) text else "$current\n\n$text"
                        accessor.update { it.copy(input = merged) }
                    } finally {
                        accessor.update { it.copy(isOcrProcessing = false) }
                    }
                } else {
                    val base64 = readImageAsBase64(uri, context, addError)
                    if (base64.isBlank()) {
                        reportError(context.getString(R.string.err_image_gen_read_failed))
                        return@launch
                    }
                    val current = accessor.snapshot.pendingImages
                    if (current.size >= 4) {
                        reportError(context.getString(R.string.err_image_gen_max_images))
                        return@launch
                    }
                    accessor.update { it.copy(pendingImages = current + base64) }
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
        return withContext(AppDispatchers.io) {
            runCatching {
                val resolver = context.contentResolver
                val target = IMAGE_SCALE_TARGET

                val bitmap: android.graphics.Bitmap? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val source = android.graphics.ImageDecoder.createSource(resolver, uri)
                    android.graphics.ImageDecoder.decodeBitmap(source,
                        object : android.graphics.ImageDecoder.OnHeaderDecodedListener {
                            override fun onHeaderDecoded(
                                decoder: android.graphics.ImageDecoder,
                                info: android.graphics.ImageDecoder.ImageInfo,
                                src: android.graphics.ImageDecoder.Source,
                            ) {
                                val w = info.size.width
                                val h = info.size.height
                                var sample = 1
                                while (w / sample > target || h / sample > target) sample *= 2
                                decoder.setTargetSampleSize(sample)
                            }
                        },
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use { input ->
                        android.graphics.BitmapFactory.decodeStream(input, null, opts)
                    }
                    var sample = 1
                    while (opts.outWidth / sample > target || opts.outHeight / sample > target) sample *= 2
                    val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    resolver.openInputStream(uri)?.use { input ->
                        android.graphics.BitmapFactory.decodeStream(input, null, decodeOpts)
                    }
                }

                if (bitmap == null) {
                    Logger.e(tag, "readImageAsBase64: bitmap is null, uri=$uri")
                    return@runCatching ""
                }

                val baos = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, baos)
                bitmap.recycle()
                val result = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                Logger.i(tag, "readImageAsBase64: success, base64 length=${result.length}")
                result
            }.getOrElse { e ->
                Logger.e(tag, "readImageAsBase64 failed", e)
                addError(ChatErrorType.UNKNOWN, context.getString(R.string.err_image_gen_read_failed_msg, e.message ?: context.getString(R.string.err_chat_unknown)))
                ""
            }
        }
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
        updateAssistant: (
            id: Uuid, content: String, reasoning: String?, imageBase64List: List<String>?,
            imageUrls: List<String>?, isStreaming: Boolean,
        ) -> Unit,
    ) {
        val userMsg = UIMessage(role = MessageRole.USER, content = "[绘图] $prompt")
        val assistantMsg = UIMessage(role = MessageRole.ASSISTANT, content = "")
        accessor.update {
            it.copy(
                messages = it.messages + userMsg + assistantMsg,
                input = "",
                isGeneratingImage = true,
                errors = emptyList(),
            )
        }
        accessor.coroutineScope.launch {
            sessionRepository.appendMessage(sessionId, userMsg)
        }
        imageJob?.cancel()
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
                    accessor.update {
                        it.copy(
                            errors = listOf(ChatError(type = ChatErrorType.UNKNOWN, message = appContext.getString(R.string.err_image_gen_no_provider))),
                            isGeneratingImage = false,
                        )
                    }
                    return@launch
                }

                val explicitProvider = if (imageGenConfig.providerId.isNotBlank()) {
                    settings.getProviderById(imageGenConfig.providerId)
                } else null

                val config = explicitProvider ?: settings.get()
                val model = if (explicitProvider != null) {
                    if (imageGenConfig.modelId.isNotBlank()) {
                        explicitProvider.models.firstOrNull { it.id == imageGenConfig.modelId }
                            ?: explicitProvider.models.firstOrNull()
                    } else {
                        explicitProvider.models.firstOrNull()
                    }
                } else {
                    settings.getSelectedModel()
                }

                if (config == null) {
                    accessor.update {
                        it.copy(
                            errors = listOf(ChatError(type = ChatErrorType.UNKNOWN, message = appContext.getString(R.string.err_image_gen_no_provider))),
                            isGeneratingImage = false,
                        )
                    }
                    return@launch
                }
                if (model == null) {
                    accessor.update {
                        it.copy(
                            errors = listOf(ChatError(type = ChatErrorType.UNKNOWN, message = appContext.getString(R.string.err_image_gen_no_model))),
                            isGeneratingImage = false,
                        )
                    }
                    return@launch
                }

                // v1.0.18: Gemini 走 streamChat 多模态;其余(OpenAI / Agnes / 中转站)统一走
                // ImageService → ImageProviderRegistry,由 registry 按配置自动选择适配器。
                when (config.type) {
                    ProviderType.GEMINI -> {
                        if (!model.supportsImageOutput()) {
                            accessor.update {
                                it.copy(
                                    errors = listOf(ChatError(type = ChatErrorType.UNKNOWN, message = appContext.getString(R.string.err_image_gen_model_no_output))),
                                    isGeneratingImage = false,
                                )
                            }
                            return@launch
                        }
                        generateImageViaGemini(prompt, sessionId, assistantMsg.id, config, model, addError, updateAssistant)
                    }
                    else -> {
                        generateImageViaOpenAi(prompt, sessionId, assistantMsg.id, config, model, addError)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Exception) {
                Logger.e(tag, "image gen failed", t)
                accessor.update {
                    it.copy(
                        errors = listOf(ChatError(type = ChatErrorType.UNKNOWN, message = appContext.getString(R.string.err_image_gen_failed, t.message ?: appContext.getString(R.string.err_chat_unknown)))),
                        isGeneratingImage = false,
                    )
                }
            } finally {
                // 9.1 修复: 任何路径下都重置 isGeneratingImage,避免 Gemini 返回空图片列表等
                // 错误路径早返回时 loading 卡死(成功/失败/取消均会触发 finally)
                accessor.update { it.copy(isGeneratingImage = false) }
            }
        }
    }

    /** Phase 10.2: OpenAI 兼容绘图(走 ImageService 独立端点)。 */
    private suspend fun generateImageViaOpenAi(
        prompt: String,
        sessionId: String,
        assistantId: Uuid,
        providerConfig: ProviderConfig,
        model: Model,
        addError: (ChatErrorType, String) -> Unit,
    ) {
        // v1.136: 把 UI 选中的绘图模型 ID 传入 ImageService,避免 model 为空导致自定义端点 404
        val urls = imageService.generate(
            prompt,
            accessor.snapshot.imageGenParams.copy(model = model.id),
            providerConfig,
        )
        if (urls.isEmpty()) {
            accessor.update {
                it.copy(
                    errors = listOf(ChatError(type = ChatErrorType.UNKNOWN, message = appContext.getString(R.string.err_image_gen_no_result))),
                    isGeneratingImage = false,
                )
            }
            return
        }
        val md = urls.joinToString("\n\n") { "![]($it)" }
        accessor.update {
            it.copy(
                messages = it.messages.map { msg ->
                    if (msg.id == assistantId) msg.copy(content = md, imageUrls = urls)
                    else msg
                },
                isGeneratingImage = false,
                imageGenParams = it.imageGenParams.copy(referenceImageUri = null),
            )
        }
        val finalAssistant = accessor.snapshot.messages.firstOrNull { it.id == assistantId }
        if (finalAssistant != null) {
            try {
                sessionRepository.upsertMessage(sessionId, finalAssistant)
            } catch (e: Exception) {
                Logger.e(tag, "generateImageViaOpenAi upsertMessage failed", e)
                addError(ChatErrorType.UNKNOWN, appContext.getString(R.string.err_image_gen_save_failed, e.message ?: appContext.getString(R.string.err_chat_unknown)))
            }
        }
    }

    /** Phase 10.2: Gemini 绘图(走 streamChat 多模态路径,收集 ImageDelta base64)。 */
    private suspend fun generateImageViaGemini(
        prompt: String,
        sessionId: String,
        assistantId: Uuid,
        providerConfig: ProviderConfig,
        model: Model,
        addError: (ChatErrorType, String) -> Unit,
        updateAssistant: (
            id: Uuid, content: String, reasoning: String?, imageBase64List: List<String>?,
            imageUrls: List<String>?, isStreaming: Boolean,
        ) -> Unit,
    ) {
        val drawMessages = listOf(
            UIMessage(role = MessageRole.USER, content = "请生成一张图片: $prompt")
        )
        val flow = chatService.streamChat(
            messages = drawMessages,
            model = model,
            tools = null,
            temperature = null,
            maxTokens = null,
            providerConfig = providerConfig,
        )
        val textBuilder = StringBuilder()
        val imageBase64List = mutableListOf<String>()
        val imageMimeTypes = mutableListOf<String>()
        flow.collect { event ->
            when (event) {
                is ChatStreamEvent.ContentDelta -> {
                    textBuilder.append(event.delta)
                    updateAssistant(assistantId, textBuilder.toString(), null, null, null, true)
                }
                is ChatStreamEvent.ImageDelta -> {
                    imageBase64List.add(event.imageBase64)
                    imageMimeTypes.add(event.mimeType.ifBlank { "image/png" })
                    val mime = event.mimeType.ifBlank { "image/png" }
                    val dataUri = "data:$mime;base64,${event.imageBase64}"
                    val currentMsg = accessor.snapshot.messages.firstOrNull { it.id == assistantId }
                    val currentUrls = currentMsg?.imageUrls ?: emptyList()
                    updateAssistant(
                        assistantId,
                        textBuilder.toString(),
                        null,
                        imageBase64List.toList(),
                        currentUrls + dataUri,
                        true,
                    )
                }
                is ChatStreamEvent.Error -> {
                    throw event.throwable ?: RuntimeException(event.message)
                }
                else -> {}
            }
        }
        if (imageBase64List.isEmpty()) {
            accessor.update {
                it.copy(
                    messages = it.messages.map { msg ->
                        if (msg.id == assistantId) msg.copy(content = textBuilder.toString()) else msg
                    },
                    errors = listOf(ChatError(type = ChatErrorType.UNKNOWN, message = appContext.getString(R.string.err_image_gen_no_image))),
                    isGeneratingImage = false,
                )
            }
            val finalAssistant = accessor.snapshot.messages.firstOrNull { it.id == assistantId }
            if (finalAssistant != null) {
                sessionRepository.upsertMessage(sessionId, finalAssistant)
            }
            return
        }
        val mdImages = imageBase64List.mapIndexed { idx, b64 ->
            val mime = imageMimeTypes.getOrNull(idx) ?: "image/png"
            "![](data:$mime;base64,$b64)"
        }.joinToString("\n\n")
        val finalContent = when {
            textBuilder.isNotBlank() && mdImages.isNotBlank() -> "${textBuilder}\n\n$mdImages"
            mdImages.isNotBlank() -> mdImages
            else -> textBuilder.toString()
        }
        accessor.update {
            it.copy(
                messages = it.messages.map { msg ->
                    if (msg.id == assistantId) msg.copy(content = finalContent) else msg
                },
                isGeneratingImage = false,
            )
        }
        val finalAssistant = accessor.snapshot.messages.firstOrNull { it.id == assistantId }
        if (finalAssistant != null) {
            try {
                sessionRepository.upsertMessage(sessionId, finalAssistant)
            } catch (e: Exception) {
                Logger.e(tag, "generateImageViaGemini upsertMessage failed", e)
                addError(ChatErrorType.UNKNOWN, appContext.getString(R.string.err_image_gen_save_failed, e.message ?: appContext.getString(R.string.err_chat_unknown)))
            }
        }
    }
}
