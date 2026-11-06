package io.zer0.muse.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * 数量文案必须真的走复数（实机走查 实-W4-02）。
 *
 * 西语界面里出现过 "Se eliminarán 1 conversaciones" —— 单数数量配了复数名词。
 * 根因是这些字符串用 `%1$d` 拼在单条 <string> 里，语言本身有单复数变化却只有一份写法。
 * 这条测试钉住：会变单复数的语言里，1 与 5 必须取到不同文案；
 * 不变化的语言（中/日/韩）则两种数量都读得出数字。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CountPluralsTest {

    private val base: Context = ApplicationProvider.getApplicationContext()

    private val inflecting = listOf("en", "es", "pt-rBR", "ru")
    private val nonInflecting = listOf("values" to "zh", "values-ja" to "ja", "values-ko" to "ko")

    private val keys = listOf(
        R.plurals.chat_list_edit_delete_confirm,
        R.plurals.chat_list_edit_done,
        R.plurals.cloud_backup_interval_days,
        R.plurals.onboarding_provider_test_success,
        R.plurals.settings_search_result_count,
    )

    private fun context(tag: String): Context {
        val parts = tag.split("-")
        val locale = when (parts.size) {
            1 -> Locale(parts[0])
            2 -> Locale(parts[0], parts[1])
            else -> Locale(parts[0], parts[1], parts[2])
        }
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    @Test
    fun `有单复数变化的语言里 1 与 5 必须取到不同文案`() {
        inflecting.forEach { tag ->
            val res = context(tag).resources
            keys.forEachIndexed { index, key ->
                val one = res.getQuantityString(key, 1, 1)
                val five = res.getQuantityString(key, 5, 5)
                assertNotEquals(
                    "[$tag] 复数键 #$index 的 1 与 5 完全一样,说明没写 one 形式: $one",
                    one,
                    five,
                )
                assertTrue("[$tag] 1 的形式里丢了数字: $one", one.contains("1"))
                assertTrue("[$tag] 5 的形式里丢了数字: $five", five.contains("5"))
            }
        }
    }

    @Test
    fun `不变化的语言两种数量都读得出数字`() {
        nonInflecting.forEach { (_, tag) ->
            val res = context(tag).resources
            keys.forEach { key ->
                assertTrue("[$tag] 1 的形式里丢了数字", res.getQuantityString(key, 1, 1).contains("1"))
                assertEquals(
                    "[$tag] 该语言无单复数变化,两种数量应同文",
                    res.getQuantityString(key, 1, 1).replace("1", "N"),
                    res.getQuantityString(key, 5, 5).replace("5", "N"),
                )
            }
        }
    }
}
