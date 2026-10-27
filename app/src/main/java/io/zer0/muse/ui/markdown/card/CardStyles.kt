package io.zer0.muse.ui.markdown.card

/**
 * 卡片的默认视觉词汇。
 *
 * 卡片里的样式一律走这里的类名和 [CardTheme] 的变量，不写内联 CSS、不写字面色。
 * 这样做的好处不是"整洁"，而是让模型不必每次从零发明版式：给它一组名字，
 * 它只要挑名字，出的东西就稳定。
 *
 * 三层结构：
 *
 *  - [BASE_CSS] 排版件：标题、正文、小节、指标、表格、引用块、图片框
 *  - [CONTROLS_CSS] 控件：按钮、输入、滑杆、选择、标签页
 *  - [LAYOUT_CSS] 骨架：阅读容器与安全区
 */
internal object CardStyles {

    /** 一次性拼好，避免每次渲染重复拼接。 */
    val ALL: String by lazy { LAYOUT_CSS + BASE_CSS + CONTROLS_CSS }

    /**
     * 骨架：阅读容器与右上安全区。
     *
     * 安全区是硬约束：右上 100×40 留给宿主控件，正文、图表、按钮都不进这块。
     * 装饰性背景可以铺，那是唯一例外。
     */
    private const val LAYOUT_CSS = """
/* body 的高度由内容决定，不参与视口分配。
   卡片里常见的 height:100% / min-height:100vh 会让 body 恒等于视口，
   高度测量随之失效 —— 外面看到的是「内容只有一小块，下面一大片空白」。 */
html,body{height:auto !important;min-height:0 !important;}
html,body{overflow:visible;}
.m-shell{
  max-width:var(--muse-measure,100%);
  margin:0 auto;
  padding:var(--muse-s3) var(--muse-s4) var(--muse-s4);
}
.m-safe-top{min-height:40px;}
.m-narrow{padding-left:var(--muse-s3);padding-right:var(--muse-s3);}
"""

