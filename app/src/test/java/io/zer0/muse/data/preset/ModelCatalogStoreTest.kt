package io.zer0.muse.data.preset

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelCatalogStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun builtinEntries_containsOpenAiModels() {
        val store = ModelCatalogStore(context)
        val openai = store.entries("openai")
        assertTrue(openai.isNotEmpty())
        assertTrue(openai.any { it.modelId == "gpt-4o" })
    }

    @Test
    fun userOverride_winsAndPreservesOtherFields() {
        val store = ModelCatalogStore(context)
        store.saveUserOverride(
            "openai",
            "gpt-4o",
            ModelCatalogStore.ModelCatalogEntry(
                providerId = "openai",
                modelId = "gpt-4o",
                contextWindow = 999_999,
            ),
        )

        val merged = store.find("openai", "gpt-4o")
        assertNotNull(merged)
        assertEquals(999_999, merged?.contextWindow)
        assertEquals("GPT-4o", merged?.displayName)
        assertTrue(merged?.supportsVision == true)
        assertTrue(merged?.userEdited == true)
    }

    @Test
    fun removeModel_hidesBuiltinButKeepsCatalogData() {
        val store = ModelCatalogStore(context)
        store.removeModel("openai", "gpt-4o")

        assertNull(store.find("openai", "gpt-4o"))
        // 内置数据仍在，只是被用户删除标记隐藏
        assertTrue(store.builtinEntries().any { it.modelId == "gpt-4o" })
    }

    @Test
    fun resetModel_restoresBuiltin() {
        val store = ModelCatalogStore(context)
        store.saveUserOverride(
            "openai",
            "gpt-4o",
            ModelCatalogStore.ModelCatalogEntry(
                providerId = "openai",
                modelId = "gpt-4o",
                contextWindow = 123,
            ),
        )
        store.resetModel("openai", "gpt-4o")

        val merged = store.find("openai", "gpt-4o")
        assertNotNull(merged)
        assertEquals(128_000, merged?.contextWindow)
        assertFalse(merged?.userEdited == true)
    }

    @Test
    fun addUserModel_appendsUnknownModel() {
        val store = ModelCatalogStore(context)
        store.addUserModel("openai", "my-custom-model", "My Custom")

        val merged = store.find("openai", "my-custom-model")
        assertNotNull(merged)
        assertEquals("My Custom", merged?.displayName)
    }

    @Test
    fun exportImport_roundTripsUserOverrides() {
        val store = ModelCatalogStore(context)
        store.saveUserOverride(
            "anthropic",
            "claude-sonnet-4-5-20250514",
            ModelCatalogStore.ModelCatalogEntry(
                providerId = "anthropic",
                modelId = "claude-sonnet-4-5-20250514",
                maxOutputTokens = 77_777,
            ),
        )
        val json = store.exportJson()

        val store2 = ModelCatalogStore(context)
        // 清掉第一个 store 的缓存，模拟新实例
        assertTrue(store2.importJson(json))
        val merged = store2.find("anthropic", "claude-sonnet-4-5-20250514")
        assertNotNull(merged)
        assertEquals(77_777, merged?.maxOutputTokens)
    }
}
