package io.zer0.muse.tools

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 内置浏览器的设置（地址栏搜索引擎、Cookies 策略）。
 *
 * ## 为什么需要它
 * 地址栏以前只做一件事：不是 `http(s)://` 就补一个 `https://` 前缀。
 * 于是输入"天气"会变成 `https://天气` —— 用户看到的是打不开的页面。
 * 内置浏览器要有普通浏览器的行为：**关键词交给搜索引擎，完整网址仍然直接打开**。
 *
 * ## 为什么自带一份存储
 * 应用的设置读写集中在 `SettingsRepository`，但那条链路上要动的文件此刻正被并行修改；
 * 这里用一份自包含的 SharedPreferences 键区，既能立刻落地，也不去碰别人的未提交改动。
 * 仓内已有 13 处同样的自包含键区先例。
 */
internal enum class BrowserSearchEngine(
    /** 存储用的稳定 id（不随文案变化）。 */
    val id: String,
    /** 关键词检索的 URL 模板，`%s` 处填 URL 编码后的查询词。 */
    val searchTemplate: String,
) {
    BING("bing", "https://www.bing.com/search?q=%s"),
    BAIDU("baidu", "https://www.baidu.com/s?wd=%s"),
    SOGOU("sogou", "https://www.sogou.com/web?query=%s"),
    THREE_SIX_ZERO("360", "https://www.so.com/s?q=%s"),
    GOOGLE("google", "https://www.google.com/search?q=%s"),
    DUCKDUCKGO("duckduckgo", "https://duckduckgo.com/?q=%s"),
    ;

    companion object {
        fun of(id: String?): BrowserSearchEngine = entries.firstOrNull { it.id == id } ?: BING
    }
}

/**
 * 把地址栏输入解析成可导航的目标（纯函数，不含任何 Android 依赖）。
 *
 * 判据顺序是有意的：
 *  1. 空输入 → `about:blank`（清空页面，而不是去搜一个空串）；
 *  2. 已带协议（`http/https/about/file/data`）→ 原样交给下游的 SSRF 守卫判断；
 *  3. **看起来像主机名**才当网址：必须含点（或 `localhost`、IP 形态），
 *     否则像 `天气`、`python 教程` 这种普通词会被误当网址打开成 `https://天气`；
 *  4. 其余一律按关键词走搜索引擎。
 *
 * 端口与路径不算"像主机名"的反例，但会随主机一起被识别（`example.com:8080/x` 仍是网址）。
 */
internal fun resolveBrowserTarget(raw: String, engine: BrowserSearchEngine): String {
    val text = raw.trim()
    if (text.isEmpty()) return "about:blank"
    val lowered = text.lowercase()
    if (SCHEME_PREFIXES.any { lowered.startsWith(it) }) return text
    if (looksLikeHost(text)) {
        return if (isLocalTarget(text)) {
            // 本机与局域网地址常被省略协议，这里不强迫用户写 http://
            "http://$text"
        } else {
            "https://$text"
        }
    }
    val query = java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    return engine.searchTemplate.replace("%s", query)
}

/**
 * 本机 / 局域网目标（省略协议时默认 http，带端口也算）。
 *
 * 注意：这里只负责"补对协议"，**能不能访问**仍由 `navigate` 里的 SSRF 守卫决定 ——
 * 回环与内网地址默认是被拦的（防止 AI 被注入后访问内网服务），这条函数不放宽它。
 */
private fun isLocalTarget(text: String): Boolean {
    val bare = text.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
    return bare.equals("localhost", ignoreCase = true) || isBareIp(bare) ||
        bare == "127.0.0.1" || bare.startsWith("192.168.") || bare.startsWith("10.")
}

private val SCHEME_PREFIXES = listOf("http://", "https://", "about:", "file:", "data:")

/** 像不像主机名：含点、`localhost`、或 IP 形态。普通词几乎不会含点。 */
private fun looksLikeHost(text: String): Boolean {
    val hostPart = text.substringBefore('/').substringBefore('?').substringBefore('#')
    // 端口必须一起去掉，否则 `localhost:8080` 会被当成关键词（这是最常见的本机调试地址）。
    val bare = hostPart.substringBefore(':')
    if (bare.equals("localhost", ignoreCase = true)) return true
    if (isBareIp(bare)) return true
    return hostPart.contains('.') && !hostPart.startsWith('.')
}

private fun isBareIp(host: String): Boolean = host.split('.').size == 4 &&
    host.split('.').all { it.isNotEmpty() && it.all(Char::isDigit) }

/**
 * 设置读写。默认值刻意与"改之前"的行为一致（Bing 搜索、接受 Cookies），
 * 这样这次改动只会新增"关键词能搜了"，不会顺手改掉用户已有的 Cookie 习惯。
 */
internal class BrowserSettingsStore private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(KEY_STORE, Context.MODE_PRIVATE)

    private val _engine = MutableStateFlow(BrowserSearchEngine.of(prefs.getString(KEY_ENGINE, null)))
    val engine: StateFlow<BrowserSearchEngine> = _engine.asStateFlow()

    private val _acceptCookies = MutableStateFlow(prefs.getBoolean(KEY_COOKIES, true))
    val acceptCookies: StateFlow<Boolean> = _acceptCookies.asStateFlow()

    suspend fun setEngine(engine: BrowserSearchEngine) {
        prefs.edit().putString(KEY_ENGINE, engine.id).apply()
        _engine.value = engine
    }

    suspend fun setAcceptCookies(accept: Boolean) {
        prefs.edit().putBoolean(KEY_COOKIES, accept).apply()
        _acceptCookies.value = accept
    }

    companion object {
        private const val KEY_STORE = "browser_settings"
        private const val KEY_ENGINE = "search_engine"
        private const val KEY_COOKIES = "accept_cookies"

        @Volatile
        private var shared: BrowserSettingsStore? = null

        /**
         * 全应用共用一个实例。
         *
         * 注册表里可能同时存在多个 BrowserManager（sharedManager + 每会话一个），
         * 各建一份就会各存一份内存态 StateFlow：设置页改了引擎，正在用的 manager
         * 还读着旧值 —— 这类"看起来保存了但没生效"的 bug 只能靠单一实例堵住。
         */
        fun of(context: Context): BrowserSettingsStore {
            shared?.let { return it }
            return synchronized(this) {
                shared ?: BrowserSettingsStore(context.applicationContext).also { shared = it }
            }
        }

        /** 仅测试用：清掉进程内单例，避免用例之间互相污染。 */
        internal fun resetForTests() {
            synchronized(this) { shared = null }
        }
    }
}

/**
 * 清掉内置浏览器保存的站点数据（Cookies + DOM Storage + 已访问 URL 记录）。
 *
 * 这是**一处定义**：`BrowserManager.clearCookies()` 与设置页的「清除 Cookies」都调它，
 * 免得两处各自决定"清除"到底清什么 —— 那种分叉会让用户按了按钮却还有半份登录态留着。
 * WebView 自己的渲染缓存是实例级的，由 `BrowserManager` 额外清。
 */
internal fun clearBrowserSiteData() {
    runCatching {
        android.webkit.CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        android.webkit.WebStorage.getInstance().deleteAllData()
    }
}
