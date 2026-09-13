package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Provider/model 联合引用的格式与兼容性测试。 */
class ModelReferenceTest {

    @Test
    fun `parses provider model reference without losing slash in model id`() {
        val reference = ModelReference.parse("siliconflow/Qwen/Qwen3-8B")

        assertEquals("siliconflow", reference?.providerId)
        assertEquals("Qwen/Qwen3-8B", reference?.modelId)
        assertEquals("siliconflow/Qwen/Qwen3-8B", reference?.asString())
    }

    @Test
    fun `rejects bare model id instead of guessing provider`() {
        assertNull(ModelReference.parse("deepseek-v4-flash"))
        assertNull(ModelReference.parse("/deepseek-v4-flash"))
        assertNull(ModelReference.parse("deepseek/"))
        assertNull(ModelReference.parse(null))
    }

    @Test
    fun `creates reference from resolved model`() {
        val model = Model(
            id = "deepseek-v4-flash",
            providerId = "sensenova",
        )

        assertEquals("sensenova/deepseek-v4-flash", ModelReference.of(model).asString())
    }
}
