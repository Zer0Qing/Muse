package io.zer0.muse.tools

import android.content.Context
import io.zer0.ai.image.ImageGenParams
import io.zer0.ai.image.ImageService
import io.zer0.ai.video.VideoGenRequest
import io.zer0.ai.video.VideoGenerationService
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.qrcode.QrCodeGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** P2-23: 媒体生成类型。 */
enum class MediaGenKind { IMAGE, VIDEO, QR_CODE }

/**
 * P2-23: 一次媒体生成的结果(供 [MediaGenSlot] 写入会话消息)。
 */
sealed interface MediaGenResult {
    /** 生成成功。[imageUrls] 为原始 URL / data URI;[videoFileUri] 为视频地址。 */
    data class Success(
        val imageUrls: List<String> = emptyList(),
        val videoFileUri: String? = null,
    ) : MediaGenResult

    /** 生成失败;[message] 只含原因,前缀文案由宿主按 [MediaGenKind] 套本地化模板。 */
    data class Failure(val message: String) : MediaGenResult

    /** 本轮生成被取消(工具协程被取消)。 */
    data object Cancelled : MediaGenResult
}

/**
 * P2-23: 一次媒体生成的会话投递句柄。
 *
 * 由 [MediaGenHost.begin] 在生成开始前锚定目标会话消息,生成结束后由
 * [MediaGenToolsImpl] 调用 [report] 写入结果。
 */
interface MediaGenSlot {
    /**
     * 本轮生成是否仍然有效。
     *
     * 用户发送新消息 / 切会话会使代际令牌变化,晚到的生成结果不得写进错误消息。
     */
    fun isActive(): Boolean

    /** 长任务进度回调(视频生成每 5 秒一次)。 */
    suspend fun onProgress(elapsedSeconds: Int)

    /** 把生成结果写入锚定的会话消息。 */
    suspend fun report(result: MediaGenResult)
}

/**
 * P2-23: 媒体生成结果的会话投递宿主。
 *
 * 有 UI 的对话进程由 ChatViewModel 安装实现(把结果追加到当前助手消息并落库);
 * 定时任务 / 群聊 / 子代理等无 UI 进程不安装宿主,[MediaGenToolsImpl] 退化为
 * 把结果落到应用缓存文件或直接返回 URL,由模型转述,不再依赖 UI 才能注册/执行。
 */
interface MediaGenHost {
    /**
     * 生成开始:宿主展示"正在生成"占位并返回投递句柄。
     *
     * 当前没有可写入的会话消息(无 UI 进程 / 无活跃生成)时返回 null。
     */
    suspend fun begin(kind: MediaGenKind): MediaGenSlot?

    /** 生成结束(成功/失败/取消):宿主清理占位状态。 */
    suspend fun finish(kind: MediaGenKind)
}

/**
 * P2-23: 媒体生成工具实现(图片 / 视频 / 二维码),与 ViewModel 无关。
 *
 * 从 ChatViewModel 的 `execGenerateImage / execGenerateVideo / execGenerateQrCode` 迁出的
 * 工具实现,由 [MediaGenToolsRegistrar] 在 App 启动时注册进 [ToolRegistry],因此
 * 定时任务、群聊、子代理等无 UI 链路同样可用。
 *
 * 依赖只保留生成真正需要的东西:
 *  - [settings]:绘图/视频生成的持久化配置(图片参数、供应商、模型)与供应商列表;
 *  - [imageService] / [videoGenerationService]:实际生成调用;
 *  - [context]:本地化文案与无 UI 时的结果落盘目录。
 *
 * 原先从 `ChatViewModel._state` 读取的会话内临时状态(绘图参数、参考图)改为读持久化配置:
 *  - 尺寸/质量/风格/数量/返回格式 → `SettingsRepository.imageGenConfigFlow`([ImageGenConfig]);
 *  - 参考图只取工具参数 —— 相册临时选择不持久化,不跨进程传递。
 *
 * 会话消息写入通过 [MediaGenHost] 端口交由 UI 侧实现,本类不感知消息模型。
 */
