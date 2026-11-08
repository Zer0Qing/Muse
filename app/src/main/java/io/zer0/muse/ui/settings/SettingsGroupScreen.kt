package io.zer0.muse.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.ProxyConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.feature.FeatureFlags
import io.zer0.muse.ui.common.form.MuseSettingsIcon
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** 分类页里每一行条目的测试标签与语义锚点。 */
internal const val SETTINGS_ENTRY_ROW_TAG: String = "settings-entry-row"

/** 已解析好文案的条目行（CardGroup 的 DSL 不在组合上下文里，字符串必须先算好）。 */
private data class ResolvedEntryRow(
    val entry: SettingsEntrySpec,
    val title: String,
    val subtitle: String?,
)

/** 搜索直达后高亮保持时长:够看清,又不至于变成常驻状态。 */
private const val FOCUS_FLASH_MS = 1600L

/** 搜索直达高亮的左条宽度。 */
private const val FOCUS_BAR_WIDTH_DP = 3

/** 开关条目的当前值与写回回调。 */
data class SettingsToggleUiState(val checked: Boolean, val onCheckedChange: (Boolean) -> Unit)

/**
 * 分类页路由入口：把仓库里的实时状态接到纯渲染的 [SettingsGroupScreen] 上。
 *
 * 代理条目的说明与 PII 开关的值都来自设置仓库，这两件事留在这里做；
 * 分类页本身只认声明清单 + 已解析好的字符串，方便脱离 DI 渲染与断言。
 */
@Composable
fun SettingsGroupPage(
    kind: SettingsGroupKind,
    onBack: () -> Unit,
    onNavigate: (Any) -> Unit,
    /** 搜索命中开关类条目时带来的行 id:进组后定位到它并短暂高亮。 */
    focusEntry: String? = null,
) {
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val actionHost = rememberSettingsActionHost()
    val piiGuardEnabled by settings.piiGuardEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val proxyConfig by settings.proxyConfigFlow.collectAsStateWithLifecycle(initialValue = ProxyConfig())

    val proxyDisabled = stringResource(R.string.proxy_disabled)
    val proxySummary = when {
        !proxyConfig.enabled -> proxyDisabled
        proxyConfig.host.isBlank() || proxyConfig.port <= 0 -> proxyDisabled
        else -> "${proxyConfig.type} ${proxyConfig.host}:${proxyConfig.port}"
    }

    SettingsGroupScreen(
        kind = kind,
        focusEntryId = focusEntry,
        onBack = onBack,
        onNavigate = onNavigate,
        onAction = actionHost.run,
        busyAction = actionHost.busyAction,
        subtitles = mapOf(SettingsEntryId.PROXY to proxySummary),
        toggleState = { key ->
            when (key) {
                SettingsToggleKey.PII_GUARD -> SettingsToggleUiState(piiGuardEnabled) { value ->
                    scope.launch { settings.savePiiGuardEnabled(value) }
                }
            }
        },
    )
}

/**
 * 一级分类页：顶栏（返回 + 分类名）+ 该分类的条目卡片。
 *
 * 条目全部来自 [SettingsCatalog]，这里只负责把声明翻译成行：能点进去的给箭头，
 * 开关条目给开关，页内动作给动作行（执行中显示进度圈）。
 */
@Composable
fun SettingsGroupScreen(
    kind: SettingsGroupKind,
    onBack: () -> Unit,
    focusEntryId: String? = null,
    onNavigate: (Any) -> Unit,
    onAction: (SettingsActionId) -> Unit = {},
    busyAction: SettingsActionId? = null,
    subtitles: Map<SettingsEntryId, String> = emptyMap(),
    toggleState: (SettingsToggleKey) -> SettingsToggleUiState = { SettingsToggleUiState(false) {} },
) {
    val group = SettingsCatalog.group(kind)
    // 每个分区在滚动列表里是一张卡片 item(大标题占 index 0),所以定位索引 = 分区下标 + 1。
    val sectionIndex = group.sections.indexOfFirst { section ->
        section.entries.any { entry -> entry.isAvailable() && entry.id.name == focusEntryId }
    }
    SettingsSubPageScaffold(
        title = stringResource(group.titleRes),
        onBack = onBack,
        focusItemIndex = sectionIndex.takeIf { it >= 0 }?.plus(1),
    ) {
        group.sections.forEach { section ->
            item(key = "settings_section_${kind.name}_${section.labelRes ?: 0}") {
                SettingsEntryCard(
                    label = section.labelRes?.let { stringResource(it) },
                    entries = section.entries.filter { it.isAvailable() },
                    onNavigate = onNavigate,
                    onAction = onAction,
                    busyAction = busyAction,
                    subtitles = subtitles,
                    toggleState = toggleState,
                    focusEntryId = focusEntryId,
                )
            }
        }
    }
}

