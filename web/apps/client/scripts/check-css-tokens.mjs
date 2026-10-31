/**
 * 页面样式只读令牌的静态校验。
 *
 * 规则（与 tokens.json / token-css.ts 对齐）：
 *  1. 任何裸 hex / rgba() 颜色 → 失败（颜色只能来自 `--muse-*`）。
 *  2. Host 作用域的裸 `px` 长度 → 失败（白名单见 PX_* 常量，每条都写明理由）。
 *     Host 作用域 = styles.css 里 `Host mobile shell` 分隔注释之后的部分；
 *     primitives.css 整份都是对齐 Compose 的基元层，整份检查。
 *  3. 引用不存在的 `--muse-*` 变量 → 失败（令牌改名/删除时立刻暴露）。
 *
 * 令牌全集不在这里另写一套命名规则：直接打包 `src/design/token-css.ts`（构建时
 * 也用同一份代码生成 `:root` 变量）再取 `tokenVariables()`，因此校验和产物不会漂。
 *
 * 用法：npm --workspace @muse/client run test（与设置页渲染断言串在一起跑）
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join, relative } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".token-css-check.mjs");
const TOKENS_FILE = join(ROOT, "src", "design", "tokens.json");

/** 只检查 Host 作用域的文件：从这段分隔注释开始算起。 */
const HOST_REGION_MARKER = "Host mobile shell";

/** 整份文件的 px 都要过检查（基元层，取值全部来自 Compose 规格）。 */
const FULLY_CHECKED_FILES = ["src/components/primitives.css"];
/** 只有 Host 作用域检查 px 的文件。 */
const HOST_ONLY_FILES = ["src/styles.css"];

/**
 * px 白名单。
 *
 * 只接受两种：0（长度为零，缩放与规格都无意义），以及「App 里没有对应令牌、
 * 且是纯桌面临时 UI」的单点特例（必须逐条写明理由）。
 * 任何新增条目都要在这里留一行解释，否则就该去 tokens.json 加令牌。
 */
const PX_WHITELIST = [
  {
    id: "zero",
    reason: "0 长度：没有可缩放的量，tokens.json 也不存 0 档",
    allows: (value) => value === 0,
  },
];

