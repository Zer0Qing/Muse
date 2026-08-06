package io.zer0.muse.tools

import android.content.Context
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.UIMessage
import io.zer0.ai.image.ImageGenParams
import io.zer0.ai.image.ImageService
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import io.zer0.muse.data.cover.CoverItem
import io.zer0.muse.data.cover.CoverLibraryRepository
import io.zer0.muse.transformer.PromptTemplateLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * v1.0.53: AI 封面生成服务(对标 Beautify 封面工作流的生图链路)。
 *
 * 流程:
 *  1. 用 cover_generation 模板 + 文档标题/摘要渲染 LLM prompt 生成指令
 *  2. ChatService 生成封面绘图 prompt(英文,50 词内)
 *  3. ImageService.generate 生图(16:9,1536x640 或供应商最接近尺寸)
 *  4. OkHttp 下载结果 URL 到临时文件
 *  5. CoverLibraryRepository.registerGenerated 入库
 *
 * 任一步失败返回 Error(调用方 Toast 提示),不留半成品。
 */
class CoverGenerator(
    private val context: Context,
    private val templateLoader: PromptTemplateLoader,
    private val chatService: io.zer0.ai.ChatService,
    private val imageService: ImageService?,
    private val coverLibraryRepository: CoverLibraryRepository,
    private val okHttpClient: OkHttpClient,
) {

    companion object {
        private const val TAG = "CoverGenerator"
        private const val COVER_TEMPLATE_NAME = "cover_generation"
        private const val COVER_SIZE = "1536x640"
        private const val MAX_PROMPT_TOKENS = 300
        private const val DOWNLOAD_TIMEOUT_MS = 30_000L
        private const val MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024

        /** R-UI-06/R-TEST-18: 空 LLM 输出降级为固定绘图指令,便于单元测试。 */
        internal fun resolveCoverPrompt(raw: String, title: String): String =
            raw.trim().ifBlank { "Minimal modern banner cover for: $title, no text, 16:9 aspect ratio" }
    }

    /**
     * 生成封面并入库。
     *
     * @param title 文档标题(模板变量)
     * @param description 文档摘要(模板变量,可空)
     * @param locale 语言代码(模板 locale 回落)
     * @return 入库后的 [CoverItem];失败返回 Error
     */
    suspend fun generateCover(
        title: String,
        description: String? = null,
        locale: String? = null,
    ): Result<CoverItem> = withContext(Dispatchers.IO) {
        resultOf {
            // 1. 渲染模板 → LLM 生成封面 prompt
            val directive = templateLoader.render(
                name = COVER_TEMPLATE_NAME,
                locale = locale,
                context = mapOf(
                    "title" to title,
                    "description" to (description ?: ""),
                ),
                fallback = "Generate a minimal modern banner cover image for a document titled: $title",
            )

            val promptCompletion = chatService.completeText(
                messages = listOf(
                    UIMessage(role = MessageRole.SYSTEM, content = directive),
                    UIMessage(role = MessageRole.USER, content = "请生成封面图的英文绘图 prompt。"),
                ),
                temperature = 0.7f,
                maxTokens = MAX_PROMPT_TOKENS,
                tools = null,
                reasoningLevel = ReasoningLevel.OFF,
                mode = ChatRequestMode.UTILITY,
            )
            val rawCoverPrompt = promptCompletion.text.trim()
            // R-UI-06: 空 LLM 输出降级为固定绘图指令,不再抛 IllegalStateException。
            val coverPrompt = resolveCoverPrompt(rawCoverPrompt, title)

            // 2. 生图
            val service = imageService
                ?: error("未配置绘图模型")
            val urls = service.generate(
                prompt = coverPrompt,
                params = ImageGenParams(
                    model = "",
                    size = COVER_SIZE,
                    responseFormat = "url",
                    n = 1,
                ),
            )
            if (urls.isEmpty()) error("生图结果为空")
            val url = urls.first()

            // 3. 下载到临时文件
            val tmpFile = downloadToTemp(url)

            // 4. 入库(尺寸未知时传 0,registerGenerated 内部按文件实际处理)
            val item = coverLibraryRepository.registerGenerated(
                file = tmpFile,
                width = 0,
                height = 0,
            ).getOrThrow()

            // 5. 清理临时文件
            runCatching { tmpFile.delete() }
            item
        }.onError { msg, t ->
            Logger.w(TAG, "封面生成失败: $msg", t)
        }
    }

    /** 下载图片 URL 到 cache 临时文件(带大小与超时限制)。 */
    private suspend fun downloadToTemp(url: String): File = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        val response = okHttpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) error("下载封面失败: HTTP ${resp.code}")
            val body = resp.body ?: error("下载封面失败: 空响应体")
            val file = File.createTempFile("cover_dl_", ".img", context.cacheDir)
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buf = ByteArray(8 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_DOWNLOAD_BYTES) {
                            runCatching { file.delete() }
                            error("封面图片超过 ${MAX_DOWNLOAD_BYTES / 1024 / 1024}MB 限制")
                        }
                        output.write(buf, 0, read)
                    }
                }
            }
            file
        }
    }
}
