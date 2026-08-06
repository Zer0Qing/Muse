package io.zer0.ai.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-20: 绘图模型目录解析与默认值测试。
 */
class ImageModelCatalogTest {

    @Test
    fun `default model is dalle 3`() {
        assertEquals("dall-e-3", ImageModelCatalog.DEFAULT_MODEL_ID)
        assertEquals("dall-e-3", ImageModelCatalog.defaultModel().id)
    }

    @Test
    fun `all contains known models`() {
        val ids = ImageModelCatalog.all().map { it.id }
        assertTrue("dall-e-2" in ids)
        assertTrue("dall-e-3" in ids)
        assertTrue("gpt-image-1" in ids)
        assertTrue("agnes-image-2.1-flash" in ids)
    }

    @Test
    fun `findById and contains handle known unknown and null`() {
        assertNull(ImageModelCatalog.findById(null))
        assertEquals("dall-e-3", ImageModelCatalog.findById("dall-e-3")?.id)
        assertNull(ImageModelCatalog.findById("missing"))
        assertTrue(ImageModelCatalog.contains("dall-e-3"))
        assertFalse(ImageModelCatalog.contains("missing"))
        assertFalse(ImageModelCatalog.contains(null))
    }

    @Test
    fun `defaultSizeFor uses catalog default or fallback`() {
        assertEquals("1024x1024", ImageModelCatalog.defaultSizeFor("dall-e-2"))
        assertEquals("3:2", ImageModelCatalog.defaultSizeFor("agnes-image-2.1-flash"))
        assertEquals("800x800", ImageModelCatalog.defaultSizeFor("missing", "800x800"))
    }

    @Test
    fun `resolveById returns catalog model for known id`() {
        val model = ImageModelCatalog.resolveById("gpt-image-1")
        assertEquals("gpt-image-1", model?.id)
        assertTrue(model?.supportsReferenceImage == true)
        assertEquals(false, model?.supportsResponseFormatParam)
    }

    @Test
    fun `resolveById creates conservative defaults for unknown id`() {
        val model = ImageModelCatalog.resolveById("custom-model")
        assertEquals("custom-model", model?.id)
        assertEquals(1, model?.maxN)
        assertFalse(model?.supportsReferenceImage == true)
        assertEquals(6, model?.supportedSizes?.size)
        assertNull(ImageModelCatalog.resolveById(null))
        assertNull(ImageModelCatalog.resolveById(""))
    }
}