    /** 排版件。 */
    private const val BASE_CSS = """
.m-eyebrow{
  margin:0 0 var(--muse-s2);
  font-size:12px;letter-spacing:.08em;text-transform:none;
  color:var(--muse-faint);
}
.m-title{
  margin:0 0 var(--muse-s3);
  font-family:var(--muse-serif);
  font-size:22px;font-weight:600;line-height:1.32;
  color:var(--muse-fg);
}
.m-title--solo{margin-bottom:var(--muse-s2);}
.m-lead{
  margin:0 0 var(--muse-s4);
  font-size:14.5px;line-height:1.7;color:var(--muse-muted);
}
.m-section{margin:0 0 var(--muse-s5);}
.m-section:last-child{margin-bottom:0;}
.m-heading{
  margin:0 0 var(--muse-s2);
  font-size:15px;font-weight:600;line-height:1.4;color:var(--muse-fg);
}
.m-caption{
  margin:var(--muse-s1) 0 0;
  font-size:12px;line-height:1.5;color:var(--muse-faint);
}
.m-copy{
  margin:0 0 var(--muse-s3);
  font-size:14.5px;line-height:1.72;color:var(--muse-fg);
}
.m-copy:last-child{margin-bottom:0;}
.m-label{
  display:inline-block;
  font-size:11.5px;letter-spacing:.02em;color:var(--muse-faint);
}
.m-highlight{
  margin:var(--muse-s3) 0;
  padding:var(--muse-s3);
  border-radius:10px;
  background:var(--muse-accent-soft);
  color:var(--muse-accent-soft-fg);
  font-size:14px;line-height:1.66;
}
.m-stack{display:flex;flex-direction:column;gap:var(--muse-s3);}
.m-flow{display:flex;flex-wrap:wrap;gap:var(--muse-s2);align-items:center;}
.m-split{display:flex;gap:var(--muse-s4);align-items:flex-start;}
.m-split>*{flex:1 1 0;min-width:0;}
.m-panel{
  padding:var(--muse-s3);
  border-radius:10px;
  background:var(--muse-surface-2);
}
.m-badge{
  display:inline-flex;align-items:center;gap:4px;
  padding:2px 8px;border-radius:999px;
  font-size:11.5px;font-weight:500;
  background:var(--muse-accent-soft);color:var(--muse-accent-soft-fg);
}
.m-badge--good{background:color-mix(in srgb,var(--muse-good) 14%,transparent);color:var(--muse-good);}
.m-badge--warn{background:color-mix(in srgb,var(--muse-warn) 16%,transparent);color:var(--muse-warn);}
.m-badge--danger{background:color-mix(in srgb,var(--muse-danger) 14%,transparent);color:var(--muse-danger);}
.m-metrics{
  display:flex;gap:var(--muse-s4);
  padding:var(--muse-s3) 0;
  border-top:1px solid var(--muse-card-line-soft);
  border-bottom:1px solid var(--muse-card-line-soft);
}
.m-metric{flex:1 1 0;min-width:0;}
.m-metric-value{
  display:block;
  font-size:19px;font-weight:600;line-height:1.2;
  color:var(--muse-accent);
  font-variant-numeric:tabular-nums;
}
.m-metric-label{margin-top:2px;font-size:11.5px;color:var(--muse-faint);}
.m-table-region{overflow-x:auto;-webkit-overflow-scrolling:touch;}
.m-table{width:100%;border-collapse:collapse;font-size:13px;font-variant-numeric:tabular-nums;}
.m-table th{
  padding:6px 10px 6px 0;text-align:left;font-weight:600;
  color:var(--muse-muted);border-bottom:1px solid var(--muse-border);
  white-space:nowrap;
}
.m-table td{
  padding:7px 10px 7px 0;color:var(--muse-fg);
  border-bottom:1px solid var(--muse-card-line-soft);
}
.m-table tr:last-child td{border-bottom:none;}
.m-kv{display:grid;grid-template-columns:auto 1fr;gap:6px var(--muse-s4);font-size:13.5px;}
.m-kv dt{color:var(--muse-faint);white-space:nowrap;}
.m-kv dd{margin:0;color:var(--muse-fg);}
.m-disclosure{
  margin-top:var(--muse-s3);
  font-size:12.5px;color:var(--muse-faint);
}
.m-disclosure>summary{
  cursor:pointer;list-style:none;padding:2px 0;
  background:transparent;border:none;color:var(--muse-faint);
}
.m-disclosure>summary::-webkit-details-marker{display:none;}
.m-disclosure>summary::before{content:"› ";display:inline-block;transition:transform .16s ease;}
.m-disclosure[open]>summary::before{transform:rotate(90deg);}
.m-disclosure-body{margin-top:var(--muse-s2);}
.m-figure{margin:var(--muse-s3) 0;}
.m-media-frame{
  display:block;width:100%;overflow:hidden;
  border-radius:10px;
  border:2px solid var(--muse-surface-3);
  box-shadow:0 1px 3px rgba(0,0,0,.06);
}
.m-media-frame img{display:block;width:100%;height:auto;}
.m-banner{
  display:block;width:100%;border-radius:10px;
  margin:0 0 var(--muse-s3);
}
.m-banner img{display:block;width:100%;height:auto;border-radius:10px;}
.m-footer{
  margin-top:var(--muse-s4);padding-top:var(--muse-s2);
  border-top:1px solid var(--muse-card-line-soft);
  font-size:11.5px;color:var(--muse-faint);
}
"""

