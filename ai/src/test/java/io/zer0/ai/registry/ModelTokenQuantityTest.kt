package io.zer0.ai.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参数量与上下文长度不能被当成版本号。
 *
 * `qwen3-5b` 里的 `5b` 是参数量，不是版本 3.5；`32k` 是上下文长度，不是版本 32。
 * 切词时不区分的话，`qwen 3 5` 这条定义会误中 `qwen3-5b`，
 * 于是给它安上用不上的能力（比如被判成视觉模型），用户选了它却传不了图。
 */
class ModelTokenQuantityTest {

    @Test
    fun `参数量不拆成版本号`() {
        // 分隔符本身也会成为一个 token，这里只断言"参数量没有被拆开"这件事
        assertEquals(listOf("qwen", "3", "5b"), tokenize("qwen3-5b").filter { it != "-" })
        assertEquals(listOf("qwen", "3", "235b"), tokenize("qwen3-235b-a22b").filter { it != "-" }.take(3))
        assertEquals(listOf("qwen", "3", "8b"), tokenize("qwen3-8b").filter { it != "-" })
    }

    @Test
    fun `上下文长度同样不拆`() {
        assertTrue("32k 应是一个词", "32k" in tokenize("qwen3-32k"))
    }

    @Test
    fun `真版本号仍然拆开`() {
        assertEquals(listOf("qwen", "3", "5"), tokenize("qwen3.5").filter { it != "." })
        assertEquals(listOf("qwen", "3", "5"), tokenize("qwen3-5").filter { it != "-" })
        assertEquals(listOf("qwen", "3", "5", "turbo"), tokenize("qwen-3-5-turbo").filter { it != "-" })
    }

    @Test
    fun `参数量模型不被判成视觉`() {
        assertFalse("qwen3-5b 是纯文本模型", ModelRegistry.supportsVision("qwen3-5b"))
        assertFalse(ModelRegistry.supportsVision("qwen3-8b"))
        assertFalse(ModelRegistry.supportsVision("qwen3-32k"))
    }

    @Test
    fun `真视觉模型仍然被判成视觉`() {
        assertTrue("qwen3.5 是视觉模型", ModelRegistry.supportsVision("qwen3.5"))
        assertTrue(ModelRegistry.supportsVision("qwen3-5"))
        assertTrue(ModelRegistry.supportsVision("qwen2.5-vl-7b"))
    }
}
