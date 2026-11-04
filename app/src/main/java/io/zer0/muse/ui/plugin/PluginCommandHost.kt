package io.zer0.muse.ui.plugin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.plugin.PluginCommandRegistry
import androidx.compose.ui.platform.LocalContext
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.tools.SkillMediaToolsImpl
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import org.koin.compose.koinInject

/**
 * 插件斜杠命令的界面宿主：提示列表 + 执行。
 *
 * 三条与产品行为相关的决定：
 *  1. **结果写回输入框，不写聊天历史**。往历史里插消息等于冒充模型说话（`chat.append` 上已论证过），
 *     命令不该破例；用户能先看见、再决定发不发。
 *  2. **只有"命令真的存在"才拦截发送**。`/` 开头的普通文本（例如贴一个路径）照常发给模型 ——
 *     静默吞掉用户输入是最糟的一种"功能"。
 *  3. 失败也写回输入框并附原因：用户能看到发生了什么，而不是点了没反应。
 */
class PluginCommandHost internal constructor(
    private val registry: PluginCommandRegistry,
    private val mediaTools: SkillMediaToolsImpl,
    private val setInput: (String) -> Unit,
) {

    var suggestions by mutableStateOf<List<PluginCommandRegistry.Entry>>(emptyList())
        private set

    /** 输入变化时刷新候选：只在 `/` 开头且还没打空格时提示。 */
    suspend fun refresh(text: String) {
        val query = text.takeIf { it.startsWith("/") && !it.contains(' ') && !it.contains('\n') }
            ?.removePrefix("/")?.lowercase()
        suggestions = if (query == null) {
            emptyList()
        } else {
            registry.list().filter { it.name.startsWith(query) }
        }
    }

    /**
     * 尝试把 [text] 当成插件命令执行。
     *
     * @return true 表示已处理（调用方不要再发送）；false 表示不是插件命令，按普通消息处理。
     */
    suspend fun handle(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return false
        val name = trimmed.removePrefix("/").substringBefore(' ').lowercase()
        // 含 '/' 的是路径不是命令（用户可能想发 /usr/local/bin）。宁可放行给模型，也不要误吞。
        if (name.isBlank() || name.contains('/')) return false
        val entry = registry.find(name) ?: return false
        val input = trimmed.removePrefix("/").removePrefix(name).trim()
        val result = mediaTools.invokePluginCommand(entry.pluginId, entry.handler, input)
        setInput(result)
        suggestions = emptyList()
        return true
    }
}

@Composable
fun rememberPluginCommandHost(setInput: (String) -> Unit): PluginCommandHost {
    val pluginManager: PluginManager = koinInject()
    val settings: SettingsRepository = koinInject()
    val mediaTools: SkillMediaToolsImpl = koinInject()
    val context = LocalContext.current
    return remember(pluginManager, settings, mediaTools, context) {
        PluginCommandHost(
            // 与执行侧同一把闸：设置总开关 **且** 安全模式的文件标志。
            // 只查设置项会造成"命令照常列出、按下去被拒"的割裂；
            // 而执行侧（SkillMediaToolsImpl.prepareDeclaredCall）查的是并了文件标志的那个谓词。
            registry = PluginCommandRegistry(pluginManager) {
                !io.zer0.muse.data.plugin.PluginKillSwitch.isDisabled(context) &&
                    settings.arePluginsGloballyEnabled()
            },
            mediaTools = mediaTools,
            setInput = setInput,
        )
    }
}

/** 输入岛上方的插件命令候选（与内置命令的候选视觉一致）。 */
@Composable
fun PluginCommandSuggestionStrip(
    host: PluginCommandHost,
    text: String,
    onPick: (PluginCommandRegistry.Entry) -> Unit,
) {
    LaunchedEffect(text) { host.refresh(text) }
    val entries = host.suggestions
    if (entries.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(vertical = MusePaddings.tightGap)) {
            entries.take(6).forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onPick(entry) },
                        )
                        .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "/${entry.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = entry.description.ifBlank { entry.pluginName },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 供调用方判断是否需要渲染提示条（避免每次输入都跑一次 suspend 查询）。 */
fun shouldQueryPluginCommands(text: String): Boolean =
    text.startsWith("/") && !text.contains(' ') && !text.contains('\n') && text.length > 0
