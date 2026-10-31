package io.zer0.muse.ui.chat

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 会话路径的绘图模型兜底:文本模型不得被当成出图模型硬用。
 *
 * 工具路径(MediaGenToolsImpl)一直带 supportsImageOutput 校验,会话路径原先直接
 * `models.firstOrNull()`,两条口径不一致,导致同一份配置在聊天里报上游错误、在工具里正常。
 */
class ImageGenModelResolutionTest {

    private val textModel = Model(id = "gpt-4o", providerId = "p1")
    private val imageModel = Model(id = "gpt-image-1", providerId = "p1", outputModalities = setOf("text", "image"))

    private fun provider(vararg models: Model): ProviderConfig = ProviderConfig(
        id = "p1",
        displayName = "P1",
        models = models.toList(),
    )

    @Test
    fun requestedImageModelIsUsed() {
        val resolved = resolveImageCapableModel(provider(textModel, imageModel), imageModel)

        assertEquals("gpt-image-1", resolved?.id)
    }

    @Test
    fun textOnlyModelFallsBackToFirstImageCapableModel() {
        val resolved = resolveImageCapableModel(provider(textModel, imageModel), textModel)

        assertEquals("不能出图的模型必须被跳过", "gpt-image-1", resolved?.id)
    }

    @Test
    fun providerWithoutImageCapableModelResolvesNothing() {
        val resolved = resolveImageCapableModel(provider(textModel), textModel)

        assertNull("供应商没有可出图模型时必须返回 null,交给调用方报错", resolved)
    }

    @Test
    fun nullRequestedModelPicksFirstImageCapableModel() {
        val resolved = resolveImageCapableModel(provider(textModel, imageModel), null)

        assertEquals("gpt-image-1", resolved?.id)
    }

    @Test
    fun noProviderResolvesNothing() {
        assertNull("供应商缺失时不得凭空给出模型", resolveImageCapableModel(null, textModel))
    }
}
