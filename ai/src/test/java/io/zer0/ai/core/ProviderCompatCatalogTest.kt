package io.zer0.ai.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.zer0.ai.registry.ModelRegistry as RuntimeModelRegistry
import io.zer0.common.Logger

/**
 * 兼容性派生优先级:[目录] > [硬编码表] > [默认值]。
 *
 * 目录(model-catalog)里显式声明了 `compat.thinkingFormat` 的模型,行为按目录走;
 * 目录没写(或词汇映射不了的)模型,行为与接入目录前完全一致 —— 仍由
 * [ProviderCompatRules] 的 host / 模型前缀硬编码表决定;两条都缺时回到默认 null。
 */
class ProviderCompatCatalogTest {

    private val openCodeGo = "https://opencode.ai/zen/go/v1"
    private val deepSeek = "https://api.deepseek.com/v1"
    private val kimi = "https://api.kimi.com/v1"
    private val siliconFlow = "https://api.siliconflow.cn/v1"
    private val unknownRelay = "https://tokenrhythm.studio/v1"

    @After
    fun tearDown() {
        RuntimeModelRegistry.installCatalog(ModelCatalog())
    }

    private fun install(providerId: String, modelId: String, compat: CompatProfile?) {
        RuntimeModelRegistry.installCatalog(
            ModelCatalog(
                providers = mapOf(providerId to mapOf(modelId to ModelCatalogEntry(compat = compat))),
            ),
        )
    }

    // ── 目录写了值 → 行为按目录值走 ──────────────────────────────

    @Test
    fun `catalog declaration overrides the host table`() {
        // 硬编码表在 opencode.ai 上判 DeepSeek 协议,目录对本模型声明的是 zhipu。
        assertEquals(
            ThinkingFormat.DEEPSEEK,
            ProviderCompatRules.resolve(ProviderType.OPENAI, openCodeGo, "glm-5.2").thinkingFormat,
        )

        install("opencode-go", "glm-5.2", CompatProfile(thinkingFormat = "zhipu"))

        assertEquals(
            ThinkingFormat.ZHIPU,
            ProviderCompatRules.resolve(ProviderType.OPENAI, openCodeGo, "glm-5.2").thinkingFormat,
        )
    }

    @Test
    fun `catalog declaration fills a model the table leaves at the default`() {
        // 硬编码表对 glm-5.2 无模型级规则,host 层也只落到 DeepSeek;目录声明 kimi 时应以目录为准。
        install("moonshot", "glm-5.2", CompatProfile(thinkingFormat = "kimi"))

        assertEquals(
            ThinkingFormat.KIMI,
            ProviderCompatRules.resolve(ProviderType.OPENAI, siliconFlow, "glm-5.2").thinkingFormat,
        )
    }

    @Test
    fun `model exposes the catalog declaration`() {
        install("opencode-go", "glm-5.2", CompatProfile(thinkingFormat = "zhipu"))

        assertEquals(
            ThinkingFormat.ZHIPU,
            Model(id = "glm-5.2", providerId = "opencode-go").catalogThinkingFormat,
        )
        assertNull(Model(id = "no-such-model", providerId = "opencode-go").catalogThinkingFormat)
    }

    // ── 目录没写值 → 回落硬编码表,行为与接入前一致 ────────────────

    @Test
    fun `catalog entry without compat keeps the hardcoded result`() {
        // 目录里有这个模型,但没写 compat → 仍按 api.deepseek.com 的 host 规则走 DEEPSEEK。
        install("deepseek", "deepseek-v4-flash", null)

        assertEquals(
            ThinkingFormat.DEEPSEEK,
            ProviderCompatRules.resolve(ProviderType.OPENAI, deepSeek, "deepseek-v4-flash").thinkingFormat,
        )
    }

    @Test
    fun `unknown model keeps the hardcoded host and prefix results`() {
        install("opencode-go", "glm-5.2", CompatProfile(thinkingFormat = "zhipu"))

        assertEquals(
            ThinkingFormat.DEEPSEEK,
            ProviderCompatRules.resolve(ProviderType.OPENAI, deepSeek, "deepseek-reasoner").thinkingFormat,
        )
        assertEquals(
            ThinkingFormat.KIMI,
            ProviderCompatRules.resolve(ProviderType.OPENAI, kimi, "kimi-k2.5").thinkingFormat,
        )
        assertEquals(
            ThinkingFormat.QWEN,
            ProviderCompatRules.resolve(ProviderType.OPENAI, siliconFlow, "Qwen/Qwen3.5-4B").thinkingFormat,
        )
        assertEquals(
            ThinkingFormat.ZHIPU,
            ProviderCompatRules.resolve(ProviderType.OPENAI, siliconFlow, "zhipu/GLM-4-thinking").thinkingFormat,
        )
    }

    @Test
    fun `unknown relay still refuses vendor extension fields`() {
        install("kimi-coding", "kimi-for-coding", CompatProfile(thinkingFormat = "kimi"))

        // 未知中转站不下发 thinking / enable_thinking 这类扩展字段(v1.0.53 的保守处理)。
        assertNull(ProviderCompatRules.resolve(ProviderType.OPENAI, unknownRelay, "kimi-for-coding").thinkingFormat)
        // 已知端点上才按目录走。
        assertEquals(
            ThinkingFormat.KIMI,
            ProviderCompatRules.resolve(ProviderType.OPENAI, kimi, "kimi-for-coding").thinkingFormat,
        )
    }

