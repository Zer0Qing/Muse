package io.zer0.muse.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * 机翻硬伤的回归钉子（实机走查 实-W4-01）。
 *
 * 主题名与 PII 这类专有名词被机器翻译当成普通词翻过：mono → 传染性单核细胞增多症、
 * 暖纸 → "papel caliente"（温度烫的纸）、PII → IIP（缩写被翻乱）。这类错误静态扫不出来，
 * 只在真机上肉眼可见，所以把口径钉在这里：改坏了就直接断。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MistranslationGuardTest {

    private val base: Context = ApplicationProvider.getApplicationContext()

    private fun text(localeTag: String, resId: Int): String {
        val parts = localeTag.split("-")
        val locale = when (parts.size) {
            1 -> Locale(parts[0])
            2 -> Locale(parts[0], parts[1])
            else -> Locale(parts[0], parts[1], parts[2])
        }
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config).resources.getString(resId)
    }

    private val locales = listOf("zh", "en", "es", "ja", "ko", "pt-rBR", "ru")

    @Test
    fun `Chinese greeting event aliases preserve original labels`() {
        assertEquals("开会", text("zh", R.string.greeting_event_attend_meeting))
        assertEquals("deadline", text("zh", R.string.greeting_event_deadline_english))
        assertEquals("DDL", text("zh", R.string.greeting_event_deadline_abbreviation))
        assertEquals("reunión", text("es", R.string.greeting_event_attend_meeting))
        assertEquals("fecha límite", text("es", R.string.greeting_event_deadline_english))
        assertEquals("fecha límite", text("es", R.string.greeting_event_deadline_abbreviation))
    }

    @Test
    fun `Spanish channel tutorials resolve as arrays without Chinese fallback`() {
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag("es")) }
        val resources = base.createConfigurationContext(config).resources
        val tutorials = mapOf(
            R.array.channel_tutorial_weclaw_steps to 2,
            R.array.channel_tutorial_telegram_steps to 5,
            R.array.channel_tutorial_feishu_steps to 9,
            R.array.channel_tutorial_dingtalk_steps to 7,
            R.array.channel_tutorial_qq_steps to 3,
        )
        tutorials.forEach { (id, count) ->
            val steps = resources.getStringArray(id)
            assertEquals(count, steps.size)
            steps.forEach { step ->
                assertTrue(step.isNotBlank())
                assertTrue("Chinese fallback in $step", step.none { it in '\u4e00'..'\u9fff' })
            }
        }
    }

    @Test
    fun `主题 mono 在任何语言里都不是疾病名`() {
        val banned = listOf("mononucleosis", "mononukleoz", "単核", "단핵", "мононуклеоз")
        locales.forEach { tag ->
            val value = text(tag, R.string.theme_mono)
            assertTrue("[$tag] theme_mono 又不像主题名了: $value", value.isNotBlank())
            banned.forEach { b ->
                assertTrue("[$tag] theme_mono 含疾病词 '$b': $value", !value.lowercase().contains(b.lowercase()))
            }
        }
    }

    @Test
    fun `暖纸在西语里是色调暖而不是温度烫`() {
        assertEquals("Papel cálido", text("es", R.string.theme_warm_paper))
    }

    @Test
    fun `樱花主题名保持首字母大写`() {
        assertEquals("Sakura", text("es", R.string.theme_sakura))
        assertEquals("Sakura", text("en", R.string.theme_sakura))
    }

    @Test
    fun `PII 开关不出现翻乱的缩写`() {
        locales.forEach { tag ->
            val value = text(tag, R.string.settings_screen_pii_guard)
            assertTrue("[$tag] PII 标签为空", value.isNotBlank())
            assertTrue("[$tag] 缩写被翻成 IIP: $value", !value.contains("IIP"))
        }
    }

    @Test
    fun `琥珀金西语用形容词形式`() {
        assertEquals("Ámbar dorado", text("es", R.string.theme_amber_gold))
    }
}
