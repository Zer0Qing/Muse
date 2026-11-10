import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { build } from "esbuild";
import ts from "typescript";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".saas-i18n-check.mjs");
const source = readFileSync(join(ROOT, "src/main.tsx"), "utf8");
const tree = ts.createSourceFile("main.tsx", source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const chinese = /[\u4e00-\u9fff]/;
const used = new Set();
const technicalCopy = new Set(["MUSE WEB · PWA", "PWA", "Muse", "Web", "tokens", "·", "☰"]);
function visit(node) {
  if (ts.isCallExpression(node) && node.expression.getText(tree) === "t") {
    assert(ts.isStringLiteral(node.arguments[0]), "SaaS translation keys must be literal source copy");
    used.add(node.arguments[0].text);
  }
  if ((ts.isStringLiteral(node) || ts.isJsxText(node) || ts.isTemplateExpression(node)) && chinese.test(node.getText(tree))) {
    assert(ts.isCallExpression(node.parent) && node.parent.expression.getText(tree) === "t" && node.parent.arguments[0] === node,
      `Untranslated SaaS copy: ${node.getText(tree)}`);
  }
  if (ts.isJsxText(node) && node.text.trim()) {
    assert(technicalCopy.has(node.text.trim()), `Untranslated visible SaaS copy: ${node.text.trim()}`);
  }
  if (ts.isJsxAttribute(node) && ["placeholder", "aria-label", "title"].includes(node.name.getText(tree))) {
    assert(!node.initializer || !ts.isStringLiteral(node.initializer), `Untranslated SaaS attribute: ${node.getText(tree)}`);
  }
  ts.forEachChild(node, visit);
}
visit(tree);

// Render without mounting the entry point, running effects or contacting providers.
const entryEnd = source.indexOf("// iOS over plain");
assert(entryEnd > 0, "Entry point boundary exists");
const entry = source.slice(0, entryEnd) + `
export { Login, ProviderEditor, AssistantEditor, KnowledgePanel, FilePanel, PushPanel, Chat, App };
export { SAAS_ES, translateSaas } from "./i18n/saas.js";
`;
const descriptors = new Map(["navigator", "window", "sessionStorage"].map((key) => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
try {
  await build({
    stdin: { contents: entry, resolveDir: join(ROOT, "src"), loader: "tsx" },
    bundle: true, format: "esm", platform: "node", outfile: TEMP, jsx: "automatic",
    external: ["react", "react/*", "react-dom", "react-dom/*"], loader: { ".css": "empty" },
    define: { "import.meta.env": "{}" }, logLevel: "warning",
  });
  const mod = await import(pathToFileURL(TEMP).href);
  assert.deepEqual([...used].sort(), Object.keys(mod.SAAS_ES).sort(), "All SaaS source copy has exactly one Spanish translation");
  const slots = (text) => (text.match(/\{\w+\}/g) ?? []).sort();
  for (const [key, value] of Object.entries(mod.SAAS_ES)) {
    assert(value.trim() && !chinese.test(value), `Spanish copy for ${key}`);
    assert.deepEqual(slots(value), slots(key), `Preserved placeholders for ${key}`);
    for (const language of ["zh-CN", "en-US", "ja-JP", "ko-KR", "ru-RU", "fr-FR"]) {
      assert.equal(mod.translateSaas(key, undefined, [language]), key, `Chinese SaaS retained for ${language}`);
    }
    for (const language of ["es-ES", "es-MX", "es_MX"]) {
      assert.equal(mod.translateSaas(key, undefined, [language]), value);
    }
  }
  assert.equal(mod.translateSaas("发送", undefined, []), "发送");
  assert.equal(mod.translateSaas("发送", undefined, ["en-US", "es-ES"]), "发送");
  assert.equal(mod.translateSaas("发送", undefined, ["fr-FR", "es-MX"]), "Enviar");
  assert.equal(mod.translateSaas("对象存储上传失败（{status}）", { status: 403 }, ["es"]), "La carga al almacenamiento de objetos ha fallado (403)");
  assert.equal(mod.translateSaas("{name} 已上传并登记，地址有效至 {time}", { name: "原始.txt", time: "14:25" }, ["es"]), "原始.txt se ha subido y registrado; la dirección es válida hasta las 14:25");
  Object.defineProperty(globalThis, "window", { configurable: true, value: { location: { search: "" } } });
  Object.defineProperty(globalThis, "sessionStorage", { configurable: true, value: { getItem: () => null } });
  const session = { token: "fixture", userId: "fixture", expiresAt: 0 };
  const provider = { id: "fixture-provider", displayName: "原始 Provider", type: "openai", modelIds: ["model-original"], baseUrl: "https://example.invalid/v1", hasSecret: true };
  const noop = () => {};
  const fixtures = [
    [mod.App, {}, "Detectando el entorno de ejecución…", "正在识别运行环境…"],
    [mod.Login, { onLogin: noop }, "Iniciar sesión", "登录"],
    [mod.ProviderEditor, { session, providers: [provider], onSaved: noop }, "Guardar proveedor", "保存 Provider"],
    [mod.AssistantEditor, { session, assistants: [], onSaved: noop }, "Crear asistente", "创建 Assistant"],
    [mod.KnowledgePanel, { session, assistantId: "default" }, "Conocimiento y memoria", "知识与记忆"],
    [mod.FilePanel, { session }, "Elegir archivo", "选择文件"],
    [mod.PushPanel, { session }, "Activar notificaciones", "启用通知"],
    [mod.Chat, { session, onLogout: noop }, "Historial de mensajes", "消息记录"],
  ];
  for (const language of ["es-ES", "es-MX", "zh-CN", "en-US"]) {
    Object.defineProperty(globalThis, "navigator", { configurable: true, value: { languages: [language], language } });
    for (const [Component, props, spanish, original] of fixtures) {
      const html = renderToStaticMarkup(createElement(Component, props));
      assert(html.includes(language.startsWith("es") ? spanish : original), `${Component.name} renders ${language}`);
      if (Component === mod.ProviderEditor) {
        assert(html.includes("原始 Provider"), "Backend display names remain unchanged");
        assert(html.includes('value="openai"'), "Technical provider identifiers remain unchanged");
      }
      if (Component === mod.Login) assert(html.includes(`aria-label="${language.startsWith("es") ? "Correo electrónico" : "邮箱"}"`));
    }
  }
  console.log(`SaaS i18n passed: ${used.size} entries, placeholders, locale selection, original-copy preservation and ${fixtures.length * 4} component renders (including boot).`);
} finally {
  for (const [key, descriptor] of descriptors) {
    if (descriptor) Object.defineProperty(globalThis, key, descriptor);
    else Reflect.deleteProperty(globalThis, key);
  }
  await rm(TEMP, { force: true });
}
