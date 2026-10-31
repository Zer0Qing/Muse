package io.zer0.muse.web

import android.content.Context
import android.content.res.Configuration
import io.zer0.common.Logger
import io.zer0.muse.util.AppLocales
import java.util.concurrent.ConcurrentHashMap

/**
 * 设置文案解析：字符串资源名 → **已本地化**文本。
 *
 * 协议里 `titleKey` / `descKey` / 分组 `key` 是资源名，只作稳定标识（导航、测试断言）；
 * 界面要显示的文本由这里按当前应用语言解析后随 `settings.snapshot` 一起下发，
 * 网页端因此不需要（也不应该）再维护一份自己的文案表。
 *
 * 解析不到返回 null，由调用方决定兜底（快照里留空而不是塞资源名给用户看）。
 */
fun interface HostSettingsStrings {
    fun resolve(resourceName: String, language: String): String?
}

/**
 * [HostSettingsStrings] 的 Android 实现。
 *
 * 语言值取自 `SettingsRepository.languageFlow`（"zh" / "en" / … / "system"），
 * 与 `MainActivity.attachBaseContext` 包 Activity 基资源用的是同一条映射
 * （[AppLocales]）：只有按目标 locale 新建一个 ConfigurationContext，
 * 才能让 `resources.getString` 真的走 `values-ja` 那类目录——`applicationContext`
 * 永远是系统配置，直接读不到应用内语言。
 */
internal class AndroidHostSettingsStrings(private val baseContext: Context) : HostSettingsStrings {

    /** language → 该语言的 Context（只随语言数量增长）。 */
    private val localizedContexts = ConcurrentHashMap<String, Context>()

    /** "language|resourceName" → 已解析文本；未命中的名字不进缓存，改完资源能立刻生效。 */
    private val resolved = ConcurrentHashMap<String, String>()

    override fun resolve(resourceName: String, language: String): String? {
        if (resourceName.isBlank()) return null
        val cacheKey = "$language|$resourceName"
        return resolved[cacheKey] ?: lookup(cacheKey, resourceName, language)
    }

    /** 真解析：命中就写回缓存；未命中不缓存，改完资源能立刻生效。 */
    private fun lookup(cacheKey: String, resourceName: String, language: String): String? {
        val context = localizedContext(language)
        val id = context.resources.getIdentifier(resourceName, RESOURCE_TYPE, context.packageName)
        if (id == 0) {
            Logger.w(TAG, "设置文案资源缺失: $resourceName (language=$language)")
            return null
        }
        val text = context.getString(id).takeIf { it.isNotBlank() } ?: return null
        resolved[cacheKey] = text
        return text
    }

    private fun localizedContext(language: String): Context = localizedContexts.getOrPut(language) {
        // "system" / 未知值 → 跟随系统：直接用调用方给的 context。
        val locale = AppLocales.parse(language) ?: return@getOrPut baseContext
        val configuration = Configuration(baseContext.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        baseContext.createConfigurationContext(configuration)
    }

    private companion object {
        const val TAG = "HostSettingsStrings"
        const val RESOURCE_TYPE = "string"
    }
}