    @Test
    fun `native-protocol vocabulary is not mapped onto the openai compat field`() {
        RuntimeModelRegistry.installCatalog(
            ModelCatalog(
                providers = mapOf(
                    "anthropic" to mapOf(
                        "claude-opus-5" to ModelCatalogEntry(
                            compat = CompatProfile(thinkingFormat = "anthropic"),
                        ),
                    ),
                    "gemini" to mapOf(
                        "gemini-4-argon" to ModelCatalogEntry(
                            compat = CompatProfile(thinkingFormat = "google-thinking-level"),
                        ),
                    ),
                ),
            ),
        )

        // anthropic / google-thinking-level 由原生 Provider 自己处理,不能当 OpenAI 兼容链路的扩充字段。
        assertNull(ProviderCompatRules.resolve(ProviderType.OPENAI, siliconFlow, "claude-opus-5").thinkingFormat)
        assertNull(ProviderCompatRules.resolve(ProviderType.OPENAI, siliconFlow, "gemini-4-argon").thinkingFormat)
    }

    // ── 两者都缺失 → 默认值 ─────────────────────────────────────

    @Test
    fun `both missing yields the default`() {
        // 目录空 + host 未知 + 模型名不认识。
        assertNull(ProviderCompatRules.resolve(ProviderType.OPENAI, unknownRelay, "totally-unknown-model").thinkingFormat)
        // 目录空 + 已知 host 但表里也没声明。
        assertNull(ProviderCompatRules.resolve(ProviderType.OPENAI, siliconFlow, "totally-unknown-model").thinkingFormat)
        assertNull(ProviderCompatRules.resolve(ProviderType.OPENAI, "", "totally-unknown-model").thinkingFormat)
        // 不给 modelId 时也不会有目录命中。
        assertNull(ProviderCompatRules.resolve(ProviderType.OPENAI, siliconFlow).thinkingFormat)
    }

    // ── 同名跳供应商声明:只报警,不改行为 ────────────────────

    @Test
    fun `ambiguous cross-provider declaration is reported without changing behaviour`() {
        val lines = mutableListOf<String>()
        Logger.sink = { level, tag, msg, _ -> if (tag == "ProviderCompatRules") lines += "$level $msg" }
        try {
            // 形态 1:两家都声明且取值不同 → 报「冲突」,行为仍是先出现者获胜。
            RuntimeModelRegistry.installCatalog(
                ModelCatalog(
                    providers = mapOf(
                        "opencode-go" to mapOf(
                            "glm-5.2" to ModelCatalogEntry(compat = CompatProfile(thinkingFormat = "zhipu")),
                        ),
                        "zhipu" to mapOf(
                            "glm-5.2" to ModelCatalogEntry(compat = CompatProfile(thinkingFormat = "kimi")),
                        ),
                    ),
                ),
            )
            assertEquals(1, lines.size)
            assertTrue(lines.single().contains("目录声明冲突"))
            assertTrue(lines.single().contains("opencode-go=zhipu"))
            assertTrue(lines.single().contains("zhipu=kimi"))
            assertEquals(
                ThinkingFormat.ZHIPU,
                ProviderCompatRules.resolve(ProviderType.OPENAI, siliconFlow, "glm-5.2").thinkingFormat,
            )

            // 形态 2:只有一家声明 → 报「单边声明」(未声明的供应商借了别人的宣告)。
            lines.clear()
            RuntimeModelRegistry.installCatalog(
                ModelCatalog(
                    providers = mapOf(
                        "opencode-go" to mapOf(
                            "glm-5.2" to ModelCatalogEntry(compat = CompatProfile(thinkingFormat = "zhipu")),
                        ),
                        "zhipu" to mapOf("glm-5.2" to ModelCatalogEntry()),
                    ),
                ),
            )
            assertEquals(1, lines.size)
            assertTrue(lines.single().contains("目录单边声明"))
            assertTrue(lines.single().contains("zhipu=<未声明>"))
        } finally {
            Logger.sink = null
        }
    }

    @Test
    fun `single provider declaration logs nothing`() {
        val lines = mutableListOf<String>()
        Logger.sink = { level, tag, msg, _ -> if (tag == "ProviderCompatRules") lines += "$level $msg" }
        try {
            install("opencode-go", "glm-5.2", CompatProfile(thinkingFormat = "zhipu"))
            assertTrue(lines.isEmpty())
        } finally {
            Logger.sink = null
        }
    }

    @Test
    fun `other compat fields are untouched by the catalog`() {
        install("opencode-go", "glm-5.2", CompatProfile(thinkingFormat = "zhipu", reasoningProfile = "zhipu-openai"))

        val compat = ProviderCompatRules.resolve(ProviderType.OPENAI, openCodeGo, "glm-5.2")

        // 目录只覆盖 thinkingFormat;回放契约等仍按原有链路派生(opencode.ai → DeepSeek 协议)。
        assertEquals(ReasoningCarrier.REASONING_CONTENT, compat.reasoningReplayContract?.carrier)
        assertEquals(ReasoningReplayPolicy.REQUIRE_TOOL_CALL, compat.reasoningReplayContract?.policy)
    }
}
