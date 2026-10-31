import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import { renderTokenCss, type Tokens } from "./src/design/token-css";

const ROOT = fileURLToPath(new URL(".", import.meta.url));
const TOKENS_FILE = `${ROOT}src/design/tokens.json`;

/** One id per build: cache-busts the service worker shell and is shown in the drawer. */
const buildId = (process.env.MUSE_BUILD_ID ?? new Date().toISOString().replace(/\D/g, "").slice(0, 14)) || "0";

/**
 * public/sw.js ships with a `__MUSE_BUILD_ID__` placeholder (Vite copies public/
 * verbatim). Rewrite it in dist so every build gets a fresh cache name.
 */
function serviceWorkerVersion(id: string): Plugin {
  return {
    name: "muse-sw-version",
    apply: "build",
    closeBundle() {
      const file = `${ROOT}dist/sw.js`;
      if (!existsSync(file)) return;
      const source = readFileSync(file, "utf8");
      writeFileSync(file, source.split("muse-web-shell-__MUSE_BUILD_ID__").join(`muse-web-shell-${id}`), "utf8");
    },
  };
}

/**
 * Design tokens are the single source of truth (`src/design/tokens.json`).
 * They are rendered into `:root { --muse-* }` custom properties and injected into the
 * document head, so page CSS never carries a raw colour value of its own. Reading the
 * file per request keeps `vite dev` in sync with edits to the JSON.
 */
function designTokens(): Plugin {
  return {
    name: "muse-design-tokens",
    transformIndexHtml() {
      const tokens = JSON.parse(readFileSync(TOKENS_FILE, "utf8")) as Tokens;
      return [
        {
          tag: "style",
          attrs: { id: "muse-design-tokens" },
          children: renderTokenCss(tokens),
          injectTo: "head-prepend",
        },
      ];
    },
  };
}

export default defineConfig(({ command }) => ({
  plugins: [react(), designTokens(), serviceWorkerVersion(command === "build" ? buildId : "dev")],
  define: {
    __MUSE_BUILD_ID__: JSON.stringify(command === "build" ? buildId : "dev"),
  },
}));
