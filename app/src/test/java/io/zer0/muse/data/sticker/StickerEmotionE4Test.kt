package io.zer0.muse.data.sticker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.ui.chat.StickerEmotionDetector
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v2.7.x(E4): 情绪标签、按标签筛选、使用计数与低使用退场。
 *
 * 这几条规则的共同风险是「把表情变没」:标错、筛空、误退场,用户看到的都是
 * 一个突然不出图的面板。所以除了"命中"的一侧,每条都配一条"不许因此消失"的反向断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class StickerEmotionE4Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = StickerLibraryRepository(context)

    private val manifest get() = File(File(context.filesDir, "stickers"), "manifest.json")

    @Before
    fun setUp() = runTest {
        repository.clearAll()
    }

    private fun item(
        id: String,
        category: String = "日常",
        emotions: List<String> = emptyList(),
        useCount: Int = 0,
        addedAt: Long = 1_000L,
        retiredAt: Long? = null,
    ) = StickerItem(
        id = id,
        category = category,
        fileName = "$id.png",
        relativePath = "stickers/$category/$id.png",
        addedAt = addedAt,
        emotions = emotions,
        useCount = useCount,
        retiredAt = retiredAt,
    )

    /** 直接写清单(绕开导入链路,专注测选取与计数)。 */
    private fun seed(items: List<StickerItem>) {
        manifest.parentFile.mkdirs()
        manifest.writeText(
            io.zer0.common.AppJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(StickerItem.serializer()),
                items,
            ),
        )
    }

    private fun readBack(): List<StickerItem> = io.zer0.common.AppJson.decodeFromString(
        kotlinx.serialization.builtins.ListSerializer(StickerItem.serializer()),
        manifest.readText(),
    )

    // ── 打标 ──────────────────────────────────────────────────

    @Test
    fun `注册时按文件名与包名打标`() {
        val tags = StickerEmotionTagger.tagOf("生气猫.png", category = "怼人专用")

        assertTrue("该出现 angry: $tags", tags.contains("angry"))
        assertTrue("标签必须是 3 个以内", tags.size <= StickerEmotionTagger.MAX_TAGS)
    }

    @Test
    fun `配文里的描述也参与打标`() {
        val tags = StickerEmotionTagger.tagOf("img_0001.png", caption = "小猫歪头 好可爱 哈哈")

        assertTrue("配文里的「可爱/哈哈」该命中 joyful: $tags", tags.contains("joyful"))
    }

    @Test
    fun `没有任何情绪线索时返回空标签而不是猜一个`() {
        assertEquals(emptyList<String>(), StickerEmotionTagger.tagOf("IMG_20240117_103045.jpg"))
        assertEquals(emptyList<String>(), StickerEmotionTagger.tagOf(""))
    }

    @Test
    fun `打标是确定的同一输入永远同一结果`() {
        val first = StickerEmotionTagger.tagOf("崩溃想哭.png", caption = "呜呜", category = "难过包")
        repeat(5) { assertEquals(first, StickerEmotionTagger.tagOf("崩溃想哭.png", caption = "呜呜", category = "难过包")) }
    }

    @Test
    fun `检测器给情绪集合而不是只有最强一个`() {
        // 两个词典都真有命中词(「气死」在 angry 表、「哈哈」在 joyful 表),
        // 这才是「多标签」要测的形状;上一版我用了一句只命中一边、却指望它命中的话。
        val emotions = StickerEmotionDetector.emotionsOf("气死我了，但还是想哈哈")

        assertTrue("愤怒该命中: $emotions", emotions.contains(StickerEmotionDetector.EmotionType.ANGRY))
        assertTrue(
            "打分只差一点时也要保留另一个情绪,否则一张「又气又无奈」的图只剩一个标签",
            emotions.contains(StickerEmotionDetector.EmotionType.JOYFUL),
        )
        assertEquals(emptyList<StickerEmotionDetector.EmotionType>(), StickerEmotionDetector.emotionsOf("今天星期三"))
    }

    // ── 选取 ──────────────────────────────────────────────────

    @Test
    fun `情绪命中优先`() = runTest {
        seed(listOf(item("a", emotions = listOf("angry")), item("b", emotions = listOf("joyful"))))
        val snap = repository.snapshot()

        val picked = repository.pickSticker(snap, "日常", seed = 0L, emotions = listOf("joyful"))

        assertEquals("b", picked?.id)
    }

    @Test
    fun `同一粒seed在过滤后的候选里仍然稳定`() = runTest {
        seed(listOf(item("a", emotions = listOf("angry")), item("b", emotions = listOf("angry")), item("c", emotions = listOf("joyful"))))
        val snap = repository.snapshot()

        val first = repository.pickSticker(snap, "日常", 7L, listOf("angry"))
        repeat(4) { assertEquals("重进会话不该跳成另一张", first?.id, repository.pickSticker(snap, "日常", 7L, listOf("angry"))?.id) }
        assertTrue("只该在 angry 里选", first?.id in listOf("a", "b"))
    }

    @Test
    fun `没打标签的条目在情绪筛选后依然可用`() = runTest {
        seed(listOf(item("tagged", emotions = listOf("sad")), item("untagged")))
        val snap = repository.snapshot()

        val joyfulPick = repository.pickSticker(snap, "日常", 0L, listOf("joyful"))

        assertNotNull("一个标签都没命中时不许不出图", joyfulPick)
        assertEquals("没立场的条目应作为回退被选中", "untagged", joyfulPick?.id)
    }

    @Test
    fun `整包都没标签时行为与今天一致`() = runTest {
        seed(listOf(item("x"), item("y"), item("z")))
        val snap = repository.snapshot()

        assertEquals(repository.pickSticker(snap, "日常", 3L)?.id, repository.pickSticker(snap, "日常", 3L, listOf("angry"))?.id)
    }

    @Test
    fun `退场的条目不参与选取但整包都退场时仍能出图`() = runTest {
        seed(listOf(item("retired", retiredAt = 5_000L), item("live")))
        val snap = repository.snapshot()

        assertEquals("live", repository.pickSticker(snap, "日常", 0L, listOf("angry"))?.id)

        seed(listOf(item("retired", retiredAt = 5_000L, emotions = listOf("angry"))))
        val allRetired = repository.snapshot()
        assertNotNull("全被退场也不该让面板变成空白", repository.pickSticker(allRetired, "日常", 0L, listOf("angry")))
    }

    // ── 计数与退场 ────────────────────────────────────────────

    @Test
    fun `计数落在清单里并清掉退场标记`() = runTest {
        seed(listOf(item("a", retiredAt = 9_000L)))

        repository.recordUse("a", now = 20_000L)

        val saved = readBack().single()
        assertEquals(1, saved.useCount)
        assertEquals(20_000L, saved.lastUsedAt)
        assertNull("被重新用起来的表情不该继续躺在退场区", saved.retiredAt)

        repository.recordUse("a", now = 21_000L)
        assertEquals(2, readBack().single().useCount)
    }

    @Test
    fun `计数不存在的条目安静地什么都不做`() = runTest {
        seed(listOf(item("a")))

        repository.recordUse("ghost", now = 1L)

        assertTrue(manifest.readText().contains("\"a\""))
    }

    @Test
    fun `退场只处理用满观察期且零使用的条目`() = runTest {
        val now = 1_000_000_000L
        val old = StickerEmotionTagger.RETIRE_AFTER_MILLIS
        seed(
            listOf(
                item("candidate", addedAt = now - old - 1),
                item("too_new", addedAt = now - 1_000L),
                item("used", addedAt = now - old - 1, useCount = 1),
                item("already", addedAt = now - old - 1, retiredAt = now - 10L),
            ),
        )

        val retired = repository.retireRarelyUsed(now = now)

        assertEquals("只有该退场的那一条", listOf("candidate"), retired)
        assertTrue(manifest.readText().contains("\"retiredAt\":$now"))
    }

    @Test
    fun `退场不是删除`() = runTest {
        val now = 1_000_000_000L
        seed(listOf(item("a", addedAt = 0L)))

        repository.retireRarelyUsed(now = now, minAgeMillis = 1L)

        val text = manifest.readText()
        assertTrue("条目仍在清单里", text.contains("\"id\":\"a\""))
        assertFalse("文件也没被删", !File(context.filesDir, "stickers").exists())
    }

    @Test
    fun `旧清单没有新字段也必须能读`() = runTest {
        manifest.parentFile.mkdirs()
        manifest.writeText("""[{"id":"legacy","category":"日常","fileName":"a.png","relativePath":"stickers/日常/a.png","addedAt":5}]""")

        val snap = repository.snapshot()

        val legacy = snap.byCategory["日常"]?.single()
        assertNotNull(legacy)
        assertEquals(emptyList<String>(), legacy!!.emotions)
        assertEquals(0, legacy.useCount)
        assertNull(legacy.lastUsedAt)
        assertNull(legacy.retiredAt)
    }

    @Test
    fun `手动标注会归一化并去重`() = runTest {
        seed(listOf(item("a")))

        repository.setEmotions("a", listOf(" Joyful ", "joyful", "", "SAD"))

        val text = manifest.readText()
        assertTrue(text.contains("\"joyful\""))
        assertTrue(text.contains("\"sad\""))
        assertFalse("空串不许成为标签", text.contains("\"\""))
    }
}
