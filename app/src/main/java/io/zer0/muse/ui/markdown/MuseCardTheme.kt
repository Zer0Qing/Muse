package io.zer0.muse.ui.markdown

/**
 * v2.6.6: 生成式卡片的主题令牌与组件片段样式（注入所有 WebView 卡片）。
 *
 * 目标：让模型手写的 HTML 卡片「看起来像是我们原生画的」——统一颜色/圆角/间距/字体，
 * 并自动适配深浅色。模型只需使用下面这套 CSS 变量与 class，无需自己造样式。
 *
 * 用法（模型侧）：卡片 HTML 里直接用 `var(--muse-fg)` 等变量，或套 `.muse-card`
 * `.muse-metric` `.muse-btn` 等 class。
 *
 * 注入位置：RichContentCard 的 html/svg 包装模板 head 内（[styleBlock]）。
 */
internal object MuseCardTheme {

    /**
     * 注入到每张卡片的 <style>。
     *
     * - 变量跟随 prefers-color-scheme 切换深浅色；
     * - 基础元素（body/表格/按钮/输入）已带样式，模型不写 CSS 也体面；
     * - 组件 class 供模型选用（metric 指标卡、steps 步骤条、progress 进度环、row 行等）。
     */
    val styleBlock: String = """
        :root {
            --muse-bg: transparent;
            --muse-surface: #ffffff;
            --muse-surface-2: #f2f2f4;
            --muse-fg: #1a1a1c;
            --muse-muted: #6b6b70;
            --muse-border: rgba(0,0,0,0.10);
            --muse-accent: #2f7cf6;
            --muse-accent-fg: #ffffff;
            --muse-radius: 14px;
            --muse-radius-sm: 10px;
            --muse-gap: 12px;
        }
        @media (prefers-color-scheme: dark) {
            :root {
                --muse-surface: #1e1e22;
                --muse-surface-2: #2a2a30;
                --muse-fg: #ececef;
                --muse-muted: #9a9aa2;
                --muse-border: rgba(255,255,255,0.14);
                --muse-accent: #5a9bff;
                --muse-accent-fg: #06121f;
            }
        }
        * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
        html, body { margin: 0; padding: 0; background: var(--muse-bg); }
        body {
            color: var(--muse-fg);
            font-family: -apple-system, "Segoe UI", Roboto, "Noto Sans SC", sans-serif;
            font-size: 14px; line-height: 1.5;
            padding: 8px;
            word-break: break-word; overflow-wrap: anywhere;
        }
        h1,h2,h3,h4 { margin: 0 0 8px; font-weight: 600; line-height: 1.3; }
        h1 { font-size: 18px; } h2 { font-size: 16px; } h3,h4 { font-size: 14px; }
        p { margin: 0 0 8px; }
        small, .muse-muted { color: var(--muse-muted); font-size: 12px; }
        a { color: var(--muse-accent); text-decoration: none; }
        /* 卡片外壳 */
        .muse-card {
            background: var(--muse-surface);
            border: 1px solid var(--muse-border);
            border-radius: var(--muse-radius);
            padding: var(--muse-gap);
            margin: 0 0 var(--muse-gap);
        }
        .muse-card:last-child { margin-bottom: 0; }
        /* 指标卡 */
        .muse-metric { display: inline-flex; flex-direction: column; gap: 2px; padding: 10px 14px;
            background: var(--muse-surface-2); border-radius: var(--muse-radius-sm); min-width: 84px; }
        .muse-metric .v { font-size: 20px; font-weight: 700; }
        .muse-metric .k { color: var(--muse-muted); font-size: 12px; }
        /* 行 / 列表 */
        .muse-row { display: flex; align-items: center; gap: 10px; padding: 8px 0;
            border-bottom: 1px solid var(--muse-border); }
        .muse-row:last-child { border-bottom: none; }
        /* 按钮 */
        .muse-btn {
            display: inline-flex; align-items: center; justify-content: center; gap: 6px;
            min-height: 44px; padding: 0 16px; border: none; cursor: pointer;
            background: var(--muse-accent); color: var(--muse-accent-fg);
            border-radius: var(--muse-radius-sm); font-size: 14px; font-weight: 600;
        }
        .muse-btn.ghost { background: var(--muse-surface-2); color: var(--muse-fg); }
        .muse-btn:active { filter: brightness(0.94); }
        /* 输入 / 滑块 */
        input, select, textarea {
            min-height: 44px; padding: 8px 10px; color: var(--muse-fg);
            background: var(--muse-surface-2); border: 1px solid var(--muse-border);
            border-radius: var(--muse-radius-sm); font-size: 14px; font-family: inherit;
        }
        input[type="range"] { min-height: 0; padding: 0; width: 100%; accent-color: var(--muse-accent); }
        /* 表格 */
        table { width: 100%; border-collapse: collapse; font-size: 13px; }
        th, td { padding: 8px 10px; text-align: left; border-bottom: 1px solid var(--muse-border); }
        th { color: var(--muse-muted); font-weight: 600; }
        /* 进度条 */
        .muse-progress { height: 8px; background: var(--muse-surface-2); border-radius: 999px; overflow: hidden; }
        .muse-progress > i { display: block; height: 100%; background: var(--muse-accent); border-radius: 999px; }
    """.trimIndent()

    /** 完整 <style> 标签（连标签一起，便于直接拼进 head）。 */
    val styleTag: String = "<style>$styleBlock</style>"

    /** 统一的 viewport meta（窄屏适配必需）。 */
    const val VIEWPORT_META: String = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
}
