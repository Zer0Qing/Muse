package io.zer0.ai.image

import io.zer0.ai.ProviderConfigStore
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.ErrorCode
import io.zer0.common.Logger
import io.zer0.common.toMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * F-03 回归锚点: 历史事故「生图 null 假图」。
 *
 * 事故: 旧版在图片 Provider 返回空结果时静默继续, UI 层把空结果渲染成
 * 「假图」占位, 用户看到一张不存在于任何 Provider 的图片。
 *
 * 防护链(已存在, 本测试锁定):
 *  1. [ImageService.generate] 在 `finalImages.isEmpty()` 时抛
 *     [ErrorCode.IMAGE_NO_RESULTS] 业务错误(ImageService.kt:107);
 *  2. [convertToOutputStrings] 用 mapNotNull 过滤 base64/url 全空的
 *     [GeneratedImage], 保证 null 图不会漏到 UI(ImageService.kt:219);
 *  3. 调用方 [io.zer0.muse.ui.chat.ImageGenCoordinator] 对空结果展示
 *     失败提示并停止生成, 绝不落一张假图。
 */
class ImageServiceEmptyResultTest {

    private val emptyProvider = object : ImageProvider {
        override val providerId: String = "empty-provider"
        override val supportsImageEdit: Boolean = false
        override val supportsAsync: Boolean = false
        override suspend fun submit(request: ImageGenRequest): ImageSubmitResult = ImageSubmitResult()
        override suspend fun poll(taskId: String): ImagePollResult = ImagePollResult(PollStatus.PENDING)
    }

    // 注意: ImageProviderRegistry.register 内部会调 Logger.i(ImageProviderRegistry.kt:32),
    // 必须在 @Before 置 Logger.enabled=false 之后注册, 否则字段初始化期即触发
    // android.util.Log not mocked。
    private val registry = ImageProviderRegistry()

    private val configStore = object : ProviderConfigStore {
        override suspend fun get(): ProviderConfig? = null
    }

    private val config = ProviderConfig(
        id = "p",
        displayName = "p",
        type = ProviderType.OPENAI,
        baseUrl = "https://example.com",
        apiKey = "k",
        // selectFor 按 specId 精确匹配 provider, 显式指向 emptyProvider
        specId = "empty-provider",
    )

    @Before
    fun disableAndroidLogging() {
        Logger.enabled = false
        registry.register(emptyProvider)
    }

    @After
    fun restoreLogging() {
        Logger.enabled = true
    }

    @Test
    fun `empty provider result throws IMAGE_NO_RESULTS instead of fake image`() {
        val service = ImageService(configStore, registry)
        runBlocking {
            try {
                service.generate(prompt = "一只猫", providerConfig = config)
                fail("空结果必须抛 IMAGE_NO_RESULTS, 而非返回空列表/假图")
            } catch (e: IllegalStateException) {
                // error(ErrorCode...) 在 Kotlin 中抛 IllegalStateException
                assertTrue(
                    "错误消息应标识 IMAGE_NO_RESULTS, 实际: ${e.message}",
                    e.message?.contains(ErrorCode.IMAGE_NO_RESULTS.toMessage()) == true,
                )
            }
        }
    }

    @Test
    fun `convertToOutputStrings drops null images and keeps valid urls`() {
        val images = listOf(
            GeneratedImage(base64 = null, url = null),
            GeneratedImage(base64 = "YmFzZTY0"),
            GeneratedImage(base64 = null, url = "https://example.com/a.png"),
        )
        val out = ImageService(configStore, registry).convertToOutputStrings(images)
        // mapNotNull: null 图被过滤, 有效 url/base64 保留(顺序稳定)
        assertEquals(2, out.size)
        assertEquals("data:image/png;base64,YmFzZTY0", out[0])
        assertEquals("https://example.com/a.png", out[1])
    }

    @Test
    fun `non empty provider result maps to output strings`() {
        val oneImageProvider = object : ImageProvider {
            override val providerId: String = "one-image-provider"
            override val supportsImageEdit: Boolean = false
            override val supportsAsync: Boolean = false
            override suspend fun submit(request: ImageGenRequest): ImageSubmitResult =
                ImageSubmitResult(images = listOf(GeneratedImage(url = "https://example.com/a.png")))
            override suspend fun poll(taskId: String): ImagePollResult = ImagePollResult(PollStatus.PENDING)
        }
        val reg = ImageProviderRegistry().apply { register(oneImageProvider) }
        val service = ImageService(configStore, reg)
        val oneConfig = config.copy(specId = "one-image-provider")
        val urls = runBlocking { service.generate(prompt = "一只猫", providerConfig = oneConfig) }
        assertEquals(listOf("https://example.com/a.png"), urls)
    }
}
