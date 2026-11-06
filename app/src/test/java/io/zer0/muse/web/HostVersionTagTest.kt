package io.zer0.muse.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6:构建信息注入的纯函数测试。
 *
 * 这段代码改的是**要交给浏览器的 HTML**,所以重点不是「有没有加上」,而是
 * 「有没有把页面结构改坏」:位置错、重复插、版本串里的引号没处理,
 * 后果都是白屏或脚本执行失败 —— 恰好是它想修的那个症状。
 */
class HostVersionTagTest {

    private val template = """
        <!doctype html>
        <html lang="zh-CN">
          <head>
            ${HostVersionTag.TOKEN}
            <title>Muse</title>
          </head>
          <body><div id="root"></div></body>
        </html>
    """.trimIndent()

    @Test
    fun `占位符被替换成一段脚本,且只出现一次`() {
        val html = HostVersionTag.inject(template, "2.7.0", 1)

        assertFalse("占位符不该留在产物里", html.contains(HostVersionTag.TOKEN))
        assertEquals("注入必须恰好一次", 1, Regex("""id="muse-host-build"""").findAll(html).count())
        assertTrue("脚本要在 head 里,不能落到 body 之后", html.indexOf("<title>") > html.indexOf("muse-host-build"))
    }

    @Test
    fun `没有占位符的旧资源在 head 开始标签后插入`() {
        val legacy = template.replace(HostVersionTag.TOKEN, "<meta charset=\"UTF-8\" />")
        val html = HostVersionTag.inject(legacy, "2.7.0", 1)

        assertEquals("旧资源同样要拿到构建信息", 1, Regex("""id="muse-host-build"""").findAll(html).count())
        val headOpen = html.indexOf("<head")
        val injected = html.indexOf("muse-host-build")
        assertTrue("必须插在 head 开标签之后", injected > headOpen)
        assertTrue("必须仍在 head 内(在 title 之前)", injected < html.indexOf("<title>"))
    }

    @Test
    fun `认不出的结构原样返回而不是硬塞`() {
        val odd = "<!doctype html><p>没有 head 的页面</p>"

        assertEquals("改不动就不改:宁可没有提示,也不能把 HTML 弄坏", odd, HostVersionTag.inject(odd, "2.7.0", 1))
    }

    @Test
    fun `版本串里的引号与反斜杠被转义`() {
        // 版本名理论上来自构建参数;一旦带引号就会把 window.__MUSE_HOST_BUILD__ 的赋值截断,
        // 那是「一条构建参数让网页端白屏」的走法。
        val html = HostVersionTag.inject(template, "2.7.0\"-evil\\x", 2)

        assertTrue("引号必须被转义", html.contains("\\\"-evil"))
        assertTrue("反斜杠必须被双写", html.contains("\\\\x"))
        assertFalse("原始引号不许出现在脚本里", html.contains("__MUSE_HOST_BUILD__={\"appVersion\":\"2.7.0\"-evil"))
    }

    @Test
    fun `协议版本是数字而不是字符串`() {
        // 客户端拿它做数值比较;写成 "1" 会让 `!== 1` 恒成立,变成每次升级都弹提示。
        val script = HostVersionTag.script("2.7.0", 3)

        assertTrue(script, script.contains("\"protocolVersion\":3"))
        assertFalse(script, script.contains("\"protocolVersion\":\"3\""))
    }

    @Test
    fun `已注入过的产物不重复注入`() {
        val once = HostVersionTag.inject(template, "2.7.0", 1)

        assertTrue(HostVersionTag.isAlreadyInjected(once))
        assertFalse("未注入的模板不许被误判成已注入", HostVersionTag.isAlreadyInjected(template))
        val twice = if (HostVersionTag.isAlreadyInjected(once)) once else HostVersionTag.inject(once, "2.7.1", 2)
        assertEquals(1, Regex("""id="muse-host-build"""").findAll(twice).count())
    }

    @Test
    fun `脚本形状与客户端读的一致`() {
        val script = HostVersionTag.script("2.7.0", 1)

        for (key in listOf("appVersion", "protocolVersion")) {
            assertTrue("客户端读 $key", script.contains("\"$key\""))
        }
        assertTrue(script.startsWith("<script id=\"muse-host-build\">"))
        assertTrue(script.endsWith("</script>"))
    }
}
