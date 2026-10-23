package io.zer0.muse.ui.common.media

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class AssistantAvatarMemoryWiringTest {

    @Test
    fun `assistant avatar constrains Coil decode to rendered size`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/common/media/AssistantAvatar.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/common/media/AssistantAvatar.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("AssistantAvatar.kt not found")

        assertTrue("头像请求必须经过有界尺寸 helper", source.contains("assistantAvatarImageRequest"))
        assertTrue("头像请求必须传入渲染尺寸，不能按原图解码", source.contains(".size(boundedSize, boundedSize)"))
        assertTrue("渲染尺寸必须从实际 dp 约束换算为像素", source.contains("density.density"))
    }
}
