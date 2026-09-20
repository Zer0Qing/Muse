package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.data.skill.SkillEntity
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

    /** Phase 5 P0: 旧 skill 记录指向已被 manifest 移除的函数时，必须在进入 JS 沙盒前拒绝。 */
    @Test
    fun pluginTool_rejectsFunctionRemovedFromManifestBeforeSandbox() = runBlocking {
        val manager = mockk<PluginManager>(relaxed = true)
        every { manager.findPlugin("p1") } returns PluginManager.InstalledPlugin(
            id = "p1",
            name = "Plugin One",
            version = "1.0.0",
            enabled = true,
            installationConfirmed = true,
        )
        every { manager.loadVerifiedFunction("p1", "removed") } returns null
        val impl = SkillMediaToolsImpl(
            context = context,
            stickerLibraryRepository = null,
            imageService = null,
            imageDrawConfigProvider = { null to null },
            pluginManager = manager,
        )

        val output = impl.execPluginTool(
            SkillEntity(
                id = "plugin_2_p1_removed",
                name = "removed",
                description = "",
                implementationKotlin = "plugin:p1:removed",
            ),
            "{}",
        )

        assertTrue(output.contains("已移除") || output.contains("校验失败"))
    }
}