const DECLARATION_WHITELIST = [
  {
    id: "drawer-desktop-max-width",
    reason:
      "桌面抽屉宽度上限 330px：App 里是手机全屏侧滑（无宽度上限），Web 在桌面上必须封顶；MusePaddings 没有对应的宽度档位，不为此新建令牌",
    matches: (declaration) => /width:\s*min\(\s*84vw\s*,\s*330px\s*\)/.test(declaration),
  },
  {
    id: "toast-desktop-max-width",
    reason:
      "toast 在桌面端的宽度上限 520px：App 的 toast 宽度随内容自适应，MusePaddings 没有对应档位；移动端实际生效的是 92vw",
    matches: (declaration) => /max-width:\s*min\(\s*92vw\s*,\s*520px\s*\)/.test(declaration),
  },
  {
    id: "host-breakpoint-tablet",
    reason:
      "平板档断点 600px：媒体查询里不允许用自定义属性（var() 在 @media 前置条件里无效），且断点是布局切换阈值，不是可缩放的设计尺寸",
    matches: (declaration) => /^@media \(min-width: 600px\) \{/.test(declaration),
  },
  {
    id: "host-breakpoint-desktop",
    reason:
      "桌面档断点 1024px：同上，媒体查询前置条件不能引用 var()，断点是布局阈值而非设计尺寸",
    matches: (declaration) => /^@media \(min-width: 1024px\) \{/.test(declaration),
  },
  {
    id: "host-content-max-tablet",
    reason:
      "平板档单栏内容最大宽 640px：tokens 里没有「内容栏宽」档位（MusePaddings 只有间距与组件尺寸），且只在 600–1023 生效，改一处即可",
    matches: (declaration) => /--host-content-max:\s*640px/.test(declaration),
  },
  {
    id: "host-content-max-desktop",
    reason:
      "桌面档内容最大宽 720px：16px 正文约 80 字符/行的阅读上限，同一变量的桌面档取值",
    matches: (declaration) => /--host-content-max:\s*720px/.test(declaration),
  },
  {
    id: "host-sidebar-width",
    reason:
      "桌面档左栏宽 300px（要求 280–320，取中值）：App 是手机全屏侧滑，没有竖向栏宽档位，tokens 不为此新建",
    matches: (declaration) => /--host-sidebar-w:\s*300px/.test(declaration),
  },
];

const failures = [];

function fail(file, line, reason, content) {
  failures.push({ file, line, reason, content });
}

async function tokenNames() {
  await build({
    entryPoints: [join(ROOT, "src", "design", "token-css.ts")],
    bundle: true,
    format: "esm",
    platform: "node",
    outfile: TEMP,
    logLevel: "warning",
  });
  const module = await import(`${pathToFileURL(TEMP).href}?t=${Date.now()}`);
  const tokens = JSON.parse(readFileSync(TOKENS_FILE, "utf8"));
  const names = new Set(
    module.tokenVariables(tokens).map((declaration) => declaration.trim().split(":")[0]),
  );
  if (names.size === 0) throw new Error("token-css.ts 没有生成任何令牌变量");
  return names;
}

/** 把块注释替换成等长空白，保留行号与列位置。 */
function blankComments(source) {
  return source.replace(/\/\*[\s\S]*?\*\//g, (block) => block.replace(/[^\n]/g, " "));
}

function checkHexAndRgba(file, lines) {
  lines.forEach((line, index) => {
    const hex = line.match(/#[0-9a-fA-F]{3,8}\b/);
    if (hex) fail(file, index + 1, `裸颜色 ${hex[0]}（改用 --muse-* 令牌）`, line.trim());
    const func = line.match(/\b(rgba?|hsla?)\s*\(/);
    if (func) fail(file, index + 1, `裸颜色函数 ${func[1]}()（改用 --muse-* 令牌）`, line.trim());
  });
}

function checkPx(file, lines, startIndex) {
  for (let index = startIndex; index < lines.length; index += 1) {
    const line = lines[index];
    const declaration = line.trim();
    if (declaration === "" || declaration.startsWith("/*")) continue;
    if (DECLARATION_WHITELIST.some((entry) => entry.matches(declaration))) continue;
    for (const match of line.matchAll(/(?<![\w.-])(\d*\.?\d+)px\b/g)) {
      const value = Number(match[1]);
      if (PX_WHITELIST.some((entry) => entry.allows(value))) continue;
      fail(
        file,
        index + 1,
        `裸 px 长度 ${match[0]}（改用 --muse-* 令牌，或在 PX_WHITELIST 里写明理由）`,
        declaration,
      );
      break;
    }
  }
}

function checkMuseVariables(file, lines, declared) {
  lines.forEach((line, index) => {
    for (const match of line.matchAll(/var\(\s*(--muse-[a-z0-9-]+)/g)) {
      if (declared.has(match[1])) continue;
      fail(file, index + 1, `引用了不存在的令牌 ${match[1]}`, line.trim());
    }
  });
}

const declared = await tokenNames();
try {
  const files = [...FULLY_CHECKED_FILES, ...HOST_ONLY_FILES];
  for (const relativePath of files) {
    const absolute = join(ROOT, relativePath);
    const rawLines = readFileSync(absolute, "utf8").split(/\r?\n/);
    // 注释要先隐掉再找 px/颜色，否则注释里的举例也会被当成样式值。
    const lines = blankComments(rawLines.join("\n")).split(/\r?\n/);
    const display = relative(process.cwd(), absolute).split("\\").join("/");

    checkHexAndRgba(display, lines);
    checkMuseVariables(display, lines, declared);

    if (FULLY_CHECKED_FILES.includes(relativePath)) {
      checkPx(display, lines, 0);
      continue;
    }
    // 作用域分隔标记本身是注释，必须在原始行里找。
    const markerIndex = rawLines.findIndex((line) => line.includes(HOST_REGION_MARKER));
    if (markerIndex < 0) {
      fail(display, 1, `找不到 Host 作用域分隔注释「${HOST_REGION_MARKER}」，无法界定裸 px 的检查范围`, "");
      continue;
    }
    checkPx(display, lines, markerIndex);
  }
} finally {
  await rm(TEMP, { force: true });
}

console.log(`令牌变量 ${declared.size} 个；检查文件 ${[...FULLY_CHECKED_FILES, ...HOST_ONLY_FILES].join(", ")}`);

if (failures.length > 0) {
  for (const item of failures) {
    console.error(`✗ ${item.file}:${item.line} ${item.reason}`);
    if (item.content) console.error(`    ${item.content}`);
  }
  throw new Error(`页面样式令牌校验未通过（${failures.length} 处）`);
}

console.log("页面样式令牌校验通过：无裸颜色、Host 作用域无裸 px、无未定义令牌。");
