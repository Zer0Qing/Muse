package io.zer0.muse.ui.settings

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.asr.AsrConfig
import io.zer0.muse.asr.AsrProviderType
import io.zer0.muse.data.MediaConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音模型选择（TTS / ASR）的候选与写入规则。
 *
 * 两件事最容易错，各自钉死：候选是不是来自供应商、选中后写的是 model 还是 modelId。
 * 另外旧配置里的手填值必须在候选之外也活下来 —— 打开设置页不能把用户的模型清掉。
 */
class SpeechModelPickerTest {

    private val provider = ProviderConfig(
        id = "p1",
        displayName = "Relay",
        baseUrl = "https://relay.example.com/v1",
        apiKey = "sk-provider",
        models = listOf(
            Model(id = "tts-1", name = "TTS 1", providerId = "p1"),
            Model(id = "tts-2", name = "TTS 2", providerId = "p1"),
        ),
    )

    private val emptyProvider = ProviderConfig(
        id = "p2",
        displayName = "空供应商",
        models = emptyList(),
    )

    private val disabledProvider = ProviderConfig(
        id = "p3",
        displayName = "停用的",
        enabled = false,
        models = listOf(Model(id = "m-disabled", name = "停用模型", providerId = "p3")),
    )

    // ── 候选列表 ────────────────────────────────────────────────────────

    @Test
    fun `供应商有模型时全部列出且顺序不变`() {
        val options = SpeechModelPicker.options(provider)

        assertEquals(
            "候选必须来自供应商的模型列表",
            listOf("tts-1" to "TTS 1", "tts-2" to "TTS 2"),
            options.map { it.id to it.name },
        )
    }

    @Test
    fun `供应商没有模型时候选为空`() {
        assertTrue("空供应商不该凭空造出候选", SpeechModelPicker.options(emptyProvider).isEmpty())
        assertTrue("没有供应商时候选也是空", SpeechModelPicker.options(null).isEmpty())
    }

    @Test
    fun `候选供应商优先取配置指定的那个`() {
        val picked = SpeechModelPicker.candidateProvider(listOf(disabledProvider, provider), "p1")

        assertEquals("p1", picked?.id)
    }

    @Test
    fun `没指定供应商时取第一个启用中的`() {
        val picked = SpeechModelPicker.candidateProvider(listOf(disabledProvider, provider), "")

        assertEquals("停用的供应商不能作为默认候选", "p1", picked?.id)
    }

    @Test
    fun `指定了供应商即便已停用也用它的模型`() {
        val picked = SpeechModelPicker.candidateProvider(listOf(provider, disabledProvider), "p3")

        assertEquals("用户可能正要改回这个供应商的模型，不能悄悄换掉候选", "p3", picked?.id)
    }

    // ── 旧值兼容 ────────────────────────────────────────────────────────

    @Test
    fun `手填值不在候选里时显示为自定义且不清空`() {
        val selection = SpeechModelPicker.selection(
            currentId = "legacy-model",
            options = SpeechModelPicker.options(provider),
        )

        assertTrue("旧配置的手填值必须落到自定义态，实际: $selection", selection is SpeechModelSelection.Custom)
        assertEquals("legacy-model", (selection as SpeechModelSelection.Custom).value)
    }

    @Test
    fun `值在候选里时显示供应商模型名`() {
        val selection = SpeechModelPicker.selection("tts-2", SpeechModelPicker.options(provider))

        assertEquals(
            "TTS 2",
            (selection as SpeechModelSelection.FromProvider).model.name,
        )
    }

    @Test
    fun `空值表示未选择`() {
        assertEquals(
            SpeechModelSelection.Empty,
            SpeechModelPicker.selection("   ", SpeechModelPicker.options(provider)),
        )
    }

    // ── 写入字段 ────────────────────────────────────────────────────────

    @Test
    fun `TTS 手动模式写 ttsModel 且不动 ttsModelId`() {
        val saved = SpeechModelPicker.applyToTts(
            config = MediaConfig(ttsEngine = "openai", ttsModel = "", ttsModelId = "", ttsFollowProvider = false),
            modelId = "tts-1",
        )

        assertEquals("tts-1", saved.ttsModel)
        assertEquals("", saved.ttsModelId)
    }

    @Test
    fun `TTS 跟随模式写 ttsModelId 且不动 ttsModel`() {
        val saved = SpeechModelPicker.applyToTts(
            config = MediaConfig(
                ttsEngine = "openai",
                ttsModel = "manual-model",
                ttsModelId = "",
                ttsFollowProvider = true,
            ),
            modelId = "tts-2",
        )

        assertEquals("tts-2", saved.ttsModelId)
        assertEquals("手动模式的值要留着，关掉开关还会用", "manual-model", saved.ttsModel)
    }

    @Test
    fun `ASR 手动模式写 model`() {
        val saved = SpeechModelPicker.applyToAsr(
            config = AsrConfig(
                provider = AsrProviderType.OPENAI_WHISPER,
                model = "whisper-1",
                modelId = "old-id",
                followProvider = false,
            ),
            modelId = "whisper-large-v3",
        )

        assertEquals("whisper-large-v3", saved.model)
        assertEquals("old-id", saved.modelId)
    }

    @Test
    fun `ASR 跟随模式写 modelId`() {
        val saved = SpeechModelPicker.applyToAsr(
            config = AsrConfig(
                provider = AsrProviderType.OPENAI_WHISPER,
                model = "whisper-1",
                modelId = "",
                followProvider = true,
                providerId = "p1",
            ),
            modelId = "tts-1",
        )

        assertEquals("tts-1", saved.modelId)
        assertEquals("whisper-1", saved.model)
    }

    @Test
    fun `ASR DashScope 即便开了跟随也写 model`() {
        val config = AsrConfig(
            provider = AsrProviderType.DASHSCOPE,
            model = "paraformer-realtime-v2",
            modelId = "",
            followProvider = true,
            providerId = "p1",
        )

        assertTrue("DashScope 走内置端点，跟随开关不生效", !SpeechModelPicker.asrFollowActive(config))
        assertEquals("paraformer-v2", SpeechModelPicker.applyToAsr(config, "paraformer-v2").model)
        assertEquals("", SpeechModelPicker.applyToAsr(config, "paraformer-v2").modelId)
    }

    @Test
    fun `没有供应商时自定义写入仍然生效`() {
        assertNull(SpeechModelPicker.candidateProvider(emptyList(), "p1"))

        val saved = SpeechModelPicker.applyToTts(MediaConfig(ttsEngine = "openai"), "my-model")

        assertEquals("my-model", saved.ttsModel)
    }
}
