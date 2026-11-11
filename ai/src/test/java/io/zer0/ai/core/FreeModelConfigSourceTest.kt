package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 免费模型远端派发合同。
 *
 * 断言三层解析顺序（服务端 → 缓存 → 内置默认）、失败静默回退、字段校验与缓存落盘。
 */
class FreeModelConfigSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val builtIn = FreeModelRemoteConfig(
        version = 1,
        baseUrl = "https://builtin.example.com/v1",
        model = "builtin-model",
        apiKey = "builtin-key",
    )

    private fun source(
        dispatchUrl: String = "https://dispatch.example.com/free",
        cache: File? = null,
        response: String? = null,
        throwOnFetch: Boolean = false,
        clock: () -> Long = { 0L },
    ) = FreeModelConfigSource(
        dispatchUrl = dispatchUrl,
        cacheFile = cache,
        builtInDefault = builtIn,
        fetch = { if (throwOnFetch) throw java.io.IOException("boom") else response },
        clock = clock,
    )

    @Test
    fun `falls back to built-in default when nothing fetched`() {
        val s = source(dispatchUrl = "")
        assertEquals(builtIn, s.current())
        assertEquals(FreeModelConfigSource.FetchOutcome.Disabled, s.refresh())
    }

    @Test
    fun `remote config is applied and wins over built-in`() {
        val json = """
            {"version":2,"baseUrl":"https://remote.example.com/v1","model":"remote-model","apiKey":"remote-key"}
        """.trimIndent()
        val s = source(response = json)
        val outcome = s.refresh(force = true)
        assertTrue(outcome is FreeModelConfigSource.FetchOutcome.Applied)
        assertEquals("remote-model", s.current().model)
        assertEquals("https://remote.example.com/v1", s.current().baseUrl)
    }

    @Test
    fun `envelope form is accepted`() {
        val json = """
            {"data":{"version":3,"baseUrl":"https://env.example.com/v1","model":"env-model","apiKey":"env-key"}}
        """.trimIndent()
        val s = source(response = json)
        s.refresh(force = true)
        assertEquals("env-model", s.current().model)
    }

    @Test
    fun `fetch failure keeps built-in and reports reason`() {
        val s = source(throwOnFetch = true)
        val outcome = s.refresh(force = true)
        assertTrue(outcome is FreeModelConfigSource.FetchOutcome.Failed)
        assertEquals("失败必须回退内置默认", builtIn, s.current())
    }

    @Test
    fun `incomplete remote config is rejected`() {
        val json = """{"version":2,"baseUrl":"https://x.example.com/v1","model":"m","apiKey":""}"""
        val s = source(response = json)
        val outcome = s.refresh(force = true)
        assertTrue("缺密钥必须拒绝", outcome is FreeModelConfigSource.FetchOutcome.Failed)
        assertEquals(builtIn, s.current())
    }

    @Test
    fun `malformed json is rejected without throwing`() {
        val s = source(response = "{ not json ")
        val outcome = s.refresh(force = true)
        assertTrue(outcome is FreeModelConfigSource.FetchOutcome.Failed)
        assertEquals(builtIn, s.current())
    }

    @Test
    fun `cache is written and reloaded after restart`() {
        val cache = tmp.newFile("free_model_config.json").also { it.delete() }
        val json = """
            {"version":5,"baseUrl":"https://cached.example.com/v1","model":"cached-model","apiKey":"cached-key"}
        """.trimIndent()
        source(cache = cache, response = json).refresh(force = true)
        assertTrue("缓存文件必须落盘", cache.isFile)

        // 新实例(模拟进程重启)且派发不可用:应从缓存读出。
        val restarted = source(cache = cache, dispatchUrl = "")
        assertEquals("cached-model", restarted.current().model)
    }

    @Test
    fun `throttle prevents repeated fetch within interval`() {
        var now = 1_000_000L
        val s = source(response = """{"version":2,"baseUrl":"https://a.example.com/v1","model":"a","apiKey":"k"}""") {
            now
        }
        assertTrue(s.refresh() is FreeModelConfigSource.FetchOutcome.Applied)
        now += 1_000L
        assertEquals(
            "节流窗口内不得重复拉取",
            FreeModelConfigSource.FetchOutcome.Throttled,
            s.refresh(),
        )
        now += FreeModelConfigSource.DEFAULT_REFRESH_INTERVAL_MS
        assertTrue(s.refresh() is FreeModelConfigSource.FetchOutcome.Applied)
    }

    @Test
    fun `rolled back version is accepted but flagged`() {
        val cache = tmp.newFile("c.json").also { it.delete() }
        val v5 = """{"version":5,"baseUrl":"https://v5.example.com/v1","model":"m5","apiKey":"k5"}"""
        val v3 = """{"version":3,"baseUrl":"https://v3.example.com/v1","model":"m3","apiKey":"k3"}"""
        val s = source(cache = cache, response = v5)
        s.refresh(force = true)
        val rolled = FreeModelConfigSource(
            dispatchUrl = "https://dispatch.example.com/free",
            cacheFile = cache,
            builtInDefault = builtIn,
            fetch = { v3 },
            clock = { 0L },
        ).let { fresh ->
            // 让新实例先读缓存拿到 v5,再拉 v3。
            fresh.current()
            fresh.refresh(force = true)
        }
        assertTrue("回滚版本必须被标记", (rolled as FreeModelConfigSource.FetchOutcome.Applied).rolledBack)
    }

    @Test
    fun `isUsable requires baseUrl model and apiKey`() {
        assertFalse(FreeModelRemoteConfig(baseUrl = "", model = "m", apiKey = "k").isUsable)
        assertFalse(FreeModelRemoteConfig(baseUrl = "u", model = "", apiKey = "k").isUsable)
        assertFalse(FreeModelRemoteConfig(baseUrl = "u", model = "m", apiKey = "").isUsable)
        assertTrue(FreeModelRemoteConfig(baseUrl = "u", model = "m", apiKey = "k").isUsable)
    }

    @Test
    fun `effective display name falls back to model id`() {
        val noName = FreeModelRemoteConfig(baseUrl = "u", model = "m", apiKey = "k")
        assertEquals("m", noName.effectiveDisplayName)
        assertNotNull(noName.copy(displayName = "Nice Name").effectiveDisplayName)
        assertEquals("Nice Name", noName.copy(displayName = "Nice Name").effectiveDisplayName)
    }

    @Test
    fun `apply ignores unusable config`() {
        val s = source(dispatchUrl = "")
        s.apply(FreeModelRemoteConfig(baseUrl = "", model = "m", apiKey = "k"))
        assertEquals("不可用配置不得覆盖", builtIn, s.current())
        assertNull(null)
    }

    @Test
    fun `parses the exact shape served by museai ltd`() {
        // 与 museai.ltd/scripts/free-model/free-model.example.json 保持同一形状，
        // 防止服务端与客户端字段名漂移（这类漂移只会在真机上表现为"一直是内置默认值"）。
        val served = """
            {
              "version": 1,
              "baseUrl": "https://apihub.agnes-ai.com/v1",
              "model": "agnes-2.5-flash",
              "apiKey": "sk-test",
              "displayName": "",
              "contextWindow": 512000,
              "maxOutputTokens": 65536,
              "supportsVision": true,
              "supportsTools": true,
              "supportsReasoning": true,
              "rotationNote": "test"
            }
        """.trimIndent()
        val s = source(response = served)
        s.refresh(force = true)
        val config = s.current()
        assertEquals("agnes-2.5-flash", config.model)
        assertEquals("https://apihub.agnes-ai.com/v1", config.baseUrl)
        assertEquals(512_000, config.contextWindow)
        assertEquals(65_536, config.maxOutputTokens)
        assertTrue("vision 必须解析", config.supportsVision)
        assertTrue("tools 必须解析", config.supportsTools)
        assertTrue("reasoning 必须解析", config.supportsReasoning)
        assertTrue("空 displayName 时回退到 model", config.effectiveDisplayName == "agnes-2.5-flash")
    }}