/** 分类页展示条件：编译期闸门开着，且这个入口没有被标为暂不展示。 */
internal fun SettingsEntrySpec.isAvailable(): Boolean =
    browseVisible && (id != SettingsEntryId.MINIPHONE || FeatureFlags.MINI_PHONE)

@Composable
private fun SettingsEntryCard(
    label: String?,
    entries: List<SettingsEntrySpec>,
    onNavigate: (Any) -> Unit,
    onAction: (SettingsActionId) -> Unit,
    busyAction: SettingsActionId?,
    subtitles: Map<SettingsEntryId, String>,
    toggleState: (SettingsToggleKey) -> SettingsToggleUiState,
    focusEntryId: String? = null,
) {
    // CardGroup 的内容块是普通 DSL，不是组合上下文：标题/说明先在这里解析成字符串。
    val rows = entries.map { entry ->
        ResolvedEntryRow(
            entry = entry,
            title = stringResource(entry.titleRes),
            subtitle = subtitles[entry.id] ?: entry.descRes?.let { stringResource(it) },
        )
    }
    // 高亮只在"这次带着搜索命中进来"的那段时间里存在,之后回到常态:
    // 不做常显选中态,否则分类页会多出一套与开关本身无关的视觉状态。
    var flashFocused by remember(focusEntryId) { mutableStateOf(focusEntryId != null) }
    LaunchedEffect(focusEntryId) {
        if (focusEntryId != null) {
            delay(FOCUS_FLASH_MS)
            flashFocused = false
        }
    }
    val focusTint by animateColorAsState(
        targetValue = if (flashFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS),
        label = "settingsFocusFlash",
    )
    // 单靠底色在 Mono 主题里会被读成"分组底色";补一条主色左条,才看得出是"你要找的在这"。
    val focusBarColor = MaterialTheme.colorScheme.primary
    CardGroup(
        modifier = Modifier.fillMaxWidth(),
        title = label?.let { text ->
            {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        rows.forEach { row ->
            val entry = row.entry
            val action = entry.action
            item(
                key = entry.id,
                modifier = Modifier
                    .testTag(SETTINGS_ENTRY_ROW_TAG)
                    .background(if (flashFocused && row.entry.id.name == focusEntryId) focusTint else Color.Transparent)
                    .drawBehind {
                        // 判定在组合期算好,绘制期只读布尔:组合期读状态会破坏快照规则
                        if (flashFocused && row.entry.id.name == focusEntryId) {
                            drawRect(
                                color = focusBarColor,
                                size = androidx.compose.ui.geometry.Size(FOCUS_BAR_WIDTH_DP.dp.toPx(), size.height),
                            )
                        }
                    },
                onClick = when (action) {
                    is SettingsEntryAction.Navigate -> ({ onNavigate(action.route) })
                    is SettingsEntryAction.Toggle -> null
                    is SettingsEntryAction.Action -> ({ onAction(action.id) })
                },
                leadingContent = { MuseSettingsIcon(entry.icon) },
                headlineContent = { Text(row.title) },
                supportingContent = row.subtitle?.let { text -> { Text(text) } },
                trailingContent = {
                    when (action) {
                        is SettingsEntryAction.Toggle -> {
                            val state = toggleState(action.key)
                            MuseSwitch(checked = state.checked, onCheckedChange = state.onCheckedChange)
                        }
                        is SettingsEntryAction.Action -> if (action.id == busyAction) {
                            MuseSpinner(size = 20.dp)
                        } else {
                            ChevronRight()
                        }
                        is SettingsEntryAction.Navigate -> ChevronRight()
                    }
                },
            )
        }
    }
}
