package io.zer0.muse.util

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
