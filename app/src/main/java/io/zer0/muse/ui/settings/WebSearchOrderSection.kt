package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBarIconButton
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.web.WebSearchConfig
import io.zer0.muse.web.customSearchOrderKey

/**
 * 「搜索顺序」区块（整改计划 第 1 条 ② 的界面那半）。
 *
 * 顺序表本身只有一份：`AUTO_CHAIN_PROVIDER_KEYS`（链构造与这里共用）。
 * 之所以强调这件事 —— 界面若要自己抄一份顺序，抄来的那份一旦与代码不同步，
 * 用户拖动后看到的就是**假顺序**：列表说 Tavily 第一，实际请求还是按硬编码走。
 */

/**
 * 顺序列表的纯逻辑。抽出来是为了不依赖 Compose 也能测：
 * 「显示顺序」「上下移动」这两件事的正确性比它长什么样更值得被锁住。
 */

@Composable
internal fun WebSearchOrderSection(webSearchConfig: WebSearchConfig, onSave: (List<String>) -> Unit) {
    val customKeys = webSearchConfig.customEngines
        .filter { it.enabled && it.endpoint.isNotBlank() }
        .map { customSearchOrderKey(it.name) }

    CardGroup(
        title = { Text(stringResource(R.string.settings_web_search_order_title)) },
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    ) {
        content(key = "web_search_order") {
            Column(Modifier.padding(MusePaddings.cardInner)) {
                Text(
                    text = stringResource(R.string.settings_web_search_order_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val order = WebSearchOrderState.displayedOrder(customKeys, webSearchConfig.providerOrder)
                order.forEachIndexed { index, key ->
                    Spacer(Modifier.height(MuseSpacing.xxs))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = WebSearchOrderState.displayName(key),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        MuseTopBarIconButton(
                            icon = MuseIcons.arrowUp,
                            contentDescription = stringResource(R.string.settings_web_search_order_move_up),
                            enabled = index > 0,
                            onClick = { onSave(WebSearchOrderState.moved(order, index, -1)) },
                        )
                        MuseTopBarIconButton(
                            icon = MuseIcons.arrowDown,
                            contentDescription = stringResource(R.string.settings_web_search_order_move_down),
                            enabled = index < order.lastIndex,
                            onClick = { onSave(WebSearchOrderState.moved(order, index, +1)) },
                        )
                    }
                }
                Spacer(Modifier.height(MuseSpacing.xs))
                MuseCapsuleButton(
                    text = stringResource(R.string.settings_web_search_order_reset),
                    onClick = { onSave(WebSearchOrderState.defaultOrder) },
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                )
            }
        }
    }
}
