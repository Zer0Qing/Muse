package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 作品卡画幅选择:只有 data-URI 预占固定画幅,URL / 文件路径不预占。
 *
 * 这条分支是这次滚动优化的观感关键:
 *  - data-URI 的位图在 IO 线程异步解码,比例无法提前知道,必须预占画幅,否则解码前后
 *    高度会跳一次(滚动时叠加 animateContentSize 就是一串 relayout);
 *  - URL / 文件路径的比例同样未知,但它们由 Coil 加载、也没有可靠的预占值,锁成 4:3
 *    会让横向/竖向长图都留白一大片,属于退化 —— 必须继续走 120..420dp 高度区间。
 *
 * 断言写成「data-URI 返回 4:3、其它一律返回 null(不预占)」,如果哪天判决写错成
 * 「一律 4:3」或「一律不预占」,这里会直接挂。
 */
class GeneratedImageAspectTest {

    @Test
    fun `data uri gets fixed 4 by 3 frame`() {
        val aspect = generatedImageAspectOf("data:image/png;base64,AAAA")

        assertEquals(4f / 3f, requireNotNull(aspect), 1e-6f)
    }

    @Test
    fun `data uri frame does not depend on payload`() {
        val small = generatedImageAspectOf("data:image/png;base64,AAAA")
        val large = generatedImageAspectOf("data:image/jpeg;base64," + "x".repeat(4096))

        assertEquals(small, large)
    }

    @Test
    fun `http url does not preallocate frame`() {
        assertNull(generatedImageAspectOf("https://example.com/generated/a.png"))
    }

    @Test
    fun `absolute file path does not preallocate frame`() {
        assertNull(generatedImageAspectOf("/sdcard/Android/data/io.zer0.muse/files/a.png"))
    }

    @Test
    fun `file uri does not preallocate frame`() {
        assertNull(generatedImageAspectOf("file:///data/user/0/io.zer0.muse/files/stickers/s1.webp"))
    }

    @Test
    fun `non image data uri does not preallocate frame`() {
        assertNull(generatedImageAspectOf("data:audio/mpeg;base64,AAAA"))
    }

    @Test
    fun `blank model does not preallocate frame`() {
        assertNull(generatedImageAspectOf(""))
    }
}
