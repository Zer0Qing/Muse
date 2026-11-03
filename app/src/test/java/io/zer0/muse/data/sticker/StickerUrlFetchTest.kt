package io.zer0.muse.data.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 图床直链的取数守卫（表情包重做 P5）。
 *
 * 只用**字面 IP** 做正/负例：`getAllByName` 对域名要查 DNS，
 * 单元测试不该依赖网络，也不该因为解析超时变成 flakes。
 */
class StickerUrlFetchTest {

    @Test
    fun `plain http is refused`() {
        assertTrue(!StickerUrlFetch.isAllowed("http://pic.example.com/i/1.jpg"))
    }

    @Test
    fun `non-http protocols are refused`() {
        assertTrue(!StickerUrlFetch.isAllowed("file:///etc/passwd"))
        assertTrue(!StickerUrlFetch.isAllowed("gopher://127.0.0.1:6379"))
        assertTrue(!StickerUrlFetch.isAllowed("not a url at all"))
    }

    @Test
    fun `loopback and private addresses are refused`() {
        // 表情包文档里塞一条 127.0.0.1 或内网地址，就是拿应用当内网探针
        assertTrue(!StickerUrlFetch.isAllowed("https://127.0.0.1/admin"))
        assertTrue(!StickerUrlFetch.isAllowed("https://localhost:8080/x"))
        assertTrue(!StickerUrlFetch.isAllowed("https://10.0.0.5/secret"))
        assertTrue(!StickerUrlFetch.isAllowed("https://192.168.1.1/"))
        assertTrue(!StickerUrlFetch.isAllowed("https://172.16.0.9/"))
    }

    @Test
    fun `link-local including cloud metadata is refused`() {
        assertTrue(!StickerUrlFetch.isAllowed("https://169.254.169.254/latest/meta-data/"))
    }

    @Test
    fun `a publicly routable https host is allowed`() {
        assertTrue(StickerUrlFetch.isAllowed("https://93.184.216.34/i/1.jpg"))
    }

    @Test
    fun `fetch refuses a disallowed url without touching the filesystem`() {
        val target = File.createTempFile("muse-fetch-", ".png")
        try {
            val reason = StickerUrlFetch.fetch("http://127.0.0.1/a.png", target)

            assertTrue("应拒绝并有原因: $reason", reason != null)
            assertEquals("拒绝时不该写文件", 0L, target.length())
        } finally {
            target.delete()
        }
    }
}
