package io.zer0.muse.asr.local

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.asr.AsrConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地语音模型下载器：断点续传（.part + HTTP Range）+ SHA-256 校验 + 串行下载。
 *
 * 失败不自动重试 —— 设计文档明确「给出原因和重试按钮」，自动循环在移动流量下
 * 会烧流量还不被察觉。取消后 .part 保留，下次「下载」从断点接着下。
 *
 * 校验约定：下载完成先对 .part 做整段 SHA-256，比对设计文档锁定值，通过后原子
 * rename 到正式文件名。校验失败删除 .part（Range 断点对坏文件毫无意义，续传只会
 * 永远校验不过）。
 *
 * 计费网络确认与空间检查不在这里做 —— 那是 UI 层（AsrSection 卡片）按下「下载」
 * 前的判定；本类只负责把字节安全地搬到文件系统。
 */
internal object LocalModelDownloader {

    private const val TAG = "LocalModelDownloader"

    /** ModelScope 镜像（国内可用，支持 Range 断点续传）。 */
    private const val BASE_URL =
        "https://www.modelscope.cn/models/pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue/" +
            "resolve/73eca47697f980daa3d16112404174b6b950b514/"

    /** 官方 HuggingFace 兜底源（国内多半不通，仅当镜像失败时试一次）。 */
    private const val FALLBACK_BASE_URL = "https://huggingface.co/FunAudioLLM/SenseVoiceSmall/resolve/main/"

    /** 设计文档锁定的校验值：字节不符说明镜像被篡改或下载损坏。 */
    const val MODEL_SHA256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"
    const val TOKENS_SHA256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
    const val MODEL_BYTES = 239_233_841L
    const val TOKENS_BYTES = 315_894L

    /** 下载总大小（UI 显示用）。 */
    const val TOTAL_BYTES = MODEL_BYTES + TOKENS_BYTES

    /** 单文件读取停滞超时：超过这么久没有新字节视为连接死了。 */
    private const val READ_STALL_TIMEOUT_SEC = 30L

    private val client: OkHttpClient = io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
        .connectTimeout(AsrConstants.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_STALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    private val running = AtomicBoolean(false)
    private val jobRef = java.util.concurrent.atomic.AtomicReference<Job?>(null)

    /** 下载用的独立 scope：cancel 时只杀下载协程，不依赖调用方生命周期。 */
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前下载状态（UI 卡片订阅）。 */
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    /** 是否有下载在进行（跨重组稳定）。 */
    fun isRunning(): Boolean = running.get()

    /**
     * 开始/继续下载（幂等：已在下则忽略）。
     * @param allowFallback 镜像失败后是否试官方源（UI 可关掉以免白等）
     */
    fun start(context: Context, allowFallback: Boolean = true) {
        if (!running.compareAndSet(false, true)) return
        jobRef.set(
            downloadScope.launch {
                try {
                    _state.value = DownloadState.Downloading(0, TOTAL_BYTES)
                    val fallback = if (allowFallback) FALLBACK_BASE_URL else null
                    downloadFile(context, LocalModelStore.MODEL_FILE, MODEL_SHA256, BASE_URL, fallback)
                    downloadFile(context, LocalModelStore.TOKENS_FILE, TOKENS_SHA256, BASE_URL, fallback)
                    _state.value = if (LocalModelStore.isReady(context)) {
                        DownloadState.Done
                    } else {
                        DownloadState.Failed("下载完成但文件校验缺失")
                    }
                    // 推一次可用性：输入栏的「发送键 ↔ 语音键」据此立即切换
                    LocalModelStoreBridge.refresh()
                } catch (e: CancellationException) {
                    _state.value = DownloadState.Idle
                    LocalModelStoreBridge.refresh()
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "模型下载失败: ${e.message}")
                    _state.value = DownloadState.Failed(e.message ?: "下载失败")
                    LocalModelStoreBridge.refresh()
                } finally {
                    running.set(false)
                    jobRef.set(null)
                }
            },
        )
    }

