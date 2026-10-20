package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageBubbleLayoutTest {

    @Test
    fun assistant_content_layer_uses_full_width_without_an_opaque_outer_surface() {
        val layout = messageBubbleLayout(MessageBubbleRole.ASSISTANT)

        assertEquals(1f, layout.widthFraction)
        assertEquals(false, layout.hasOpaqueOuterSurface)
    }

    @Test
    fun user_content_keeps_its_bubble_surface() {
        val layout = messageBubbleLayout(MessageBubbleRole.USER)

        // v2.6.6: 用户气泡宽度从 0.78 放宽到 0.85，
        // 长消息每行能多排一些字，不再显得拥挤。
        assertEquals(0.85f, layout.widthFraction)
        assertEquals(true, layout.hasOpaqueOuterSurface)
    }
}
