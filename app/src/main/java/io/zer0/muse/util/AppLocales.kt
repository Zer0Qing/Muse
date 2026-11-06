package io.zer0.muse.util

import androidx.annotation.StringRes
import io.zer0.muse.R
import java.util.Locale

/**
 * 语言偏好字符串 → [Locale]（与 `values-*` 资源目录对应）。
 *
 * "system" 或未知值返回 null，表示跟随系统默认语言。
 *
 * 这条映射原先私有在 MainActivity 里（`attachBaseContext` 包装 Activity 基资源、
 * Compose 的 `RuntimeLocaleProvider` 热切换各用一次）。Host 的 `settings.snapshot`
 * 也要按当前语言解析文案（网页端不再维护文案表），所以收到 util 里，
 * 让冷启动、运行时热切换和协议下发的本地化文本读同一份语言值。
 */
object AppLocales {

    /**
     * 界面语言候选：code 与 [parse]、`values-*` 目录、`locales_config.xml` 一一对应。
     *
     * 引导页、外观页选择器和 Host 协议原来各写一份列表，其中外观页和 Host 停在 5 种语言上，
     * 西语/葡语有全量文案却选不到。收成这一份后，加语言只需要加一行 + 一份资源。
     */
    enum class Language(val code: String, val labelKey: String, @StringRes val labelRes: Int) {
        ZH("zh", "app_language_zh", R.string.app_language_zh),
        EN("en", "app_language_en", R.string.app_language_en),
        JA("ja", "app_language_ja", R.string.app_language_ja),
        KO("ko", "app_language_ko", R.string.app_language_ko),
        RU("ru", "app_language_ru", R.string.app_language_ru),
        ES("es", "app_language_es", R.string.app_language_es),
        PT_BR("pt-rBR", "app_language_pt_br", R.string.app_language_pt_br),
        ;
    }

    @Suppress("DEPRECATION") // Locale(language, country) 在新 JDK 标了 deprecated（推荐 Locale.of），
    // 但 project minSdk 上 Locale.of 不可用，沿用构造器是有意的。
    fun parse(language: String): Locale? = when (language) {
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "en" -> Locale.US
        "ja" -> Locale.JAPAN
        "ko" -> Locale.KOREA
        "ru" -> Locale("ru", "RU")
        "es" -> Locale("es", "ES")
        "pt-rBR" -> Locale("pt", "BR")
        else -> null
    }
}