    /** 取消当前下载。.part 保留，下次 start 接着下。 */
    fun cancel() {
        jobRef.getAndSet(null)?.cancel()
    }

    /**
     * 下载单个文件：优先镜像，失败换兜底源。内部用 [runDownload] 真正干活。
     */
    private suspend fun downloadFile(context: Context, fileName: String, expectedSha256: String, primary: String, fallback: String?) =
        withContext(Dispatchers.IO) {
            val target = File(LocalModelStore.modelDir(context), fileName)
            if (verifyFile(target, expectedSha256)) return@withContext
            try {
                runDownload("$primary$fileName", target, expectedSha256)
                return@withContext
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "主源($primary)下载 $fileName 失败: ${e.message}")
            }
            if (fallback != null) {
                try {
                    runDownload("$fallback$fileName", target, expectedSha256)
                    return@withContext
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "兜底源($fallback)下载 $fileName 失败: ${e.message}")
                }
            }
            throw IOException("两个下载源都失败，请检查网络后重试")
        }

    /**
     * 对指定 URL 执行一次完整下载：断点续传 + 停滞超时 + SHA-256 校验 + 原子落位。
     * 失败抛异常（调用方决定是否换源重试）。
     */
    private suspend fun runDownload(url: String, target: File, expectedSha256: String) {
        val part = File(target.parentFile, "${target.name}.part")
        target.parentFile?.mkdirs()
        val already = if (part.isFile) part.length() else 0L

        val request = Request.Builder().url(url).apply {
            if (already > 0) header("Range", "bytes=$already-")
        }.build()

        client.newCall(request).execute().use { resp ->
            when {
                resp.code == 200 && already > 0 -> {
                    // 服务器不支持 Range，从头下
                    Logger.i(TAG, "服务器不支持 Range，重新下载 $url")
                    part.delete()
                }
                resp.code == 206 -> {
                    Logger.i(TAG, "断点续传 $url 从 $already 字节继续")
                }
                resp.code == 200 -> Unit
                else -> throw IOException("HTTP ${resp.code}: ${resp.message}")
            }
            val body = resp.body ?: throw IOException("响应体为空")
            val contentLength = body.contentLength()
            val expectedTotal = if (resp.code == 206) already + contentLength else contentLength

            java.io.FileOutputStream(part, true).use { out ->
                val source = body.byteStream()
                val buf = ByteArray(64 * 1024)
                var written = already
                var lastProgress = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = source.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        out.write(buf, 0, n)
                        written += n
                        if (written - lastProgress >= 512 * 1024) {
                            lastProgress = written
                            _state.value = DownloadState.Downloading(written, expectedTotal)
                        }
                    }
                }
                out.flush()
            }
            _state.value = DownloadState.Downloading(already + contentLength, expectedTotal)
        }

        // SHA-256 校验（读 .part 全量，约 239 MB 一次，手机上数秒）
        if (!verifyFile(part, expectedSha256)) {
            part.delete()
            throw IOException("SHA-256 校验失败，文件已损坏，请重试")
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException("文件落位失败（rename 失败），请重试")
        }
    }

    /** 文件 SHA-256 是否等于期望值（流式，不整段进内存）。 */
    private fun verifyFile(file: File, expectedSha256: String): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) md.update(buf, 0, n)
            }
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        return hex.equals(expectedSha256, ignoreCase = true)
    }

    /** 下载状态（UI 卡片渲染的唯一事实源）。 */
    sealed interface DownloadState {
        /** 没在下载（初始/取消后）。 */
        data object Idle : DownloadState

        /** 下载中：已写入 .part 的字节数 / 预期总字节。 */
        data class Downloading(val bytesDone: Long, val bytesTotal: Long) : DownloadState

        /** 两个文件都就绪。 */
        data object Done : DownloadState

        /** 失败原因（不自动重试，等用户按「重试」）。 */
        data class Failed(val message: String) : DownloadState
    }
}
