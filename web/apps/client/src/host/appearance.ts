/**
 * 外观（主题模式 / 字号缩放）的纯逻辑与 DOM 应用。
 *
 * 三态主题：
 *  - `system`：`data-theme="system"`，样式交给 `prefers-color-scheme` 媒体查询；
 *  - `light` / `dark`：`data-theme` 直接写死，CSS 里属性选择器优先于媒体查询。
 * 字号四档写成 `data-font-scale`，CSS 据此设定 `--text-scale`，所有排版令牌乘这个倍率。
 *
 * 应用函数只依赖一个极小的 target 结构，便于脱离浏览器断言；
 * 真实 DOM 的接线在 [documentAppearanceTarget]。
 */
export type ThemeMode = "system" | "light" | "dark";
export type FontScale = "small" | "medium" | "large" | "xlarge";
export type ResolvedScheme = "light" | "dark";

export const THEME_MODES: readonly ThemeMode[] = ["system", "light", "dark"];
export const FONT_SCALES: readonly FontScale[] = ["small", "medium", "large", "xlarge"];

/** 四档倍率：0.9 / 1.0 / 1.1 / 1.2。只乘在字号与行高上，不做整体缩放。 */
export const FONT_SCALE_FACTOR: Readonly<Record<FontScale, number>> = {
  small: 0.9,
  medium: 1,
  large: 1.1,
  xlarge: 1.2,
};

export function isThemeMode(value: unknown): value is ThemeMode {
  return typeof value === "string" && (THEME_MODES as readonly string[]).includes(value);
}

export function isFontScale(value: unknown): value is FontScale {
  return typeof value === "string" && (FONT_SCALES as readonly string[]).includes(value);
}

/** 读主题模式：不认识的值当作「没给」，由调用方退回 system。 */
export function readThemeMode(value: unknown): ThemeMode | undefined {
  if (typeof value === "boolean") return value ? "dark" : "light";
  if (typeof value !== "string") return undefined;
  const normalized = value.trim().toLowerCase();
  return isThemeMode(normalized) ? normalized : undefined;
}

/**
 * 读字号档位：除了四个档位名，也接受数字倍率（Host 的 display.fontSizeScale 历史上是 "1"
 * 这种字符串），按最接近的一档归一。
 */
export function readFontScale(value: unknown): FontScale | undefined {
  if (typeof value === "string") {
    const normalized = value.trim().toLowerCase();
    if (isFontScale(normalized)) return normalized;
  }
  const numeric = typeof value === "number" ? value : typeof value === "string" ? Number(value.trim()) : Number.NaN;
  if (!Number.isFinite(numeric) || numeric <= 0) return undefined;
  let best: FontScale = "medium";
  let distance = Number.POSITIVE_INFINITY;
  for (const scale of FONT_SCALES) {
    const gap = Math.abs(FONT_SCALE_FACTOR[scale] - numeric);
    if (gap < distance) {
      distance = gap;
      best = scale;
    }
  }
  return best;
}

/** system 跟着系统走，其余强制。 */
export function resolveScheme(mode: ThemeMode, prefersDark: boolean): ResolvedScheme {
  if (mode === "system") return prefersDark ? "dark" : "light";
  return mode;
}

/** theme-color 用当前 scheme 的 surface 令牌值（浅色 / 深色两套）。 */
export function themeColorFor(scheme: ResolvedScheme, read: (token: string) => string): string {
  return read(`--muse-color-scheme-${scheme}-surface`).trim();
}

export interface AppearanceTarget {
  /** `document.documentElement.dataset`：写 data-theme / data-font-scale。 */
  readonly dataset: Record<string, string | undefined>;
  /** `<meta name="theme-color">`：写成当前 scheme 的 surface 色。 */
  readonly meta?: { content: string } | undefined;
  /** 读 CSS 变量当前值；拿不到时返回空串。 */
  readonly readToken?: ((token: string) => string) | undefined;
}

/**
 * 应用外观：先写属性（样式立刻生效），再更新 theme-color。
 * 返回解析后的 scheme，调用方可以据此做别的事（或只管返回值做断言）。
 */
export function applyAppearance(
  target: AppearanceTarget,
  mode: ThemeMode,
  scale: FontScale,
  prefersDark: boolean,
): ResolvedScheme {
  target.dataset["theme"] = mode;
  target.dataset["fontScale"] = scale;
  const scheme = resolveScheme(mode, prefersDark);
  if (target.meta !== undefined && target.readToken !== undefined) {
    const color = themeColorFor(scheme, target.readToken);
    if (color.length > 0) target.meta.content = color;
  }
  return scheme;
}

/** 真实 DOM 的 target：属性写到 <html>，theme-color 写到 meta。 */
export function documentAppearanceTarget(doc: Document): AppearanceTarget {
  const meta = doc.querySelector('meta[name="theme-color"]');
  const view = doc.defaultView;
  return {
    dataset: doc.documentElement.dataset,
    meta:
      meta === null
        ? undefined
        : {
            get content(): string {
              return meta.getAttribute("content") ?? "";
            },
            set content(value: string) {
              meta.setAttribute("content", value);
            },
          },
    readToken: (token: string) =>
      view === null || typeof view.getComputedStyle !== "function"
        ? ""
        : view.getComputedStyle(doc.documentElement).getPropertyValue(token),
  };
}
