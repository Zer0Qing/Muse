package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.tools.BrowserSearchEngine
import io.zer0.muse.tools.BrowserSettingsStore
import io.zer0.muse.tools.clearBrowserSiteData
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.destructiveItem
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
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
    var showClearConfirm by remember { mutableStateOf(false) }
    val clearedText = stringResource(R.string.settings_browser_cleared)

    SettingsSubPageScaffold(title = stringResource(R.string.settings_screen_browser), onBack = onBack) {
        browserSearchSection(store, engine, scope)
        browserCookiesSection(
            acceptCookies = acceptCookies,
            onToggleCookies = { accept -> scope.launch { store.setAcceptCookies(accept) } },
            onRequestClear = { showClearConfirm = true },
        )
    }

    // 清站点数据会丢失所有登录态，必须二次确认；完成后再给一次明确反馈。
    if (showClearConfirm) {
        MuseDialog(
            onDismissRequest = { showClearConfirm = false },
            title = stringResource(R.string.settings_browser_clear),
            content = {
                Text(
                    text = stringResource(R.string.settings_browser_clear_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.common_delete),
            destructive = true,
            onConfirm = {
                showClearConfirm = false
                clearBrowserSiteData()
                MuseToast.show(clearedText)
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showClearConfirm = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.browserSearchSection(store: BrowserSettingsStore, engine: BrowserSearchEngine, scope: CoroutineScope) {
    item(key = "browser_search") {
        CardGroup(title = { Text(stringResource(R.string.settings_browser_search_title)) }) {
            content(key = "browser_search_body") {
                Column(Modifier.padding(MusePaddings.cardInner)) {
                    Text(
                        text = stringResource(R.string.settings_browser_search_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // FlowRow 而不是 Row：引擎名长短不一，窄屏一行放不下会被压成竖排（本仓踩过）。
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MuseSpacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
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
}

private fun LazyListScope.browserCookiesSection(acceptCookies: Boolean, onToggleCookies: (Boolean) -> Unit, onRequestClear: () -> Unit) {
    item(key = "browser_cookies") {
        CardGroup(
            title = { Text(stringResource(R.string.settings_browser_cookies_title)) },
            modifier = Modifier.padding(top = MusePaddings.cardGap),
        ) {
            switchItem(
                key = "browser_accept_cookies",
                title = stringResource(R.string.settings_browser_accept_cookies),
                checked = acceptCookies,
                onCheckedChange = onToggleCookies,
            )
        }
    }
    item(key = "browser_clear") {
        CardGroup(modifier = Modifier.padding(top = MusePaddings.cardGap)) {
            destructiveItem(
                key = "browser_clear_data",
                title = stringResource(R.string.settings_browser_clear),
                onClick = onRequestClear,
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