class MediaGenToolsImpl(
    private val context: Context,
    private val settings: SettingsRepository,
    private val imageService: ImageService,
    private val videoGenerationService: VideoGenerationService,
) {

    /** UI 投递宿主;无 UI 进程保持 null(结果以文件路径/URL 返回)。 */
    @Volatile
    private var host: MediaGenHost? = null

    /** 安装/卸载会话投递宿主(仅 UI 对话进程调用)。 */
    fun installHost(host: MediaGenHost?) {
        this.host = host
    }

    /**
     * 根据用户描述生成图片。
     *
     * 模型选择优先级:args.model → 持久化的 imageGenConfig.modelId →
     * provider 模型列表中 outputModalities 含 image 的模型(留空由 ImageService 兜底)。
     */
    suspend fun execGenerateImage(args: Map<String, String>): String {
        val prompt = args["prompt"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: prompt"
        // P2-23: 原实现在缺失参数时回退 ChatViewModel._state.imageGenParams(会话内临时覆盖),
        // 现改为回退「设置→图片生成」的持久化默认值,后台进程同样可读。
        val genConfig = settings.imageGenConfigFlow.first()
        val size = args["size"]?.takeIf { it.isNotBlank() } ?: genConfig.size
        val quality = args["quality"]?.takeIf { it.isNotBlank() } ?: genConfig.quality
        val style = args["style"]?.takeIf { it.isNotBlank() } ?: genConfig.style
        val n = args["n"]?.toIntOrNull()?.coerceAtLeast(1) ?: genConfig.n
        // v1.0.18: 参考图(图生图),支持 URL / base64 / data URI。仅取工具参数 —
        // 用户在相册里选的临时参考图不持久化,无 UI 进程也无从注入。
        val referenceImage = args["reference_image"]?.takeIf { it.isNotBlank() }
        val slot = host?.begin(MediaGenKind.IMAGE)

        return try {
            val providerConfig = genConfig.providerId.takeIf { it.isNotBlank() }
                ?.let { settings.getProviderById(it) }
                ?: settings.get()
                ?: return "未配置图片生成供应商,请先添加支持绘图的 Provider(如 OpenAI / Agnes)"
            if (providerConfig.apiKey.isBlank() && !providerConfig.allowMissingApiKey) {
                return "图片生成供应商的 API Key 为空"
            }
            val model = args["model"]?.takeIf { it.isNotBlank() }
                ?: genConfig.modelId.takeIf { it.isNotBlank() }
                ?: providerConfig.models.firstOrNull { it.supportsImageOutput() }?.id
                ?: ""
            val params = ImageGenParams(
                model = model,
                size = size,
                quality = quality,
                style = style,
                responseFormat = genConfig.responseFormat,
                n = n,
                referenceImageUri = referenceImage,
            )
            val urls = imageService.generate(prompt, params, providerConfig)
            if (urls.isEmpty()) {
                slot.safeReport(MediaGenResult.Failure("未返回结果"))
                return "图片生成失败: 未返回结果"
            }
            // A-13: 图片生成可达数十秒,期间可能已发新消息 — 代际令牌失效时媒体不得写入任何消息
            if (slot != null && !slot.isActive()) {
                Logger.w(TAG, "A-13: 图片生成完成但生成已失效(新一轮生成),跳过媒体写入")
                return "图片已生成,但当前生成已失效,结果未展示(如需可重新请求)"
            }
            if (slot != null) {
                slot.safeReport(MediaGenResult.Success(imageUrls = urls))
                // v1.0.75 fix (用户反馈): 返回给模型的字符串不再包含 URL —
                //   图片已渲染进对话,模型复述 URL 只会造成"聊天页里塞链接"的体验。
                "图片已生成并展示在对话中"
            } else {
                // P2-23: 无 UI 会话(定时任务/群聊/子代理)——媒体不进对话,把可访问位置给模型转述
                "图片已生成(当前无对话界面,未展示),结果位置:\n" +
                    externalizeMedia(urls, "img").joinToString("\n")
            }
        } catch (e: CancellationException) {
            slot.safeReport(MediaGenResult.Cancelled)
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: context.getString(R.string.err_chat_unknown)
            slot.safeReport(MediaGenResult.Failure(msg))
            "图片生成失败: $msg"
        } finally {
            safeFinish(MediaGenKind.IMAGE)
        }
    }

    /**
     * 根据用户描述生成短视频。
     *
     * 动态选择已配置且支持视频输出的供应商/模型:
     *  - 若 args 显式指定 provider_id,优先使用该供应商;
     *  - 若 args 显式指定 model,优先使用包含该模型的供应商;
     *  - 否则优先使用持久化的 VideoGenConfig.providerId;
     *  - 再否则自动选择第一个支持视频输出的模型。
     */
    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    suspend fun execGenerateVideo(args: Map<String, String>): String {
        val prompt = args["prompt"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: prompt"
        val duration = args["duration"]?.toIntOrNull()?.let { if (it == 5 || it == 10) it else 5 } ?: 5
        val resolution = args["resolution"]?.takeIf { it.isNotBlank() } ?: "720p"
        val requestedModelId = args["model"]?.takeIf { it.isNotBlank() }
        val requestedProviderId = args["provider_id"]?.takeIf { it.isNotBlank() }
        val referenceImages = parseReferenceImages(args["reference_images"])

        val providers = settings.getAllProviders().filter { it.enabled && it.apiKey.isNotBlank() }
        // 读取用户在"设置→视频生成"配置的默认供应商/模型(LLM 未显式指定时优先使用)
        val videoGenConfig = settings.videoGenConfigFlow.first()
        val (providerConfig, videoModel) = when {
            requestedProviderId != null -> {
                val config = providers.firstOrNull { it.id == requestedProviderId }
                    ?: return "未找到供应商: $requestedProviderId"
                val model = requestedModelId?.let { id ->
                    config.models.firstOrNull { it.id == id && it.supportsVideoOutput() }
                } ?: config.models.firstOrNull { it.supportsVideoOutput() }
                    ?: return "供应商 ${config.displayName} 没有支持视频输出的模型"
                config to model
            }
            requestedModelId != null -> {
                val config = providers.firstOrNull { p ->
                    p.models.any { it.id == requestedModelId && it.supportsVideoOutput() }
                } ?: return "未找到支持模型 $requestedModelId 的供应商"
                val model = config.models.first { it.id == requestedModelId && it.supportsVideoOutput() }
                config to model
            }
            // 优先使用用户配置的视频供应商(VideoGenConfig.providerId)
            videoGenConfig.providerId.isNotBlank() -> {
                val config = providers.firstOrNull { it.id == videoGenConfig.providerId }
                    ?: return "未找到视频生成供应商: ${videoGenConfig.providerId}"
                val model = videoGenConfig.modelId.takeIf { it.isNotBlank() }?.let { id ->
                    config.models.firstOrNull { it.id == id && it.supportsVideoOutput() }
                } ?: config.models.firstOrNull { it.supportsVideoOutput() }
                    ?: return "供应商 ${config.displayName} 没有支持视频输出的模型"
                config to model
            }
            else -> {
                val config = providers.firstOrNull { p -> p.models.any { it.supportsVideoOutput() } }
                    ?: return "未配置支持视频生成的供应商。请在「设置→模型与服务」中为某个模型开启「视频输出」能力。"
                val model = config.models.first { it.supportsVideoOutput() }
                config to model
            }
        }

        val slot = host?.begin(MediaGenKind.VIDEO)
        val startedAt = System.currentTimeMillis()
        // v1.135: 每 5 秒刷新一次进度提示,让用户感知长任务仍在进行。
        val progressJob = slot?.let { target ->
            CoroutineScope(coroutineContext).launch {
                while (isActive) {
                    delay(PROGRESS_INTERVAL_MS)
                    target.onProgress(((System.currentTimeMillis() - startedAt) / 1000).toInt())
                }
            }
        }
        return try {
            val request = VideoGenRequest(
                prompt = prompt,
                model = videoModel.id,
                duration = duration,
                resolution = resolution,
                referenceImages = referenceImages,
            )
            // v1.137: 通过 VideoProviderRegistry 按 specId/host 路由,
            // 不再按 providerId 硬匹配(修复 preset_kling ≠ kling 的路由 bug)
            val videoUrl = videoGenerationService.generateVideo(providerConfig, request).getOrThrow()
            // A-13: 视频生成耗时可观,校验本轮生成仍活跃,避免跨会话污染
            if (slot != null && !slot.isActive()) {
                Logger.w(TAG, "A-13: 视频生成完成但生成已失效(新一轮生成),跳过媒体写入")
                return "视频已生成,但当前生成已失效,结果未展示(如需可重新请求)"
            }
            if (slot != null) {
                slot.safeReport(MediaGenResult.Success(videoFileUri = videoUrl))
                "视频已生成并展示在对话中"
            } else {
                "视频已生成(当前无对话界面,未展示),结果位置:\n$videoUrl"
            }
        } catch (e: CancellationException) {
            slot.safeReport(MediaGenResult.Cancelled)
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: context.getString(R.string.err_chat_unknown)
            slot.safeReport(MediaGenResult.Failure(msg))
            "视频生成失败: $msg"
        } finally {
            progressJob?.cancel()
            safeFinish(MediaGenKind.VIDEO)
        }
    }

    /**
     * 把任意文本转换为二维码图片。
     *
     * UI 会话中写入当前助手消息的 imageUrls 展示;无 UI 进程落盘到应用缓存目录并返回路径。
     */
    suspend fun execGenerateQrCode(args: Map<String, String>): String {
        val content = args["content"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: content"
        val size = args["size"]?.toIntOrNull()?.coerceIn(128, 1024) ?: 400
        val slot = host?.begin(MediaGenKind.QR_CODE)

        return try {
            val bitmap = QrCodeGenerator.generateQrBitmap(content, size)
                ?: return "二维码生成失败"
            val bytes = java.io.ByteArrayOutputStream().apply {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, this)
            }.toByteArray()
            bitmap.recycle()
            val dataUri = "data:image/png;base64," +
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            // A-13: 校验本轮生成仍活跃,避免跨会话污染
            if (slot != null && !slot.isActive()) {
                Logger.w(TAG, "A-13: 二维码生成完成但生成已失效(新一轮生成),跳过媒体写入")
                return "二维码已生成,但当前生成已失效,结果未展示(如需可重新请求)"
            }
            if (slot != null) {
                slot.safeReport(MediaGenResult.Success(imageUrls = listOf(dataUri)))
                "二维码生成成功"
            } else {
                val path = writePngToCache(bytes, "qr")
                if (path == null) "二维码生成失败: 结果无法保存" else "二维码已生成: $path"
            }
        } catch (e: Exception) {
            "二维码生成失败: ${e.message ?: "未知错误"}"
        } finally {
            safeFinish(MediaGenKind.QR_CODE)
        }
    }

    // ── 内部工具 ────────────────────────────────────────────────────────

    /**
     * 投递结果到会话消息。
     *
     * 包 [NonCancellable]:取消路径上工具协程已取消,普通挂起调用会立即抛
     * CancellationException,导致"生成已取消"提示写不进消息(与旧实现一致的行为要求)。
     */
    private suspend fun MediaGenSlot?.safeReport(result: MediaGenResult) {
        val slot = this ?: return
        runCatching { withContext(NonCancellable) { slot.report(result) } }
            .onFailure { Logger.w(TAG, "媒体结果投递失败: ${it.message}") }
    }

    /** 结束生成(清理 UI 占位状态),失败不影响工具返回值。 */
    private suspend fun safeFinish(kind: MediaGenKind) {
        val target = host ?: return
        runCatching { withContext(NonCancellable) { target.finish(kind) } }
            .onFailure { Logger.w(TAG, "媒体生成收尾失败(kind=$kind): ${it.message}") }
    }

    /**
     * v1.137: 解析参考图列表(可选,用于图生视频/多图生视频)。
     *
     * B-05: data URI 内含逗号(data:image/png;base64,AAA),按逗号拆分必被拆坏。
     * 审查修复 (2.0 B-05): 支持 URL 与 data URI 混排 — 逐段遍历,以 "data:" 开头的
     * 段与其后一段(base64 载荷)用逗号重新拼接(data URI 内仅分隔符一个逗号,
     * base64 字母表不含逗号),普通 URL 段原样保留。
     */
    private fun parseReferenceImages(raw: String?): List<String> = raw?.let { value ->
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            emptyList()
        } else {
            val tokens = trimmed.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val rebuilt = mutableListOf<String>()
            var i = 0
            while (i < tokens.size) {
                if (tokens[i].startsWith("data:")) {
                    // data URI:本段 + 下一段(base64 载荷,本身不含逗号)
                    val payload = tokens.getOrNull(i + 1)
                    if (payload != null) {
                        rebuilt.add(tokens[i] + "," + payload)
                        i += 2
                    } else {
                        rebuilt.add(tokens[i])
                        i += 1
                    }
                } else {
                    rebuilt.add(tokens[i])
                    i += 1
                }
            }
            rebuilt
        }
    } ?: emptyList()

    /**
     * 无 UI 会话时的结果外化:data URI 落盘为缓存文件(返回绝对路径),
     * http(s) URL 原样返回。避免把 MB 级 base64 塞进工具结果撑爆模型上下文。
     */
    private fun externalizeMedia(urls: List<String>, prefix: String): List<String> = urls.mapNotNull { url ->
        when {
            url.startsWith("data:") -> writeDataUriToCache(url, prefix)
            else -> url
        }
    }.ifEmpty { listOf("(无可访问的结果位置)") }

    /** 把 base64 data URI 解码落盘;失败返回 null。 */
    private fun writeDataUriToCache(dataUri: String, prefix: String): String? {
        val base64 = dataUri.substringAfter("base64,", "")
        if (base64.isBlank()) return null
        return runCatching {
            writePngToCache(android.util.Base64.decode(base64, android.util.Base64.DEFAULT), prefix)
        }.getOrNull()
    }

    /** 把 PNG 字节写入应用缓存目录,返回绝对路径;失败返回 null。 */
    private fun writePngToCache(bytes: ByteArray, prefix: String): String? = runCatching {
        val dir = File(context.cacheDir, CACHE_DIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, "${prefix}_${System.currentTimeMillis()}.png")
        file.writeBytes(bytes)
        file.absolutePath
    }.getOrNull()

    private companion object {
        const val TAG = "MediaGenTools"

        /** 无 UI 会话时媒体结果的落盘目录(应用缓存,可被系统清理)。 */
        const val CACHE_DIR = "mediagen"

        /** 视频生成进度刷新间隔(v1.135 行为:每 5 秒一次)。 */
        const val PROGRESS_INTERVAL_MS = 5_000L
    }
}
