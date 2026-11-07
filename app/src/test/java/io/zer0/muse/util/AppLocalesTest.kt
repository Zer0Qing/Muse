package io.zer0.muse.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AppLocales.Language] 是引导页、外观页选择器和 Host 协议共用的候选表，
 * 三处原来各写一份，结果西语/葡语有全量文案却在设置里选不到。
 * 这里守住收成一份后最容易漂移的两件事：表里的 code 真的能解析，标签真的指向那条资源。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppLocalesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `every listed language resolves to a locale`() {
        AppLocales.Language.entries.forEach { language ->
            assertNotNull("code=${language.code} 没有对应的 Locale", AppLocales.parse(language.code))
        }
    }

    @Test
    fun `labelKey and labelRes point at the same resource`() {
        AppLocales.Language.entries.forEach { language ->
            val id = context.resources.getIdentifier(language.labelKey, "string", context.packageName)
            assertEquals("${language.code} 的 labelKey/labelRes 不一致", language.labelRes, id)
        }
    }

    @Test
    fun `system and unsupported codes fall back to no locale`() {
        assertNull(AppLocales.parse("system"))
        assertNull(AppLocales.parse("zh-TW"))
        assertNull(AppLocales.parse(""))
    }

    @Test
    fun `codes are unique`() {
        val codes = AppLocales.Language.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }
}
