/**
 * Renders `src/design/tokens.json` into Muse CSS custom properties.
 *
 * This module is shared by the build (`vite.config.ts`, which injects the generated
 * `:root { … }` block into `index.html`) and by anything that needs the same mapping
 * outside the bundle. It deliberately has no Node or DOM dependencies.
 *
 * Naming: `--muse-<group prefix>-<path>-<leaf>`, kebab-cased, e.g.
 *   colors.brand.primary                  -> --muse-color-brand-primary
 *   colors.scheme.light.onSurface         -> --muse-color-scheme-light-on-surface
 *   colors.web.light.steelA12             -> --muse-color-web-light-steel-a12
 *   typography.scale.bodyLarge.fontSize   -> --muse-type-scale-body-large-font-size
 *   radii.pill                            -> --muse-radius-pill: 50%
 *   component.card.innerPaddingX          -> --muse-component-card-inner-padding-x
 *   component.bubble.maxWidthRatio         -> --muse-component-bubble-max-width-ratio
 *
 * Units follow the design-token groups: lengths become px (Android dp/sp are 1:1 with
 * CSS px at scale 1), motion durations become ms, ratios/weights/stroke widths stay
 * unitless, and string values pass through untouched.
 */

export type TokenLeaf = string | number;
export type TokenValue = TokenLeaf | TokenGroup;
export interface TokenGroup {
  readonly [key: string]: TokenValue;
}
export interface Tokens {
  readonly [group: string]: TokenValue;
}

/** Group name -> CSS variable prefix. Keeps `--muse-color-*` instead of `--muse-colors-*`. */
const GROUP_PREFIX: Readonly<Record<string, string>> = {
  colors: "color",
  typography: "type",
  spacing: "space",
  radii: "radius",
  motion: "motion",
  elevation: "elevation",
  iconSizes: "icons",
};

/** Leaves that are numbers but must not get a px suffix. */
const UNITLESS_LEAVES: ReadonlySet<string> = new Set(["weight", "maxIndex", "strokeWidth"]);

function isGroup(value: TokenValue | undefined): value is TokenGroup {
  return typeof value === "object" && value !== null;
}

function kebab(segment: string): string {
  return segment
    .replace(/([a-z0-9])([A-Z])/g, "$1-$2")
    .replace(/[^a-zA-Z0-9]+/g, "-")
    .toLowerCase();
}

function isUnitless(key: string): boolean {
  return UNITLESS_LEAVES.has(key) || key.endsWith("Ratio") || key.endsWith("Scale");
}

function unitFor(path: readonly string[], key: string): string {
  const group = path[0];
  if (isUnitless(key)) return "";
  if (group === "motion") return path[1] === "durations" ? "ms" : "";
  if (group === "typography" || group === "spacing" || group === "radii" || group === "elevation" || group === "iconSizes" || group === "component") {
    return "px";
  }
  return "";
}

function parseHex(value: string): [number, number, number] {
  const hex = value.trim().replace(/^#/, "");
  const expand = hex.length === 3 ? hex.replace(/./g, (char) => char + char) : hex;
  if (!/^[0-9a-fA-F]{6}$/.test(expand)) {
    throw new Error(`tokens.json: 无法解析的颜色值 ${value}（期望 #RRGGBB 或 #RGB）`);
  }
  return [
    Number.parseInt(expand.slice(0, 2), 16),
    Number.parseInt(expand.slice(2, 4), 16),
    Number.parseInt(expand.slice(4, 6), 16),
  ];
}

function rgba(hex: string, alpha: number): string {
  const [red, green, blue] = parseHex(hex);
  return `rgba(${red}, ${green}, ${blue}, ${Number(alpha.toFixed(2))})`;
}

/**
 * `colors.web.alpha.<mode>.<family>` is a list of alpha percentages that expand into
 * `colors.web.<mode>.<family>A<percent>` rgba() entries, so the tints of a palette colour
 * can never drift away from the colour itself.
 */
function expandWebAlpha(tokens: Tokens): Tokens {
  const colors = tokens["colors"];
  if (!isGroup(colors)) return tokens;
  const web = colors["web"];
  if (!isGroup(web)) return tokens;
  const alpha = web["alpha"];
  if (!isGroup(alpha)) return tokens;

  const resolved: Record<string, TokenValue> = {};
  for (const [key, value] of Object.entries(web)) {
    if (key !== "alpha") resolved[key] = value;
  }

  for (const [mode, families] of Object.entries(alpha)) {
    if (!isGroup(families)) continue;
    const palette = web[mode];
    if (!isGroup(palette)) {
      throw new Error(`tokens.json: colors.web.alpha.${mode} 需要同名的基色组 colors.web.${mode}`);
    }
    const expanded: Record<string, TokenValue> = { ...palette };
    for (const [family, steps] of Object.entries(families)) {
      const base = palette[family];
      if (typeof base !== "string") {
        throw new Error(`tokens.json: colors.web.alpha.${mode}.${family} 缺少同名基色 colors.web.${mode}.${family}`);
      }
      if (!Array.isArray(steps)) {
        throw new Error(`tokens.json: colors.web.alpha.${mode}.${family} 必须是 alpha 百分比数组`);
      }
      for (const step of steps) {
        if (typeof step !== "number") {
          throw new Error(`tokens.json: colors.web.alpha.${mode}.${family} 含非数字项 ${String(step)}`);
        }
        expanded[`${family}A${step}`] = rgba(base, step / 100);
      }
    }
    resolved[mode] = expanded;
  }

  return { ...tokens, colors: { ...colors, web: resolved } };
}

/**
 * Typography sizes follow the reader's font-scale (四档：small/medium/large/xlarge)：
 * `--text-scale` 由界面在 `:root` 上设定，字号与行高都乘上它，比例才不会散。
 * 只在 font-size / line-height 上做，不缩放间距令牌（组件尺寸改成随字号变会挤压布局）。
 */
function scaledDeclaration(value: TokenValue, path: readonly string[], key: string): string | undefined {
  if (typeof value !== "number") return undefined;
  if (path[0] !== "typography" || (key !== "fontSize" && key !== "lineHeight")) return undefined;
  return `calc(${value}px * var(--text-scale, 1))`;
}

function collect(node: TokenGroup, path: readonly string[], out: string[]): void {
  for (const [key, value] of Object.entries(node)) {
    if (key.startsWith("_")) continue;
    const nextPath = [...path, key];
    if (isGroup(value)) {
      collect(value, nextPath, out);
      continue;
    }
    const head = GROUP_PREFIX[nextPath[0] ?? ""] ?? kebab(nextPath[0] ?? "");
    const name = ["--muse", head, ...nextPath.slice(1).map(kebab)].join("-");
    const unit = unitFor(nextPath, key);
    const scaled = scaledDeclaration(value, nextPath, key);
    if (scaled !== undefined) {
      out.push(`  ${name}: ${scaled};`);
      continue;
    }
    out.push(`  ${name}: ${typeof value === "number" ? `${value}${unit}` : value};`);
  }
}

/** Flattens the token tree into ordered `--muse-*` custom property declarations. */
export function tokenVariables(tokens: Tokens): readonly string[] {
  const lines: string[] = [];
  collect(expandWebAlpha(tokens), [], lines);
  if (lines.length === 0) throw new Error("tokens.json: 未生成任何令牌变量，检查文件结构");
  return lines;
}

/** Full `:root { … }` block, ready to be injected into the document head. */
export function renderTokenCss(tokens: Tokens): string {
  return `:root {\n${tokenVariables(tokens).join("\n")}\n}\n`;
}
