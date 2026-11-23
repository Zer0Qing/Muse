package io.zer0.muse.ui.common.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseShapes

/**
 * 分组容器 — 一组共用圆角容器。
 *
 * **新代码不要用。** 用 [io.zer0.muse.ui.common.surface.CardGroup] + `item(...)`。
 *
 * 保留原因：存量页面里有一部分分组内容不是标准「图标 + 标题 + 副标题 + 右侧控件」
 * 四件套（内嵌 MuseCapsuleTab / MuseTextField / when 分支 / 表单列），
 * 而本容器的 content 是 `@Composable` 作用域、CardGroup 的 item 是普通 DSL ——
 * 两者作用域模型不同，这些页面无法机械迁移。
 *
 * 视觉上两者已经一致（同一 surface 背景、同一 outlineVariant 描边、圆角同源），
 * 所以存量页面不迁也不影响观感。
 *
 * @param modifier Modifier(默认 fillMaxWidth)
 * @param content 分组内容
 */
@Deprecated(
    "CMP-06: co-exists with CardGroup; consolidate to CardGroup + DSL items",
)
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    // CMP-08: 走令牌 — 与 CardGroup 一致:surface 背景 + outlineVariant 描边,
    // 不再用硬编码 White@96%/10% 手写明暗分支。
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
        color = colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(MuseBorderWidth.hairline, colorScheme.outlineVariant),
    ) {
        Column {
            content()
        }
    }
}
