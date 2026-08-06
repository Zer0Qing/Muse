package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserManagerInstrumentedTest {

    @Test
    fun navigateAndEvaluateOnDevice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = BrowserManager(context)
        try {
            withTimeout(30_000) {
                val blank = manager.navigate("about:blank")
                assertTrue("about:blank 导航失败: ${blank.exceptionOrNull()}", blank.isSuccess)

                val js = manager.evaluateJs("1+1")
                assertTrue("evaluateJs 失败: ${js.exceptionOrNull()}", js.isSuccess)
                assertEquals("2", js.getOrNull())

                val web = manager.navigate("file:///android_asset/test_browser_page.html")
                assertTrue("本地测试页导航失败: ${web.exceptionOrNull()}", web.isSuccess)

                var html = ""
                repeat(25) {
                    html = manager.currentHtml.value
                    if (html.contains("Muse Local Test")) return@repeat
                    delay(200)
                }
                assertTrue("本地测试页 HTML 缺失: $html", html.contains("Muse Local Test"))
            }
        } finally {
            manager.close()
        }
    }

    @Test
    fun browserToolsWorkThroughToolRegistry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = ToolRegistry(context)
        val names = registry.listTools().map { it.name }
        for (name in listOf(
            "browser_navigate", "browser_click", "browser_type",
            "browser_extract", "browser_scroll_bottom", "browser_get_html",
        )) {
            assertTrue("ToolRegistry 缺少 $name", name in names)
        }

        val nav = registry.executeFromJson(
            "browser_navigate",
            """{"url": "file:///android_asset/test_browser_page.html"}""",
        )
        assertTrue("browser_navigate 结果异常: $nav", nav.contains("\"success\":true"))

        var htmlResult = ""
        repeat(25) {
            htmlResult = registry.executeFromJson("browser_get_html", "{}")
            if (htmlResult.contains("Muse Local Test")) return@repeat
            delay(200)
        }
        assertTrue("browser_get_html 结果异常: $htmlResult", htmlResult.contains("Muse Local Test"))
    }
}
