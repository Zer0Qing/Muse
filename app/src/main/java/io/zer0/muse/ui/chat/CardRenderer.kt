package io.zer0.muse.ui.chat

import android.annotation.SuppressLint
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.common.LifecycleAwareWebViewContainer

/**
 * 卡片渲染器(openhanako show-card-tool.ts + card-guide-tool.ts 移植)。
 *
 * 渲染 AI show_card 工具生成的 HTML/SVG 卡片内容,
 * 在 Material Card 内的沙箱 WebView 中展示。
 */
object CardRenderer {

    private const val CARD_PREFIX = "Card '"
    private const val RENDERED_MARKER = "' rendered (id: "

    /** 检查工具结果是否包含卡片内容。 */
    fun isCardResult(toolResult: String): Boolean {
        return toolResult.startsWith(CARD_PREFIX) && toolResult.contains(RENDERED_MARKER)
    }

    /** 从工具结果提取卡片 id。 */
    fun extractCardId(toolResult: String): String? {
        if (!isCardResult(toolResult)) return null
        val start = toolResult.indexOf(RENDERED_MARKER) + RENDERED_MARKER.length
        val end = toolResult.indexOf(")", start)
        return if (end > start) toolResult.substring(start, end) else null
    }

    /** 构建包裹卡片片段的完整 HTML 页面。 */
    fun buildCardHtml(code: String, title: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        background: transparent;
                        color: #1a1a2e;
                        padding: 16px;
                    }
                    svg { max-width: 100%; height: auto; }
                    table { border-collapse: collapse; width: 100%; }
                    th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
                    th { background: #f5f5f5; }
                    @media (prefers-color-scheme: dark) {
                        body { color: #e0e0e0; }
                        th { background: #2a2a3e; }
                        th, td { border-color: #444; }
                    }
                </style>
            </head>
            <body>$code</body>
            </html>
        """.trimIndent()
    }

    /**
     * 使用 WebView 的 Composable 卡片渲染器。
     *
     * @param htmlCode 待渲染的 HTML/SVG 片段
     * @param title 用于无障碍的卡片标题
     * @param modifier Compose 修饰符
     */
    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun RenderCard(
        htmlCode: String,
        title: String,
        modifier: Modifier = Modifier,
    ) {
        Card(
            modifier = modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // v1.88 修复: 改用 LifecycleAwareWebViewContainer,自动处理 ON_PAUSE/ON_RESUME/ON_DESTROY,
            // 解决 Activity 后台时 WebView 残留资源占用问题。
            // baseUrl=null 表示不加载本地 assets;原 factory 内的 loadDataWithBaseURL 交给容器统一处理。
            LifecycleAwareWebViewContainer(
                htmlContent = buildCardHtml(htmlCode, title),
                baseUrl = null,
                // sandboxed:禁用 JS
                javaScriptEnabled = false,
                webViewClient = WebViewClient(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
            )
        }
    }
}
