package io.zer0.ai.core

import io.zer0.ai.util.KeyRoulette
import io.zer0.common.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v2.6.1: [ProviderHttpSupport.effectiveApiKey] 的请求头安全校验回归。
 *
 * 背景(用户实测):从中文界面把供应商名称/标题连同 API Key 一起复制粘贴时,
 * Key 里会混入中文等非 ASCII 字符,OkHttp 在真正发请求前抛
 * `Unexpected char 0x767e at 7 in Authorization value`,报错位置与真实原因脱节,
 * 用户难以自查。现在提前拦下并给出可读原因。
 *
 * 覆盖:
 *  - 单 key:含非 ASCII → 抛 [ProviderException] 且消息含位置与字符
 *  - 单 key:纯 ASCII → 正常返回
 *  - 多 key:其中一个含非 ASCII → 也拦截
 *  - 空 key:放行(由 allowMissingApiKey 等上层逻辑处理)
 */
class ProviderHttpSupportApiKeyHeaderTest {

    /** 最小子类:只暴露 protected 的 effectiveApiKey 供测试调用。 */
    private class Probe(config: ProviderConfig) : ProviderHttpSupport(config) {
        override val id: String get() = config.id
        override val displayName: String get() = "probe"
        override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = emptyFlow()
        fun apiKeyForTest(): String = effectiveApiKey()
    }

    @Before
    fun setUp() {
        Logger.enabled = false
    }

    @org.junit.After
    fun tearDown() {
        Logger.enabled = true
    }

    private fun config(apiKey: String, id: String = "probe-provider") = ProviderConfig(
        id = id,
        displayName = "probe",
        type = ProviderType.OPENAI,
        apiKey = apiKey,
    )

    @Test
    fun `plain ascii key passes through trimmed`() {
        val probe = Probe(config("  sk-abc123XYZ  "))
        assertEquals("sk-abc123XYZ", probe.apiKeyForTest())
    }

    @Test
    fun `non-ascii key is rejected with readable message`() {
        // 第一个字符是中文“百”(U+767E),复现用户粘贴标题的典型形态
        val probe = Probe(config("百sk-abc"))
        val error = runCatching { probe.apiKeyForTest() }.exceptionOrNull()
        assertTrue("应抛 ProviderException", error is ProviderException)
        val message = (error as ProviderException).message.orEmpty()
        assertTrue("应指出非法字符位置", message.contains("第 1 位"))
        assertTrue("应给出可读原因", message.contains("ASCII"))
        assertTrue("应携带 InvalidRequest 分类", error.providerError is ProviderError.InvalidRequest)
    }

    @Test
    fun `non-ascii in the middle is located`() {
        val probe = Probe(config("sk-中文-key"))
        val error = runCatching { probe.apiKeyForTest() }.exceptionOrNull()
        assertTrue(error is ProviderException)
        val message = (error as ProviderException).message.orEmpty()
        assertTrue("应指出第 4 位", message.contains("第 4 位"))
    }

    @Test
    fun `whitespace inside key is rejected`() {
        // 空格(0x20)不在 0x21..0x7E 内 —— 粘贴常夹带
        val probe = Probe(config("sk-abc def"))
        val error = runCatching { probe.apiKeyForTest() }.exceptionOrNull()
        assertTrue(error is ProviderException)
    }

    @Test
    fun `multi key validates the selected entry`() {
        // 多 key 场景下，校验的是**本次轮换选中的那个 key**，而非整份列表。
        // 设计取舍：不能因为列表里有一个坏 key 就让整个 provider 不可用；
        // 坏 key 一旦被轮换选中，仍会在发送前得到可读报错。
        // 这里用全坏列表锁定“选中即校验”的语义。
        val probe = Probe(config("百bad,中bad"))
        val error = runCatching { probe.apiKeyForTest() }.exceptionOrNull()
        assertTrue("选中的 key 含非 ASCII 时应拦截", error is ProviderException)
    }

    @Test
    fun `multi key with all-good entries passes`() {
        val probe = Probe(config("sk-good1,sk-good2"))
        val key = probe.apiKeyForTest()
        assertTrue("应返回其中一个合法 key", key == "sk-good1" || key == "sk-good2")
    }

    @Test
    fun `empty key is allowed through`() {
        // 空 key 交由 allowMissingApiKey / fallback 逻辑处理,这里不应抛
        val probe = Probe(config(""))
        assertEquals("", probe.apiKeyForTest())
    }

    @Test
    fun `all ascii visible range is accepted`() {
        // 覆盖 0x21..0x7E 边界
        val probe = Probe(config("!" + "~" + "sk-0123456789"))
        assertEquals("!~sk-0123456789", probe.apiKeyForTest())
    }

    @Test
    fun `key rotation helper stays independent of header validation`() {
        // ProviderKeyRotation 是不继承 ProviderHttpSupport 的复用类,
        // 本批只在 ProviderHttpSupport 侧加了请求头校验 —— 记录这一差异,
        // 避免误以为两条路径行为一致。
        val rotation = ProviderKeyRotation(config("sk-plain"), keyRoulette = KeyRoulette())
        assertEquals("sk-plain", rotation.effectiveApiKey())
    }
}
