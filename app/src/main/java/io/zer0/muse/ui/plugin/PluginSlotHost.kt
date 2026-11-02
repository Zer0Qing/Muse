package io.zer0.muse.ui.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import io.zer0.muse.R
import io.zer0.muse.data.plugin.PluginCapabilityGateway
import io.zer0.muse.data.plugin.PluginCapabilityLedgers
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.data.plugin.PluginSlot
import io.zer0.muse.ui.theme.MusePaddings
import org.koin.compose.koinInject

/**
 * 界面槽位的宿主（`ui.slot`）。
 *
 * 插件往槽位里放的是一段**声明式界面**，由宿主用同一套设计系统画出来：
 * 颜色、字号、深色模式都跟随用户设置，插件既拿不到也破坏不了。
 *
 * 每条都带**来源标记**（"由插件 X 提供"）。理由与系统提示词注入那条一样：
 * 用户得知道眼前这块界面是谁放的，出问题时才知道去找谁。
 *
 * 三道过滤在 [PluginManager.loadSlotViews] 里：已启用且安装已确认、声明了该槽位、
 * 拿到了 `ui.slot` 授权。这里不重复判权限 —— 两处各判一次，迟早有一处先松动。
 */
@Composable
fun PluginSlotHost(slot: PluginSlot.Id, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pluginManager: PluginManager = koinInject()
    val ledger = remember(context) { PluginCapabilityLedgers.shared(context) }
    // 槽位内容在进入组合时取一次：它是"装了什么插件"的投影，不随聊天滚动变化。
    val views = remember(slot) {
        runCatching {
            pluginManager.loadSlotViews(slot) { pluginId ->
                ledger.isApproved(pluginId, PluginCapabilityGateway.UI_SLOT)
            }
        }.getOrDefault(emptyList())
    }
    if (views.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        views.forEach { view ->
            Text(
                text = stringResource(R.string.plugin_slot_provided_by, view.pluginName),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val root = view.tree.root
            if (root != null) {
                PluginUiTreeView(
                    root = root,
                    assetsRoot = runCatching { pluginManager.pluginDirFor(view.pluginId) }.getOrNull(),
                )
            } else {
                // 解析失败时给原因，而不是留一片空白让作者猜
                Text(
                    text = stringResource(R.string.plugin_ui_render_failed) + "：" + view.tree.reason.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
