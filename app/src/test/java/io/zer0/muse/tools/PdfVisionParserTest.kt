package io.zer0.muse.tools

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.ChatService
import io.zer0.ai.ProviderConfigStore
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v1.0.52 P2-4: PdfVisionParser + PdfVisionSkill + DefaultVisionOcrClient 单元测试。
 *
 * 覆盖范围:
 *  - [PdfVisionSkill] 参数校验(缺 path / path 含 ..)
 *  - [PdfVisionSkill] max_pages 钳制(C-32: resolveMaxPages 纯逻辑,不依赖文件存在性检查)
 *  - [PdfVisionSkill] 路径解析(工作区/filesDir/Download/绝对路径)
 *  - [PdfVisionParser] 早失败路径(文件不存在 / 视觉模型不可用)
 *  - [DefaultVisionOcrClient] 容错逻辑(text/reasoning/空/异常)
 *
 * 不覆盖(需 Android 真机/PdfRenderer):
 *  - 实际 PDF 渲染为 Bitmap
 *  - 4 路并发的真实执行
 *  - 视觉模型真实调用
 *
 * 运行方式: `./gradlew :app:testDebugUnitTest --tests "*PdfVisionParserTest*"`
 *
 * C-31 评估:[FakeVisionOcrClient] 仅在本文件使用,全仓库无同名/同职责跨文件复制。
 * 重复度低,不值得建 testFixtures 基建,保持文件内私有。见深度审计报告 C-31 修正说明。
 */
class PdfVisionParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ── PdfVisionSkill 参数校验 ─────────────────────────────────────────

    private val mockParser: PdfVisionParser = mockk(relaxed = true)
    private val mockContext: Context = mockk(relaxed = true)
    private val workspaceRoot: File by lazy { tempFolder.newFolder("workspace") }

    @Test
    fun `execute returns error when path missing`() = runTest {
        val result = PdfVisionSkill.execute(
            args = emptyMap(),
            parser = mockParser,
            context = mockContext,
            workspaceRoot = workspaceRoot,
        )
        assertTrue("缺 path 应返回错误", result.startsWith("[错误]"))
        assertTrue("应提示缺少 path", result.contains("path"))
    }

    @Test
    fun `execute returns error when path is blank`() = runTest {
        val result = PdfVisionSkill.execute(
            args = mapOf("path" to "   "),
            parser = mockParser,
            context = mockContext,
            workspaceRoot = workspaceRoot,
        )
        assertTrue("空白 path 应返回错误", result.startsWith("[错误]"))
    }

    @Test
    fun `execute rejects path containing dot-dot`() = runTest {
        val result = PdfVisionSkill.execute(
            args = mapOf("path" to "../etc/passwd"),
            parser = mockParser,
            context = mockContext,
            workspaceRoot = workspaceRoot,
        )
        assertTrue("含 .. 的路径应被拒", result.startsWith("[错误]"))
        assertTrue("应提示禁止 ..", result.contains(".."))
    }

    @Test
    fun `execute returns error when file not found in any candidate`() = runTest {
        // mockContext 返回临时目录,确保所有候选路径都不存在
        every { mockContext.filesDir } returns tempFolder.newFolder("files")
        every { mockContext.cacheDir } returns tempFolder.newFolder("cache")

        val result = PdfVisionSkill.execute(
            args = mapOf("path" to "nonexistent.pdf"),
            parser = mockParser,
            context = mockContext,
            workspaceRoot = workspaceRoot,
        )
        assertTrue("文件不存在应返回错误", result.startsWith("[错误]"))
        assertTrue("应提示文件不存在", result.contains("不存在"))
    }

    @Test
    fun `resolveMaxPages clamps above hard cap to hard cap`() {
        // C-32: max_pages 钳制改为纯逻辑测试,不依赖文件存在性检查与 mockk coEvery 顺序。
        assertEquals("201 应钳制到 200", 200, PdfVisionSkill.resolveMaxPages("201"))
        assertEquals("远超上限应钳制到 200", 200, PdfVisionSkill.resolveMaxPages("9999"))
    }

    @Test
    fun `resolveMaxPages clamps below 1 to 1`() {
        assertEquals("0 应钳制到 1", 1, PdfVisionSkill.resolveMaxPages("0"))
        assertEquals("负数应钳制到 1", 1, PdfVisionSkill.resolveMaxPages("-5"))
    }

    @Test
    fun `resolveMaxPages uses default when missing or invalid`() {
        assertEquals("缺省用默认值", PdfVisionSkill.DEFAULT_MAX_PAGES, PdfVisionSkill.resolveMaxPages(null))
        assertEquals("非数字用默认值", PdfVisionSkill.DEFAULT_MAX_PAGES, PdfVisionSkill.resolveMaxPages("abc"))
        assertEquals("空串用默认值", PdfVisionSkill.DEFAULT_MAX_PAGES, PdfVisionSkill.resolveMaxPages(""))
    }

    @Test
    fun `resolveMaxPages keeps in-range value`() {
        assertEquals("1 边界", 1, PdfVisionSkill.resolveMaxPages("1"))
        assertEquals("合法值原样保留", 80, PdfVisionSkill.resolveMaxPages("80"))
        assertEquals("上限边界原样保留", PdfVisionSkill.HARD_MAX_PAGES, PdfVisionSkill.resolveMaxPages("200"))
    }

    @Test
    fun `execute uses default max_pages when parameter missing`() = runTest {
        val fakePdf = File(workspaceRoot, "test.pdf").apply { writeText("fake") }
        every { mockContext.filesDir } returns tempFolder.newFolder("files")
        every { mockContext.cacheDir } returns tempFolder.newFolder("cache")

        coEvery {
            mockParser.parse(any(), any())
        } returns PdfVisionParser.ParseResult(
            success = true,
            text = "OCR 内容",
            pagesProcessed = 5,
            pageCount = 5,
        )

        val result = PdfVisionSkill.execute(
            args = mapOf("path" to "test.pdf"),
            parser = mockParser,
            context = mockContext,
            workspaceRoot = workspaceRoot,
        )
        assertTrue("成功时应包含标题", result.contains("[PDF 视觉解析成功]"))
        assertTrue("应包含 OCR 内容", result.contains("OCR 内容"))
    }

    @Test
    fun `execute handles non-numeric max_pages gracefully`() = runTest {
        val fakePdf = File(workspaceRoot, "test.pdf").apply { writeText("fake") }
        every { mockContext.filesDir } returns tempFolder.newFolder("files")
        every { mockContext.cacheDir } returns tempFolder.newFolder("cache")

        coEvery {
            mockParser.parse(any(), any())
        } returns PdfVisionParser.ParseResult(
            success = true,
            text = "内容",
            pagesProcessed = 1,
            pageCount = 1,
        )

        // 非数字 max_pages 应降级到默认值,不抛异常
        val result = PdfVisionSkill.execute(
            args = mapOf("path" to "test.pdf", "max_pages" to "abc"),
            parser = mockParser,
            context = mockContext,
            workspaceRoot = workspaceRoot,
        )
        assertTrue("非数字 max_pages 应降级到默认值,不崩", result.contains("[PDF 视觉解析成功]"))
    }

    @Test
    fun `execute returns error when parser throws exception`() = runTest {
        val fakePdf = File(workspaceRoot, "test.pdf").apply { writeText("fake") }
        every { mockContext.filesDir } returns tempFolder.newFolder("files")
        every { mockContext.cacheDir } returns tempFolder.newFolder("cache")

        coEvery {
            mockParser.parse(any(), any())
        } throws RuntimeException("模拟 PdfRenderer 异常")

        val result = PdfVisionSkill.execute(
            args = mapOf("path" to "test.pdf"),
            parser = mockParser,
            context = mockContext,
            workspaceRoot = workspaceRoot,
        )
        assertTrue("parser 抛异常应返回错误", result.startsWith("[错误]"))
        assertTrue("应包含异常消息", result.contains("模拟 PdfRenderer 异常"))
    }

    @Test
    fun `execute returns error when parser reports failure`() = runTest {
        val fakePdf = File(workspaceRoot, "test.pdf").apply { writeText("fake") }
        every { mockContext.filesDir } returns tempFolder.newFolder("files")
        every { mockContext.cacheDir } returns tempFolder.newFolder("cache")

        coEvery {
            mockParser.parse(any(), any())
        } returns PdfVisionParser.ParseResult(
            success = false,
            text = "",
            pagesProcessed = 0,
            pageCount = 0,
            error = "未配置视觉模型",
        )

        val result = PdfVisionSkill.execute(
            args = mapOf("path" to "test.pdf"),
            parser = mockParser,
            context = mockContext,
            workspaceRoot = workspaceRoot,
        )
        assertTrue("parser 失败应返回错误", result.startsWith("[错误]"))
        assertTrue("应包含 parser 的错误消息", result.contains("未配置视觉模型"))
    }

    // ── PdfVisionParser 早失败路径(用 fake ocrClient,绕开真实 LLM) ──────

    @Test
    fun `parse returns failure when file does not exist`() = runTest {
        val fakeOcr = FakeVisionOcrClient(available = true)
        val parser = PdfVisionParser(
            context = mockContext,
            ocrClient = fakeOcr,
        )

        val result = parser.parse("/nonexistent/path/file.pdf")

        assertFalse("文件不存在应失败", result.success)
        assertTrue("应提示文件不存在", result.error?.contains("不存在") == true)
        assertEquals(0, result.pageCount)
        assertEquals(0, result.pagesProcessed)
    }

    @Test
    fun `parse returns failure when vision model unavailable`() = runTest {
        // 创建临时文件模拟 PDF(内容不重要,只验证 isAvailable=false 的早失败)
        val fakePdf = tempFolder.newFile("test.pdf").apply { writeText("fake") }
        val fakeOcr = FakeVisionOcrClient(available = false)
        val parser = PdfVisionParser(
            context = mockContext,
            ocrClient = fakeOcr,
        )

        val result = parser.parse(fakePdf.absolutePath)

        assertFalse("视觉模型不可用应失败", result.success)
        assertTrue("应提示未配置视觉模型", result.error?.contains("视觉") == true)
        assertEquals("isAvailable=false 时不应调用 ocr", 0, fakeOcr.ocrCallCount)
    }

    // ── DefaultVisionOcrClient 容错逻辑 ────────────────────────────────

    @Test
    fun `DefaultVisionOcrClient returns text when non-blank`() = runTest {
        val mockChatService = mockk<ChatService>()
        val mockConfigStore = mockk<ProviderConfigStore>()
        coEvery { mockConfigStore.get() } returns testProviderConfig(visionSupported = true)
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(text = "OCR 提取的文字", reasoningContent = null)

        val client = DefaultVisionOcrClient(mockChatService, mockConfigStore)
        val result = client.ocr("base64data")

        assertEquals("应返回非空 text", "OCR 提取的文字", result)
    }

    @Test
    fun `DefaultVisionOcrClient falls back to reasoningContent when text empty`() = runTest {
        val mockChatService = mockk<ChatService>()
        val mockConfigStore = mockk<ProviderConfigStore>()
        coEvery { mockConfigStore.get() } returns testProviderConfig(visionSupported = true)
        // 模拟推理模型:text 空,但 reasoningContent 有内容
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(text = "", reasoningContent = "推理模型把 OCR 放这里")

        val client = DefaultVisionOcrClient(mockChatService, mockConfigStore)
        val result = client.ocr("base64data")

        assertEquals("text 空时应降级用 reasoningContent", "推理模型把 OCR 放这里", result)
    }

    @Test
    fun `DefaultVisionOcrClient returns empty marker when both text and reasoning blank`() = runTest {
        val mockChatService = mockk<ChatService>()
        val mockConfigStore = mockk<ProviderConfigStore>()
        coEvery { mockConfigStore.get() } returns testProviderConfig(visionSupported = true)
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(text = "", reasoningContent = "")

        val client = DefaultVisionOcrClient(mockChatService, mockConfigStore)
        val result = client.ocr("base64data")

        assertEquals("text+reasoning 都空应返回空内容标记", "[OCR 返回空内容]", result)
    }

    @Test
    fun `DefaultVisionOcrClient returns empty input marker when base64 blank`() = runTest {
        val mockChatService = mockk<ChatService>()
        val mockConfigStore = mockk<ProviderConfigStore>()
        val client = DefaultVisionOcrClient(mockChatService, mockConfigStore)

        val result = client.ocr("")

        assertEquals("空 base64 应返回输入为空标记", "[OCR 输入为空]", result)
    }

    @Test
    fun `DefaultVisionOcrClient returns failure marker when ChatService throws`() = runTest {
        val mockChatService = mockk<ChatService>()
        val mockConfigStore = mockk<ProviderConfigStore>()
        coEvery { mockConfigStore.get() } returns testProviderConfig(visionSupported = true)
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } throws RuntimeException("网络错误")

        val client = DefaultVisionOcrClient(mockChatService, mockConfigStore)
        val result = client.ocr("base64data")

        assertTrue("ChatService 抛异常应返回失败标记", result.startsWith("[OCR 调用失败"))
        assertTrue("应包含异常消息", result.contains("网络错误"))
    }

    @Test
    fun `DefaultVisionOcrClient isAvailable returns false when no provider config`() = runTest {
        val mockChatService = mockk<ChatService>()
        val mockConfigStore = mockk<ProviderConfigStore>()
        coEvery { mockConfigStore.get() } returns null

        val client = DefaultVisionOcrClient(mockChatService, mockConfigStore)
        assertFalse("无 Provider 配置时 isAvailable 应为 false", client.isAvailable())
    }

    @Test
    fun `DefaultVisionOcrClient isAvailable returns false when no vision model`() = runTest {
        val mockChatService = mockk<ChatService>()
        val mockConfigStore = mockk<ProviderConfigStore>()
        coEvery { mockConfigStore.get() } returns testProviderConfig(visionSupported = false)

        val client = DefaultVisionOcrClient(mockChatService, mockConfigStore)
        assertFalse("无视觉模型时 isAvailable 应为 false", client.isAvailable())
    }

    @Test
    fun `DefaultVisionOcrClient isAvailable returns true when vision model configured`() = runTest {
        val mockChatService = mockk<ChatService>()
        val mockConfigStore = mockk<ProviderConfigStore>()
        coEvery { mockConfigStore.get() } returns testProviderConfig(visionSupported = true)

        val client = DefaultVisionOcrClient(mockChatService, mockConfigStore)
        assertTrue("配置了视觉模型时 isAvailable 应为 true", client.isAvailable())
    }

    // ── 辅助工具 ────────────────────────────────────────────────────────

    /**
     * Fake VisionOcrClient,绕开真实 LLM 调用。
     *
     * @param available isAvailable() 返回值
     * @param ocrResult ocr() 返回的固定文本(默认 "fake-ocr")
     */
    private class FakeVisionOcrClient(
        private val available: Boolean,
        private val ocrResult: String = "fake-ocr",
    ) : VisionOcrClient {
        var ocrCallCount: Int = 0
            private set

        override suspend fun isAvailable(): Boolean = available

        override suspend fun ocr(imageBase64: String): String {
            ocrCallCount++
            return ocrResult
        }
    }

    /**
     * 构造测试用 ProviderConfig。
     *
     * @param visionSupported 模型是否支持 vision 输入
     */
    private fun testProviderConfig(visionSupported: Boolean): ProviderConfig {
        val model = Model(
            id = "test-model",
            name = "Test Model",
            providerId = "test-provider",
            supportsVision = visionSupported,
        )
        return ProviderConfig(
            id = "test-provider",
            displayName = "Test Provider",
            type = ProviderType.OPENAI,
            baseUrl = "https://test.example.com",
            apiKey = "test-key",
            models = listOf(model),
        )
    }
}
