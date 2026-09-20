package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.ai.image.ImageService
import io.zer0.ai.video.VideoGenerationService
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P2-23 回归:媒体生成工具(图片/视频/二维码)改为启动期注册后的注册台账护栏。
 *
 * 迁移前这三个工具由 ChatViewModel.registerMediaTools() 在"用户打开过聊天页"时才注册,
 * 定时任务 / 群聊 / 子代理链路查不到它们。现在由 [MediaGenToolsRegistrar] 在 App 启动时
 * ([ToolRegistrarBootstrapper])注册,本测试不创建 ChatViewModel、不启动任何 UI,
 * 只校验"注册后 ToolRegistry 里查得到、风险等级不降级、执行器确实接线"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaGenToolsRegistrarTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** 构造注册器:生成依赖全部 relaxed mock(本测试只关心注册结果,不执行真实生成)。 */
    private fun buildRegistrar(registry: ToolRegistry): MediaGenToolsRegistrar = MediaGenToolsRegistrar(
        toolRegistry = registry,
        impl = MediaGenToolsImpl(
            context = context,
            settings = mockk(relaxed = true),
            imageService = mockk(relaxed = true),
            videoGenerationService = mockk(relaxed = true),
        ),
    )

    @Test
    fun `registrar registers image video and qr tools with unchanged risk levels`() {
        val registry = ToolRegistry(context)
        buildRegistrar(registry)

        val defs = registry.listTools().associateBy { it.name }
        listOf("generate_image", "generate_video", "generate_qr_code").forEach { name ->
            assertNotNull("$name 未注册,启动期注册链路断了", defs[name])
        }
        // 风险等级必须与迁移前 ChatViewModel.registerMediaTools() 完全一致:
        // 生图/生视频 = HIGH(消耗外部额度、上传用户描述),二维码 = SAFE(纯本地编码)
        assertEquals(ToolRiskLevel.HIGH, defs.getValue("generate_image").riskLevel)
        assertEquals(ToolRiskLevel.HIGH, defs.getValue("generate_video").riskLevel)
        assertEquals(ToolRiskLevel.SAFE, defs.getValue("generate_qr_code").riskLevel)
    }

    @Test
    fun `media tools stay in built-in tool ids so default assistants keep them`() {
        listOf("generate_image", "generate_video", "generate_qr_code").forEach { name ->
            assertTrue(
                "$name 不在 BUILT_IN_TOOL_IDS,默认助手将看不到该工具",
                ToolRegistry.BUILT_IN_TOOL_IDS.contains(name),
            )
        }
    }

    @Test
    fun `registered executors are wired to the impl`() = runBlocking {
        val registry = ToolRegistry(context)
        buildRegistrar(registry)

        // 不触发任何网络/生成:缺必填参数时注册的 lambda 必须被真正调用(而非空注册)。
        val image = registry.execute("generate_image", emptyMap())
        assertTrue("generate_image 执行器未接线: ${image.content}", image.isError)
        assertTrue(image.content.contains("prompt"))

        val video = registry.execute("generate_video", emptyMap())
        assertTrue("generate_video 执行器未接线: ${video.content}", video.isError)
        assertTrue(video.content.contains("prompt"))

        val qr = registry.execute("generate_qr_code", emptyMap())
        assertTrue("generate_qr_code 执行器未接线: ${qr.content}", qr.isError)
        assertTrue(qr.content.contains("content"))
    }

    @Test
    fun `params metadata survives the move`() {
        val registry = ToolRegistry(context)
        buildRegistrar(registry)

        val defs = registry.listTools().associateBy { it.name }
        val image = defs.getValue("generate_image")
        assertEquals(setOf("prompt"), image.required)
        // 审批卡片注入本地参考图的参数键必须仍在参数表里(否则图生图/图片编辑回归)
        assertTrue("generate_image 参数表缺 reference_image", image.parameters.containsKey("reference_image"))
        assertEquals("integer", image.parameterTypes["n"])
        assertEquals(setOf("prompt"), defs.getValue("generate_video").required)
        assertEquals(setOf("content"), defs.getValue("generate_qr_code").required)
    }
}
