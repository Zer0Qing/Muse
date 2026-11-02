package io.zer0.muse.ui.plugin

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import io.zer0.muse.R
import io.zer0.muse.data.plugin.PluginUiTree
import io.zer0.muse.ui.common.surface.MuseDivider
import io.zer0.muse.ui.common.feedback.MuseDialog
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.util.SafePaths
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File

/**
 * 声明式插件界面的渲染器（D4）。
 *
 * 全部用宿主的设计系统：颜色、字号、深色模式跟随用户设置，插件拿不到也破坏不了。
 * 与 HTML 面板的分工写在 [PluginUiTree] 里，这里只负责"照着树画"。
 *
 * 两条不显眼但重要的规矩：
 *  1. **图片只允许包内文件**。`assetImage.path` 来自插件 JSON，属于外部输入，
 *     所以这里再走一次 [SafePaths.safeResolve] —— 不能因为"清单路径已经校验过"就假设子字段也安全；
 *  2. **解析失败要显示原因**。以前这类地方一律"静默不显示"，作者只能靠猜；
 *     现在给一条可点开的"界面未能渲染"，把上限/拼错的位置原样呈现。
 */
@Composable
fun PluginUiTreeView(root: PluginUiTree.Node, assetsRoot: File?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(MusePaddings.screen),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NodeView(root, assetsRoot)
    }
}

@Composable
private fun NodeView(node: PluginUiTree.Node, assetsRoot: File?) {
    when (node) {
        is PluginUiTree.Node.Column -> Column(verticalArrangement = Arrangement.spacedBy(node.gap.dp(8))) {
            node.children.forEach { NodeView(it, assetsRoot) }
        }
        is PluginUiTree.Node.Row -> Row(horizontalArrangement = Arrangement.spacedBy(node.gap.dp(8))) {
            node.children.forEach { NodeView(it, assetsRoot) }
        }
        PluginUiTree.Node.Divider -> MuseDivider()
        is PluginUiTree.Node.Spacer -> Spacer(modifier = Modifier.height((node.height ?: 8).dp))
        // 叶子节点另表：一支 when 里塞 15 个分支，谁也读不动它
        else -> LeafView(node, assetsRoot)
    }
}

/**
 * 供交互视图复用只读节点渲染。
 *
 * 存在的理由只有一句：**颜色/字号/上限只能有一份实现**。
 * 交互视图若自己再写一遍 when，两处的排版会在下一次改设计令牌时分叉。
 */
@Composable
internal fun PluginUiReadOnlyNode(node: PluginUiTree.Node, assetsRoot: File?) = NodeView(node, assetsRoot)

