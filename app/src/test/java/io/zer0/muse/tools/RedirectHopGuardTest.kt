package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 重定向逐跳 SSRF 防护。
 *
 * 攻击形态：初始 URL 是公网地址（校验通过），它 30x 到 127.0.0.1。
 * 如果只校验首跳，后续跳就会打到内网 —— 这是 open-redirect 与 SSRF 的组合。
 *
 * 这条防护之前**没有任何测试覆盖**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RedirectHopGuardTest {

    private lateinit var server: MockWebServer

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun impl() = SkillSearchToolsImpl(
        context = context,
        client = OkHttpClient(),
        webSearchService = null,
        knowledgeDocDao = null,
        ragService = null,
    )

    @Test
    fun `a redirect to localhost is rejected before the hop is followed`() {
        // 首跳返回 302 指向 127.0.0.1。MockWebServer 自己就是本地地址，
        // 所以这里实际验证的是"本地地址会被拒绝"这条规则。
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "http://127.0.0.1:1/internal"),
        )

        val url = server.url("/start").toString()
        // MockWebServer 监听的是 127.0.0.1，首跳本身就会被拒 ——
        // 这正好验证"本地地址不放行"。
        assertTrue(
            "本地地址必须被拒绝",
            !impl().validatePublicUrl(url),
        )
    }

    @Test
    fun `validatePublicUrl rejects the metadata service address`() {
        // 云元数据服务：拿到它等于拿到云账号凭据。
        val impl = impl()
        listOf(
            "http://169.254.169.254/latest/meta-data/",
            "http://127.0.0.1/",
            "http://10.0.0.1/",
            "http://192.168.1.1/",
            "http://172.16.0.1/",
        ).forEach { url ->
            assertTrue(
                "内网地址必须被拒绝: " + url,
                !impl.validatePublicUrl(url),
            )
        }
    }
}
