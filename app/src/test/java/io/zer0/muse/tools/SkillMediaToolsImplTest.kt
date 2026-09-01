package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SkillMediaToolsImplTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun withoutRepositories_returnsFriendlyErrors() = runBlocking {
        val impl = SkillMediaToolsImpl(
            context = context,
            stickerLibraryRepository = null,
            imageService = null,
            imageDrawConfigProvider = { null to null },
            pluginManager = null,
        )
        val stickers = impl.execListStickers(emptyMap())
        assertTrue(stickers.isNotBlank())

        val image = impl.execGenerateImage(mapOf("prompt" to "cat"))
        assertTrue(image.isNotBlank())
        assertFalse(image.contains("Exception"))
    }
}