@Composable
private fun LeafView(node: PluginUiTree.Node, assetsRoot: File?) {
    when (node) {
        is PluginUiTree.Node.Heading -> Text(
            text = node.text,
            style = when (node.level) {
                1 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleLarge
            },
        )
        is PluginUiTree.Node.Body -> Text(text = node.text, style = MaterialTheme.typography.bodyMedium)
        is PluginUiTree.Node.Caption -> Text(
            text = node.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is PluginUiTree.Node.Badge -> BadgeChip(node.text, node.tone)
        is PluginUiTree.Node.Stat -> StatBlock(node.label, node.value, node.hint)
        is PluginUiTree.Node.KeyValue -> KeyValueRow(node.key, node.value)
        is PluginUiTree.Node.List_ -> BulletList(node.items, node.ordered == true)
        is PluginUiTree.Node.Progress -> ProgressBar(node.value, node.max, node.label)
        is PluginUiTree.Node.AssetImage -> AssetImage(node.path, node.alt, assetsRoot)
        is PluginUiTree.Node.Code -> CodeBlock(node.text)
        else -> Unit
    }
}

private fun Int?.dp(default: Int) = (this ?: default).coerceIn(0, 64).dp

@Composable
private fun BadgeChip(text: String, tone: String?) {
    val palette = when (tone?.lowercase()) {
        "warn", "warning" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        "error", "danger" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(color = palette.first, shape = MuseShapes.pill) {
        Text(
            text = text,
            color = palette.second,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatBlock(label: String, value: String, hint: String?) {
    Column(modifier = Modifier.padding(end = 12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = value, style = MaterialTheme.typography.headlineSmall)
        if (!hint.isNullOrBlank()) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeyValueRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BulletList(items: List<String>, ordered: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (ordered) "${index + 1}." else "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = item, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ProgressBar(value: Float, max: Float?, label: String?) {
    val ceiling = (max ?: 1f).coerceAtLeast(0.0001f)
    val fraction = (value / ceiling).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }
}

/** 只渲染插件包内的图片；越界或解码不了都退回纯文本 alt，不弹错、不显示破图。 */
@Composable
private fun AssetImage(path: String, alt: String?, assetsRoot: File?) {
    val file = assetsRoot?.let { SafePaths.safeResolve(it, path) }
    if (file == null || !file.isFile) {
        Text(
            text = alt?.takeIf { it.isNotBlank() } ?: stringResource(R.string.muse_plugins_view_image_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Image(
        painter = rememberAsyncImagePainter(model = file),
        contentDescription = alt,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
    )
}

@Composable
private fun CodeBlock(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MuseShapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(MusePaddings.tightGap),
        )
    }
}

/**
 * 插件管理页里的一行入口：声明式界面存在时给出"打开面板"，解析失败时给出原因。
 *
 * HTML 面板的入口由调用方保留（逃生口），两者不互相顶替 ——
 * 一个插件可以同时提供原生界面与自定义排版的 HTML。
 */
@Composable
fun PluginDeclarativeViewEntry(
    pluginName: String,
    loaded: PluginUiTree.Parsed?,
    assetsRoot: File?,
    /** 是否已授予 `ui.interactive`。未授予时输入控件禁用并写明原因（不隐藏）。 */
    interactiveEnabled: Boolean = false,
    disabledReason: String? = null,
    /** 提交入口。为 null 时界面按只读渲染（例如调用方还没有接上执行链）。 */
    onSubmit: (suspend (handler: String, valuesJson: String) -> PluginUiSubmitOutcome)? = null,
) {
    if (loaded == null) return
    var open by remember { mutableStateOf(false) }
    val broken = !loaded.success
    val label = if (broken) {
        stringResource(R.string.muse_plugins_view_broken)
    } else {
        stringResource(R.string.muse_plugins_open_panel)
    }
    // 刻意复用 HTML 面板那一行的形状：同一个页面里两种入口长得不一样，
    // 用户会以为它们是不同的东西。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(vertical = MusePaddings.tightGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        Icon(
            imageVector = if (broken) MuseIcons.alertTriangle else MuseIcons.bolt,
            contentDescription = null,
            tint = if (broken) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(MuseIconSizes.iconSmall),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (broken) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (open) {
        MuseDialog(
            onDismissRequest = { open = false },
            title = pluginName,
            content = {
                if (broken) {
                    Text(
                        text = loaded.reason ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    val tree = loaded.root
                    // 只有树里真的有交互节点、且调用方接上了提交链路，才走交互渲染；
                    // 否则保持只读 —— 给一个点了没反应的界面比不给更糟。
                    if (tree != null && onSubmit != null && hasInteractiveNodes(tree)) {
                        PluginInteractiveUiView(
                            root = tree,
                            assetsRoot = assetsRoot,
                            interactiveEnabled = interactiveEnabled,
                            disabledReason = disabledReason,
                            onSubmit = onSubmit,
                        )
                    } else {
                        tree?.let { PluginUiTreeView(it, assetsRoot) }
                    }
                }
            },
            confirmText = stringResource(R.string.common_close),
            onConfirm = { open = false },
            dismissText = null,
        )
    }
}
