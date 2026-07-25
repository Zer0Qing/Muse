package io.zer0.muse.ui.markdown

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.LifecycleAwareWebViewContainer

/** M8 修复: 公式源码长度上限,超过则降级为纯文本,避免 WebView 内存压力。 */
private const val MAX_LATEX_LENGTH = 5000

/**
 * Phase 8.6: LaTeX 公式渲染(WebView + KaTeX)。
 *
 * 实现:
 *  - 用 AndroidView 嵌入 WebView
 *  - 加载 KaTeX CSS+JS(从本地 assets/vendor/ 离线加载,v1.97 改造)
 *  - 调用 katex.render(latex, element, {displayMode: true}) 渲染
 *  - 背景透明,文字色用 MaterialTheme.onBackground
 *
 * 体积对比:
 *  - JLatexMath: ~2MB(但需要 Android 移植版,官方版用 java.awt 不可用)
 *  - KaTeX via WebView: ~305KB APK 体积(katex.min.js ~280KB + katex.min.css ~25KB,打包在 assets/vendor/)
 *
 * 局限:
 *  - WebView 启动有 ~50ms 开销
 *  - 不支持 AMS 全部环境(但 KaTeX 已覆盖 95% 常用 LaTeX 语法)
 *
 * 安全说明: v1.97 起 KaTeX 资源已从本地 assets 加载(base URL 为 file:///android_asset/),
 * 不再依赖任何远程 CDN,无 SRI/劫持风险。RichContentWebViewClient 拦截所有顶层导航。
 *
 * M8 内存优化说明: 每个公式块创建独立 WebView,长消息多公式时内存压力大;
 * 当 latex 长度超过 [MAX_LATEX_LENGTH] 时降级为纯文本渲染,不再创建 WebView。
 *
 * @param latex LaTeX 公式源码(不含 $$ 定界符)
 * @param modifier 外部布局修饰(M-MD7 新增)
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FormulaView(
    latex: String,
    modifier: Modifier = Modifier,
) {
    val onBackgroundColor = MaterialTheme.colorScheme.onBackground
    val textColorHex = String.format("#%06X", 0xFFFFFF and onBackgroundColor.toArgb())
    // L-MD14 修复: 错误色用 MaterialTheme.error 而非硬编码 #B00020
    val errorColorHex = String.format("#%06X", 0xFFFFFF and MaterialTheme.colorScheme.error.toArgb())

    // M8 修复: 超长公式降级为源码文本,避免每个公式块创建 WebView 造成内存压力
    // 已知限制: 降级后不渲染公式图形,仅显示原始 LaTeX 源码;长消息多公式场景优先保内存。
    if (latex.length > MAX_LATEX_LENGTH) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = latex,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
                .fillMaxWidth()
                .padding(8.dp),
        )
        Spacer(Modifier.height(6.dp))
        return
    }

    // 缓存 HTML 模板:latex 或颜色变化时才重新生成
    val html = remember(latex, textColorHex, errorColorHex) {
        buildHtml(latex, textColorHex, errorColorHex)
    }

    Spacer(Modifier.height(6.dp))
    // stringResource 需在 @Composable 直接调用位置提取,不能在 semantics{} 内使用。
    val formulaCd = stringResource(R.string.markdown_formula_cd, latex)
    // v1.88 修复: 改用 LifecycleAwareWebViewContainer,自动处理 ON_PAUSE/ON_RESUME/ON_DESTROY,
    // 解决 Activity 后台时 KaTeX 渲染相关 JS 定时器/动画继续运行耗电的问题。
    // 原 L9 已知限制已消除;原 v0.53 的 onRelease 释放逻辑由容器统一兜底。
    // v1.97: base URL 为 file:///android_asset/ 以加载本地 KaTeX
    LifecycleAwareWebViewContainer(
        htmlContent = html,
        baseUrl = "file:///android_asset/",
        javaScriptEnabled = true,
        // L11 修复: KaTeX 不需要 DOM 存储,保持默认 false
        domStorageEnabled = false,
        // 拦截所有顶层导航,资源走本地 assets 不需远程白名单
        webViewClient = RichContentWebViewClient(),
        // M-MD7 修复: 接受外部 modifier;M-MD8 修复: height(80.dp) 改为 heightIn(min=80.dp)
        // M5 修复: 加 contentDescription 供无障碍朗读
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .semantics { contentDescription = formulaCd },
    )
    Spacer(Modifier.height(6.dp))
}

/**
 * 构造包含 KaTeX 渲染脚本的 HTML。
 *
 * - KaTeX CSS+JS 从本地 assets/vendor/ 加载(v1.97 改造,免 CDN 依赖)
 * - 页面加载完成后自动调用 katex.render()
 * - displayMode: true 对应 $$...$$ 块级公式
 * - throwOnError: false 让解析失败时显示原 latex 文本而非崩溃
 *
 * @param latex LaTeX 源码
 * @param textColorHex 正文颜色(ARGB 转 #RRGGBB)
 * @param errorColorHex 错误提示颜色(L-MD14: 由调用方传入主题 error 色)
 */
private fun buildHtml(latex: String, textColorHex: String, errorColorHex: String): String {
    // 转义 latex 中的特殊字符,避免破坏 JS 字符串
    val escaped = latex
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")
        .replace("</", "<\\/")  // 防 </script> 注入
    return """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<!-- M-MD11 修复: 移除 user-scalable=no,允许用户缩放查看公式细节;maximum-scale=5.0 限制最大缩放 -->
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0">
<!-- v1.97: KaTeX CSS+JS 改为从本地 assets 加载,解决 CDN 在中国不可用的问题。字体回退到系统字体。 -->
<link rel="stylesheet" href="vendor/katex.min.css">
<script defer src="vendor/katex.min.js"></script>
<style>
  html, body {
    margin: 0;
    padding: 8px 4px;
    background: transparent;
    color: $textColorHex;
    font-size: 16px;
    overflow-x: auto;
    overflow-y: hidden;
  }
  #formula {
    display: inline-block;
    width: 100%;
    text-align: center;
  }
  .katex-error {
    font-family: monospace;
    color: $errorColorHex;
    font-size: 13px;
    padding: 4px;
    background: rgba(255,255,255,0.05);
    border-radius: 4px;
    display: inline-block;
  }
</style>
</head>
<body>
<div id="formula"></div>
<script>
  window.onload = function() {
    try {
      katex.render("$escaped", document.getElementById("formula"), {
        displayMode: true,
        throwOnError: false,
        output: "htmlAndMathml"
      });
    } catch (e) {
      // L5 修复: 用 textContent 替代 innerHTML,避免 e.message 被解析为 HTML 造成注入
      document.getElementById("formula").textContent = '公式错误: ' + e.message;
    }
  };
</script>
</body>
</html>
    """.trimIndent()
}
