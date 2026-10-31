package io.zer0.muse.web

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.SettingsRepositoryTestApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `settings.update` → 真 [SettingsRepository] → 再读回来。
 *
 * 前面的 [HostSettingsCommandsTest] 用假仓储验校验逻辑，这里用真 DataStore 验“写进去了没”：
 * 类型校验通过之后，字符串必须在落库那一步被翻成仓库认识的值，而不是只在内存里过了一手。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SettingsRepositoryTestApplication::class)
class HostSettingsPersistenceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * 整个测试类共用一个仓储。
     *
     * 每题新建一个 `SettingsRepository` 会让多个 DataStore 实例同时据着同一个 preferences 文件，
     * Windows 下下一次写入/改名就会 `AccessDeniedException`（而且会连累同一 JVM 里其他要用设置库的用例）。
     */
    private val settings: SettingsRepository by lazy { SettingsRepository(context, mockk(relaxed = true)) }

    /** 每题拿到的都是同一个实例（见 [settings] 的说明）。 */
    private fun repository(): SettingsRepository = settings

    private fun commands(settings: SettingsRepository) = HostSettingsCommands(
        RepositorySettingsAccess(settings),
        HostSettingsStrings { name, _ -> name },
    )

    @Test
    fun `boolean write lands in the repository`() = runBlocking {
        val settings = repository()

        assertEquals(
            HostSettingsUpdateResult.Applied(HostSettingKeys.PII_GUARD),
            commands(settings).update(HostSettingKeys.PII_GUARD, "true"),
        )
        assertTrue("布尔写入必须落库", settings.piiGuardEnabledFlow.first())

        commands(settings).update(HostSettingKeys.PII_GUARD, "false")
        assertFalse("再写 false 必须覆盖回去", settings.piiGuardEnabledFlow.first())
    }

    @Test
    fun `number write lands in the repository`() = runBlocking {
        val settings = repository()

        assertEquals(
            HostSettingsUpdateResult.Applied("liquidGlassStrength"),
            commands(settings).update("liquidGlassStrength", "70"),
        )
        assertEquals(70, settings.liquidGlassStrengthFlow.first())
    }

    @Test
    fun `enum write lands in the repository`() = runBlocking {
        val settings = repository()

        assertEquals(HostSettingsUpdateResult.Applied("themeMode"), commands(settings).update("themeMode", "dark"))
        assertEquals("dark", settings.themeModeFlow.first())

        commands(settings).update("fontSizeScale", "xlarge")
        assertEquals("xlarge", settings.fontSizeScaleFlow.first())

        commands(settings).update("language", "ja")
        assertEquals("ja", settings.languageFlow.first())
    }

    @Test
    fun `chat preference enum and booleans land in the repository`() = runBlocking {
        val settings = repository()

        assertEquals(
            HostSettingsUpdateResult.Applied("chatPreferences.reasoningLevel"),
            commands(settings).update("chatPreferences.reasoningLevel", "MEDIUM"),
        )
        commands(settings).update("chatPreferences.showTimestamp", "true")
        commands(settings).update("chatPreferences.forceReasoning", "true")

        val prefs = settings.chatPreferencesFlow.first()
        assertEquals(io.zer0.ai.core.ReasoningLevel.MEDIUM, prefs.forceReasoningLevel)
        assertTrue(prefs.showTimestamp)
        assertTrue(prefs.forceReasoning)
    }

    @Test
    fun `media config writes keep the rest of the config intact`() = runBlocking {
        val settings = repository()
        val before = settings.mediaConfigFlow.first()

        assertEquals(
            HostSettingsUpdateResult.Applied("mediaConfig.ttsEngine"),
            commands(settings).update("mediaConfig.ttsEngine", "openai"),
        )
        commands(settings).update("mediaConfig.ttsSpeechRate", "1.25")

        val after = settings.mediaConfigFlow.first()
        assertEquals("openai", after.ttsEngine)
        assertEquals(1.25, after.ttsSpeechRate.toDouble(), 1e-6)
        assertEquals("改一个字段不能顺手重置别的字段", before.ttsPitch.toDouble(), after.ttsPitch.toDouble(), 1e-6)
    }

    @Test
    fun `rejected writes leave the repository untouched`() = runBlocking {
        val settings = repository()
        val before = settings.themeModeFlow.first()

        assertTrue(commands(settings).update("themeMode", "purple") is HostSettingsUpdateResult.Rejected)
        assertTrue(commands(settings).update("liquidGlassStrength", "101") is HostSettingsUpdateResult.Rejected)
        assertTrue(commands(settings).update("notAKey", "true") is HostSettingsUpdateResult.Rejected)

        assertEquals("被拒的写入不得改库", before, settings.themeModeFlow.first())
    }

    @Test
    fun `snapshot reflects what was just written`() = runBlocking {
        val settings = repository()
        val api = commands(settings)

        api.update("themeMode", "light")
        api.update("liquidGlassStrength", "35")
        api.update("mediaConfig.ttsEngine", "edge")

        val values = api.snapshot().values.associateBy { it.key }
        assertEquals("light", values.getValue("themeMode").value)
        assertEquals("35", values.getValue("liquidGlassStrength").value)
        assertEquals("edge", values.getValue("mediaConfig.ttsEngine").value)
        // 派生值跟仓库走（预设供应商可能已让 isConfigured 为真），不写死一个期望值。
        assertEquals(
            (settings.providerConfigFlow.first() != null).toString(),
            values.getValue("model.configured").value,
        )
    }
}