    /** 控件。外观由这里给，值接口一律用原生元素。 */
    private const val CONTROLS_CSS = """
.m-button{
  all:unset;display:inline-flex;align-items:center;justify-content:center;gap:6px;
  min-height:36px;padding:0 var(--muse-s4);
  border-radius:var(--muse-radius-sm);
  font-size:13.5px;font-weight:500;
  background:var(--muse-accent);color:#fff;
  cursor:pointer;transition:opacity .14s ease,transform .14s ease;
  -webkit-tap-highlight-color:transparent;
}
.m-button:active{transform:scale(.98);opacity:.9;}
.m-button--quiet{
  background:var(--muse-surface-2);color:var(--muse-fg);
  border:1px solid var(--muse-border);
}
.m-button[aria-pressed="true"]{box-shadow:inset 0 0 0 1px var(--muse-accent);}
.m-input,.m-textarea{
  display:block;width:100%;
  padding:9px var(--muse-s3);
  border:1px solid var(--muse-border);border-radius:var(--muse-radius-sm);
  background:var(--muse-surface);color:var(--muse-fg);
  font-family:inherit;font-size:14px;line-height:1.5;
  -webkit-appearance:none;appearance:none;
}
.m-input:focus,.m-textarea:focus{outline:2px solid var(--muse-accent);outline-offset:1px;}
.m-textarea{min-height:88px;resize:vertical;}
.m-field{margin-bottom:var(--muse-s3);}
.m-field-label{display:block;margin-bottom:5px;font-size:12.5px;color:var(--muse-muted);}
.m-check{
  display:inline-flex;align-items:center;gap:8px;
  font-size:13.5px;color:var(--muse-fg);cursor:pointer;
}
.m-check input{
  -webkit-appearance:none;appearance:none;
  width:18px;height:18px;margin:0;flex:0 0 18px;
  border:1.5px solid var(--muse-border);border-radius:5px;
  background:var(--muse-surface);position:relative;cursor:pointer;
}
.m-check input:checked{background:var(--muse-accent);border-color:var(--muse-accent);}
.m-check input:checked::after{
  content:"";position:absolute;left:5px;top:1.5px;
  width:5px;height:10px;border:solid #fff;border-width:0 2px 2px 0;
  transform:rotate(45deg);
}
.m-range{
  -webkit-appearance:none;appearance:none;
  width:100%;height:22px;background:transparent;cursor:pointer;
}
.m-range::-webkit-slider-runnable-track{
  height:4px;border-radius:2px;
  background:linear-gradient(
    to right,
    var(--muse-accent) 0%,
    var(--muse-accent) var(--m-range-progress,50%),
    var(--muse-border) var(--m-range-progress,50%),
    var(--muse-border) 100%);
}
.m-range::-webkit-slider-thumb{
  -webkit-appearance:none;appearance:none;
  width:16px;height:16px;margin-top:-6px;border-radius:50%;
  background:var(--muse-accent);border:2px solid var(--muse-surface);
  box-shadow:0 1px 3px rgba(0,0,0,.18);
}
.m-range::-moz-range-track{height:4px;border-radius:2px;background:var(--muse-border);}
.m-range::-moz-range-progress{height:4px;border-radius:2px;background:var(--muse-accent);}
.m-range::-moz-range-thumb{
  width:14px;height:14px;border-radius:50%;background:var(--muse-accent);
  border:2px solid var(--muse-surface);
}
.m-tabs{
  display:flex;gap:2px;padding:3px;
  border-radius:var(--muse-radius-sm);background:var(--muse-surface-2);
  margin-bottom:var(--muse-s3);
}
.m-tab{
  all:unset;flex:1 1 0;text-align:center;
  padding:6px 10px;border-radius:calc(var(--muse-radius-sm) - 2px);
  font-size:12.5px;color:var(--muse-muted);cursor:pointer;
  transition:background .14s ease,color .14s ease;
}
.m-tab[aria-selected="true"]{
  background:var(--muse-surface);color:var(--muse-fg);font-weight:600;
  box-shadow:0 1px 2px rgba(0,0,0,.07);
}
.m-select{position:relative;display:inline-block;}
.m-select select{
  -webkit-appearance:none;appearance:none;
  padding:7px 28px 7px var(--muse-s3);
  border:1px solid var(--muse-border);border-radius:var(--muse-radius-sm);
  background:var(--muse-surface);color:var(--muse-fg);
  font-family:inherit;font-size:13.5px;cursor:pointer;
}
.m-select::after{
  content:"";position:absolute;right:11px;top:50%;
  width:6px;height:6px;margin-top:-4px;
  border-right:1.5px solid var(--muse-faint);
  border-bottom:1.5px solid var(--muse-faint);
  transform:rotate(45deg);pointer-events:none;
}
"""

    /** 窄屏下的收缩。 */
    private const val NARROW: String = """
@media (max-width:360px){
  .m-shell{padding:var(--muse-s2) var(--muse-s3) var(--muse-s3);}
  .m-title{font-size:20px;}
  .m-metrics{flex-direction:column;gap:var(--muse-s2);}
  .m-split{flex-direction:column;}
}
"""

    /** 完整样式表。 */
    fun full(): String = ALL + NARROW
}
