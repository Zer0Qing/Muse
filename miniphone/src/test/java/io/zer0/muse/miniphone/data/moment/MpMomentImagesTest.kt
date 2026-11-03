package io.zer0.muse.miniphone.data.moment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 动态图片列表与 `imagesJson` 的互转（小手机 P2b）。
 *
 * 跑在 Robolectric 下不是习惯问题：`encodeMomentImages` 用 `org.json.JSONArray`，
 * 普通 JVM 测试里它是 android.jar 的**桩类**（一调就炸），上一版我就是这么连红四条。
 *
 * 规则很短，但错的方向很难看：**一条脏数据不该让整段动态读不出来**。
 * 朋友圈是一屏多条一起渲染的，解码若抛异常，整页会一起白掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpMomentImagesTest {

    @Test
    fun `存进去再读出来顺序与内容都不变`() {
        val paths = listOf("filesDir/moments/a.png", "filesDir/moments/b.png")

        val json = encodeMomentImages(paths)

        // 不断言线上文本：org.json 会把 "/" 转义成 "\/"，那仍是合法 JSON，
        // 把它当契约钉住等于把实现细节写死。真正的契约是"存进去能原样读回来"。
        assertTrue("该是一段 JSON 数组: $json", json.startsWith("[") && json.endsWith("]"))
        assertEquals(paths, decodeMomentImages(json))
    }

    @Test
    fun `空列表存成空数组而不是空串`() {
        assertEquals("[]", encodeMomentImages(emptyList()))
        assertEquals(emptyList<String>(), decodeMomentImages("[]"))
    }

    @Test
    fun `坏文本与空值一律当作没有图而不是抛出去`() {
        assertNullLike("")
        assertNullLike(null)
        // 手改坏的数据、被截断的 JSON、根本不是 JSON 的字符串
        listOf("{不是数组", "[\"a.png\"", "null", "[1,2,3]", "[{\"x\":1}]").forEach { dirty ->
            runCatching { decodeMomentImages(dirty) }
                .onSuccess { assertTrue("脏数据 $dirty 应得到空列表，实际=$it", it.isEmpty() || it.none { p -> p.isBlank() }) }
                .onFailure { throw AssertionError("解码 $dirty 不该抛异常：${it.message}", it) }
        }
    }

    @Test
    fun `数组里的空白项被丢掉不占位`() {
        val json = """["a.png","","   ","b.png"]"""

        val paths = decodeMomentImages(json)

        assertEquals(listOf("a.png", "b.png"), paths)
    }

    @Test
    fun `九张上限之内不丢图`() {
        val paths = (1..9).map { "p$it.png" }

        assertEquals(paths, decodeMomentImages(encodeMomentImages(paths)))
    }

    private fun assertNullLike(input: String?) {
        assertEquals(emptyList<String>(), decodeMomentImages(input))
    }
}
