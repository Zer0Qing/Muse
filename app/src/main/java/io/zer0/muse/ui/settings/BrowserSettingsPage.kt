package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.tools.BrowserSearchEngine
import io.zer0.muse.tools.BrowserSettingsStore
import io.zer0.muse.tools.clearBrowserSiteData
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 内置浏览器设置页（整改计划 第 2 条）。
 *
 * 三项都对应一个真实行为，不是把参数堆成页面：
 *  - **地址栏搜索**：以前地址栏不做检索，输入关键词会被当网址打开（`https://天气`）；
 *  - **接受 Cookies**：接 `BrowserManager.applyCookiePolicy()`，关掉只影响之后是否继续保存，
 *    不会顺手把已有登录态清掉 —— 那是下面那颗按钮的职责；
 *  - **清除 Cookies 与站点数据**：与 `BrowserManager.clearCookies()` 共用
 *    `clearBrowserSiteData()` 一处定义，避免两处对「清除」各有理解。
 *
 * 状态取自 `BrowserSettingsStore.of(context)` 这个**全应用单例**：注册表里可能同时存在多个
 * `BrowserManager`（shared + 每会话各一个），各存一份内存态就会出现"改了却没生效"。
 */
@Composable
fun BrowserSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { BrowserSettingsStore.of(context) }
    val engine by store.engine.collectAsStateWithLifecycle()
    val acceptCookies by store.acceptCookies.collectAsStateWithLifecycle()

    SettingsSubPageScaffold(title = stringResource(R.string.settings_screen_browser), onBack = onBack) {
        browserSearchSection(store, engine, scope)
        browserCookiesSection(store, acceptCookies, scope)
    }
}

private fun LazyListScope.browserSearchSection(store: BrowserSettingsStore, engine: BrowserSearchEngine, scope: CoroutineScope) {
    item {
        SectionLabel(stringResource(R.string.settings_browser_search_title))
        SettingsGroup(modifier = Modifier.padding(top = 8.dp)) {
            Column(Modifier.padding(MusePaddings.cardInner)) {
                Text(
                    text = stringResource(R.string.settings_browser_search_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                // FlowRow 而不是 Row：引擎名长短不一，窄屏一行放不下会被压成竖排（本仓踩过）。
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BrowserSearchEngine.entries.forEach { candidate ->
                        MuseChip(
                            label = browserEngineLabel(candidate),
                            selected = candidate == engine,
                            onClick = { scope.launch { store.setEngine(candidate) } },
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.browserCookiesSection(store: BrowserSettingsStore, acceptCookies: Boolean, scope: CoroutineScope) {
    item {
        Spacer(Modifier.height(12.dp))
        SectionLabel(stringResource(R.string.settings_browser_cookies_title))
        SettingsGroup(modifier = Modifier.padding(top = 8.dp)) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_browser_accept_cookies),
                subtitle = stringResource(R.string.settings_browser_accept_cookies_hint),
                checked = acceptCookies,
                onCheckedChange = { accept -> scope.launch { store.setAcceptCookies(accept) } },
            )
            SettingsGroupDivider()
            SettingsItemRow(
                title = stringResource(R.string.settings_browser_clear),
                subtitle = stringResource(R.string.settings_browser_clear_hint),
                onClick = { clearBrowserSiteData() },
            )
        }
    }
}

/**
 * 引擎显示名一律走字符串资源：这里曾直接写 `"必应"`/`"百度"` 之类的中文字面量，
 * 那既过不了 `hardcoded_cjk`（UI 上出现的文字必须在资源里），也没法跟着用户语言切换。
 */
@Composable
private fun browserEngineLabel(engine: BrowserSearchEngine): String = stringResource(
    when (engine) {
        BrowserSearchEngine.BING -> R.string.settings_browser_engine_bing
        BrowserSearchEngine.BAIDU -> R.string.settings_browser_engine_baidu
        BrowserSearchEngine.SOGOU -> R.string.settings_browser_engine_sogou
        BrowserSearchEngine.THREE_SIX_ZERO -> R.string.settings_browser_engine_360
        BrowserSearchEngine.GOOGLE -> R.string.settings_browser_engine_google
        BrowserSearchEngine.DUCKDUCKGO -> R.string.settings_browser_engine_duckduckgo
    },
)
