package io.zer0.muse.data.session

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
 * 展示层那份"旧默认标题"清单必须与各语言资源逐一对上。
 *
 * 清单是写死的字符串,资源也在被反复改:两边漂开以后,切语言的历史会话会重新
 * 变成"挂着旧语言默认标题"的样子(实机走查 实-W3-02),而那种回归只有真机肉眼可见。
 * 这条测试把它变成编译期就能断的东西。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionTitleDefaultLiteralTest {

    private val base: Context = ApplicationProvider.getApplicationContext()

    private val localeTags = listOf("zh", "en", "es", "ja", "ko", "pt-rBR", "ru")

    private fun defaultTitle(tag: String): String {
        val parts = tag.split("-")
        val locale = when (parts.size) {
            1 -> Locale(parts[0])
            2 -> Locale(parts[0], parts[1])
            else -> Locale(parts[0], parts[1], parts[2])
        }
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config).resources.getString(R.string.session_repo_default_title)
    }

    @Test
    fun `每种语言的默认标题字面量都在展示层清单里`() {
        localeTags.forEach { tag ->
            val literal = defaultTitle(tag)
            assertTrue(
                "[$tag] session_repo_default_title=\"$literal\" 不在 legacyDefaultSessionTitles 里," +
                    "切语言后这类历史标题会原样挂着",
                legacyDefaultSessionTitles.contains(literal),
            )
        }
    }

    @Test
    fun `清单里没有多余语言条目`() {
        val literals = localeTags.map { defaultTitle(it) }.toSet()
        assertEquals(
            "legacyDefaultSessionTitles 与各语言资源数量不一致(有一边漏改): " +
                "清单=${legacyDefaultSessionTitles.size} 资源=${literals.size}",
            literals.size,
            legacyDefaultSessionTitles.size,
        )
    }
}
