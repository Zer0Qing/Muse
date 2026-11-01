package io.zer0.muse.data.routing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 辅助模型路由级联测试 — 三档留空规则:大工具留空复用小工具,小工具留空回退主模型。
 *
 * 注意:Robolectric 同类测试共享 DataStore 环境,所有 Provider id 带纳秒后缀保证隔离。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UtilityModelRouterTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newRepository(): SettingsRepository = SettingsRepository(context, mockk(relaxed = true))

    /** 生成唯一 Provider id,避免测试间 DataStore 残留串扰。 */
    private fun uniqueId(prefix: String): String = "$prefix-${System.nanoTime()}"

    private fun provider(id: String, vararg modelIds: String) = ProviderConfig(
        id = id,
        displayName = id,
        models = modelIds.map { Model(id = it, providerId = id) },
    )

    @Test
    fun resolveSmall_returnsBoundProviderAndModel() = runBlocking {
        val repo = newRepository()
        val pid = uniqueId("p-small")
        repo.addProvider(provider(pid, "small-a"))
        repo.saveUtilityModelBinding(UtilityModelBinding(providerId = pid, modelId = "small-a"))

        val resolved = UtilityModelRouter(repo).resolve(UtilityTier.SMALL)

        assertEquals(pid, resolved?.first?.id)
        assertEquals("small-a", resolved?.second?.id)
    }

    @Test
    fun resolveSmall_unbound_returnsNull() = runBlocking {
        val repo = newRepository()
        assertNull(UtilityModelRouter(repo).resolve(UtilityTier.SMALL))
    }

    @Test
    fun resolveLarge_usesOwnBindingFirst() = runBlocking {
        val repo = newRepository()
        val pid = uniqueId("p-large")
        repo.addProvider(provider(pid, "small-a", "large-a"))
        repo.saveUtilityModelBinding(UtilityModelBinding(providerId = pid, modelId = "small-a"))
        repo.saveUtilityLargeModelBinding(UtilityModelBinding(providerId = pid, modelId = "large-a"))

        val resolved = UtilityModelRouter(repo).resolve(UtilityTier.LARGE)

        assertEquals("large-a", resolved?.second?.id)
    }

    @Test
    fun resolveLarge_fallsBackToSmallWhenUnbound() = runBlocking {
        val repo = newRepository()
        val pid = uniqueId("p-fallback")
        repo.addProvider(provider(pid, "small-a"))
        repo.saveUtilityModelBinding(UtilityModelBinding(providerId = pid, modelId = "small-a"))

        val resolved = UtilityModelRouter(repo).resolve(UtilityTier.LARGE)

        assertEquals(pid, resolved?.first?.id)
        assertEquals("small-a", resolved?.second?.id)
    }

    @Test
    fun resolveLarge_bothUnbound_returnsNull() = runBlocking {
        val repo = newRepository()
        assertNull(UtilityModelRouter(repo).resolve(UtilityTier.LARGE))
    }

    @Test
    fun resolveVision_usesVisionKeys() = runBlocking {
        val repo = newRepository()
        val pid = uniqueId("p-vision")
        repo.addProvider(provider(pid, "vision-a"))
        repo.saveVisionModelId("vision-a")
        repo.saveVisionProviderId(pid)

        val resolved = UtilityModelRouter(repo).resolve(UtilityTier.VISION)

        assertEquals(pid, resolved?.first?.id)
        assertEquals("vision-a", resolved?.second?.id)
    }

    @Test
    fun resolveVision_missingProviderKey_returnsNull() = runBlocking {
        val repo = newRepository()
        val pid = uniqueId("p-vision-2")
        repo.addProvider(provider(pid, "vision-a"))
        repo.saveVisionProviderId(null)
        repo.saveVisionModelId("vision-a")

        assertNull(UtilityModelRouter(repo).resolve(UtilityTier.VISION))
    }

    @Test
    fun resolve_unknownProvider_returnsNull() = runBlocking {
        val repo = newRepository()
        repo.saveUtilityModelBinding(UtilityModelBinding(providerId = uniqueId("ghost"), modelId = "m"))

        assertNull(UtilityModelRouter(repo).resolve(UtilityTier.SMALL))
    }

    @Test
    fun resolve_unknownModel_returnsNull() = runBlocking {
        val repo = newRepository()
        val pid = uniqueId("p-nomodel")
        repo.addProvider(provider(pid, "small-a"))
        repo.saveUtilityModelBinding(UtilityModelBinding(providerId = pid, modelId = "ghost"))

        assertNull(UtilityModelRouter(repo).resolve(UtilityTier.SMALL))
    }

    @Test
    fun binding_persistsAndClears() = runBlocking {
        val repo = newRepository()
        val binding = UtilityModelBinding(providerId = uniqueId("p-persist"), modelId = "small-a")
        repo.saveUtilityModelBinding(binding)
        assertEquals(binding, repo.utilityModelBindingFlow.first())

        repo.saveUtilityModelBinding(null)
        assertNull(repo.utilityModelBindingFlow.first())
    }
}
